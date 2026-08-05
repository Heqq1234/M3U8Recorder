package com.dl.m3u8recorder.llhls

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

// TODO: 已废弃 — 没有任何地方调用。保留代码供参考，后续可删除。
// 功能被 PipeMuxer 替代（虽然 PipeMuxer 也处于过渡状态，最终目标是 RealTimeMuxer）。
/*
/**
 * 音视频流合成器
 * 用于将分离的视频流和音频流合成为最终文件
 *
 * 支持在 mux 前自动补齐不等长的音视频轨道：
 * - 视频短于音频 → 末尾补黑帧（tpad）
 * - 音频短于视频 → 末尾补静音（apad）
 *
 * 自动检测音视频 PTS 基准偏移并补偿，解决音画不同步问题。
 */
class StreamMuxer {
    companion object {
        private const val TAG = "StreamMuxer"
        private const val PTS_OFFSET_THRESHOLD = 0.005 // 5ms 以上才补偿
    }

    /**
     * 合并音频和视频流
     */
    fun mux(
        videoFile: File,
        audioFile: File,
        outputFile: File
    ): Boolean {
        if (!videoFile.exists()) {
            Log.e(TAG, "Video file does not exist: ${videoFile.absolutePath}")
            return false
        }

        if (!audioFile.exists()) {
            Log.e(TAG, "Audio file does not exist: ${audioFile.absolutePath}")
            return false
        }

        val command = listOf(
            "-i", videoFile.absolutePath,
            "-i", audioFile.absolutePath,
            "-c:v", "copy",
            "-c:a", "copy",
            "-movflags", "+faststart",
            "-y",
            outputFile.absolutePath
        ).joinToString(" ")

        Log.d(TAG, "Executing mux command: $command")

        val session = FFmpegKit.execute(command)

        return if (ReturnCode.isSuccess(session.returnCode)) {
            Log.d(TAG, "Mux successful: ${outputFile.absolutePath}")
            true
        } else {
            Log.e(TAG, "Mux failed: ${session.failStackTrace}")
            false
        }
    }

    /**
     * 使用时间戳修正合并音频和视频
     *
     * 在 mux 前检查两个文件的时长是否匹配。
     * 如果不匹配（例如一个 tracker 提前退出了），
     * 自动补齐短轨再 mux，保证输出文件有完整的音视频。
     *
     * 同时自动检测音视频 PTS 基准偏移并补偿，
     * 解决 LL-HLS 不同 rendition 之间 PTS 基数不同导致的音画不同步。
     *
     * @param padInfo SyncCoordinator.getPadInfo() 的结果：
     *                 Pair(短的那个轨道, 需要补齐的毫秒数)
     */
    fun muxWithTimestampFix(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        padInfo: Pair<SyncCoordinator.StreamType, Long>? = null
    ): Boolean {
        if (!videoFile.exists() || !audioFile.exists()) {
            Log.e(TAG, "Input files missing for mux")
            return false
        }

        var finalVideoFile = videoFile
        var finalAudioFile = audioFile

        if (padInfo != null) {
            val (shortType, padMs) = padInfo
            val padSec = "%.3f".format(padMs / 1000.0)
            Log.w(TAG, "音视频时长不匹配: ${shortType.name} 短了 ${padMs}ms，将补齐")
            Log.w(TAG, "补齐短轨: ${shortType.name} 短 ${padMs}ms (${padSec}s)")

            when (shortType) {
                SyncCoordinator.StreamType.VIDEO -> {
                    finalVideoFile = padVideoFile(videoFile, padSec)
                }
                SyncCoordinator.StreamType.AUDIO -> {
                    finalAudioFile = padAudioFile(audioFile, padSec)
                }
            }
        }

        // 检测音视频 PTS 基准偏移（正数 = 音频偏后）
        val ptsOffsetSec = detectPtsOffset(finalVideoFile, finalAudioFile)

        val args = mutableListOf<String>()
        args.add("-y")

        if (ptsOffsetSec != null && abs(ptsOffsetSec) > PTS_OFFSET_THRESHOLD) {
            val audioDelayMs = (ptsOffsetSec * 1000).toInt()
            Log.w(TAG, "检测到 PTS 偏移: ${"%.3f".format(ptsOffsetSec)}s (音频${if (ptsOffsetSec > 0) "偏后" else "偏前"}，${abs(audioDelayMs)}ms)，"
                    + "补偿: filter_complex + aac reencode")

            args.add("-i"); args.add(finalVideoFile.absolutePath)
            args.add("-i"); args.add(finalAudioFile.absolutePath)

            if (ptsOffsetSec > 0) {
                args.add("-filter_complex")
                args.add("[1:a]asetpts=PTS-${"%.6f".format(ptsOffsetSec)}/TB[aud]")
                args.add("-map"); args.add("0:v:0")
                args.add("-map"); args.add("[aud]")
            } else {
                args.add("-filter_complex")
                args.add("[1:a]adelay=${-audioDelayMs}|${-audioDelayMs}[aud]")
                args.add("-map"); args.add("0:v:0")
                args.add("-map"); args.add("[aud]")
            }

            args.add("-c:v"); args.add("copy")
            args.add("-c:a"); args.add("aac"); args.add("-b:a"); args.add("128k")
        } else {
            args.add("-i"); args.add(finalVideoFile.absolutePath)
            args.add("-i"); args.add(finalAudioFile.absolutePath)
            args.add("-c:v"); args.add("copy")
            args.add("-c:a"); args.add("copy")
            args.add("-map"); args.add("0:v:0")
            args.add("-map"); args.add("1:a:0")
        }

        args.addAll(listOf(
            "-avoid_negative_ts", "make_zero",
            "-fflags", "+genpts",
            "-movflags", "+faststart"
        ))

        args.add(outputFile.absolutePath)

        val command = args.joinToString(" ")
        Log.d(TAG, "Executing mux with timestamp fix: $command")

        val session = FFmpegKit.execute(command)

        if (finalVideoFile != videoFile) {
            finalVideoFile.delete()
        }
        if (finalAudioFile != audioFile) {
            finalAudioFile.delete()
        }

        return if (ReturnCode.isSuccess(session.returnCode)) {
            Log.d(TAG, "Mux with timestamp fix successful: ${outputFile.absolutePath}")
            true
        } else {
            Log.e(TAG, "Mux with timestamp fix failed: ${session.failStackTrace}")
            false
        }
    }

