package com.dl.m3u8recorder.llhls

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 音视频合成器
 *
 * 使用混合探测策略精确计算音视频 DTS 基准偏差：
 *   1. 优先：二进制解析 fMP4/CMAF 的 tfdt 盒子 (baseMediaDecodeTime)，不受 B 帧 CTS 偏移影响
 *   2. 回退：ffprobe 读取首个 packet 的 dts_time
 *   3. 再次回退：ffprobe 读取 stream 的 start_time (旧方案)
 *
 * 最终偏移公式: trueOffset = (audioDts - videoDts) - startMismatchSec
 *   - startMismatchSec: 补偿分片序列号不一致导致的物理时长差
 *
 * -itsoffset + -c copy 在 MP4/MPEG-TS 下均不生效（muxer 忽略偏移），
 * 因此必须重编码音频（-c:a aac）让 itsoffset 实际写入。
 */
class PipeMuxer {
    companion object {
        private const val TAG = "PipeMuxer"
        private const val THRESHOLD_MS = 5
        private const val ADELAY_THRESHOLD_MS = 1000
    }

    /**
     * @param videoFile 视频原始文件 (CMAF/fMP4 片段拼接)
     * @param audioFile 音频原始文件 (CMAF/fMP4 片段拼接)
     * @param outputFile 输出 MP4
     * @param padInfo 短轨补齐信息 (哪个轨道短、短多少 ms)
     * @param startMismatchMs 因首分片序列号不一致导致的时长差 (ms)，正=音频起晚了
     */
    fun mux(
        videoFile: File,
        audioFile: File?,
        outputFile: File,
        padInfo: Pair<SyncCoordinator.StreamType, Long>?,
        startMismatchMs: Long = 0L
    ): Boolean {
        if (!videoFile.exists()) { Log.e(TAG, "video missing"); return false }
        if (audioFile != null && !audioFile.exists()) { Log.e(TAG, "audio missing"); return false }

        Log.d(TAG, "=== Mux start ===")
        Log.d(TAG, "Video: ${videoFile.name} (${videoFile.length()} bytes)")
        Log.d(TAG, "Audio: ${audioFile?.name ?: "null"} (${audioFile?.length() ?: 0} bytes)")
        Log.d(TAG, "Output: ${outputFile.name}")
        Log.d(TAG, "PadInfo: $padInfo, StartMismatch: ${startMismatchMs}ms")

        if (audioFile == null) {
            return muxVideoOnly(videoFile, outputFile)
        }

        if (audioFile.length() < 1024) {
            Log.w(TAG, "Audio file is too small (${audioFile.length()} bytes), falling back to video-only")
            return muxVideoOnly(videoFile, outputFile)
        }

        val (startOffset, endOffset, driftDetected) = detectDtsOffsetWithDrift(videoFile, audioFile)

        val trueOffsetSec = if (startOffset != null) {
            startOffset
        } else {
            Log.w(TAG, "DTS offset detection failed, using simple copy mode without correction")
            0.0
        }

        val needsCorrection = abs(trueOffsetSec * 1000) > THRESHOLD_MS
        val useAdelay = needsCorrection && abs(trueOffsetSec * 1000) > ADELAY_THRESHOLD_MS && !driftDetected

        Log.d(TAG, "Offset calc: startOffset=${startOffset?.let { "%.3f".format(it) + "s" } ?: "null"} " +
                "endOffset=${endOffset?.let { "%.3f".format(it) + "s" } ?: "null"} " +
                "driftDetected=$driftDetected " +
                "startMismatch=${startMismatchMs}ms " +
                "trueOffset=${"%.3f".format(trueOffsetSec)}s " +
                "needsCorrection=$needsCorrection useAdelay=$useAdelay")

        val cmd = buildCommand(videoFile, audioFile, outputFile, trueOffsetSec, padInfo, useAdelay, driftDetected)
        Log.d(TAG, "FFmpeg command: $cmd")

        val session = FFmpegKit.execute(cmd)
        var success = ReturnCode.isSuccess(session.returnCode)
        if (success) {
            Log.d(TAG, "Mux OK: ${outputFile.absolutePath}")

            val hasAudio = checkOutputHasAudio(outputFile)
            if (!hasAudio) {
                Log.w(TAG, "Mux succeeded but output has no audio track, retrying with audio re-encode...")
                success = retryWithAudioReencode(videoFile, audioFile, outputFile)
            }
        } else {
            Log.e(TAG, "Mux failed: ${session.failStackTrace}")
            Log.e(TAG, "FFmpeg output: ${session.output}")
        }
        Log.d(TAG, "=== Mux end ===")
        return success
    }

