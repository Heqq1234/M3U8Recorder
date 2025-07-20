package com.dl.m3u8recorder.record

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.Session
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.ReturnCode
import com.dl.m3u8recorder.model.DownloadTask
import com.dl.m3u8recorder.utils.Mp4OutputHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class LiveStreamRecorder(private val context: Context) {

    private var isRecording = false
    private var currentTaskId: String? = null

    private val executor = Executors.newSingleThreadExecutor()

    // 临时合并的 .ts 文件
    private var currentTsFile: File? = null

    // 开始录制
    fun startRecording(task: DownloadTask, onUpdate: (DownloadTask) -> Unit) {
        if (isRecording) {
            Log.w("LiveStreamRecorder", "已有录制任务在运行")
            return
        }
        isRecording = true
        currentTaskId = task.id

        // 输出的 .ts 文件，初始化
        val outputFile = Mp4OutputHelper.getOutputFile(context, task.fileName)
        currentTsFile = outputFile

        val m3u8Url = task.url

        // 构造 FFmpeg 命令
        val command = listOf(
            "-y",  // 强制覆盖
            "-i", m3u8Url,  // 输入 m3u8 地址
            "-c", "copy",  // 直接复制视频流，不重新编码
            "-f", "mpegts",  // 输出为 mpegts 格式
            "-max_muxing_queue_size", "1024",  // 防止缓冲区溢出
            outputFile.absolutePath  // 输出文件
        ).joinToString(" ")

        Log.d("LiveStreamRecorder", "开始录制命令: $command")

        // 执行 FFmpeg 命令
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

                    // 更新进度
                    val timeMs = stats.time.toLong()
                    val sizeBytes = stats.size
                    task.statusMessage = "录制中: ${formatDuration(timeMs)} / ${formatSize(sizeBytes)}"
                    onUpdate(task)
                }
            )
        }
    }

    // 停止录制
    fun stopRecording(onUpdate: ((DownloadTask) -> Unit)? = null, task: DownloadTask? = null) {
        if (!isRecording) return
        FFmpegKit.cancel()  // 停止 FFmpeg 会话
        isRecording = false
        task?.let {
            it.statusMessage = "已停止录制"
            onUpdate?.invoke(it)
        }
    }

    // 暂停录制
    fun pauseRecording() {
        if (isRecording) {
            FFmpegKit.cancel()  // 暂停 FFmpeg 进程
            isRecording = false
            Log.d("LiveStreamRecorder", "录制已暂停")
        }
    }

    // 恢复录制
    fun resumeRecording(task: DownloadTask, onUpdate: (DownloadTask) -> Unit) {
        if (!isRecording) {
            startRecording(task, onUpdate)  // 恢复录制
            Log.d("LiveStreamRecorder", "录制已恢复")
        }
    }

    // 格式化时间
    private fun formatDuration(ms: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ms))
    }

    // 格式化大小
    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return "%.2f MB".format(mb)
    }
}