    /**
     * 检测音视频文件的 PTS 基准偏移
     *
     * 使用 FFprobe 读取两个文件的 start_time，计算音频相对于视频的偏移。
     * FFprobe 能正确处理 edit list 和 CTS 偏移，比二进制解析更准确。
     *
     * @return 音频 PTS - 视频 PTS（秒），正数=音频偏后，null=无法检测
     */
    private fun detectPtsOffset(videoFile: File, audioFile: File): Double? {
        try {
            val videoStart = probeStartTimeFfprobe(videoFile, "video")
            val audioStart = probeStartTimeFfprobe(audioFile, "audio")

            if (videoStart != null && audioStart != null) {
                val offset = audioStart - videoStart
                Log.d(TAG, "FFprobe: video_start=${"%.3f".format(videoStart)}s, "
                        + "audio_start=${"%.3f".format(audioStart)}s, offset=${"%.3f".format(offset)}s")
                return offset
            }
        } catch (e: Exception) {
            Log.w(TAG, "FFprobe PTS detection failed, fallback to binary probe: ${e.message}")
        }

        // 降级到二进制解析
        try {
            val videoStart = probeStartTime(videoFile)
            val audioStart = probeStartTime(audioFile)
            if (videoStart != null && audioStart != null) {
                val offset = audioStart - videoStart
                Log.d(TAG, "Binary probe fallback: video_start=${"%.3f".format(videoStart)}s, "
                        + "audio_start=${"%.3f".format(audioStart)}s, offset=${"%.3f".format(offset)}s")
                return offset
            }
        } catch (e: Exception) {
            Log.w(TAG, "Binary PTS detection also failed: ${e.message}")
        }
        return null
    }

    /** 使用 FFprobeKit 从文件中提取指定类型流的 start_time（秒） */
    private fun probeStartTimeFfprobe(file: File, expectedCodec: String): Double? {
        val cmd = "-v quiet -print_format json -show_entries stream=start_time,codec_type -i ${file.absolutePath}"
        val session = FFprobeKit.execute(cmd)
        val output = session.output

        val json = JSONObject(output)
        val streams = json.getJSONArray("streams")
        for (i in 0 until streams.length()) {
            val stream = streams.getJSONObject(i)
            if (stream.optString("codec_type") == expectedCodec) {
                return stream.optDouble("start_time", -1.0).takeIf { it >= 0 }
            }
        }
        return null
    }