    fun muxVideoOnly(videoFile: File, outputFile: File): Boolean {
        Log.d(TAG, "Muxing video-only stream")
        val cmd = listOf(
            "-y",
            "-i", videoFile.absolutePath,
            "-c:v", "copy",
            "-fflags", "+genpts",
            "-movflags", "+faststart",
            outputFile.absolutePath
        ).joinToString(" ")
        Log.d(TAG, "FFmpeg command (video-only): $cmd")

        val session = FFmpegKit.execute(cmd)
        val success = ReturnCode.isSuccess(session.returnCode)
        if (success) {
            Log.d(TAG, "Video-only mux OK: ${outputFile.absolutePath}")
        } else {
            Log.e(TAG, "Video-only mux failed: ${session.failStackTrace}")
            Log.e(TAG, "FFmpeg output: ${session.output}")
        }
        return success
    }

    private fun checkOutputHasAudio(file: File): Boolean {
        try {
            val cmd = "-v quiet -print_format json -show_entries stream=codec_type -i ${file.absolutePath}"
            val session = FFprobeKit.execute(cmd)
            val json = JSONObject(session.output)
            val streams = json.optJSONArray("streams")
            if (streams != null) {
                for (i in 0 until streams.length()) {
                    val s = streams.getJSONObject(i)
                    if (s.optString("codec_type") == "audio") {
                        return true
                    }
                }
            }
            Log.w(TAG, "Output file ${file.name} has no audio stream")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check output audio: ${e.message}")
        }
        return false
    }

    private fun retryWithAudioReencode(videoFile: File, audioFile: File, outputFile: File): Boolean {
        Log.d(TAG, "Retrying mux with audio re-encode and async=1")
        val cmd = listOf(
            "-y",
            "-i", videoFile.absolutePath,
            "-i", audioFile.absolutePath,
            "-c:v", "copy",
            "-c:a", "aac",
            "-b:a", "128k",
            "-async", "1",
            "-shortest",
            "-fflags", "+genpts",
            "-movflags", "+faststart",
            "-map", "0:v:0",
            "-map", "1:a:0",
            outputFile.absolutePath
        ).joinToString(" ")
        Log.d(TAG, "FFmpeg retry command: $cmd")

        val session = FFmpegKit.execute(cmd)
        val success = ReturnCode.isSuccess(session.returnCode)
        if (success) {
            Log.d(TAG, "Audio re-encode retry OK: ${outputFile.absolutePath}")
            if (!checkOutputHasAudio(outputFile)) {
                Log.e(TAG, "Audio re-encode retry succeeded but still no audio")
                return false
            }
        } else {
            Log.e(TAG, "Audio re-encode retry failed: ${session.failStackTrace}")
            Log.e(TAG, "FFmpeg output: ${session.output}")
        }
        return success
    }

