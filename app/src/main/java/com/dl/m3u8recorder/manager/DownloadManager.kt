package com.dl.m3u8recorder.manager

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import com.dl.m3u8recorder.downloader.M3U8Downloader
import com.dl.m3u8recorder.llhls.LLHlsRecorder
import com.dl.m3u8recorder.merger.FFmpegStreamMerger
import com.dl.m3u8recorder.model.DownloadTask
import com.dl.m3u8recorder.parser.M3U8ParserImpl
import com.dl.m3u8recorder.parser.M3U8Playlist
import com.dl.m3u8recorder.parser.ResolvedStream
import com.dl.m3u8recorder.parser.VariantStream
import com.dl.m3u8recorder.record.LiveStreamRecorder
import com.dl.m3u8recorder.service.LiveRecordingService
import com.dl.m3u8recorder.utils.MediaStoreSaver
import com.dl.m3u8recorder.utils.Mp4OutputHelper
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher

object DownloadManager {
    private const val TAG = "DownloadManager"

    private val currentTasksState = ConcurrentHashMap<String, DownloadTask>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val downloader = M3U8Downloader()

    private val m3u8Parser = M3U8ParserImpl()

    private val scheduledTasks = ConcurrentHashMap<String, DownloadTask>()
    private var schedulerJob: Job? = null

