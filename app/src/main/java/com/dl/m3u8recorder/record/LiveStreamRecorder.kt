package com.dl.m3u8recorder.record

import android.content.Context
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.Session
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.ReturnCode
import com.dl.m3u8recorder.llhls.LLHlsRecorder
import com.dl.m3u8recorder.manager.DownloadManager
import com.dl.m3u8recorder.model.DownloadTask
import com.dl.m3u8recorder.utils.MediaStoreSaver
import com.dl.m3u8recorder.utils.Mp4OutputHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LiveStreamRecorder(private val context: Context) {

    private val TAG = "LiveStreamRecorder"
    private val activeLiveSessions = ConcurrentHashMap<String, FFmpegSession>()

    // Phase 4: LL-HLS 录制器 - 使用 DownloadManager 中配置好的 OkHttpClient
    private val llhlsRecorder by lazy { LLHlsRecorder(context, DownloadManager.getOkHttpClient()) }
    private val activeLLHlsSessions = ConcurrentHashMap.newKeySet<String>()

    private val convertingTasks = ConcurrentHashMap.newKeySet<String>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 开始录制直播任务
     * Phase 4: 根据是否为 LL-HLS 选择不同的录制方式
     */
    fun startRecording(task: DownloadTask) {
        if (activeLiveSessions.containsKey(task.id) || activeLLHlsSessions.contains(task.id)) {
            Log.w(TAG, "任务 ${task.id} 已在录制中。")
            return
        }

        // Phase 4: 检测是否为 LL-HLS 直播
        if (task.isLLHls) {
            startLLHlsRecording(task)
        } else {
            startTraditionalRecording(task)
        }
    }

    /**
     * 开始 LL-HLS 录制
     */
    private fun startLLHlsRecording(task: DownloadTask) {
        activeLLHlsSessions.add(task.id)
        task.isCancelled = false
        task.isPaused = false
        task.statusMessage = "开始 LL-HLS 录制..."
        DownloadManager.notifyTaskUpdated(task)

        scope.launch {
            try {
                val outputFile = task.customDownloadUri?.let { uriString ->
                    Mp4OutputHelper.getOutputFileFromUri(context, Uri.parse(uriString), task.fileName, ".mp4")
                } ?: Mp4OutputHelper.getOutputFile(context, task.fileName, ".mp4")

                llhlsRecorder.startRecording(task) { progress, status ->
                    task.progress = progress.coerceIn(0, 100)
                    task.statusMessage = status
                    DownloadManager.notifyTaskUpdated(task)
                }

                // startRecording 在此阻塞直到录制被停止。
                // 可能是：1) 用户手动停止 (stopRecording 已合成+保存)
                //         2) 直播流自然结束 (tracker 检测到 ENDLIST/连续错误后退出)
                //
                // 用 hasActiveSession 判断：如果 session 还在，说明 stopRecording 没被调过，
                // 是自然结束，需要在此合成+保存。如果 session 已被移除，stopRecording 已处理。

                if (llhlsRecorder.hasActiveSession(task.id)) {
                    task.statusMessage = "录制结束，正在合成文件..."
                    DownloadManager.notifyTaskUpdated(task)

                    val outputFile = llhlsRecorder.stopRecording(task.id)
                    if (outputFile != null) {
                        task.statusMessage = "录制完成"
                        task.progress = 100
                        MediaStoreSaver.saveToMediaStore(context, outputFile, task.fileName, task.customDownloadUri)
                    } else {
                        task.statusMessage = "合成失败: 无法生成输出文件"
                        task.isCancelled = true
                    }
                }
            } catch (e: Exception) {
                task.statusMessage = "录制错误: ${e.message}"
                task.isCancelled = true
            } finally {
                activeLLHlsSessions.remove(task.id)
                DownloadManager.notifyTaskUpdated(task)
            }
        }
    }

    /**
     * 开始传统直播录制（FFmpeg 方式）
     */
    private fun startTraditionalRecording(task: DownloadTask) {
        task.isCancelled = false
        task.isPaused = false
        task.statusMessage = "开始录制直播流..."
        DownloadManager.notifyTaskUpdated(task)

        // 获取目标输出 TS 文件路径
        val outputTsFile = task.customDownloadUri?.let { uriString ->
            Mp4OutputHelper.getOutputFileFromUri(context, Uri.parse(uriString), task.fileName, ".ts")
        } ?: Mp4OutputHelper.getOutputFile(context, task.fileName, ".ts")

        // 确保父目录存在
        outputTsFile.parentFile?.mkdirs()
        // Phase 1: 添加时间戳修正参数解决音画不同步和卡顿问题
        val command = listOf(
            "-i", task.url,
            "-fflags", "+genpts",
            "-avoid_negative_ts", "make_zero",
            "-c:v", "copy",
            "-c:a", "copy",
            "-f", "mpegts",
            "-flags", "+global_header",
            "-map", "0",
            "-max_muxing_queue_size", "1024",
            "-flush_packets", "1",
            outputTsFile.absolutePath
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

                    if (convertingTasks.add(task.id)) {
                        scope.launch {
                            convertTsToMp4(task)
                            convertingTasks.remove(task.id)
                        }
                    } else {
                        Log.d(TAG, "Task ${task.id} already converting, skipping callback conversion")
                    }

                } else if (ReturnCode.isCancel(rc)) {
                    Log.d(TAG, "录制会话被用户取消/中断: ${task.id}")
                } else {
                    task.statusMessage = "录制意外结束，正在尝试转换..."
                    task.progress = 99
                    Log.e(TAG, "录制失败: ${task.id}, ReturnCode: $rc, 日志: ${completedSession.logsAsString}")

                    if (convertingTasks.add(task.id)) {
                        scope.launch {
                            convertTsToMp4(task)
                            convertingTasks.remove(task.id)
                        }
                    } else {
                        Log.d(TAG, "Task ${task.id} already converting, skipping callback conversion")
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
     */
    private suspend fun convertTsToMp4(task: DownloadTask) {
        task.statusMessage = "正在转换为 MP4 格式..."
        task.progress = 99
        DownloadManager.notifyTaskUpdated(task)

        // 获取输入 TS 和输出 MP4 文件
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

        // Phase 1: TS 到 MP4 转换也需要时间戳修正
        val convertCommand = listOf(
            "-i", inputTsFile.absolutePath,
            "-fflags", "+genpts",
            "-avoid_negative_ts", "make_zero",
            "-c:v", "copy",
            "-c:a", "copy",
            "-movflags", "+faststart",
            outputMp4File.absolutePath
        ).joinToString(" ")

        Log.d(TAG, "开始 TS 到 MP4 转换命令: $convertCommand")

        val conversionSession = FFmpegKit.execute(convertCommand)

        if (ReturnCode.isSuccess(conversionSession.returnCode)) {
            task.statusMessage = "录制完成: ${outputMp4File.name}"
            task.progress = 100
            task._downloadedSize.value = outputMp4File.length()
            task._elapsedTime.value = conversionSession.duration
            Log.d(TAG, "TS 到 MP4 转换成功: ${outputMp4File.absolutePath}")
            // 删除临时 TS 文件
            inputTsFile.delete()
            // 将临时 MP4 文件保存到用户指定目录
            val savedUri = MediaStoreSaver.saveToMediaStore(context, outputMp4File, task.fileName, task.customDownloadUri)

            // 如果文件成功保存到指定目录，则删除临时 MP4 文件
            if (savedUri != null) {
                outputMp4File.delete()
                Log.d(TAG, "已删除临时 MP4 文件: ${outputMp4File.absolutePath}")
            } else {
                // 如果保存失败，可能需要处理错误，但临时文件暂时保留以便调试
                Log.e(TAG, "文件保存到指定目录失败，临时 MP4 文件未删除。")
            }
        } else {
            val failLog = conversionSession.logsAsString
            task.statusMessage = "转换失败: ${conversionSession.returnCode}"
            Log.e(TAG, "TS 到 MP4 转换失败: ${conversionSession.failStackTrace}\n日志: $failLog")
        }
        DownloadManager.notifyTaskUpdated(task)
    }

    /**
     * 暂停录制。
     * - LL-HLS: 合并导出当前内容，生成可播放的 MP4
     * - 传统 FFmpeg: 停止进程，保留 TS 文件
     * @param taskId 要暂停的任务 ID。
     */
    fun pauseRecording(taskId: String) {
        // 检查是否是 LL-HLS 会话
        if (activeLLHlsSessions.contains(taskId)) {
            scope.launch {
                val task = DownloadManager.getTasks().find { it.id == taskId }
                if (task != null) {
                    // 生成带时间戳的输出文件名
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val outputFileName = "${task.fileName}_paused_$timestamp"

                    val outputFile = task.customDownloadUri?.let { uriString ->
                        Mp4OutputHelper.getOutputFileFromUri(context, Uri.parse(uriString), outputFileName, ".mp4")
                    } ?: Mp4OutputHelper.getOutputFile(context, outputFileName, ".mp4")

                    // 停止 pipe muxer（这会移除 session）
                    val resultFile = llhlsRecorder.stopRecording(taskId)
                    activeLLHlsSessions.remove(taskId)

                    if (resultFile != null) {
                        MediaStoreSaver.saveToMediaStore(context, resultFile, outputFileName)
                    }

                    task.statusMessage = "已暂停（已保存: ${outputFile.name}）"
                    task.isPaused = true
                    DownloadManager.notifyTaskUpdated(task)
                }
            }
            Log.d(TAG, "暂停 LL-HLS 录制并导出: $taskId")
            return
        }

        // 传统 FFmpeg 录制暂停
        val session = activeLiveSessions[taskId]
        session?.cancel()
        Log.d(TAG, "发送取消信号给录制会话 (暂停): $taskId")
    }

    /**
     * 恢复录制。
     * - LL-HLS: 作为新任务重新开始（因为直播内容已过去）
     * - 传统 FFmpeg: 继续向现有 TS 文件追加
     * @param task 要恢复的 DownloadTask 对象。
     */
    fun resumeRecording(task: DownloadTask) {
        // LL-HLS 恢复：重新开始
        if (task.isLLHls) {
            if (!activeLLHlsSessions.contains(task.id)) {
                Log.d(TAG, "恢复 LL-HLS 录制 (重新开始): ${task.id}")
                task.isPaused = false
                task.isCancelled = false
                startLLHlsRecording(task)
            } else {
                Log.w(TAG, "LL-HLS 任务 ${task.id} 已经在运行，无法恢复。")
            }
            return
        }

        // 传统 FFmpeg 恢复
        if (!activeLiveSessions.containsKey(task.id)) {
            Log.d(TAG, "恢复录制: ${task.id}")
            startRecording(task)
        } else {
            Log.w(TAG, "录制任务 ${task.id} 已经在运行，无法恢复。")
        }
    }

    /**
     * 停止录制 (由用户明确触发，例如点击"停止"按钮)。
     * 停止 FFmpeg 进程或 LL-HLS 录制器。
     * @param taskId 要停止的任务 ID。
     */
    fun stopRecording(taskId: String) {
        // 检查是否是 LL-HLS 会话
        if (activeLLHlsSessions.contains(taskId)) {
            // llhlsRecorder.stopRecording 会：取消 tracker 协程 → 合成音视频 → 移除 session
            // 合成和保存完成后，startLLHlsRecording 中 startRecording 返回后会检查
            // hasActiveSession，发现 session 已被移除就不会重复保存。
            scope.launch {
                val task = DownloadManager.getTasks().find { it.id == taskId }
                if (task != null) {
                    val outputFile = task.customDownloadUri?.let { uriString ->
                        Mp4OutputHelper.getOutputFileFromUri(context, Uri.parse(uriString), task.fileName, ".mp4")
                    } ?: Mp4OutputHelper.getOutputFile(context, task.fileName, ".mp4")

                    val resultFile = llhlsRecorder.stopRecording(taskId)
                    activeLLHlsSessions.remove(taskId)

                    if (resultFile != null) {
                        MediaStoreSaver.saveToMediaStore(context, resultFile, task.fileName, task.customDownloadUri)
                        task.statusMessage = "录制完成"
                        task.progress = 100
                        DownloadManager.notifyTaskUpdated(task)
                    }
                }
            }
            Log.d(TAG, "停止 LL-HLS 录制: $taskId");
            return
        }

        // 传统 FFmpeg 录制停止
        val session = activeLiveSessions[taskId]
        session?.cancel()
        activeLiveSessions.remove(taskId)
        Log.d(TAG, "停止录制: $taskId")

        if (convertingTasks.add(taskId)) {
            scope.launch {
                val task = DownloadManager.getTasks().find { it.id == taskId }
                if (task != null) {
                    convertTsToMp4(task)
                } else {
                    Log.w(TAG, "stopRecording: 任务 $taskId 未找到，无法执行 TS 到 MP4 转换。")
                }
                convertingTasks.remove(taskId)
            }
        } else {
            Log.d(TAG, "Task $taskId already converting, skipping stopRecording conversion")
        }
    }
}