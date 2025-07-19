package com.dl.m3u8recorder.record

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File

class LiveStreamRecorder(private val context: Context) {
    private var isRecording = false

    fun startRecording(m3u8Url: String, outputPath: String) {
        isRecording = true
        val command = listOf(
            "-y",
            "-i", m3u8Url,
            "-c", "copy",
            "-f", "mp4",
            outputPath
        ).joinToString(" ")

        Log.d("LiveStreamRecorder", "开始录制: $command")

        FFmpegKit.executeAsync(command) { session ->
            isRecording = false
            Log.d("LiveStreamRecorder", "录制完成: ${session.returnCode}")
        }
    }

    fun stopRecording() {
        if (isRecording) {
            FFmpegKit.cancel()
            isRecording = false
        }
    }
}