    /**
     * 在容器 box 内部递归搜索 mdhd box 提取 timescale
     */
    private fun searchMdhdInContainer(raf: RandomAccessFile, boxPos: Long, boxSize: Long): Long? {
        val end = boxPos + boxSize
        var innerPos = boxPos + 8
        while (innerPos < end - 8) {
            raf.seek(innerPos)
            val childSize = readU32(raf)
            val childType = readBoxType(raf)
            if (childSize < 8 || childSize > end - innerPos) break
            when (childType) {
                "mdhd" -> {
                    raf.seek(innerPos + 8)
                    val version = raf.readUnsignedByte().toInt()
                    readU24(raf)
                    if (version == 0) {
                        raf.skipBytes(8)
                        return readU32(raf)
                    } else {
                        raf.skipBytes(16)
                        return readU32(raf)
                    }
                }
                "moov", "trak", "mdia", "minf", "stbl", "edts", "tkhd" -> {
                    // 这些是容器 box，递归搜索
                    val found = searchMdhdInContainer(raf, innerPos, childSize)
                    if (found != null) return found
                }
            }
            innerPos += childSize
        }
        return null
    }

    /**
     * 直接从 MP4 文件头部解析首个片段的 PTS 起始值（秒）
     *
     * 通过读取 fMP4 的 moof → traf → tfdt 盒子中的 baseMediaDecodeTime，
     * 比解析 ffmpeg 日志更可靠。
     *
     * @return 首个片段的 PTS（秒），null 表示解析失败
     */
    private fun probeStartTime(file: File): Double? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val fileSize = raf.length()
                if (fileSize < 16) return null

                // 跳过 ftyp box 和 moov box（init segment），找到第一个 moof
                var pos = 0L
                var foundMoof = false
                var timescale = 0L
                var basePts = -1L

                while (pos < fileSize - 8) {
                    raf.seek(pos)
                    val boxSize = readU32(raf)
                    val boxType = readBoxType(raf)

                    if (boxSize < 8 || boxSize > fileSize - pos) break

                    when (boxType) {
                        "moov", "trak", "mdia", "minf", "stbl", "edts" -> {
                            // 容器 box：递归搜索内部 mdhd 获取 timescale
                            val inner = searchMdhdInContainer(raf, pos, boxSize)
                            if (inner != null) timescale = inner
                        }
                        "moof" -> {
                            foundMoof = true
                            // 在 moof 内部查找 tfdt
                            val moofEnd = pos + boxSize
                            var innerPos = pos + 8
                            while (innerPos < moofEnd - 8) {
                                raf.seek(innerPos)
                                val innerSize = readU32(raf)
                                val innerType = readBoxType(raf)

                                if (innerSize < 8 || innerSize > moofEnd - innerPos) break

                                if (innerType == "traf") {
                                    // 在 traf 内部查找 tfdt
                                    val trafEnd = innerPos + innerSize
                                    var trafPos = innerPos + 8
                                    while (trafPos < trafEnd - 8) {
                                        raf.seek(trafPos)
                                        val childSize = readU32(raf)
                                        val childType = readBoxType(raf)

                                        if (childSize < 8 || childSize > trafEnd - trafPos) break

                                        if (childType == "tfdt") {
                                            raf.seek(trafPos + 8)
                                            val tfdtVersion = raf.readUnsignedByte().toInt()
                                            readU24(raf) // flags
                                            basePts = if (tfdtVersion == 1) {
                                                readU64(raf) // version 1: 64-bit
                                            } else {
                                                readU32(raf).toLong() // version 0: 32-bit
                                            }
                                            if (timescale > 0) {
                                                return basePts.toDouble() / timescale.toDouble()
                                            }
                                        }
                                        trafPos += childSize
                                    }
                                }
                                innerPos += innerSize
                            }
                        }
                        "mdat" -> {
                            // 到了 mdat 表示已经过了所有 moof，停止搜索
                            break
                        }
                    }
                    if (foundMoof && basePts >= 0 && timescale > 0) break
                    pos += boxSize
                }