    // 优化的 OkHttpClient：大连接池 + HTTP/2 + 快速超时
    private val okHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(64, 5, TimeUnit.MINUTES))
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .dispatcher(Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 20
        })
        .dns(CustomDns())
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val url = originalRequest.url.toString()

            val builder = originalRequest.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "*/*")

            if (url.contains("highwebmedia.com") || url.contains("chaturbate") || url.contains("mmcdn.com")) {
                builder.header("Referer", "https://chaturbate.com/")
            }

            chain.proceed(builder.build())
        }
        .build()

    @SuppressLint("StaticFieldLeak") // 针对 appContext 和 liveStreamRecorder 的静态引用警告
    private lateinit var appContext: Context
    @SuppressLint("StaticFieldLeak")
    lateinit var liveStreamRecorder: LiveStreamRecorder // 用于直播任务的录制器

    // Phase 4: LL-HLS 录制器
    private var llhlsRecorder: LLHlsRecorder? = null

    // 监听器列表，使用 CopyOnWriteArrayList 保证在迭代时修改的线程安全
    private val listeners = CopyOnWriteArrayList<TaskListener>()

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!::liveStreamRecorder.isInitialized) {
            liveStreamRecorder = LiveStreamRecorder(appContext)
        }
        if (llhlsRecorder == null) {
            llhlsRecorder = LLHlsRecorder(appContext, okHttpClient)
        }
        startScheduler()
        Log.d(TAG, "DownloadManager initialized.")
    }

    private fun startScheduler() {
        schedulerJob?.cancel()
        schedulerJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val tasksToStart = scheduledTasks.filter { it.value.scheduledStartTime <= now }.toList()

                for ((taskId, task) in tasksToStart) {
                    scheduledTasks.remove(taskId)
                    startScheduledTask(task)
                }

                delay(1000)
            }
        }
    }

    private fun startScheduledTask(task: DownloadTask) {
        Log.d(TAG, "启动定时任务: ${task.id}, fileName: ${task.fileName}")
        currentTasksState[task.id] = task
        notifyQueueChanged()

        if (task.isLive) {
            LiveRecordingService.startService(appContext, task)
        } else {
            startDownloadJob(task)
        }
    }

    fun addScheduledTask(task: DownloadTask) {
        scheduledTasks[task.id] = task
        Log.d(TAG, "添加定时任务: ${task.id}, 开始时间: ${task.scheduledStartTime}, fileName: ${task.fileName}")
        notifyQueueChanged()
    }

    fun cancelScheduledTask(taskId: String) {
        scheduledTasks.remove(taskId)
        Log.d(TAG, "取消定时任务: $taskId")
        notifyQueueChanged()
    }

    fun getScheduledTasks(): List<DownloadTask> = scheduledTasks.values.toList()

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
     * 添加新的下载任务（异步解析并自动选择最高画质）。
     * @param url M3U8链接。
     * @param fileName 文件名。
     * @param realtimeMerge 是否边下载边合并。
     * @param isLive 是否为直播流。
     * @param downloadDirectoryUri 自定义下载目录的 URI。
     * @param preferredResolution 首选分辨率高度（null = 自动选择最高）。
     */
    fun addTask(
        url: String,
        fileName: String,
        realtimeMerge: Boolean = true,
        isLive: Boolean = false,
        downloadDirectoryUri: Uri?,
        preferredResolution: Int? = null,
        audioTrackUrl: String? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            if (isLive) {
                // ---- 直播流：直接创建 LL-HLS 任务，跳过预请求解析 ----
                withContext(Dispatchers.Main) {
                    val task = DownloadTask(
                        id = "task_${System.currentTimeMillis()}",
                        url = url,
                        fileName = fileName,
                        realtimeMerge = realtimeMerge,
                        isLive = true,
                        customDownloadUri = downloadDirectoryUri?.toString(),
                        isLLHls = true,
                        audioTrackUrl = audioTrackUrl
                    )
                    Log.d(TAG, "addTask (live direct): ${task.id} audio=${audioTrackUrl != null}")
                    currentTasksState[task.id] = task
                    notifyQueueChanged()
                    LiveRecordingService.startService(appContext, task)
                }
                return@launch
            }

            // ---- 非直播流：解析 M3U8（可能是 Master Playlist），选择最佳画质 ----
            try {
                val resolved = analyzeAndResolveUrl(url, preferredResolution)

                withContext(Dispatchers.Main) {
                    val task = DownloadTask(
                        id = "task_${System.currentTimeMillis()}",
                        url = url,
                        fileName = fileName,
                        realtimeMerge = realtimeMerge,
                        isLive = false,
                        customDownloadUri = downloadDirectoryUri?.toString(),
                        // Phase 2: Master Playlist 支持
                        isMasterPlaylist = resolved.isMasterPlaylist,
                        selectedVariantUrl = resolved.videoPlaylistUrl,
                        selectedResolution = resolved.selectedVariant?.resolution?.toString(),
                        selectedBandwidth = resolved.selectedVariant?.bandwidth ?: 0L,
                        audioTrackUrl = resolved.audioPlaylistUrl,
                        selectedVariantLabel = resolved.selectedVariant?.resolution?.getShortLabel()
                    )

                    Log.d(TAG, "addTask (vod): ${task.id} (isMaster: ${resolved.isMasterPlaylist}, resolution: ${task.selectedResolution}, videoUrl: ${resolved.videoPlaylistUrl})")

                    currentTasksState[task.id] = task
                    notifyQueueChanged()
                    startDownloadJob(task)
                }
            } catch (e: Exception) {
                Log.e(TAG, "解析 M3U8 失败: $url", e)
                // 即使解析失败，也创建基本任务（向后兼容）
                withContext(Dispatchers.Main) {
                    val task = DownloadTask(
                        id = "task_${System.currentTimeMillis()}",
                        url = url,
                        fileName = fileName,
                        realtimeMerge = realtimeMerge,
                        isLive = false,
                        customDownloadUri = downloadDirectoryUri?.toString()
                    )
                    currentTasksState[task.id] = task
                    notifyQueueChanged()
                    startDownloadJob(task)
                }
            }
        }
    }

    /**
     * 添加任务并指定变体（用于 UI 选择分辨率后）
     */
    fun addTaskWithVariant(
        url: String,
        fileName: String,
        variant: VariantStream,
        audioUrl: String?,
        realtimeMerge: Boolean = true,
        isLive: Boolean = false,
        downloadDirectoryUri: Uri?
    ) {
        val task = DownloadTask(
            id = "task_${System.currentTimeMillis()}",
            url = url,
            fileName = fileName,
            realtimeMerge = realtimeMerge,
            isLive = isLive,
            customDownloadUri = downloadDirectoryUri?.toString(),
            isMasterPlaylist = true,
            selectedVariantUrl = variant.uri,
            selectedResolution = variant.resolution?.toString(),
            selectedBandwidth = variant.bandwidth,
            audioTrackUrl = audioUrl,
            selectedVariantLabel = variant.resolution?.getShortLabel() ?: "${variant.bandwidth / 1000}kbps"
        )

        currentTasksState[task.id] = task
        notifyQueueChanged()

        if (isLive) {
            LiveRecordingService.startService(appContext, task)
        } else {
            startDownloadJob(task)
        }

        Log.d(TAG, "addTaskWithVariant: ${task.id} resolution=${task.selectedResolution}")
    }

    /**
     * 判断输入是 URL 还是 M3U8 内容
     */
    fun isM3U8Content(input: String): Boolean {
        val trimmed = input.trim()
        // 检查是否以 M3U8 标签开始，或包含 Master Playlist 标签
        return trimmed.startsWith("#EXTM3U") ||
               trimmed.contains("#EXT-X-STREAM-INF") ||
               trimmed.contains("#EXT-X-TARGETDURATION")
    }

    /**
     * 从可能包含 curl 输出的内容中提取纯净的 M3U8 内容
     */
    private fun extractM3U8Content(input: String): String {
        val lines = input.lines()
        val m3u8StartIndex = lines.indexOfFirst { it.trim().startsWith("#EXTM3U") }

        return if (m3u8StartIndex >= 0) {
            // 从 #EXTM3U 开始提取
            lines.drop(m3u8StartIndex).joinToString("\n")
        } else {
            input
        }
    }

    /**
     * 异步分析 URL 或 M3U8 内容，返回可用的分辨率列表（供 UI 展示）
     * @param input URL 或 M3U8 内容
     * @param baseUrl 如果输入是 M3U8 内容，需要提供基础 URL 用于解析相对路径
     */
    suspend fun analyzeUrl(input: String, baseUrl: String? = null): List<VariantStream> = withContext(Dispatchers.IO) {
        try {
            val (content, effectiveBaseUrl) = if (isM3U8Content(input)) {
                // 输入是 M3U8 内容，提取纯净内容
                val cleanContent = extractM3U8Content(input)
                val base = baseUrl ?: extractBaseUrlFromContent(cleanContent)
                Log.d(TAG, "M3U8内容模式: baseUrl=$base")
                Pair(cleanContent, base)
            } else {
                // 输入是 URL
                Log.d(TAG, "URL模式: $input")
                Pair(fetchM3U8Content(input), input)
            }

            if (effectiveBaseUrl.isBlank()) {
                Log.e(TAG, "基础 URL 为空，无法解析相对路径")
                return@withContext emptyList<VariantStream>()
            }

            val playlist = m3u8Parser.parse(content, effectiveBaseUrl)

            when (playlist) {
                is M3U8Playlist.Master -> {
                    Log.d(TAG, "Master Playlist: ${playlist.variants.size} 个变体, ${playlist.audioRenditions.size} 个音频轨")
                    // 按带宽降序排列
                    playlist.variants.sortedByDescending { it.bandwidth }
                }
                is M3U8Playlist.Media -> {
                    Log.d(TAG, "Media Playlist: 非Master，单分辨率")
                    // 单一分辨率，返回空列表表示无需选择
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "分析失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 从 M3U8 内容中提取基础 URL（尝试从内容中提取）
     */
    private fun extractBaseUrlFromContent(content: String): String {
        // 尝试从内容中找到任何 URL 来推断基础 URL
        val lines = content.lines()
        for (line in lines) {
            val trimmedLine = line.trim()
            // 检查是否是完整的 URL 行
            if (trimmedLine.startsWith("http://") || trimmedLine.startsWith("https://")) {
                try {
                    val uri = java.net.URI(trimmedLine)
                    val baseUrl = "${uri.scheme}://${uri.authority}"
                    Log.d(TAG, "从内容推断基础 URL: $baseUrl")
                    return baseUrl
                } catch (e: Exception) {
                    Log.w(TAG, "URL 解析失败: $trimmedLine")
                }
            }
        }
        Log.w(TAG, "无法从内容推断基础 URL，需要用户手动输入")
        return ""
    }

    /**
     * 从 M3U8 内容中提取最佳流的完整 URL
     * @param content M3U8 内容
     * @param baseUrl 基础 URL（curl 命令中的 URL）
     * @return 最佳流的完整 URL，如果解析失败返回 null
     */
    fun extractBestStreamUrl(content: String, baseUrl: String): String? {
        return try {
            val cleanContent = extractM3U8Content(content)
            val playlist = m3u8Parser.parse(cleanContent, baseUrl)

            when (playlist) {
                is M3U8Playlist.Master -> {
                    val bestVariant = m3u8Parser.selectBestVariant(playlist.variants)
                    Log.d(TAG, "最佳流: ${bestVariant.resolution}, ${bestVariant.getBandwidthLabel()}, URL: ${bestVariant.uri}")
                    bestVariant.uri
                }
                is M3U8Playlist.Media -> {
                    // 已经是 Media Playlist，直接返回基础 URL
                    baseUrl
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "提取最佳流 URL 失败", e)
            null
        }
    }

    /**
     * 从 Master Playlist 内容中解析音频轨 URL
     */
    fun getAudioUrlFromContent(content: String, baseUrl: String, audioGroupId: String?): String? {
        if (audioGroupId == null) {
            Log.d(TAG, "无音频组ID，跳过音频轨获取")
            return null
        }

        return try {
            val cleanContent = extractM3U8Content(content)
            val playlist = m3u8Parser.parse(cleanContent, baseUrl)
            if (playlist is M3U8Playlist.Master) {
                val audioUrl = playlist.audioRenditions.find { it.groupId == audioGroupId && it.uri != null }?.uri
                Log.d(TAG, "音频轨 URL: $audioUrl (groupId: $audioGroupId)")
                audioUrl
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取音频轨 URL 失败", e)
            null
        }
    }

    /**
     * 从 Master Play url 获取音频轨 URL (异步版本)
     */
    suspend fun getAudioRenditions(url: String, audioGroupId: String?): String? = withContext(Dispatchers.IO) {
        if (audioGroupId == null) return@withContext null

        try {
            val content = fetchM3U8Content(url)
            val playlist = m3u8Parser.parse(content, url)

            if (playlist is M3U8Playlist.Master) {
                playlist.audioRenditions.find { it.groupId == audioGroupId && it.uri != null }?.uri
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取音频轨失败", e)
            null
        }
    }

    /**
     * 内部方法：分析并解析 URL
     */
    private suspend fun analyzeAndResolveUrl(url: String, preferredResolution: Int?): ResolvedStream = withContext(Dispatchers.IO) {
        Log.d(TAG, "analyzeAndResolveUrl: 开始解析 $url")
        val content = fetchM3U8Content(url)
        Log.d(TAG, "analyzeAndResolveUrl: 获取到内容长度 ${content.length}")

        val playlist = m3u8Parser.parse(content, url)

        when (playlist) {
            is M3U8Playlist.Master -> {
                Log.d(TAG, "analyzeAndResolveUrl: Master Playlist, ${playlist.variants.size} 个变体")
                val resolved = m3u8Parser.resolveMasterPlaylist(playlist, preferredResolution)

                Log.d(TAG, "analyzeAndResolveUrl: 选择的视频URL: ${resolved.videoPlaylistUrl}")
                Log.d(TAG, "analyzeAndResolveUrl: 选择的音频URL: ${resolved.audioPlaylistUrl}")

                // 检测视频 playlist 是否为 LL-HLS
                val isLLHls = resolved.videoPlaylistUrl?.let { videoUrl ->
                    if (videoUrl.isBlank()) {
                        Log.e(TAG, "analyzeAndResolveUrl: 视频URL为空!")
                        false
                    } else {
                        try {
                            val videoContent = fetchM3U8Content(videoUrl)
                            val result = videoContent.contains("#EXT-X-PART") || videoContent.contains("#EXT-X-SERVER-CONTROL")
                            Log.d(TAG, "analyzeAndResolveUrl: LL-HLS 检测结果: $result")
                            result
                        } catch (e: Exception) {
                            Log.e(TAG, "analyzeAndResolveUrl: 获取视频playlist失败", e)
                            false
                        }
                    }
                } ?: false

                resolved.copy(isLLHls = isLLHls)
            }
            is M3U8Playlist.Media -> {
                Log.d(TAG, "analyzeAndResolveUrl: Media Playlist, isLLHls=${playlist.isLLHls}")
                ResolvedStream(
                    videoPlaylistUrl = url,
                    audioPlaylistUrl = null,
                    isMasterPlaylist = false,
                    isLLHls = playlist.isLLHls,
                    selectedVariant = null,
                    availableVariants = emptyList()
                )
            }
        }
    }

    /**
     * 获取 M3U8 内容
     */
    private suspend fun fetchM3U8Content(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        val response = okHttpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "未知错误"
            Log.e(TAG, "HTTP ${response.code}: $errorBody")
            throw Exception("HTTP ${response.code}: ${errorBody.take(200)}")
        }

        val body = response.body?.string() ?: throw Exception("响应体为空")

        // 检查是否是有效的 M3U8 内容
        if (!body.trim().startsWith("#EXTM3U")) {
            Log.e(TAG, "返回内容不是有效的 M3U8, HTTP=${response.code}, 前100字符: ${body.take(100)}")
            throw Exception("服务器返回了非 M3U8 内容: ${body.take(100)}")
        }

        body
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

                // Phase 4: 检测 LL-HLS
                if (task.isLLHls && !task.isLive) {
                    // LL-HLS 点播流
                    llhlsRecorder?.startRecording(task) { progress, status ->
                        task.progress = progress.coerceIn(0, 100)
                        task.statusMessage = status
                        notifyTaskUpdated(task)
                    }
                } else {
                    // 传统 HLS 下载
                    if (task.realtimeMerge) {
                        val merger = FFmpegStreamMerger(outputFile)
                        downloader.startDownloadWithRealtimeMerge(task, merger) { progress, statusMessage ->
                            notifyTaskUpdated(task)
                        }
                    } else {
                        downloader.downloadAllTsThenMerge(task, outputFile) { progress, statusMessage ->
                            notifyTaskUpdated(task)
                        }
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

    fun cancelTask(taskId: String) {
        val scheduledTask = scheduledTasks.remove(taskId)
        if (scheduledTask != null) {
            scheduledTask.isCancelled = true
            scheduledTask.statusMessage = "已取消"
            notifyTaskUpdated(scheduledTask)
            Log.d(TAG, "cancelTask: 定时任务 ${taskId} 已取消。")
            return
        }

        currentTasksState[taskId]?.let { task ->
            if (task.isLive) {
                LiveRecordingService.stopService(appContext, taskId)
                task.isCancelled = true
                task.isPaused = false
                task.statusMessage = "已取消"
                notifyTaskUpdated(task)
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

    fun getTasks(): List<DownloadTask> {
        val allTasks = mutableListOf<DownloadTask>()
        allTasks.addAll(scheduledTasks.values)
        allTasks.addAll(currentTasksState.values)
        return allTasks.sortedByDescending {
            if (it.isScheduled) it.scheduledStartTime else System.currentTimeMillis()
        }
    }

    interface TaskListener {
        fun onTaskUpdated(task: DownloadTask)
        fun onQueueChanged(tasks: List<DownloadTask>)
    }

    /**
     * 获取配置好的 OkHttpClient（包含请求头等）
     */
    fun getOkHttpClient(): OkHttpClient = okHttpClient
}