    private fun buildCommand(video: File, audio: File, out: File, offsetSec: Double, padInfo: Pair<SyncCoordinator.StreamType, Long>?, useAdelay: Boolean, driftDetected: Boolean = false): String {
        val args = mutableListOf("-y")
        val hasPad = padInfo != null
        val needsAudioPad = hasPad && padInfo?.first == SyncCoordinator.StreamType.AUDIO
        val needsVideoPad = hasPad && padInfo?.first == SyncCoordinator.StreamType.VIDEO

        val audioFilters = mutableListOf<String>()
        val videoFilters = mutableListOf<String>()
        
        if (driftDetected) {
            audioFilters.add("aresample=async=1")
            Log.w(TAG, "Using aresample=async=1 for drift correction")
        } else if (useAdelay) {
            val delayMs = (offsetSec * 1000).toInt()
            audioFilters.add("adelay=${delayMs}|${delayMs}")
        }

        if (needsAudioPad) {
            val padSec = padInfo!!.second / 1000.0
            audioFilters.add("apad=pad_dur=${"%.3f".format(padSec)}")
        }

        if (needsVideoPad) {
            val padSec = padInfo!!.second / 1000.0
            videoFilters.add("tpad=stop_mode=clone:stop_duration=${"%.3f".format(padSec)}")
        }

        val needsAudioEncode = audioFilters.isNotEmpty() || driftDetected
        val needsVideoEncode = videoFilters.isNotEmpty()

        if (needsAudioEncode || needsVideoEncode) {
            args.addAll(listOf("-i", video.absolutePath,
                "-i", audio.absolutePath))

            if (needsVideoEncode) {
                args.add("-vf")
                args.add(videoFilters.joinToString(","))
                args.add("-c:v")
                args.add("libx264")
                args.add("-preset")
                args.add("ultrafast")
            } else {
                args.add("-c:v")
                args.add("copy")
            }

            if (needsAudioEncode) {
                args.add("-af")
                args.add(audioFilters.joinToString(","))
                args.add("-c:a")
                args.add("aac")
                args.add("-b:a")
                args.add("128k")
            } else {
                args.add("-c:a")
                args.add("copy")
            }

            args.add("-map")
            args.add("0:v:0")
            args.add("-map")
            args.add("1:a:0")
        } else {
            args.addAll(listOf("-i", video.absolutePath, "-i", audio.absolutePath,
                "-c:v", "copy", "-c:a", "copy",
                "-map", "0:v:0", "-map", "1:a:0"))
        }

        args.add("-fflags")
        args.add("+genpts")
        args.add("-movflags")
        args.add("+faststart")
        args.add("-shortest")

        if (!useAdelay) {
            args.add("-avoid_negative_ts")
            args.add("make_non_negative")
        }

        args.add("-y")
        args.add(out.absolutePath)

        return args.joinToString(" ")
    }

    // ═══════════════════════════════════════════════════════════════
    // 混合 DTS 探测
    // ═══════════════════════════════════════════════════════════════

    /**
     * 混合探测音视频 DTS 偏移（包含漂移检测）
     * 优先级: 二进制 tfdt → ffprobe packet dts_time → ffprobe stream start_time
     * @return Triple(startOffset, endOffset, driftDetected) - startOffset 和 endOffset 都是 audioDts - videoDts (秒)
     */
    private fun detectDtsOffsetWithDrift(videoFile: File, audioFile: File): Triple<Double?, Double?, Boolean> {
        val vStartBin = parseTfdtSeconds(videoFile)
        val aStartBin = parseTfdtSeconds(audioFile)
        val vEndPts = probeLastPacketPts(videoFile, "video")
        val aEndPts = probeLastPacketPts(audioFile, "audio")

        var startOffset: Double? = null
        var endOffset: Double? = null
        var driftDetected = false

        if (vStartBin != null && aStartBin != null) {
            startOffset = aStartBin - vStartBin
            Log.d(TAG, "Binary tfdt (DTS start): video=${"%.3f".format(vStartBin)}s audio=${"%.3f".format(aStartBin)}s offset=${"%.3f".format(startOffset)}s")
        } else {
            val vStart = probePacketDts(videoFile, "video")
            val aStart = probePacketDts(audioFile, "audio")
            if (vStart != null && aStart != null) {
                startOffset = aStart - vStart
                Log.d(TAG, "FFprobe packet dts (start): video=${"%.3f".format(vStart)}s audio=${"%.3f".format(aStart)}s offset=${"%.3f".format(startOffset)}s")
            }
        }

        if (vEndPts != null && aEndPts != null) {
            endOffset = aEndPts - vEndPts
            Log.d(TAG, "FFprobe last packet PTS (end): video=${"%.3f".format(vEndPts)}s audio=${"%.3f".format(aEndPts)}s offset=${"%.3f".format(endOffset)}s")
        }

        if (startOffset != null && endOffset != null) {
            val drift = abs(endOffset - startOffset)
            if (drift > 0.1) {
                driftDetected = true
                Log.w(TAG, "DRIFT DETECTED: start offset=${"%.3f".format(startOffset)}s, end offset=${"%.3f".format(endOffset)}s, drift=${"%.3f".format(drift)}s")
            }
        }

        val vStart = probeStreamStartTime(videoFile, "video")
        val aStart = probeStreamStartTime(audioFile, "audio")
        if (vStart != null && aStart != null && startOffset != null) {
            val ptsOffset = aStart - vStart
            Log.d(TAG, "FFprobe start_time (PTS): video=${"%.3f".format(vStart)}s audio=${"%.3f".format(aStart)}s offset=${"%.3f".format(ptsOffset)}s")

            if (abs(startOffset) <= 0.005 && abs(ptsOffset) > 0.005) {
                Log.w(TAG, "CTS offset detected: DTS aligned but PTS offset, this is normal B-frame reordering")
                return Triple(0.0, endOffset, driftDetected)
            }
        }

        return Triple(startOffset, endOffset, driftDetected)
    }

