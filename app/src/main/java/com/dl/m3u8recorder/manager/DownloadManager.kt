package com.dl.m3u8recorder.manager

import android.content.Context
import android.util.Log
import com.dl.m3u8recorder.downloader.M3U8Downloader
import com.dl.m3u8recorder.merger.FFmpegStreamMerger
import com.dl.m3u8recorder.model.DownloadTask
import com.dl.m3u8recorder.record.LiveStreamRecorder
import com.dl.m3u8recorder.utils.MediaStoreSaver
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object DownloadManager {
    private const val TAG = "DownloadManager"

    private val taskQueue = mutableListOf<DownloadTask>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val downloader = M3U8Downloader()

    private lateinit var downloadDir: File
    private lateinit var appContext: Context
    private var listener: TaskListener? = null

    fun init(context: Context, downloadDirectory: File, taskListener: TaskListener?) {
        appContext = context.applicationContext
        downloadDir = downloadDirectory
        listener = taskListener
    }

    fun addTask(url: String, fileName: String, realtimeMerge: Boolean = true, isLive: Boolean = false) {
        val task = DownloadTask(
            id = "task_${System.currentTimeMillis()}",
            url = url,
            fileName = fileName,
            realtimeMerge = realtimeMerge,
            isLive = isLive
        )
        taskQueue.add(task)
        listener?.onQueueChanged(taskQueue.toList())
        startDownloadIfIdle(task)
    }

    fun cancelTask(taskId: String) {
        taskQueue.find { it.id == taskId }?.let {
            it.isCancelled = true
            activeJobs[taskId]?.cancel()
            activeJobs.remove(taskId)
            listener?.onTaskUpdated(it)
        }
    }

    fun pauseTask(taskId: String) {
        taskQueue.find { it.id == taskId }?.let {
            it.isPaused = true
            activeJobs[taskId]?.cancel()
            activeJobs.remove(taskId)
            listener?.onTaskUpdated(it)
        }
    }

    fun retryTask(taskId: String) {
        val task = taskQueue.find { it.id == taskId }
        if (task != null) {
            task.isCancelled = false
            task.isPaused = false
            startDownloadIfIdle(task)
        }
    }

    private fun startDownloadIfIdle(task: DownloadTask) {
        if (activeJobs.containsKey(task.id)) return

        val outputFile = File(downloadDir, task.fileName)

        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                if (task.isLive) {
                    // 直播流录制
                    val recorder = LiveStreamRecorder(appContext)
                    recorder.startRecording(task) { updatedTask ->
                        listener?.onTaskUpdated(updatedTask)
                    }
                    // 注意：这里录制是异步执行，视需求你可以改造成挂起函数或者管理好生命周期
                } else {
                    // 非直播，走传统下载合并逻辑
                    if (task.realtimeMerge) {
                        val merger = FFmpegStreamMerger(outputFile)
                        downloader.startDownloadWithRealtimeMerge(task, merger)
                    } else {
                        downloader.downloadAllTsThenMerge(task, outputFile)
                    }
                    listener?.onTaskUpdated(task)
                }
                Log.d(TAG, "任务完成: ${task.id}")
            } catch (e: Exception) {
                Log.e(TAG, "下载任务失败: ${task.id}", e)
            } finally {
                activeJobs.remove(task.id)
                if (!task.isCancelled && !task.isLive) {
                    MediaStoreSaver.saveToMediaStore(appContext, outputFile, task.fileName)
                }
            }
        }

        activeJobs[task.id] = job
    }

    fun getTasks(): List<DownloadTask> = taskQueue.toList()

    interface TaskListener {
        fun onTaskUpdated(task: DownloadTask)
        fun onQueueChanged(tasks: List<DownloadTask>)
    }
}