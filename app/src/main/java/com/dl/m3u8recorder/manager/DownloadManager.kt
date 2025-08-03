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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object DownloadManager {
    private const val TAG = "DownloadManager"

    // 使用 ConcurrentHashMap 存储任务状态，保证线程安全
    private val currentTasksState = ConcurrentHashMap<String, DownloadTask>()
    // 存储活跃的 Coroutine Job，用于非直播任务的控制
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val downloader = M3U8Downloader() // 用于非直播任务的下载器

    @SuppressLint("StaticFieldLeak") // 针对 appContext 和 liveStreamRecorder 的静态引用警告
    private lateinit var appContext: Context
    @SuppressLint("StaticFieldLeak")
    lateinit var liveStreamRecorder: LiveStreamRecorder // 用于直播任务的录制器

    // 监听器列表，使用 CopyOnWriteArrayList 保证在迭代时修改的线程安全
    private val listeners = CopyOnWriteArrayList<TaskListener>()

    // 初始化 DownloadManager，在 Application 或主 Activity 的 onCreate 中调用
    fun init(context: Context) {
        appContext = context.applicationContext
        if (!::liveStreamRecorder.isInitialized) {
            liveStreamRecorder = LiveStreamRecorder(appContext)
        }
        Log.d(TAG, "DownloadManager initialized.")
    }

    // 添加 UI 监听器
    fun addListener(listener: TaskListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            // 同样，在这里也发送排序后的列表
            val sortedList = currentTasksState.values.toList().sortedByDescending { it.id }
            listener.onQueueChanged(sortedList)
            Log.d(TAG, "Listener added: ${listener.javaClass.simpleName}. Total listeners: ${listeners.size}")
        }
    }

    // 移除 UI 监听器
    fun removeListener(listener: TaskListener) {
        listeners.remove(listener)
        Log.d(TAG, "Listener removed: ${listener.javaClass.simpleName}. Total listeners: ${listeners.size}")
    }

    /**
     * 通知所有监听器某个任务状态已更新。
     * 这个方法是核心，因为它会同步 `DownloadTask` 的所有可变状态，
     * 包括 `_downloadedSize` 和 `_elapsedTime`，确保 UI 刷新。
     * @param updatedTaskFromSource 来自下载器或录制器回调的最新任务对象。
     */
    fun notifyTaskUpdated(updatedTaskFromSource: DownloadTask) {
        val taskInManager = currentTasksState[updatedTaskFromSource.id]

        if (taskInManager != null) {
            // 更新 DownloadManager 中持有的任务实例的各项可变属性
            taskInManager.progress = updatedTaskFromSource.progress
            taskInManager.statusMessage = updatedTaskFromSource.statusMessage
            taskInManager.isPaused = updatedTaskFromSource.isPaused
            taskInManager.isCancelled = updatedTaskFromSource.isCancelled
            // 【关键修改】同步下载大小和时间，确保UI实时刷新
            taskInManager._downloadedSize.value = updatedTaskFromSource._downloadedSize.value
            taskInManager._elapsedTime.value = updatedTaskFromSource._elapsedTime.value

            listeners.forEach { listener ->
                listener.onTaskUpdated(taskInManager)
            }
        } else {
            Log.w(TAG, "Attempted to update task ${updatedTaskFromSource.id} but it's not in DownloadManager's active tasks.")
        }
    }
    private fun notifyQueueChanged() {
        // 在通知监听器之前，对任务列表进行排序。
        // 使用 .sortedByDescending { it.id } 可以让最新的任务排在列表的最前面。
        val sortedList = currentTasksState.values.toList().sortedByDescending { it.id }
        listeners.forEach { it.onQueueChanged(sortedList) }
    }

    /**
     * 添加新的下载任务。
     * @param url M3U8链接。
     * @param fileName 文件名。
     * @param realtimeMerge 是否边下载边合并。
     * @param isLive 是否为直播流。
     * @param downloadDirectoryUri 自定义下载目录的 URI。
     */
    fun addTask(url: String, fileName: String, realtimeMerge: Boolean = true, isLive: Boolean = false,
                downloadDirectoryUri: Uri?) {
        val task = DownloadTask(
            id = "task_${System.currentTimeMillis()}",
            url = url,
            fileName = fileName,
            realtimeMerge = realtimeMerge,
            isLive = isLive,
            customDownloadUri = downloadDirectoryUri?.toString() // 将 URI 转换为字符串保存
        )
        currentTasksState[task.id] = task
        notifyQueueChanged() // 通知 UI 队列有新任务

        if (isLive) {
            LiveRecordingService.startService(appContext, task) // 启动直播录制服务
        } else {
            startDownloadJob(task) // 启动非直播下载任务
        }
        Log.d(TAG, "addTask: ${task.id} (isLive: $isLive) to ${task.customDownloadUri ?: "default"}")
    }

    // 启动非直播下载任务的协程
    private fun startDownloadJob(task: DownloadTask) {
        // 如果任务已经在运行，或已取消，或已完成，则不重复启动
        if (activeJobs.containsKey(task.id) || task.isCancelled || task.progress == 100) return

        // 根据任务的 customDownloadUri 获取最终输出文件路径 (.mp4)
        val outputFile = task.customDownloadUri?.let { uriString ->
            Mp4OutputHelper.getOutputFileFromUri(appContext, Uri.parse(uriString), task.fileName, ".mp4")
        } ?: Mp4OutputHelper.getOutputFile(appContext, task.fileName, ".mp4") // 默认目录

        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                // 立即更新任务状态，给用户即时反馈
                task.statusMessage = "准备开始下载"
                task.progress = 0
                // 重置下载大小和时间，以防是重试任务
                task._downloadedSize.value = 0L
                task._elapsedTime.value = 0L
                notifyTaskUpdated(task)

                // 根据合并方式选择下载流程
                if (task.realtimeMerge) {
                    val merger = FFmpegStreamMerger(outputFile)
                    downloader.startDownloadWithRealtimeMerge(task, merger) { progress, statusMessage ->
                        // FFmpegStreamMerger 会更新 task.progress, task.statusMessage 等
                        // 这里只需要通知 DownloadManager 来广播更新
                        notifyTaskUpdated(task) // task 对象在 FFmpegStreamMerger 内部已被更新
                    }
                } else {
                    downloader.downloadAllTsThenMerge(task, outputFile) { progress, statusMessage ->
                        // downloader.downloadAllTsThenMerge 会更新 task.progress, task.statusMessage 等
                        notifyTaskUpdated(task) // task 对象在 downloader 内部已被更新
                    }
                }

                // 下载任务完成后的最终状态处理
                if (!task.isCancelled && !task.isPaused && task.progress == 100) {
                    task.statusMessage = "下载完成"
                    notifyTaskUpdated(task)
                    Log.d(TAG, "非直播任务完成: ${task.id}")
                    // 保存到媒体库
                    MediaStoreSaver.saveToMediaStore(appContext, outputFile, task.fileName)
                } else if (!task.isCancelled && !task.isPaused) {
                    // 任务未被取消/暂停，但进度未达100%，表示异常结束
                    Log.d(TAG, "非直播任务异常结束。状态: ${task.statusMessage}, 进度: ${task.progress}")
                }
            } catch (e: CancellationException) {
                // 协程被取消（例如被 pauseTask 或 cancelTask 调用）
                Log.d(TAG, "非直播任务协程被取消: ${task.id}")
                // 状态已在 cancelTask/pauseTask 中更新，这里无需重复设置
            } catch (e: Exception) {
                // 捕获其他下载过程中的异常
                task.isCancelled = true // 标记为取消或失败
                task.statusMessage = "下载失败: ${e.localizedMessage ?: e.message}"
                notifyTaskUpdated(task) // 通知 UI 失败状态
                Log.e(TAG, "非直播任务失败: ${task.id}", e)
            } finally {
                // 无论成功、失败或取消，都从活跃任务中移除 Job
                activeJobs.remove(task.id)
            }
        }
        activeJobs[task.id] = job // 将 Job 存入映射以便后续控制
    }

    /**
     * 取消指定任务。
     * 对于直播任务，会通过 LiveRecordingService 停止 FFmpeg。
     * 对于非直播任务，会取消其协程。
     * @param taskId 要取消的任务 ID。
     */
    fun cancelTask(taskId: String) {
        currentTasksState[taskId]?.let { task ->
            if (task.isLive) {
                LiveRecordingService.stopService(appContext, taskId) // 通过服务停止直播录制
                // 立即更新 UI 状态，改善响应性
                task.isCancelled = true
                task.isPaused = false
                task.statusMessage = "已取消"
                notifyTaskUpdated(task)
                Log.d(TAG, "cancelTask: 请求停止直播任务 ${task.id}。")
            } else {
                activeJobs[taskId]?.cancel() // 取消非直播任务的协程
                activeJobs.remove(taskId) // 从活跃 Job 列表中移除
                // 立即更新 UI 状态
                task.isCancelled = true
                task.isPaused = false
                task.statusMessage = "已取消"
                notifyTaskUpdated(task)
                Log.d(TAG, "cancelTask: 非直播任务 ${task.id} 已取消。")
            }
        }
    }

    /**
     * 删除任务及相关的临时 TS 文件（如果存在）。
     * 不删除已转换的 MP4 文件。
     * @param taskId 要删除的任务 ID。
     */
    fun deleteTaskAndTsFile(taskId: String) {
        currentTasksState[taskId]?.let { task ->
            // 1. 取消正在进行的任务（这将终止 FFmpeg 进程或协程）
            // 注意：这里调用 cancelTask 是为了确保后台进程停止，而不是为了设置UI状态
            cancelTask(taskId)

            // 2. 异步删除对应的 TS 文件
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

                // 3. 从任务列表中移除 DownloadTask 对象
                currentTasksState.remove(taskId)
                notifyQueueChanged() // 通知 UI 任务列表已更新，移除该任务
                Log.d(TAG, "任务 ${task.id} 及其 TS 文件已删除。")
            }
        }
    }

    /**
     * 暂停指定任务。
     * 对于直播任务，通过 LiveRecordingService 暂停 FFmpeg。
     * 对于非直播任务，取消其协程。
     * @param taskId 要暂停的任务 ID。
     */
    fun pauseTask(taskId: String) {
        currentTasksState[taskId]?.let { task ->
            if (task.isLive) {
                LiveRecordingService.pauseService(appContext, taskId)
                // 立即更新 UI 状态
                task.isPaused = true
                task.statusMessage = "已暂停"
                notifyTaskUpdated(task)
            } else {
                activeJobs[taskId]?.cancel() // 取消非直播任务协程
                activeJobs.remove(taskId) // 从活跃 Job 列表中移除
                // 立即更新 UI 状态
                task.isPaused = true
                task.statusMessage = "已暂停"
                notifyTaskUpdated(task)
            }
            Log.d(TAG, "pauseTask: 任务 ${task.id} 已暂停。")
        }
    }

    /**
     * 恢复指定任务。
     * @param taskId 要恢复的任务 ID。
     */
    fun resumeTask(taskId: String) {
        currentTasksState[taskId]?.let { task ->
            if (task.isPaused) { // 只有暂停状态的任务才能恢复
                if (task.isLive) {
                    LiveRecordingService.resumeService(appContext, taskId)
                    // 立即更新 UI 状态
                    task.isPaused = false
                    task.isCancelled = false
                    task.statusMessage = "恢复中"
                    notifyTaskUpdated(task)
                } else {
                    task.isPaused = false
                    task.isCancelled = false
                    task.statusMessage = "恢复中"
                    notifyTaskUpdated(task)
                    startDownloadJob(task) // 重新启动非直播下载 Job
                }
                Log.d(TAG, "resumeTask: 任务 ${task.id} 已恢复。")
            }
        }
    }

    /**
     * 重试指定任务（通常用于取消或失败的任务）。
     * 重置任务状态并重新启动。
     * @param taskId 要重试的任务 ID。
     */
    fun retryTask(taskId: String) {
        currentTasksState[taskId]?.let { task ->
            // 重置所有相关状态
            task.isCancelled = false
            task.isPaused = false
            task.progress = 0
            task.statusMessage = "重试中"
            // 【关键修改】重置下载大小和时间，确保从头开始
            task._downloadedSize.value = 0L
            task._elapsedTime.value = 0L
            notifyTaskUpdated(task) // 通知 UI 状态已重置

            if (task.isLive) {
                LiveRecordingService.startService(appContext, task) // 启动直播录制服务
            } else {
                startDownloadJob(task) // 启动非直播下载 Job
            }
            Log.d(TAG, "retryTask: 重新启动任务 ${task.id}.")
        }
    }

    // 获取当前所有任务的列表
    fun getTasks(): List<DownloadTask> = currentTasksState.values.toList()

    // 任务状态监听器接口
    interface TaskListener {
        fun onTaskUpdated(task: DownloadTask) // 当单个任务状态更新时
        fun onQueueChanged(tasks: List<DownloadTask>) // 当任务队列（增删）发生变化时
    }
}