                // 如果找到 PTS 但没有 timescale，用默认值 1000（很多流用 1000）
                if (basePts >= 0) {
                    val effectiveTimescale = if (timescale > 0) timescale else 1000L
                    return basePts.toDouble() / effectiveTimescale.toDouble()
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Binary PTS probe failed: ${e.message}")
            null
        }
    }

    /** 读取 4 字节大端无符号整数 */
    private fun readU32(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.readFully(b)
        return ((b[0].toLong() and 0xFF) shl 24) or
               ((b[1].toLong() and 0xFF) shl 16) or
               ((b[2].toLong() and 0xFF) shl 8) or
               (b[3].toLong() and 0xFF)
    }

    /** 读取 8 字节大端无符号长整数 */
    private fun readU64(raf: RandomAccessFile): Long {
        val hi = readU32(raf)
        val lo = readU32(raf)
        return (hi shl 32) or lo
    }

    /** 读取 3 字节大端无符号整数 */
    private fun readU24(raf: RandomAccessFile): Int {
        val b = ByteArray(3)
        raf.readFully(b)
        return ((b[0].toInt() and 0xFF) shl 16) or
               ((b[1].toInt() and 0xFF) shl 8) or
               (b[2].toInt() and 0xFF)
    }

    /** 读取 4 字节 box type 字符串 */
    private fun readBoxType(raf: RandomAccessFile): String {
        val b = ByteArray(4)
        raf.readFully(b)
        return String(b, Charsets.ISO_8859_1)
    }

    /**
     * 给视频文件末尾补齐黑帧
     * 使用 FFmpeg tpad filter
     *
     * libx264 在部分 FFmpegKit 构建中不可用，自动回退到 mpeg4 编码器。
     */
    private fun padVideoFile(inputFile: File, durationSec: String): File {
        val outputFile = File(inputFile.parentFile, "${inputFile.nameWithoutExtension}_padded.mp4")

        // 尝试不同的视频编码器（libx264 在部分构建中不可用）
        val encoders = listOf(
            listOf("-c:v", "libx264", "-preset", "ultrafast"),
            listOf("-c:v", "libopenh264"),
            listOf("-c:v", "mpeg4", "-qscale:v", "5")
        )

        for (encoderArgs in encoders) {
            val encoderName = encoderArgs[1]
            val command = mutableListOf(
                "-i", inputFile.absolutePath,
                "-vf", "tpad=stop_mode=clone:stop_duration=$durationSec"
            )
            command.addAll(encoderArgs)
            command.addAll(listOf(
                "-c:a", "copy",
                "-movflags", "+faststart",
                "-y",
                outputFile.absolutePath
            ))

            val cmdStr = command.joinToString(" ")
            Log.d(TAG, "Padding video with $encoderName: $cmdStr")
            val session = FFmpegKit.execute(cmdStr)

            if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists()) {
                Log.d(TAG, "Video padded successfully with $encoderName")
                return outputFile
            }
            // 删除失败的输出文件
            if (outputFile.exists()) outputFile.delete()
            Log.w(TAG, "Encoder $encoderName failed, trying next...")
        }

        Log.w(TAG, "All video pad encoders failed, using original")
        return inputFile
    }

    /**
     * 给音频文件末尾补齐静音
     * 使用 FFmpeg apad filter
     */
    private fun padAudioFile(inputFile: File, durationSec: String): File {
        val outputFile = File(inputFile.parentFile, "${inputFile.nameWithoutExtension}_padded.m4a")
        // apad: pad_dur 指定补齐时长
        val command = listOf(
            "-i", inputFile.absolutePath,
            "-af", "apad=pad_dur=$durationSec",
            "-c:v", "copy",
            "-c:a", "aac",
            "-b:a", "128k",
            "-y",
            outputFile.absolutePath
        ).joinToString(" ")

        Log.d(TAG, "Padding audio with $durationSec s: $command")
        val session = FFmpegKit.execute(command)

        return if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists()) {
            outputFile
        } else {
            Log.w(TAG, "Audio pad failed, using original")
            inputFile
        }
    }

    /**
     * 将单个视频流重命名为输出文件（无音频的情况）
     */
    fun copySingleStream(
        videoFile: File,
        outputFile: File
    ): Boolean {
        if (!videoFile.exists()) {
            Log.e(TAG, "Video file does not exist: ${videoFile.absolutePath}")
            return false
        }

        return try {
            videoFile.copyTo(outputFile, overwrite = true)
            Log.d(TAG, "Single stream copied: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy single stream", e)
            false
        }
    }
}
*/