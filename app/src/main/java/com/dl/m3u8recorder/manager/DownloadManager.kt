package com.dl.m3u8recorder.manager

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import com.dl.m3u8recorder.downloader.M3U8Downloader
import com.dl.m3u8recorder.merger.FFmpegStreamMerger
import com.dl.m3u8recorder.model.DownloadTask
import com.dl.m3u8recorder.record.LiveStreamRecorder
import com.dl.m3u8recorder.service.LiveRecordingService
import com.dl.m3u8recorder.utils.MediaStoreSaver
import com.dl.m3u8recorder.utils.Mp4OutputHelper
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object DownloadManager {
    private const val TAG = "DownloadManager"

    private val currentTasksState = ConcurrentHashMap<String, DownloadTask>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val downloader = M3U8Downloader()

    private lateinit var appContext: Context
    @SuppressLint("StaticFieldLeak")
    lateinit var liveStreamRecorder: LiveStreamRecorder
    private val listeners = CopyOnWriteArrayList<TaskListener>()

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!::liveStreamRecorder.isInitialized) {
            liveStreamRecorder = LiveStreamRecorder(appContext)
        }
        Log.d(TAG, "DownloadManager initialized.")
    }

    fun addListener(listener: TaskListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            listener.onQueueChanged(currentTasksState.values.toList())
            Log.d(TAG, "Listener added: ${listener.javaClass.simpleName}. Total listeners: ${listeners.size}")
        }
    }

    fun removeListener(listener: TaskListener) {
        listeners.remove(listener)
        Log.d(TAG, "Listener removed: ${listener.javaClass.simpleName}. Total listeners: ${listeners.size}")
    }

    fun notifyTaskUpdated(updatedTaskFromSource: DownloadTask) {
        val taskInManager = currentTasksState[updatedTaskFromSource.id]

        if (taskInManager != null) {
            taskInManager.progress = updatedTaskFromSource.progress
            taskInManager.statusMessage = updatedTaskFromSource.statusMessage
            taskInManager.isPaused = updatedTaskFromSource.isPaused
            taskInManager.isCancelled = updatedTaskFromSource.isCancelled
            listeners.forEach { listener ->
                listener.onTaskUpdated(taskInManager)
            }
        } else {
            Log.w(TAG, "Attempted to update task ${updatedTaskFromSource.id} but it's not in DownloadManager's active tasks.")
        }
    }

    private fun notifyQueueChanged() {
        listeners.forEach { it.onQueueChanged(currentTasksState.values.toList()) }
    }

    // 修改 addTask：新增 downloadDirectoryUri 参数
    fun addTask(url: String, fileName: String, realtimeMerge: Boolean = true, isLive: Boolean = false,
                downloadDirectoryUri: Uri?) {
        val task = DownloadTask(
            id = "task_${System.currentTimeMillis()}",
            url = url,
            fileName = fileName,
            realtimeMerge = realtimeMerge,
            isLive = isLive,
            customDownloadUri = downloadDirectoryUri?.toString() // 保存URI字符串
        )
        currentTasksState[task.id] = task
        notifyQueueChanged()

        if (isLive) {
            LiveRecordingService.startService(appContext, task)
        } else {
            startDownloadJob(task)
        }
        Log.d(TAG, "addTask: ${task.id} (isLive: $isLive) to ${task.customDownloadUri ?: "default"}")
    }

    private fun startDownloadJob(task: DownloadTask) {
        if (activeJobs.containsKey(task.id) || task.isCancelled || task.progress == 100) return

        // 根据任务的 customDownloadUri 获取输出文件
        val outputFile = task.customDownloadUri?.let { uriString ->
            Mp4OutputHelper.getOutputFileFromUri(appContext, Uri.parse(uriString), task.fileName, ".mp4")
        } ?: Mp4OutputHelper.getOutputFile(appContext, task.fileName, ".mp4") // 默认目录

        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                task.statusMessage = "准备开始下载"
                task.progress = 0
                notifyTaskUpdated(task)

                if (task.realtimeMerge) {
                    val merger = FFmpegStreamMerger(outputFile)
                    downloader.startDownloadWithRealtimeMerge(task, merger) { progress, statusMessage ->
                        task.progress = progress
                        task.statusMessage = statusMessage
                        notifyTaskUpdated(task)
                    }
                } else {
                    downloader.downloadAllTsThenMerge(task, outputFile) { progress, statusMessage ->
                        task.progress = progress
                        task.statusMessage = statusMessage
                        notifyTaskUpdated(task)
                    }
                }

                if (!task.isCancelled && !task.isPaused && task.progress == 100) {
                    task.statusMessage = "下载完成"
                    notifyTaskUpdated(task)
                    Log.d(TAG, "非直播任务完成: ${task.id}")
                    MediaStoreSaver.saveToMediaStore(appContext, outputFile, task.fileName)
                } else if (!task.isCancelled && !task.isPaused) {
                    Log.d(TAG, "非直播任务异常结束。状态: ${task.statusMessage}, 进度: ${task.progress}")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "非直播任务协程被取消: ${task.id}")
            } catch (e: Exception) {
                task.isCancelled = true
                task.statusMessage = "下载失败: ${e.localizedMessage ?: e.message}"
                notifyTaskUpdated(task)
                Log.e(TAG, "非直播任务失败: ${task.id}", e)
            } finally {
                activeJobs.remove(task.id)
            }
        }
        activeJobs[task.id] = job
    }

    // 修改 cancelTask：不再处理文件删除，只处理任务状态和FFmpeg会话
    fun cancelTask(taskId: String) {
        currentTasksState[taskId]?.let { task ->
            if (task.isLive) {
                LiveRecordingService.stopService(appContext, taskId)
                Log.d(TAG, "cancelTask: 请求停止直播任务 ${task.id}。")
            } else {
                activeJobs[taskId]?.cancel()
                activeJobs.remove(taskId)
                task.isCancelled = true
                task.isPaused = false
                task.statusMessage = "已取消"
                notifyTaskUpdated(task)
                Log.d(TAG, "cancelTask: 非直播任务 ${task.id} 已取消。")
            }
        }
    }

    // 新增：删除任务和TS文件的方法
    fun deleteTaskAndTsFile(taskId: String) {
        currentTasksState[taskId]?.let { task ->
            // 1. 取消正在进行的任务（如果是直播任务，会通过服务停止FFmpeg）
            cancelTask(taskId) // 确保FFmpeg会话被终止

            // 2. 删除对应的 TS 文件
            CoroutineScope(Dispatchers.IO).launch {
                val tsFile = task.customDownloadUri?.let { uriString ->
                    Mp4OutputHelper.getOutputFileFromUri(appContext, Uri.parse(uriString), task.fileName, ".ts")
                } ?: Mp4OutputHelper.getOutputFile(appContext, task.fileName, ".ts")

                if (tsFile.exists()) {
                    val deleted = tsFile.delete()
                    if (deleted) {
                        Log.d(TAG, "已删除 TS 文件: ${tsFile.absolutePath}")
                    } else {
                        Log.e(TAG, "无法删除 TS 文件: ${tsFile.absolutePath}")
                    }
                } else {
                    Log.d(TAG, "TS 文件不存在，无需删除: ${tsFile.absolutePath}")
                }

                // 3. 从任务列表中移除
                currentTasksState.remove(taskId)
                notifyQueueChanged() // 通知UI任务列表已更新
                Log.d(TAG, "任务 ${task.id} 及其 TS 文件已删除。")
            }
        }
    }


    fun pauseTask(taskId: String) {
        currentTasksState[taskId]?.let { task ->
            if (task.isLive) {
                LiveRecordingService.pauseService(appContext, taskId)
            } else {
                activeJobs[taskId]?.cancel()
                activeJobs.remove(taskId)
                task.isPaused = true
                task.statusMessage = "已暂停"
                notifyTaskUpdated(task)
            }
            Log.d(TAG, "pauseTask: 任务 ${task.id} 已暂停。")
        }
    }

    fun resumeTask(taskId: String) {
        currentTasksState[taskId]?.let { task ->
            if (task.isPaused) {
                if (task.isLive) {
                    LiveRecordingService.resumeService(appContext, taskId)
                } else {
                    task.isPaused = false
                    task.isCancelled = false
                    task.statusMessage = "恢复中"
                    notifyTaskUpdated(task)
                    startDownloadJob(task)
                }
                Log.d(TAG, "resumeTask: 任务 ${task.id} 已恢复。")
            }
        }
    }

    fun retryTask(taskId: String) {
        currentTasksState[taskId]?.let { task ->
            task.isCancelled = false
            task.isPaused = false
            task.progress = 0
            task.statusMessage = "重试中"
            notifyTaskUpdated(task)

            if (task.isLive) {
                LiveRecordingService.startService(appContext, task)
            } else {
                startDownloadJob(task)
            }
            Log.d(TAG, "retryTask: 重新启动任务 ${task.id}.")
        }
    }

    fun getTasks(): List<DownloadTask> = currentTasksState.values.toList()

    interface TaskListener {
        fun onTaskUpdated(task: DownloadTask)
        fun onQueueChanged(tasks: List<DownloadTask>)
    }
}