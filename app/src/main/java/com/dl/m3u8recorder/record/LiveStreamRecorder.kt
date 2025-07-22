package com.dl.m3u8recorder.record

import android.content.Context
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
        task.progress = 0 // 重置进度（如果需要从零开始）
        DownloadManager.listener?.onTaskUpdated(task)

        // 获取目标输出 TS 文件路径
        val outputTsFile = Mp4OutputHelper.getOutputFile(context, task.fileName, ".ts")

        // FFmpeg 命令：直接录制到 TS 文件。
        // 关键点：移除 -y 参数，允许 FFmpeg 向现有 TS 文件追加数据。
        val command = listOf(
            // "-y", // 已移除 -y 参数，允许追加写入
            "-i", task.url,
            "-c", "copy", // 复制流，不重新编码
            "-f", "mpegts", // 输出格式为 MPEG Transport Stream
            "-map", "0", // 映射所有流 (视频、音频等)
            "-max_muxing_queue_size", "1024", // 防止缓冲区溢出
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
                    // 录制过程完成，但还未转换为 MP4，所以进度不设为100%
                    Log.d(TAG, "录制成功 (TS文件已生成): ${task.id}")
                } else if (ReturnCode.isCancel(rc)) {
                    Log.d(TAG, "录制会话被取消/中断: ${task.id}")
                    // 如果被取消，TS 文件保持现状，为可能的恢复做准备
                } else {
                    task.statusMessage = "录制失败: $rc"
                    task.isCancelled = true
                    Log.e(TAG, "录制失败: ${task.id}, ReturnCode: $rc, 日志: ${completedSession.logsAsString}")
                }
                DownloadManager.listener?.onTaskUpdated(task)
            },
            { log ->
                Log.d(TAG, "FFmpeg 日志: ${log.message}")
            },
            { stats: Statistics ->
                if (task.isCancelled || task.isPaused) {
                    currentFFmpegSession.cancel() // 如果任务被外部取消或暂停，确保 FFmpeg 进程也被终止
                    return@executeAsync
                }

                val timeMs = stats.time.toLong()
                val sizeBytes = stats.size

                val elapsedSeconds = timeMs / 1000
                // 对于直播流，精确的进度百分比很难计算，因为不知道总时长。
                // 我们可以显示一个模拟的活跃进度，或者直接显示时长和文件大小。
                // 这里模拟一个渐进的进度，直到最终完成时才到100%。
                val simulatedProgress = (elapsedSeconds.toFloat() / 3600 * 99).roundToInt().coerceIn(0, 99) // 假设1小时模拟99%

                task.progress = simulatedProgress
                task.statusMessage = "录制中: ${formatDuration(timeMs)} / ${formatSize(sizeBytes)}"

                DownloadManager.listener?.onTaskUpdated(task)
            }
        )

        currentFFmpegSession = session
        activeLiveSessions[task.id] = currentFFmpegSession
    }

    /**
     * 将已累积的 TS 文件转换为最终的 MP4 文件。
     * 通常在用户明确停止录制时调用。
     * @param task 对应的 DownloadTask 对象。
     * @param inputTsFile 输入的 TS 文件。
     * @param outputMp4File 最终的 MP4 输出文件。
     */
    private suspend fun convertTsToMp4(task: DownloadTask, inputTsFile: File, outputMp4File: File) {
        task.statusMessage = "正在转换为 MP4 格式..."
        task.progress = 99 // 转换阶段的进度
        DownloadManager.listener?.onTaskUpdated(task)

        if (!inputTsFile.exists() || inputTsFile.length() == 0L) {
            task.statusMessage = "没有可转换的 TS 文件或文件为空。"
            task.progress = 100 // 如果没有数据，直接视为完成
            DownloadManager.listener?.onTaskUpdated(task)
            return
        }

        // 转换 TS 到 MP4 的 FFmpeg 命令。
        // -y: 覆盖如果 MP4 文件已存在
        // -i: 输入 TS 文件
        // -c copy: 复制流，不重新编码
        // -movflags +faststart: 优化 MP4，使元数据位于文件开头，便于流式播放
        val convertCommand = listOf(
            "-y",
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
        DownloadManager.listener?.onTaskUpdated(task)
    }

    /**
     * 暂停录制。仅停止 FFmpeg 进程，保留已录制的 TS 文件。
     * @param taskId 要暂停的任务 ID。
     */
    fun pauseRecording(taskId: String) {
        val session = activeLiveSessions[taskId]
        session?.cancel() // 这将停止 FFmpeg 进程，TS 文件将处于可追加的良好状态
        Log.d(TAG, "发送取消信号给录制会话 (暂停): $taskId")
        // 任务状态（isPaused, statusMessage）将在 DownloadManager 的 pauseTask 中更新
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
        session?.cancel() // 停止 FFmpeg 进程
        activeLiveSessions.remove(taskId)
        Log.d(TAG, "停止录制: $taskId")

        // 触发最终的 TS 到 MP4 转换
        CoroutineScope(Dispatchers.IO).launch {
            val task = DownloadManager.getTasks().find { it.id == taskId } ?: return@launch
            val outputTsFile = Mp4OutputHelper.getOutputFile(context, task.fileName, ".ts")
            val outputMp4File = Mp4OutputHelper.getOutputFile(context, task.fileName, ".mp4")
            convertTsToMp4(task, outputTsFile, outputMp4File)
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