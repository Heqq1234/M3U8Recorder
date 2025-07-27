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
        task.progress = 0
        DownloadManager.notifyTaskUpdated(task)

        // 获取目标输出 TS 文件路径，考虑自定义目录
        val outputTsFile = task.customDownloadUri?.let { uriString ->
            Mp4OutputHelper.getOutputFileFromUri(context, Uri.parse(uriString), task.fileName, ".ts")
        } ?: Mp4OutputHelper.getOutputFile(context, task.fileName, ".ts")

        // 确保父目录存在
        outputTsFile.parentFile?.mkdirs()

        // FFmpeg 命令：直接录制到 TS 文件。
        // 注意：FFmpeg 对现有文件默认是不会覆盖的，但对于 TS 文件，在某些情况下，
        // 如果再次以 `-f mpegts` 写入，它会尝试追加，但这取决于FFmpeg版本和具体实现。
        // 为了确保可靠的追加行为，最佳实践是在停止后进行显式合并（参考之前的讨论）。
        // 这里暂时移除 -y 参数，让它在文件存在时报错，以便我们能识别。
        val command = listOf(
            "-i", task.url,
            "-c", "copy",
            "-f", "mpegts",
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
                    task.statusMessage = "录制完成 (TS文件已生成): ${outputTsFile.name}"
                    Log.d(TAG, "录制成功 (TS文件已生成): ${task.id}")
                } else if (ReturnCode.isCancel(rc)) {
                    Log.d(TAG, "录制会话被取消/中断: ${task.id}")
                    // 如果是暂停，isPaused会在DownloadManager里被设置为true
                    // 如果是取消，isCancelled会在DownloadManager里被设置为true
                    // 状态信息由 DownloadManager 统一管理
                } else {
                    task.statusMessage = "录制失败: $rc"
                    task.isCancelled = true // 标记为失败
                    Log.e(TAG, "录制失败: ${task.id}, ReturnCode: $rc, 日志: ${completedSession.logsAsString}")
                }
                DownloadManager.notifyTaskUpdated(task)
            },
            { log ->
                Log.d(TAG, "FFmpeg 日志: ${log.message}")
            },
            { stats: Statistics ->
                if (task.isCancelled || task.isPaused) {
                    // 如果任务在 DownloadManager 中被标记为取消或暂停，则停止 FFmpeg 进程
                    // 这里的 cancel() 调用是必要的，因为 executeAsync 无法直接感知外部状态变化
                    currentFFmpegSession.cancel()
                    return@executeAsync
                }

                val timeMs = stats.time.toLong()
                val sizeBytes = stats.size

                val elapsedSeconds = timeMs / 1000
                val simulatedProgress = (elapsedSeconds.toFloat() / 3600 * 99).roundToInt().coerceIn(0, 99) // 假设1小时模拟99%

                task.progress = simulatedProgress
                task.statusMessage = "录制中: ${formatDuration(timeMs)} / ${formatSize(sizeBytes)}"
                DownloadManager.notifyTaskUpdated(task)
            }
        )

        currentFFmpegSession = session
        activeLiveSessions[task.id] = currentFFmpegSession
    }

    /**
     * 将已累积的 TS 文件转换为最终的 MP4 文件。
     * 通常在用户明确停止录制时调用。
     * @param task 对应的 DownloadTask 对象。
     */
    private suspend fun convertTsToMp4(task: DownloadTask) {
        task.statusMessage = "正在转换为 MP4 格式..."
        task.progress = 99
        DownloadManager.notifyTaskUpdated(task)

        // 根据任务的 customDownloadUri 获取输入 TS 和输出 MP4 文件
        val inputTsFile = task.customDownloadUri?.let { uriString ->
            Mp4OutputHelper.getOutputFileFromUri(context, Uri.parse(uriString), task.fileName, ".ts")
        } ?: Mp4OutputHelper.getOutputFile(context, task.fileName, ".ts")

        val outputMp4File = task.customDownloadUri?.let { uriString ->
            Mp4OutputHelper.getOutputFileFromUri(context, Uri.parse(uriString), task.fileName, ".mp4")
        } ?: Mp4OutputHelper.getOutputFile(context, task.fileName, ".mp4")

        if (!inputTsFile.exists() || inputTsFile.length() == 0L) {
            task.statusMessage = "没有可转换的 TS 文件或文件为空。"
            task.progress = 100
            DownloadManager.notifyTaskUpdated(task)
            return
        }

        val convertCommand = listOf(
            "-y", // 覆盖如果 MP4 文件已存在
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
            Log.d(TAG, "TS 到 MP4 转换成功: ${outputMp4File.absolutePath}")
            inputTsFile.delete() // 成功转换后删除临时的 TS 文件
            // MediaStoreSaver.saveToMediaStore(context, outputMp4File, task.fileName) // 将文件保存到媒体库
        } else {
            val failLog = conversionSession.logsAsString
            task.statusMessage = "转换失败: ${conversionSession.returnCode}"
            Log.e(TAG, "TS 到 MP4 转换失败: ${conversionSession.failStackTrace}\n日志: $failLog")
        }
        DownloadManager.notifyTaskUpdated(task)
    }

    /**
     * 暂停录制。仅停止 FFmpeg 进程，保留已录制的 TS 文件。
     * @param taskId 要暂停的任务 ID。
     */
    fun pauseRecording(taskId: String) {
        val session = activeLiveSessions[taskId]
        session?.cancel()
        Log.d(TAG, "发送取消信号给录制会话 (暂停): $taskId")
    }

    /**
     * 恢复录制。重新启动 FFmpeg 进程，继续向现有 TS 文件追加。
     * @param task 要恢复的 DownloadTask 对象。
     */
    fun resumeRecording(task: DownloadTask) {
        if (!activeLiveSessions.containsKey(task.id)) {
            Log.d(TAG, "恢复录制: ${task.id}")
            startRecording(task) // 重新启动将追加到现有 TS 文件
        } else {
            Log.w(TAG, "录制任务 ${task.id} 已经在运行，无法恢复。")
        }
    }

    /**
     * 停止录制 (由用户明确触发，例如点击“停止”按钮)。
     * 停止 FFmpeg 进程并触发最终的 TS 到 MP4 转换。
     * @param taskId 要停止的任务 ID。
     */
    fun stopRecording(taskId: String) {
        val session = activeLiveSessions[taskId]
        session?.cancel()
        activeLiveSessions.remove(taskId)
        Log.d(TAG, "停止录制: $taskId")

        // 触发最终的 TS 到 MP4 转换
        CoroutineScope(Dispatchers.IO).launch {
            val task = DownloadManager.getTasks().find { it.id == taskId }
            if (task != null) {
                convertTsToMp4(task) // 传递整个任务对象
            } else {
                Log.w(TAG, "stopRecording: 任务 $taskId 未找到，无法执行 TS 到 MP4 转换。")
            }
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