    /**
     * 混合探测音视频 DTS 偏移（兼容旧接口）
     * 优先级: 二进制 tfdt → ffprobe packet dts_time → ffprobe stream start_time
     * @return audioDts - videoDts (秒)
     */
    private fun detectDtsOffset(videoFile: File, audioFile: File): Double? {
        return detectDtsOffsetWithDrift(videoFile, audioFile).first
    }

    /**
     * [策略 1] 二进制解析 fMP4/CMAF 文件的首个 fragment DTS
     *
     * 文件结构:
     *   [ftyp/styp] [moov?] [moof] [mdat] [moof] [mdat] ...
     *
     * 导航路径: moof → traf → tfdt → baseMediaDecodeTime
     * 时基来源: moov → trak → mdia → mdhd → timescale
     *
     * @return DTS (秒)，失败返回 null
     */
    private fun parseTfdtSeconds(file: File): Double? {
        try {
            val bytes = file.inputStream().use { stream ->
                val buffer = ByteArray(2 * 1024 * 1024)
                var totalRead = 0
                var bytesRead: Int
                do {
                    bytesRead = stream.read(buffer, totalRead, buffer.size - totalRead)
                    if (bytesRead > 0) {
                        totalRead += bytesRead
                        val currentData = buffer.copyOf(totalRead)
                        if (findFirstBaseMediaDecodeTime(currentData) != null) {
                            break
                        }
                    }
                } while (bytesRead > 0 && totalRead < buffer.size)
                if (totalRead <= 0) return null
                buffer.copyOf(totalRead)
            }
            if (bytes.size < 8) return null

            val timescale = findTimescale(bytes) ?: return null

            val baseMediaDecodeTime = findFirstBaseMediaDecodeTime(bytes) ?: return null

            val dtsSec = baseMediaDecodeTime.toDouble() / timescale
            Log.d(TAG, "Binary parse ${file.name}: baseMediaDecodeTime=$baseMediaDecodeTime timescale=$timescale dts=${"%.3f".format(dtsSec)}s read=${bytes.size} bytes")
            return dtsSec
        } catch (e: Exception) {
            Log.w(TAG, "Binary tfdt parse failed for ${file.name}: ${e.message}")
            return null
        }
    }

    /**
     * 在 ISOBMFF 字节中查找时基 (timescale)
     * 从 moov/trak/mdia/mdhd 或文件中任意 mdhd 读取
     */
    private fun findTimescale(bytes: ByteArray): Long? {
        // 查找 moov box
        val moovOffset = findBox(bytes, "moov", 0, bytes.size)
        if (moovOffset >= 0) {
            val moovEnd = moovOffset + readUint32(bytes, moovOffset).toInt()
            // 在 moov 内递归找 mdhd
            val timescale = findTimescaleInRange(bytes, moovOffset + 8, minOf(moovEnd, bytes.size))
            if (timescale != null) return timescale
        }
        // 如果没找到 moov，尝试在整个文件中找 mdhd
        return findTimescaleInRange(bytes, 0, bytes.size)
    }

