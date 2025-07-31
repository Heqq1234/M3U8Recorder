package com.dl.m3u8recorder.record

import android.content.Context
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.Session
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.ReturnCode
import com.dl.m3u8recorder.manager.DownloadManager
import com.dl.m3u8recorder.model.DownloadTask
import com.dl.m3u8recorder.utils.MediaStoreSaver
import com.dl.m3u8recorder.utils.Mp4OutputHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LiveStreamRecorder(private val context: Context) {

    private val TAG = "LiveStreamRecorder"
    private val activeLiveSessions = mutableMapOf<String, FFmpegSession>()

    /**
     * 开始录制直播任务，直接写入到 TS 文件，支持追加。
     * @param task 要录制的 DownloadTask 对象。
     */
    fun startRecording(task: DownloadTask) {
        if (activeLiveSessions.containsKey(task.id)) {
            Log.w(TAG, "任务 ${task.id} 已在录制中。")
            return
        }

        task.isCancelled = false
        task.isPaused = false
        task.statusMessage = "开始录制直播流..."
        DownloadManager.notifyTaskUpdated(task)

        val outputTsFile = task.customDownloadUri?.let { uriString ->
            Mp4OutputHelper.getOutputFileFromUri(context, Uri.parse(uriString), task.fileName, ".ts")
        } ?: Mp4OutputHelper.getOutputFile(context, task.fileName, ".ts")

        // 确保父目录存在
        outputTsFile.parentFile?.mkdirs()

        // FFmpeg 命令：直播流录制到 TS 文件，FFmpeg 在文件已存在时默认会追加。
        val command = listOf(
            "-i", task.url,
            "-c", "copy",
            "-f", "mpegts",
            "-flags", "+global_header",
            "-map", "0",
            "-max_muxing_queue_size", "1024",
            outputTsFile.absolutePath // 输出文件路径
        ).joinToString(" ")

        Log.d(TAG, "开始录制命令: $command")

        lateinit var currentFFmpegSession: FFmpegSession

        val session = FFmpegKit.executeAsync(command,
            { completedSession: Session ->
                activeLiveSessions.remove(task.id)
                val rc = completedSession.returnCode

                if (ReturnCode.isSuccess(rc)) {
                    task.statusMessage = "录制完成 (TS文件已生成)"
                    task.progress = 100
                    Log.d(TAG, "录制成功 (TS文件已生成): ${task.id}")

                    // 成功结束，触发转换
                    CoroutineScope(Dispatchers.IO).launch {
                        convertTsToMp4(task)
                    }
                } else if (ReturnCode.isCancel(rc)) {
                    Log.d(TAG, "录制会话被用户取消/中断: ${task.id}")
                    task.isCancelled = true
                    task.statusMessage = "已取消"
                    // 用户取消，不进行转换，只清理状态。
                    // 暂停时也会进入这个分支，但不应该转换。
                } else {
                    // 可能是直播源断开等原因导致的失败，也触发转换
                    task.statusMessage = "录制意外结束，正在尝试转换..."
                    task.progress = 99
                    Log.e(TAG, "录制失败: ${task.id}, ReturnCode: $rc, 日志: ${completedSession.logsAsString}")

                    // 失败结束，也触发转换。
                    CoroutineScope(Dispatchers.IO).launch {
                        convertTsToMp4(task)
                    }
                }
                DownloadManager.notifyTaskUpdated(task)
            },
            { log ->
                Log.d(TAG, "FFmpeg 日志: ${log.message}")
            },
            { stats: Statistics ->
                val timeMs = stats.time.toLong()
                val sizeBytes = stats.size

                task._downloadedSize.value = sizeBytes
                task._elapsedTime.value = timeMs

                val simulatedProgress = (timeMs.toFloat() / 3600_000 * 99).roundToInt().coerceIn(0, 99)
                task.progress = simulatedProgress

                task.statusMessage = "下载中"
                DownloadManager.notifyTaskUpdated(task)
            }
        )
        currentFFmpegSession = session
        activeLiveSessions[task.id] = currentFFmpegSession
    }

    /**
     * 将已累积的 TS 文件转换为最终的 MP4 文件。
     * **关键修改点：每次转换都生成一个带时间戳的新文件**
     */
    private suspend fun convertTsToMp4(task: DownloadTask) {
        task.statusMessage = "正在转换为 MP4 格式..."
        task.progress = 99
        DownloadManager.notifyTaskUpdated(task)

        // 获取输入 TS 文件
        val inputTsFile = task.customDownloadUri?.let { uriString ->
            Mp4OutputHelper.getOutputFileFromUri(context, Uri.parse(uriString), task.fileName, ".ts")
        } ?: Mp4OutputHelper.getOutputFile(context, task.fileName, ".ts")

        if (!inputTsFile.exists() || inputTsFile.length() == 0L) {
            task.statusMessage = "没有可转换的 TS 文件或文件为空。"
            task.progress = 100
            DownloadManager.notifyTaskUpdated(task)
            return
        }

        // 动态生成带时间戳的新文件名，避免覆盖
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val outputMp4FileName = "${task.fileName}_$timestamp.mp4"

        val outputMp4File = task.customDownloadUri?.let { uriString ->
            Mp4OutputHelper.getOutputFileFromUri(context, Uri.parse(uriString), outputMp4FileName, "")
        } ?: Mp4OutputHelper.getOutputFile(context, outputMp4FileName, "")

        val convertCommand = listOf(
            "-i", inputTsFile.absolutePath,
            "-c", "copy",
            "-movflags", "+faststart",
            outputMp4File.absolutePath
        ).joinToString(" ")

        Log.d(TAG, "开始 TS 到 MP4 转换命令: $convertCommand")

        val conversionSession = FFmpegKit.execute(convertCommand)

        if (conversionSession.returnCode.isSuccess) {
            task.statusMessage = "录制完成: ${outputMp4File.name}"
            task.progress = 100
            task._downloadedSize.value = outputMp4File.length()
            task._elapsedTime.value = conversionSession.duration
            Log.d(TAG, "TS 到 MP4 转换成功: ${outputMp4File.absolutePath}")
            inputTsFile.delete()
            MediaStoreSaver.saveToMediaStore(context, outputMp4File, task.fileName, task.customDownloadUri)
        } else {
            val failLog = conversionSession.logsAsString
            task.statusMessage = "转换失败: ${conversionSession.returnCode}"
            Log.e(TAG, "TS 到 MP4 转换失败: ${conversionSession.failStackTrace}\n日志: $failLog")
        }
        DownloadManager.notifyTaskUpdated(task)
    }

    /**
     * 暂停录制。仅停止 FFmpeg 进程，保留已录制的 TS 文件。
     */
    fun pauseRecording(taskId: String) {
        val session = activeLiveSessions[taskId]
        session?.cancel()
        Log.d(TAG, "发送取消信号给录制会话 (暂停): $taskId")

        val task = DownloadManager.getTasks().find { it.id == taskId }
        if (task != null) {
            task.isPaused = true
            task.statusMessage = "已暂停"
            DownloadManager.notifyTaskUpdated(task)
        }
    }

    /**
     * 恢复录制。重新启动 FFmpeg 进程，继续向现有 TS 文件追加。
     */
    fun resumeRecording(task: DownloadTask) {
        if (!activeLiveSessions.containsKey(task.id)) {
            Log.d(TAG, "恢复录制: ${task.id}")
            startRecording(task)
        } else {
            Log.w(TAG, "录制任务 ${task.id} 已经在运行，无法恢复。")
        }
    }

    /**
     * 停止录制 (由用户明确触发，例如点击“停止”按钮)。
     */
    fun stopRecording(taskId: String) {
        val session = activeLiveSessions[taskId]
        session?.cancel()
        activeLiveSessions.remove(taskId)
        Log.d(TAG, "停止录制: $taskId")

        CoroutineScope(Dispatchers.IO).launch {
            val task = DownloadManager.getTasks().find { it.id == taskId }
            if (task != null) {
                convertTsToMp4(task)
            } else {
                Log.w(TAG, "stopRecording: 任务 $taskId 未找到，无法执行 TS 到 MP4 转换。")
            }
        }
    }
}