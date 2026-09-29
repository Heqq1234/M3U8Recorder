package com.dl.m3u8recorder.manager

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.dl.m3u8recorder.downloader.M3U8Downloader
import com.dl.m3u8recorder.llhls.ChaturbateApi
import com.dl.m3u8recorder.llhls.LLHlsRecorder
import com.dl.m3u8recorder.llhls.StripchatApi
import com.dl.m3u8recorder.llhls.CamsApi
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
    // 定时任务到期后的"重新取流重试"协程，按 taskId 独立(不覆盖 schedulerJob)
    private val retryJobs = ConcurrentHashMap<String, Job>()

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

            // Stripchat / 白标站点 CDN：media 分片必须回传与页面同源的 Referer + Origin 才能 200，
            // 否则裸 URL 请求直接 404（playlist 因 URL 带 pkey 故能下；init 段是公开文件故能下 ——
            // 这正是此前录出 3KB 空壳的原因）。devtools 抓包证实浏览器成功请求带：
            //   referer: https://zh.stripchat.com/    origin: https://zh.stripchat.com
            //   sec-fetch-*: cors / cross-site / empty
            // 原拦截器只用 apex 域 https://stripchat.com/ 且缺 Origin，故分片 404。这里镜像浏览器。
            // 注：仅匹配 stripchat/doppiocdn/hotzcam 等域名，Chaturbate(highwebmedia/chaturbate/mmcdn)不受影响。
            if (url.contains("doppiocdn.net") || url.contains("doppiocdn.com") || url.contains("edge-hls") ||
                url.contains("stripchat") || url.contains("hotzcam")) {
                builder.header("Referer", "https://zh.stripchat.com/")
                    .header("Origin", "https://zh.stripchat.com")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "cross-site")
            }

            // Cams.com CDN：分片在 camshls.cams.com（实测域名，StreaMonitor 旧域名 camscdn 已弃用），
            // 补同源 Referer（与页面同源，避免反盗链 403/404；无 Cookie、无需登录态）。
            // 独立于 stripchat 分支，不影响其它站点。
            if (url.contains("camshls.cams.com")) {
                builder.header("Referer", "https://cams.com/")
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
        Log.d(TAG, "启动定时任务: ${task.id}, fileName: ${task.fileName}, platform: ${task.platform}")
        currentTasksState[task.id] = task
        notifyQueueChanged()

        if (task.platform != null) {
            // 平台定时任务：到期重新取流(主播可能未开播)，失败每10分钟重试，最多10次
            startScheduledTaskWithRetry(task)
        } else if (task.isLive) {
            // 手动URL直播：直接启动(无重试)
            LiveRecordingService.startService(appContext, task)
        } else {
            startDownloadJob(task)
        }
    }

    /**
     * 平台定时任务到期：用房间号重新调平台 API 取流。
     * 未开播/失败 → 每 10 分钟重试，最多 10 次。全失败 → 任务保留在列表(静默)。
     * 注：依赖进程存活，App 被杀则重试中断(与定时本身同限制)。
     */
    private fun startScheduledTaskWithRetry(task: DownloadTask) {
        retryJobs[task.id]?.cancel()
        retryJobs[task.id] = CoroutineScope(Dispatchers.IO).launch {
            val maxRetries = 10
            val retryIntervalMs = 10 * 60 * 1000L
            for (attempt in 1..maxRetries) {
                if (!isActive) return@launch

                // 1. 调平台 API 取流
                val fetchResult = when (task.platform) {
                    "chaturbate" -> {
                        val slug = task.roomSlug
                        if (slug.isNullOrBlank()) {
                            Log.e(TAG, "chaturbate 定时任务缺 roomSlug，放弃")
                            markRetryExhausted(task, "配置错误：缺少房间号")
                            return@launch
                        }
                        ChaturbateApi.fetchStreamUrl(slug)
                    }
                    "stripchat" -> {
                        val slug = task.roomSlug
                        val base = task.roomBaseUrl
                        if (slug.isNullOrBlank() || base.isNullOrBlank()) {
                            Log.e(TAG, "stripchat 定时任务缺 slug/baseUrl，放弃")
                            markRetryExhausted(task, "配置错误：缺少房间信息")
                            return@launch
                        }
                        StripchatApi.fetchStreamUrl(StripchatApi.RoomInfo(slug = slug, baseUrl = base))
                    }
                    "cams" -> {
                        val slug = task.roomSlug
                        if (slug.isNullOrBlank()) {
                            Log.e(TAG, "cams 定时任务缺 roomSlug，放弃")
                            markRetryExhausted(task, "配置错误：缺少房间号")
                            return@launch
                        }
                        CamsApi.fetchStreamUrl(slug)
                    }
                    else -> {
                        Log.e(TAG, "未知 platform: ${task.platform}，放弃")
                        markRetryExhausted(task, "配置错误：未知平台")
                        return@launch
                    }
                }

                // 2. 取流失败/未开播 → 等待重试
                if (!fetchResult.isSuccess) {
                    val msg = fetchResult.errorMessage ?: "未获取到流"
                    Log.d(TAG, "定时任务 ${task.id} 取流失败($attempt/$maxRetries): $msg")
                    if (attempt < maxRetries) {
                        task.statusMessage = "未开播，${retryIntervalMs / 60000}分钟后重试($attempt/$maxRetries)"
                        task.progress = 0
                        notifyTaskUpdated(task)
                        delay(retryIntervalMs)
                    } else {
                        markRetryExhausted(task, "多次重试未开播：$msg")
                    }
                    continue
                }

                // 3. 取流成功 → resolveMasterPlaylist 拿 video/audio media URL
                val resolved = when (task.platform) {
                    "chaturbate" -> ChaturbateApi.resolveMasterPlaylist(fetchResult.m3u8Url)
                    "stripchat" -> StripchatApi.resolveMasterPlaylist(fetchResult.m3u8Url, task.roomBaseUrl)
                    "cams" -> CamsApi.resolveMasterPlaylist(fetchResult.m3u8Url)
                    else -> null
                }
                if (resolved == null || resolved.videoPlaylistUrl.isBlank()) {
                    val msg = resolved?.errorMessage ?: "解析播放列表失败"
                    Log.d(TAG, "定时任务 ${task.id} resolve 失败($attempt/$maxRetries): $msg")
                    if (attempt < maxRetries) {
                        task.statusMessage = "解析失败，重试中($attempt/$maxRetries)"
                        notifyTaskUpdated(task)
                        delay(retryIntervalMs)
                    } else {
                        markRetryExhausted(task, "多次重试解析失败：$msg")
                    }
                    continue
                }

                // 4. 成功：用新 URL 启动录制
                Log.d(TAG, "定时任务 ${task.id} 取流成功，启动录制: ${resolved.videoPlaylistUrl.take(80)}")
                // statusMessage 是 body 属性(非构造函数参数)，无法通过 copy() 传入，
                // copy() 也会丢弃原 task 的运行时状态(_progress 等)，故需显式重置。
                val liveTask = task.copy(
                    url = resolved.videoPlaylistUrl,
                    audioTrackUrl = resolved.audioPlaylistUrl,
                    isLLHls = true
                ).apply {
                    statusMessage = "取流成功，开始录制"
                }
                currentTasksState[task.id] = liveTask
                notifyTaskUpdated(liveTask)
                LiveRecordingService.startService(appContext, liveTask)
                return@launch
            }
        }
    }

    /** 重试耗尽：任务保留在列表，标记状态(不删除) */
    private fun markRetryExhausted(task: DownloadTask, reason: String) {
        Log.w(TAG, "定时任务 ${task.id} 重试耗尽：$reason，任务保留在列表")
        task.statusMessage = reason
        task.progress = 100
        notifyTaskUpdated(task)
    }

    fun addScheduledTask(task: DownloadTask) {
        scheduledTasks[task.id] = task
        Log.d(TAG, "添加定时任务: ${task.id}, 开始时间: ${task.scheduledStartTime}, fileName: ${task.fileName}")
        notifyQueueChanged()
    }

    fun cancelScheduledTask(taskId: String) {
        scheduledTasks.remove(taskId)
        retryJobs.remove(taskId)?.cancel()  // 取消进行中的取流重试
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
        audioTrackUrl: String? = null,
        platform: String? = null,
        roomSlug: String? = null,
        roomBaseUrl: String? = null,
        /** 真实变体分辨率(如 "1920x1080")，仅直播分支使用，用于修正录制建轨宽高；不传则不变 */
        selectedResolution: String? = null
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
                        audioTrackUrl = audioTrackUrl,
                        // 保存平台信息，重试时可重新取流(旧 URL 会过期)
                        platform = platform,
                        roomSlug = roomSlug,
                        roomBaseUrl = roomBaseUrl,
                        selectedResolution = selectedResolution
                    )
                    Log.d(TAG, "addTask (live direct): ${task.id} platform=$platform audio=${audioTrackUrl != null} resolution=${selectedResolution ?: "null"}")
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
    @RequiresApi(Build.VERSION_CODES.O)
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
    @RequiresApi(Build.VERSION_CODES.O)
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
     * 平台任务(Chaturbate/Stripchat)：重新调 API 取流，因为旧 URL 的 CDN 签名会过期。
     * 手动 URL 任务：直接用原 URL 重试。
     * @param taskId 要重试的任务 ID。
     */
    fun retryTask(taskId: String) {
        currentTasksState[taskId]?.let { task ->
            // 重置所有相关状态
            task.isCancelled = false
            task.isPaused = false
            task.progress = 0
            task._downloadedSize.value = 0L
            task._elapsedTime.value = 0L
            notifyTaskUpdated(task)

            if (task.isLive) {
                if (task.platform != null && !task.roomSlug.isNullOrBlank()) {
                    // 平台任务：重新取流(旧 URL 的 CDN 签名已过期)
                    task.statusMessage = "重新获取直播流地址..."
                    notifyTaskUpdated(task)
                    refetchAndRetry(task)
                } else {
                    // 手动 URL 直播任务：直接用原 URL 重试
                    task.statusMessage = "重试中"
                    notifyTaskUpdated(task)
                    LiveRecordingService.startService(appContext, task)
                }
            } else {
                task.statusMessage = "重试中"
                notifyTaskUpdated(task)
                startDownloadJob(task)
            }
            Log.d(TAG, "retryTask: 重新启动任务 ${task.id}.")
        }
    }

    /**
     * 重新调平台 API 取流并启动录制。
     * 重试间隔短(15秒)，最多 3 次——用户在等，不需要像定时任务那样等 10 分钟。
     */
    private fun refetchAndRetry(task: DownloadTask) {
        retryJobs[task.id]?.cancel()
        retryJobs[task.id] = CoroutineScope(Dispatchers.IO).launch {
            val maxRetries = 3
            val retryIntervalMs = 15 * 1000L

            for (attempt in 1..maxRetries) {
                if (!isActive) return@launch

                // 1. 调平台 API 取流
                val fetchResult = when (task.platform) {
                    "chaturbate" -> ChaturbateApi.fetchStreamUrl(task.roomSlug!!)
                    "stripchat" -> StripchatApi.fetchStreamUrl(
                        StripchatApi.RoomInfo(slug = task.roomSlug!!, baseUrl = task.roomBaseUrl ?: "")
                    )
                    "cams" -> CamsApi.fetchStreamUrl(task.roomSlug!!)
                    else -> {
                        task.statusMessage = "配置错误：未知平台 ${task.platform}"
                        task.isCancelled = true
                        notifyTaskUpdated(task)
                        return@launch
                    }
                }

                if (!fetchResult.isSuccess) {
                    val msg = fetchResult.errorMessage ?: "未获取到流"
                    Log.d(TAG, "重试取流 ${task.id} 失败($attempt/$maxRetries): $msg")
                    if (attempt < maxRetries) {
                        task.statusMessage = "取流失败，${retryIntervalMs / 1000}秒后重试($attempt/$maxRetries)"
                        notifyTaskUpdated(task)
                        delay(retryIntervalMs)
                    } else {
                        task.statusMessage = "重试失败：$msg"
                        task.isCancelled = true
                        notifyTaskUpdated(task)
                    }
                    continue
                }

                // 2. resolveMasterPlaylist 拿 video/audio media URL
                val resolved = when (task.platform) {
                    "chaturbate" -> ChaturbateApi.resolveMasterPlaylist(fetchResult.m3u8Url)
                    "stripchat" -> StripchatApi.resolveMasterPlaylist(fetchResult.m3u8Url, task.roomBaseUrl)
                    "cams" -> CamsApi.resolveMasterPlaylist(fetchResult.m3u8Url)
                    else -> null
                }
                if (resolved == null || resolved.videoPlaylistUrl.isBlank()) {
                    val msg = resolved?.errorMessage ?: "解析播放列表失败"
                    Log.d(TAG, "重试 resolve ${task.id} 失败($attempt/$maxRetries): $msg")
                    if (attempt < maxRetries) {
                        task.statusMessage = "解析失败，重试中($attempt/$maxRetries)"
                        notifyTaskUpdated(task)
                        delay(retryIntervalMs)
                    } else {
                        task.statusMessage = "重试失败：$msg"
                        task.isCancelled = true
                        notifyTaskUpdated(task)
                    }
                    continue
                }

                // 3. 成功：用新 URL 更新任务并启动录制
                Log.d(TAG, "重试取流 ${task.id} 成功: ${resolved.videoPlaylistUrl.take(80)}")
                val liveTask = task.copy(
                    url = resolved.videoPlaylistUrl,
                    audioTrackUrl = resolved.audioPlaylistUrl,
                    isLLHls = true
                ).apply {
                    statusMessage = "取流成功，开始录制"
                    progress = 0
                    isCancelled = false
                    isPaused = false
                }
                liveTask._downloadedSize.value = 0L
                liveTask._elapsedTime.value = 0L
                currentTasksState[task.id] = liveTask
                notifyTaskUpdated(liveTask)
                LiveRecordingService.startService(appContext, liveTask)
                return@launch
            }
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