package com.dl.m3u8recorder.manager

import android.content.Context
import android.util.Log
import com.dl.m3u8recorder.downloader.M3U8Downloader
import com.dl.m3u8recorder.merger.FFmpegStreamMerger
import com.dl.m3u8recorder.model.DownloadTask
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

    fun addTask(url: String, fileName: String, realtimeMerge: Boolean = true) {
        val task = DownloadTask(
            id = "task_${System.currentTimeMillis()}",
            url = url,
            fileName = fileName,
            realtimeMerge = realtimeMerge
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
                if (task.realtimeMerge) {
                    val merger = FFmpegStreamMerger(outputFile)
                    downloader.startDownloadWithRealtimeMerge(task, merger)
                } else {
                    // 可实现传统模式：先下载所有 ts，后合并
                    downloader.downloadAllTsThenMerge(task, outputFile) // ✅ 新增逻辑
                }
                Log.d(TAG, "任务完成: ${task.id}")
            } catch (e: Exception) {
                Log.e(TAG, "下载任务失败: ${task.id}", e)
            } finally {
                activeJobs.remove(task.id)
                if (!task.isCancelled) {
                    MediaStoreSaver.saveToMediaStore(appContext, outputFile, task.fileName)
                }
                listener?.onTaskUpdated(task)
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