    /** 在指定范围内递归查找 mdhd 的 timescale */
    private fun findTimescaleInRange(bytes: ByteArray, start: Int, end: Int): Long? {
        var pos = start
        while (pos + 8 <= end) {
            val size = readUint32(bytes, pos).toLong()
            if (size < 8 || pos + size > end) break
            val type = readFourcc(bytes, pos + 4)
            if (type == "mdhd" && pos + 24 <= end) {
                val version = bytes[pos + 8].toInt() and 0xFF
                val timescaleOffset = if (version == 0) pos + 20 else pos + 28
                if (timescaleOffset + 4 <= end) {
                    return readUint32(bytes, timescaleOffset).toLong()
                }
            } else if (isContainerBox(type)) {
                // 递归进入容器 box
                val result = findTimescaleInRange(bytes, pos + 8, minOf(pos + size.toInt(), end))
                if (result != null) return result
            }
            pos += size.toInt()
        }
        return null
    }

    /**
     * 查找首个 moof → traf → tfdt 的 baseMediaDecodeTime
     */
    private fun findFirstBaseMediaDecodeTime(bytes: ByteArray): Long? {
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val size = readUint32(bytes, pos).toLong()
            if (size < 8 || pos + size > bytes.size) break
            val type = readFourcc(bytes, pos + 4)

            if (type == "moof") {
                // 进入 moof，查找 traf → tfdt
                val moofEnd = pos + size.toInt()
                var trafPos = pos + 8
                while (trafPos + 8 <= minOf(moofEnd, bytes.size)) {
                    val trafSize = readUint32(bytes, trafPos).toLong()
                    if (trafSize < 8 || trafPos + trafSize > minOf(moofEnd, bytes.size)) break
                    val trafType = readFourcc(bytes, trafPos + 4)

                    if (trafType == "traf") {
                        val trafEnd = trafPos + trafSize.toInt()
                        // 在 traf 内找 tfdt
                        var inner = trafPos + 8
                        while (inner + 8 <= minOf(trafEnd, bytes.size)) {
                            val innerSize = readUint32(bytes, inner).toLong()
                            if (innerSize < 8 || inner + innerSize > minOf(trafEnd, bytes.size)) break
                            val innerType = readFourcc(bytes, inner + 4)

                            if (innerType == "tfdt" && inner + 12 <= minOf(trafEnd, bytes.size)) {
                                val version = bytes[inner + 8].toInt() and 0xFF
                                return if (version == 0) {
                                    readUint32(bytes, inner + 12).toLong()
                                } else {
                                    readUint64(bytes, inner + 12)
                                }
                            }
                            inner += innerSize.toInt()
                        }
                    }
                    trafPos += trafSize.toInt()
                }
                // 只处理第一个 moof
                return null
            }
            pos += size.toInt()
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════
    // [策略 2] ffprobe 首个 packet 的 dts_time
    // ═══════════════════════════════════════════════════════════════

    /**
     * 使用 ffprobe 读取首个 packet 的 dts_time
     * dts_time 是解码时间戳，不受 B 帧 CTS 重排影响
     */
    private fun probePacketDts(file: File, codec: String): Double? {
        try {
            val cmd = "-v quiet -print_format json " +
                    "-select_streams ${if (codec == "video") "v:0" else "a:0"} " +
                    "-show_packets -read_intervals \"%+#1\" " +
                    "-i ${file.absolutePath}"
            val session = FFprobeKit.execute(cmd)
            val json = JSONObject(session.output)
            val packets = json.optJSONArray("packets")
            if (packets != null && packets.length() > 0) {
                val pkt = packets.getJSONObject(0)
                val dtsTime = pkt.optDouble("dts_time", -1.0)
                if (dtsTime >= 0) return dtsTime
            }
        } catch (e: Exception) {
            Log.w(TAG, "FFprobe packet dts failed for $codec: ${e.message}")
        }
        return null
    }

    private fun probeLastPacketPts(file: File, codec: String): Double? {
        try {
            val cmd = "-v quiet -print_format json " +
                    "-select_streams ${if (codec == "video") "v:0" else "a:0"} " +
                    "-show_packets " +
                    "-i ${file.absolutePath}"
            val session = FFprobeKit.execute(cmd)
            val json = JSONObject(session.output)
            val packets = json.optJSONArray("packets")
            if (packets != null && packets.length() > 0) {
                val pkt = packets.getJSONObject(packets.length() - 1)
                val ptsTime = pkt.optDouble("pts_time", -1.0)
                if (ptsTime >= 0) return ptsTime
            }
        } catch (e: Exception) {
            Log.w(TAG, "FFprobe last packet pts failed for $codec: ${e.message}")
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════
    // [策略 3] 旧版 ffprobe stream start_time (保留作为最后回退)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 旧版 PTS 偏移探测 (stream start_time)
     * 保留作为混合探测的最后回退方案
     */
    private fun detectPtsOffsetLegacy(videoFile: File, audioFile: File): Double? {
        try {
            val v = probeStreamStartTime(videoFile, "video")
            val a = probeStreamStartTime(audioFile, "audio")
            if (v != null && a != null) {
                val offset = a - v
                Log.d(TAG, "FFprobe stream start_time (legacy): video=$v audio=$a offset=${"%.3f".format(offset)}s")
                return offset
            }
        } catch (e: Exception) {
            Log.w(TAG, "FFprobe stream start_time failed", e)
        }
        return null
    }

    private fun probeStreamStartTime(file: File, codec: String): Double? {
        val cmd = "-v quiet -print_format json -show_entries stream=start_time,codec_type -i ${file.absolutePath}"
        val session = FFprobeKit.execute(cmd)
        val json = JSONObject(session.output)
        val streams = json.getJSONArray("streams")
        for (i in 0 until streams.length()) {
            val s = streams.getJSONObject(i)
            if (s.optString("codec_type") == codec)
                return s.optDouble("start_time", -1.0).takeIf { it >= 0 }
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════
    // ISOBMFF 二进制解析工具
    // ═══════════════════════════════════════════════════════════════

    /** Big-endian uint32 */
    private fun readUint32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
               ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
               ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
               (bytes[offset + 3].toLong() and 0xFF)
    }

    /** Big-endian uint64 */
    private fun readUint64(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFF) shl 56) or
               ((bytes[offset + 1].toLong() and 0xFF) shl 48) or
               ((bytes[offset + 2].toLong() and 0xFF) shl 40) or
               ((bytes[offset + 3].toLong() and 0xFF) shl 32) or
               ((bytes[offset + 4].toLong() and 0xFF) shl 24) or
               ((bytes[offset + 5].toLong() and 0xFF) shl 16) or
               ((bytes[offset + 6].toLong() and 0xFF) shl 8) or
               (bytes[offset + 7].toLong() and 0xFF)
    }

    /** 读取 4 字节 FourCC */
    private fun readFourcc(bytes: ByteArray, offset: Int): String {
        return String(bytes, offset, 4, Charsets.US_ASCII)
    }

    /** 在字节数组中查找指定 FourCC box 的偏移，返回 -1 表示未找到 */
    private fun findBox(bytes: ByteArray, fourcc: String, start: Int, end: Int): Int {
        var pos = start
        while (pos + 8 <= end) {
            val size = readUint32(bytes, pos).toLong()
            if (size < 8 || pos + size > end) break
            val type = readFourcc(bytes, pos + 4)
            if (type == fourcc) return pos
            pos += size.toInt()
        }
        return -1
    }

    /** 容器 box 类型（内部有子 box） */
    private fun isContainerBox(type: String): Boolean {
        return type in setOf("moov", "trak", "mdia", "minf", "stbl", "mvex",
                             "moof", "traf", "edts", "udta", "mfra", "skip")
    }
}