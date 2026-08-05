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
import kotlin.math.sign

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
        // PDT 与 tfdt 两信号一致的容差(秒)。|pdtOffset - tfdtOffset| < 此值 → 两信号一致才补偿。
        // 跨轨差在两轨首 MSN 不同时不可信(各自基准错位)，单信号补偿会把同步流推偏(amwf_poundme 实测)。
        // 仅当 PDT/tfdt 方向一致、幅度接近时才认为是真偏移、才补偿。
        private const val SIGNAL_AGREE_SEC = 0.2
        // 补偿幅度上限(秒)。超过此值视为异常信号，不补偿(避免极端假值推偏)。
        private const val OFFSET_MAX_SEC = 2.0
        // AAC Priming 补偿：48kHz 下 2048 采样 ≈ 42.7ms
        private const val AAC_PRIMING_SAMPLES = 2048
        private const val AUDIO_SAMPLE_RATE = 48000
        private val AAC_PRIMING_SEC = AAC_PRIMING_SAMPLES.toDouble() / AUDIO_SAMPLE_RATE
    }

    /**
     * @param videoFile 视频原始文件 (CMAF/fMP4 片段拼接)
     * @param audioFile 音频原始文件 (CMAF/fMP4 片段拼接)
     * @param outputFile 输出 MP4
     * @param startMismatchMs 因首分片序列号不一致导致的时长差 (ms)，正=音频起晚了
     * @param videoFirstPdtMs 视频轨实际下载的首个 segment 的墙钟 PDT(ms)，优先用它做跨轨对齐
     * @param audioFirstPdtMs 音频轨实际下载的首个 segment 的墙钟 PDT(ms)
     *
     * 偏移来源优先级：PDT(墙钟) > tfdt(轨道内)。PDT 是真实墙钟，两轨首段 PDT 差 = 真实起点错位；
     * tfdt 各轨从 0 起、跨轨不可比，仅作 PDT 缺失时的兜底。
     */
    fun mux(
        videoFile: File,
        audioFile: File?,
        outputFile: File,
        startMismatchMs: Long = 0L,
        videoFirstPdtMs: Long? = null,
        audioFirstPdtMs: Long? = null
    ): Boolean {
        if (!videoFile.exists()) { Log.e(TAG, "video missing"); return false }
        if (audioFile != null && !audioFile.exists()) { Log.e(TAG, "audio missing"); return false }

        Log.d(TAG, "=== Mux start ===")
        Log.d(TAG, "Video: ${videoFile.name} (${videoFile.length()} bytes)")
        Log.d(TAG, "Audio: ${audioFile?.name ?: "null"} (${audioFile?.length() ?: 0} bytes)")
        Log.d(TAG, "Output: ${outputFile.name}")
        Log.d(TAG, "StartMismatch: ${startMismatchMs}ms, " +
                "videoFirstPdt=${videoFirstPdtMs}ms, audioFirstPdt=${audioFirstPdtMs}ms")

        if (audioFile == null) {
            return muxVideoOnly(videoFile, outputFile)
        }

        if (audioFile.length() < 1024) {
            Log.w(TAG, "Audio file is too small (${audioFile.length()} bytes), falling back to video-only")
            return muxVideoOnly(videoFile, outputFile)
        }

        // ═══════════════════════════════════════════════════════════════════
        // 偏移决策：保守不补偿，仅当 PDT 与 tfdt 两信号一致才补偿
        //
        // 两轨起点差有两个来源，都不可单信：
        //   1. playlist 的 PDT（墙钟）—— audio/video 首段常不在同一 MSN，跨 MSN 直接相减无意义；
        //   2. fMP4 fragment 的 tfdt（baseMediaDecodeTime）—— 同样跨 MSN 时基准错位，差不等于对齐偏移。
        // 实测教训(amwf_poundme)：原始两轨本同步，单信 tfdt 算出 -1.353s → adelay 填 1.353s 静音 → 把同步流推偏 1.4s。
        // 故改为：仅当 PDT 与 tfdt 方向一致、幅度接近时才认为是真偏移并补偿，否则纯 -c copy 不补偿。
        // 最坏情况(真偏移但两信号不一致)不补偿，保留原始状态；优于用假信号把同步流推偏。
        // ═══════════════════════════════════════════════════════════════════

        val pdtOffsetSec: Double? = if (videoFirstPdtMs != null && audioFirstPdtMs != null) {
            (audioFirstPdtMs - videoFirstPdtMs) / 1000.0
        } else null

        if (pdtOffsetSec != null) {
            val probe = probeStartOffsetOnly(videoFile, audioFile)
            val tfdtOffsetSec = probe?.first

            // 两信号一致判据：都有值、同号、幅度接近
            val signalsAgree = tfdtOffsetSec != null &&
                sign(pdtOffsetSec) == sign(tfdtOffsetSec) &&
                abs(pdtOffsetSec - tfdtOffsetSec!!) <= SIGNAL_AGREE_SEC
            val offsetInRange = abs(pdtOffsetSec) <= OFFSET_MAX_SEC

            val trueOffsetSec: Double = when {
                signalsAgree && offsetInRange -> {
                    Log.d(TAG, "PDT(${"%.3f".format(pdtOffsetSec)}s) ≈ tfdt(${"%.3f".format(tfdtOffsetSec)}s): " +
                            "两信号一致, 补偿 ${"%.3f".format(pdtOffsetSec)}s")
                    pdtOffsetSec
                }
                else -> {
                    Log.d(TAG, "PDT(${"%.3f".format(pdtOffsetSec)}s) vs tfdt(${tfdtOffsetSec?.let { "%.3f".format(it) + "s" } ?: "null"}): " +
                            "信号不一致${if (!offsetInRange) "(幅度超限)" else ""}, 不补偿(纯 copy)")
                    0.0
                }
            }

            val needsCorrection = abs(trueOffsetSec * 1000) > THRESHOLD_MS
            val useAdelay = needsCorrection
            Log.d(TAG, "Offset decision: trueOffset=${"%.3f".format(trueOffsetSec)}s " +
                    "needsCorrection=$needsCorrection useAdelay=$useAdelay")
            val cmd = buildCommand(videoFile, audioFile, outputFile, trueOffsetSec, useAdelay, false)
            Log.d(TAG, "FFmpeg command: $cmd")
            return executeMux(videoFile, audioFile, outputFile, cmd)
        }

        // PDT 缺失：走完整漂移检测(含末尾 PTS 探测)，能触发 aresample=async=1 修正累积漂移
        val (startOffset, endOffset, driftDetected) = detectDtsOffsetWithDrift(videoFile, audioFile)

        val trueOffsetSec = when {
            startOffset != null -> startOffset
            else -> {
                Log.w(TAG, "Offset detection failed (no PDT, no DTS), using simple copy without correction")
                0.0
            }
        }

        val needsCorrection = abs(trueOffsetSec * 1000) > THRESHOLD_MS
        // 不设 1000ms 下限；漂移时走 aresample=async=1，互斥不叠加 adelay。
        val useAdelay = needsCorrection && !driftDetected

        Log.d(TAG, "Offset calc: dtsStartOffset=${startOffset?.let { "%.3f".format(it) + "s" } ?: "null"} " +
                "dtsEndOffset=${endOffset?.let { "%.3f".format(it) + "s" } ?: "null"} " +
                "driftDetected=$driftDetected " +
                "trueOffset=${"%.3f".format(trueOffsetSec)}s " +
                "needsCorrection=$needsCorrection useAdelay=$useAdelay")

        val cmd = buildCommand(videoFile, audioFile, outputFile, trueOffsetSec, useAdelay, driftDetected)
        Log.d(TAG, "FFmpeg command: $cmd")
        return executeMux(videoFile, audioFile, outputFile, cmd)
    }

    /**
     * 执行 ffmpeg 合并命令 + 音轨检查 + 重试。PDT 短路路径与 DTS 兜底路径共用。
     */
    private fun executeMux(videoFile: File, audioFile: File, outputFile: File, cmd: String): Boolean {
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

    private fun buildCommand(video: File, audio: File, out: File, offsetSec: Double, useAdelay: Boolean, driftDetected: Boolean = false): String {
        val args = mutableListOf("-y")

        val audioFilters = mutableListOf<String>()
        val videoFilters = mutableListOf<String>()

        if (driftDetected) {
            audioFilters.add("aresample=async=1000")
            Log.w(TAG, "Using aresample=async=1000 for drift correction")
        } else if (useAdelay) {
            // 叠加 AAC priming 补偿（音频固定滞后约42.7ms）
            val adjustedOffsetSec = offsetSec + AAC_PRIMING_SEC

            // adjustedOffsetSec = audioFirstPdt - videoFirstPdt + priming。
            // adjustedOffsetSec>0: audio 首 PDT 更晚 → audio 内容起点晚于 video → 裁掉 audio 开头 offset 秒，
            //              使 audio 起点拉到 video 起点墙钟(成片开头 offset 秒只有画面无声，因为那段本就没录到 audio)。
            // adjustedOffsetSec<0: audio 首 PDT 更早 → audio 内容起点早于 video → adelay 推后 audio |offset| 秒对齐 video。
            val offsetMs = (adjustedOffsetSec * 1000).toInt()
            if (offsetMs > 0) {
                val trimSec = "%.3f".format(adjustedOffsetSec)
                audioFilters.add("atrim=start=$trimSec,asetpts=PTS-STARTPTS")
                audioFilters.add("aresample=async=1000")
                Log.d(TAG, "Audio trim start: ${trimSec}s (audio behind, trim head to align video)")
            } else {
                val delayMs = -offsetMs
                audioFilters.add("adelay=${delayMs}|${delayMs}:all=1")
                audioFilters.add("aresample=async=1000")
                Log.d(TAG, "Audio delay: +${delayMs}ms (audio ahead, push back to align video)")
            }
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
     * 轻量探测音视频起点 DTS 偏移（仅 start，不含漂移检测）
     *
     * 用于 PDT 路径与 tfdt 跨轨差对比：判断 fMP4 的 tfdt 是否已编码真实起点偏移。
     * 只读文件头(二进制 tfdt) + ffprobe 首 packet 兜底，不做末尾 packet PTS 探测，
     * 避免长视频 -show_packets 全量输出导致 OOM。
     *
     * 优先级: 二进制 tfdt(baseMediaDecodeTime) → ffprobe 首 packet dts_time
     * @return audioDts - videoDts (秒)；两轨都探测失败返回 null
     */
    /**
     * 轻量探测音视频起点 DTS 偏移（仅 start，不含漂移检测）
     *
     * 用于 PDT 路径与 tfdt 跨轨差对比：判断 fMP4 的 tfdt 是否已编码真实起点偏移。
     * 只读文件头(二进制 tfdt) + ffprobe 首 packet 兜底，不做末尾 packet PTS 探测，
     * 避免长视频 -show_packets 全量输出导致 OOM。
     *
     * 优先级: 二进制 tfdt(baseMediaDecodeTime) → ffprobe 首 packet dts_time
     * @return Triple(offsetSec, vStartSec, aStartSec)：
     *         offset = aStart - vStart (audioDts - videoDts, 秒)；
     *         vStart/aStart 为两轨 tfdt 绝对值(秒)，packet 兜底分支为 null；
     *         两轨都探测失败返回 null
     */
    private fun probeStartOffsetOnly(videoFile: File, audioFile: File): Triple<Double, Double?, Double?>? {
        val vStart = parseTfdtSeconds(videoFile)
        val aStart = parseTfdtSeconds(audioFile)
        if (vStart != null && aStart != null) {
            val offset = aStart - vStart
            Log.d(TAG, "probeStartOffsetOnly (tfdt): video=${"%.3f".format(vStart)}s audio=${"%.3f".format(aStart)}s offset=${"%.3f".format(offset)}s")
            return Triple(offset, vStart, aStart)
        }
        val vPkt = probePacketDts(videoFile, "video")
        val aPkt = probePacketDts(audioFile, "audio")
        if (vPkt != null && aPkt != null) {
            val offset = aPkt - vPkt
            Log.d(TAG, "probeStartOffsetOnly (ffprobe pkt): video=${"%.3f".format(vPkt)}s audio=${"%.3f".format(aPkt)}s offset=${"%.3f".format(offset)}s")
            return Triple(offset, null, null)
        }
        Log.d(TAG, "probeStartOffsetOnly: failed (tfdt=${vStart ?: "v∅"}/${aStart ?: "a∅"}, pkt=${vPkt ?: "v∅"}/${aPkt ?: "a∅"})")
        return null
    }

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
            // 只读尾部少量 packet，避免 -show_packets 全量输出导致长视频 OOM。
            // -read_intervals %-end 只取末尾，但为兼容性先取 duration 再读最后 5 秒。
            val durCmd = "-v quiet -print_format json -show_entries format=duration -i ${file.absolutePath}"
            val durSession = FFprobeKit.execute(durCmd)
            val durJson = JSONObject(durSession.output)
            val format = durJson.optJSONArray("format")?.optJSONObject(0)
                ?: durJson.optJSONObject("format")
            val duration = format?.optDouble("duration", -1.0) ?: -1.0
            val readInterval = if (duration > 5) {
                // 读最后 5 秒的 packet
                "${"%.3f".format(duration - 5)}-end"
            } else {
                // 短文件直接全读
                "%-end"
            }
            val cmd = "-v quiet -print_format json " +
                    "-select_streams ${if (codec == "video") "v:0" else "a:0"} " +
                    "-show_packets -read_intervals \"$readInterval\" " +
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