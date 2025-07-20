package com.dl.m3u8recorder.record

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.Session
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.ReturnCode
import com.dl.m3u8recorder.model.DownloadTask
import com.dl.m3u8recorder.utils.Mp4OutputHelper
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class LiveStreamRecorder(private val context: Context) {

    private var isRecording = false
    private var currentTaskId: String? = null

    private val executor = Executors.newSingleThreadExecutor()

    fun startRecording(task: DownloadTask, onUpdate: (DownloadTask) -> Unit) {
        if (isRecording) {
            Log.w("LiveStreamRecorder", "已有录制任务在运行")
            return
        }
        isRecording = true
        currentTaskId = task.id

        val outputFile = Mp4OutputHelper.getOutputFile(context, task.fileName)
        val m3u8Url = task.url

        val command = listOf(
            "-y",
            "-i", m3u8Url,
            "-c", "copy",
            "-f", "mp4",
            outputFile.absolutePath
        ).joinToString(" ")

        Log.d("LiveStreamRecorder", "开始录制命令: $command")

        executor.submit {
            FFmpegKit.executeAsync(command,
                { session: Session ->
                    isRecording = false
                    val rc = session.returnCode
                    if (ReturnCode.isSuccess(rc)) {
                        task.statusMessage = "录制完成: ${outputFile.name}"
                    } else {
                        task.statusMessage = "录制失败: $rc"
                    }
                    onUpdate(task)
                },
                { log ->
                    Log.d("LiveStreamRecorder", log.message)
                },
                { stats: Statistics ->
                    if (!isRecording) return@executeAsync

                    val timeMs = stats.time.toLong()
                    val sizeBytes = stats.size
                    task.statusMessage = "录制中: ${formatDuration(timeMs)} / ${formatSize(sizeBytes)}"
                    onUpdate(task)
                }
            )
        }
    }

    fun stopRecording(onUpdate: ((DownloadTask) -> Unit)? = null, task: DownloadTask? = null) {
        if (!isRecording) return
        FFmpegKit.cancel()
        isRecording = false
        task?.let {
            it.statusMessage = "已停止录制"
            onUpdate?.invoke(it)
        }
    }

    private fun formatDuration(ms: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ms))
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return "%.2f MB".format(mb)
    }
}