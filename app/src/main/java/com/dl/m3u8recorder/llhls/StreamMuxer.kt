package com.dl.m3u8recorder.llhls

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File

/**
 * 音视频流合成器
 * 用于将分离的视频流和音频流合成为最终文件
 */
class StreamMuxer {
    companion object {
        private const val TAG = "StreamMuxer"
    }

    /**
     * 合并音频和视频流
     * @param videoFile 视频流文件
     * @param audioFile 音频流文件
     * @param outputFile 输出文件
     * @return 是否成功
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

        return if (session.returnCode.isSuccess) {
            Log.d(TAG, "Mux successful: ${outputFile.absolutePath}")
            true
        } else {
            Log.e(TAG, "Mux failed: ${session.failStackTrace}")
            false
        }
    }

    /**
     * 使用时间戳修正合并音频和视频
     * 解决可能的音画不同步问题
     */
    fun muxWithTimestampFix(
        videoFile: File,
        audioFile: File,
        outputFile: File
    ): Boolean {
        if (!videoFile.exists() || !audioFile.exists()) {
            Log.e(TAG, "Input files missing for mux")
            return false
        }

        val command = listOf(
            "-i", videoFile.absolutePath,
            "-i", audioFile.absolutePath,
            "-fflags", "+genpts",
            "-avoid_negative_ts", "make_zero",
            "-max_interleave_delta", "0",
            "-c:v", "copy",
            "-c:a", "copy",
            "-movflags", "+faststart",
            "-y",
            outputFile.absolutePath
        ).joinToString(" ")

        Log.d(TAG, "Executing mux with timestamp fix: $command")

        val session = FFmpegKit.execute(command)

        return if (session.returnCode.isSuccess) {
            Log.d(TAG, "Mux with timestamp fix successful: ${outputFile.absolutePath}")
            true
        } else {
            Log.e(TAG, "Mux with timestamp fix failed: ${session.failStackTrace}")
            false
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

        // 简单重命名或复制
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
