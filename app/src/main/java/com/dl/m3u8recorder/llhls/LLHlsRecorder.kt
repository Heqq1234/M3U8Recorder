package com.dl.m3u8recorder.llhls

import android.content.Context
import android.util.Log
import com.dl.m3u8recorder.model.DownloadTask
import com.dl.m3u8recorder.parser.Part
import com.dl.m3u8recorder.parser.Segment
import com.dl.m3u8recorder.utils.Mp4OutputHelper
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class LLHlsRecorder(
    private val context: Context,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectionPool(okhttp3.ConnectionPool(32, 5, java.util.concurrent.TimeUnit.MINUTES))
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        })
        .build()
) {
    companion object {
        private const val TAG = "LLHlsRecorder"
    }

    private val activeSessions = ConcurrentHashMap<String, RecordingSession>()
    private val globalProgressCounters = ConcurrentHashMap<String, Int>()
    private val partDownloader = PartDownloader(okHttpClient)

    /**
     * Stripchat / 白标站点专用分片下载客户端。
     *
     * 根因：Doppio CDN 的 media 分片(EXTINF / PART)请求必须带与页面同源的
     * Referer + Origin（devtools 抓包：referer/origin 均为 https://zh.stripchat.com）才能 200，
     * 否则裸 URL 直接 404。playlist 因 URL 带 pkey 能下、init 段是公开文件能下 ——
     * 这正是此前录出 3KB 空壳的原因。
     *
     * 修复：从传入的 okHttpClient 派生(newBuilder 会继承 DownloadManager 里已修正的
     * Referer/Origin/sec-fetch 拦截器与连接池)。这里额外挂上 StripchatApi.sharedCookieJar
     * 作为后备（CDN 实测并不下发 cookie，留作保险，无副作用）。
     * 仅 platform=="stripchat" 走此客户端；Chaturbate 仍用原 okHttpClient，逻辑零改动。
     */
    // Stripchat 分片请求的实际 Referer/Origin 来源：按房间页面域名动态派生，
    // 不再硬编码 zh.stripchat.com —— 顶域 stripchat.com / 白标 hotzcam.com 等必须用对应域名，
    // 否则 Doppio CDN 的反盗链(Referer 校验)会直接 404（nginx "Not Found"）。
    // 每个 Stripchat 任务在 startRecording 时按 task.roomBaseUrl 重设；单流录制无并发问题。
    @Volatile
    private var stripchatRefererOrigin: String = "https://zh.stripchat.com"

    private val stripchatClient by lazy {
        okHttpClient.newBuilder()
            .cookieJar(StripchatApi.sharedCookieJar)
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                val builder = req.newBuilder()
                if (url.contains("doppiocdn.net") || url.contains("doppiocdn.com") || url.contains("edge-hls") ||
                    url.contains("stripchat") || url.contains("hotzcam")) {
                    // 覆盖 DownloadManager 全局拦截器写死的 zh.stripchat.com，使用本任务真实页面域名
                    builder.header("Referer", "$stripchatRefererOrigin/")
                        .header("Origin", stripchatRefererOrigin)
                        .header("Sec-Fetch-Dest", "empty")
                        .header("Sec-Fetch-Mode", "cors")
                        .header("Sec-Fetch-Site", "cross-site")
                }
                chain.proceed(builder.build())
            }
            .build()
    }
    private val stripchatPartDownloader = PartDownloader(stripchatClient)

    private val muxer = PipeMuxer()

    private data class RecordingSession(
        val taskId: String,
        val task: DownloadTask,
        val outputFile: File,
        val videoFile: File,
        val audioFile: File?,
        val videoTracker: VariantPlaylistTracker?,
        val audioTracker: VariantPlaylistTracker?,
        val scope: CoroutineScope,
        var videoJob: Job?,
        var audioJob: Job?,
        // 各轨实际下载的首个 segment 的墙钟 PDT(ms)，用于事后 PipeMuxer 跨轨对齐
        @Volatile var videoFirstPdtMs: Long? = null,
        @Volatile var audioFirstPdtMs: Long? = null,
        @Volatile var isStopped: Boolean = false,
        // 跨轨首段墙钟对齐点(ms)：两轨开始落盘的统一墙钟。
        // null=无需对齐(无音频轨/无 PDT)；非 null 时各轨只下载 pdt>=align 的 segment。
        val alignDeferred: CompletableDeferred<Long?> = CompletableDeferred(),
        @Volatile var alignPdtMs: Long? = null,
        // 去重：已下载的 segment URI 集合
        val downloadedSegments: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        // 去重：已下载的 part (分块) URI 集合（整段失败回退到 PART 分块时用）
        val downloadedParts: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        // 实时合成器：样本级精确对齐
        val realTimeMuxer: RealTimeMuxer = RealTimeMuxer(outputFile, task.id),
        // 是否 Stripchat 平台：决定分片下载走 stripchatPartDownloader(带会话 Cookie) 还是原客户端
        val isStripchat: Boolean = false,
        // Stripchat 分片 404 时的实验性重试：把 playlist 的鉴权查询参数(pkey/psch/playlistType)补回分片 URL。
        // 从 playlist URL 的 query 提取，仅 isStripchat 时非空；Chaturbate 为 null → 不触发补参重试。
        val segmentAuthQuery: String? = null,
        // 原始 playlist URL（含 pkey 等查询参数），仅作诊断/上下文记录，不再用于构造取片地址。
        // 分片一律走 #EXT-X-MOUFLON 提供的真实 .mp4 URI（见 M3U8ParserImpl.normalizeMouflonUri）。
        val videoPlaylistUrl: String = ""
    )

    /**
     * 从房间页面 URL 推导同源 Referer/Origin（取 scheme://host）。
     * 例：https://zh.stripchat.com/room/foo -> https://zh.stripchat.com
     *     https://stripchat.com                -> https://stripchat.com
     *     https://xx.hotzcam.com/embed         -> https://xx.hotzcam.com
     */
    private fun deriveStripchatOrigin(baseUrl: String): String = try {
        val uri = java.net.URI(baseUrl)
        val host = uri.host
        if (host.isNullOrBlank()) "https://zh.stripchat.com" else "${uri.scheme ?: "https"}://$host"
    } catch (_: Exception) {
        "https://zh.stripchat.com"
    }

    suspend fun startRecording(
        task: DownloadTask,
        onProgress: (progress: Int, status: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (activeSessions.containsKey(task.id)) {
            Log.w(TAG, "Task ${task.id} already recording")
            return@withContext
        }

        onProgress(0, "初始化 LL-HLS 录制...")

        try {
        val videoPlaylistUrl = task.getEffectiveUrl()
        val audioPlaylistUrl = task.audioTrackUrl
        val isStripchat = task.platform == "stripchat"
        // 提取 playlist 的鉴权查询参数（pkey/psch/playlistType/preferredVideoCodec），用于分片 404 时补参重试
        val segmentAuthQuery = if (isStripchat) videoPlaylistUrl.substringAfter("?", "").takeIf { it.isNotEmpty() } else null
        Log.d(TAG, "URL debug: effectiveUrl=$videoPlaylistUrl audioUrl=$audioPlaylistUrl stripchat=$isStripchat authQuery=${segmentAuthQuery?.take(40)}")

        // 按房间页面域名动态设置 Stripchat 分片请求的 Referer/Origin（反盗链关键）。
        // roomBaseUrl 形如 https://stripchat.com 或 https://zh.stripchat.com 或 https://xx.hotzcam.com，
        // 取 scheme://host 作为同源 Referer/Origin；缺省回退 zh.。
        if (isStripchat) {
            stripchatRefererOrigin = task.roomBaseUrl
                ?.let { deriveStripchatOrigin(it) } ?: "https://zh.stripchat.com"
            Log.d(TAG, "[${task.id}] Stripchat referer origin = $stripchatRefererOrigin (roomBaseUrl=${task.roomBaseUrl})")
        }

            val outputFile = Mp4OutputHelper.getOutputFile(context, task.fileName, ".mp4")
            outputFile.parentFile?.mkdirs()
            val videoFile = File(outputFile.parentFile, "${task.id}_video.mp4")
            val audioFile = audioPlaylistUrl?.let { File(outputFile.parentFile, "${task.id}_audio.mp4") }

            val videoTracker = VariantPlaylistTracker(okHttpClient)
            val audioTracker = audioPlaylistUrl?.let { VariantPlaylistTracker(okHttpClient) }

            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            onProgress(1, "获取播放列表信息...")
            val videoState = videoTracker.fetchOnce(videoPlaylistUrl)
            if (videoState == null) {
                onProgress(0, "无法获取播放列表")
                return@withContext
            }

            val session = RecordingSession(
                taskId = task.id,
                task = task,
                outputFile = outputFile,
                videoFile = videoFile,
                audioFile = audioFile,
                videoTracker = videoTracker,
                audioTracker = audioTracker,
                isStripchat = isStripchat,
                segmentAuthQuery = segmentAuthQuery,
                videoPlaylistUrl = videoPlaylistUrl,
                scope = scope,
                videoJob = null,
                audioJob = null
            )

            activeSessions[task.id] = session

            // 下载并传入 init segment 给 RealTimeMuxer，用于初始化 parser
            if (videoState.initSegment != null) {
                onProgress(2, "下载视频初始化片段...")
                Log.d(TAG, "[${task.id}] Downloading video init segment: ${videoState.initSegment.uri}")
                val videoInit = (if (isStripchat) stripchatPartDownloader else partDownloader)
                    .downloadInitSegment(videoState.initSegment.uri, task.id)
                if (videoInit != null) {
                    Log.d(TAG, "[${task.id}] Video init segment OK (${videoInit.size} bytes), setting to RealTimeMuxer")
                    // 仅当任务携带真实分辨率（如 Stripchat 变体）时覆盖 init 占位分辨率；
                    // Chaturbate 的 selectedResolution 为 null，这里不传，行为不变。
                    session.realTimeMuxer.setVideoResolutionOverride(task.selectedResolution)
                    session.realTimeMuxer.setVideoInitData(videoInit)
                    // 缓存原始 fMP4 init 到 videoFile，供 RealTimeMuxer 失败时 FFmpeg 回退重封装
                    try {
                        session.videoFile.writeBytes(videoInit)
                        Log.d(TAG, "[${task.id}] 已缓存 video init 到 ${session.videoFile.name} (${videoInit.size} bytes)")
                    } catch (e: Exception) {
                        Log.w(TAG, "[${task.id}] 缓存 video init 失败: ${e.message}")
                    }
                } else {
                    Log.e(TAG, "[${task.id}] Failed to download video init segment")
                }
            } else {
                Log.w(TAG, "[${task.id}] No video init segment in playlist")
            }
            if (audioPlaylistUrl != null) {
                val audioState = audioTracker?.fetchOnce(audioPlaylistUrl)
                if (audioState?.initSegment != null) {
                    onProgress(3, "下载音频初始化片段...")
                    Log.d(TAG, "[${task.id}] Downloading audio init segment: ${audioState.initSegment.uri}")
                    val audioInit = (if (isStripchat) stripchatPartDownloader else partDownloader)
                        .downloadInitSegment(audioState.initSegment.uri, task.id)
                    if (audioInit != null) {
                        Log.d(TAG, "[${task.id}] Audio init segment OK (${audioInit.size} bytes), setting to RealTimeMuxer")
                        session.realTimeMuxer.setAudioInitData(audioInit)
                        try {
                            session.audioFile?.writeBytes(audioInit)
                            Log.d(TAG, "[${task.id}] 已缓存 audio init 到 ${session.audioFile?.name} (${audioInit.size} bytes)")
                        } catch (e: Exception) {
                            Log.w(TAG, "[${task.id}] 缓存 audio init 失败: ${e.message}")
                        }
                    } else {
                        Log.e(TAG, "[${task.id}] Failed to download audio init segment")
                    }
                } else {
                    Log.w(TAG, "[${task.id}] No audio init segment, setting no audio track")
                    session.realTimeMuxer.setNoAudioTrack()
                }
            } else {
                Log.d(TAG, "[${task.id}] No audio playlist URL, setting no audio track")
                session.realTimeMuxer.setNoAudioTrack()
            }

            onProgress(5, "开始录制...")

            val videoJob = scope.launch {
                try {
                    Log.w(TAG, "[$task.id] VIDEO tracker 启动 @ ${System.currentTimeMillis()}ms")
                    videoTracker.trackPlaylist(
                        playlistUrl = videoPlaylistUrl,
                        onInitSegment = {},
                        onNewSegments = { segments, parts ->
                            appendSegments(segments, parts, session, true, onProgress)
                        }
                    )
                } finally {
                    Log.d(TAG, "Video tracker finished: ${task.id}")
                }
            }

            val audioJob = audioPlaylistUrl?.let { audioUrl ->
                scope.launch {
                    try {
                        Log.w(TAG, "[$task.id] AUDIO tracker 启动 @ ${System.currentTimeMillis()}ms")
                        audioTracker?.trackPlaylist(
                            playlistUrl = audioUrl,
                            onInitSegment = {},
                            onNewSegments = { segments, parts ->
                                appendSegments(segments, parts, session, false, onProgress)
                            },
                            isOtherTrackerActive = {
                                val active = videoJob.isActive
                                Log.d(TAG, "audio isOtherTrackerActive check: videoJob.active=$active")
                                active
                            }
                        )
                    } finally {
                        Log.d(TAG, "Audio tracker finalized, videoJob.isActive=${videoJob.isActive}")
                    }
                }
            }

            // 直接在 session 上赋值 job，保持 map 里的 session 与 appendSegments 闭包捕获的是同一对象
            // （PDT 等可变字段才能跨阶段传递）。data class copy 会产生新对象，导致可变字段丢失。
            session.videoJob = videoJob
            session.audioJob = audioJob
            activeSessions[task.id] = session
            Log.d(TAG, "LL-HLS recording started: ${task.id}")

            // 跨轨首段墙钟对齐协调：等两轨各自首段 PDT 收集到后(appendSegments 在
            // 每批 segments 到达时写入 videoFirstPdtMs/audioFirstPdtMs)，取 max 作为
            // 两轨统一落盘起点。对齐点下发给 RealTimeMuxer 做样本级精确裁剪。
            // 无音频轨/无 PDT 时 align=null，放行全部。
            val audioJobLocal = audioJob
            Log.d(TAG, "[${task.id}] 跨轨对齐协调启动: hasAudio=${audioJobLocal != null}")
            scope.launch {
                if (audioJobLocal == null) {
                    Log.w(TAG, "[${task.id}] 跨轨对齐放弃: 无 audio 轨, 放行全部")
                    session.alignDeferred.complete(null)
                    return@launch
                }
                val startTime = System.currentTimeMillis()
                val ALIGN_TIMEOUT_MS = 5000L // 5秒超时

                while (isActive) {
                    val vPdt = session.videoFirstPdtMs
                    val aPdt = session.audioFirstPdtMs

                    // 两轨PDT都就绪，计算对齐点
                    if (vPdt != null && aPdt != null) {
                        val alignMs = maxOf(vPdt, aPdt)
                        session.alignPdtMs = alignMs
                        // 关键：把对齐点下发给实时合成器，做帧级裁剪
                        session.realTimeMuxer.setAlignPoint(alignMs)
                        session.alignDeferred.complete(alignMs)
                        Log.d(TAG, "[${task.id}] 跨轨对齐基准: $alignMs ms")
                        return@launch
                    }

                    // 超时兜底：5秒没拿到双轨PDT，放弃精确对齐
                    if (System.currentTimeMillis() - startTime > ALIGN_TIMEOUT_MS) {
                        Log.w(TAG, "[${task.id}] PDT对齐超时，放弃精确对齐")
                        session.alignDeferred.complete(null)
                        return@launch
                    }

                    // 某轨结束仍无PDT，放弃对齐
                    if (session.isStopped ||
                        (!videoJob.isActive && vPdt == null) ||
                        (!audioJobLocal.isActive && aPdt == null)) {
                        session.alignDeferred.complete(null)
                        Log.w(TAG, "[${task.id}] 跨轨对齐放弃: videoPdt=$vPdt audioPdt=$aPdt (某轨无PDT/已结束)")
                        return@launch
                    }
                    delay(50)
                }
            }

            videoJob.join()
            audioJob?.join()
            Log.d(TAG, "LL-HLS recording finished: ${task.id}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LL-HLS recording", e)
            onProgress(0, "启动失败: ${e.message}")
        }
    }

    /**
     * 停止录制 → RealTimeMuxer 实时合成（样本级精确对齐）
     * 如果 RealTimeMuxer 失败，回退到 PipeMuxer 后合并
     */
    suspend fun stopRecording(taskId: String): File? = withContext(Dispatchers.IO) {
        val session = activeSessions.remove(taskId) ?: return@withContext null
        session.isStopped = true
        Log.w(TAG, "[$taskId] ===== STOP RECORDING =====")
        Log.d(TAG, "[$taskId] Downloaded segments: ${session.downloadedSegments.size}")
        Log.d(TAG, "[$taskId] Video first PDT: ${session.videoFirstPdtMs}ms, Audio first PDT: ${session.audioFirstPdtMs}ms")

        // 1. 取消下载协程
        session.videoJob?.cancel()
        session.audioJob?.cancel()
        runCatching { session.videoJob?.join() }
        runCatching { session.audioJob?.join() }
        Log.d(TAG, "[$taskId] Download jobs cancelled")

        // 2. 等待合成器排空所有缓存、写入尾部、释放资源
        val muxStartMs = System.currentTimeMillis()
        val realtimeSuccess = session.realTimeMuxer.stop()
        val muxElapsed = System.currentTimeMillis() - muxStartMs
        Log.d(TAG, "[$taskId] RealTimeMuxer.stop() done: success=$realtimeSuccess, elapsed=${muxElapsed}ms")

        // 3. 判定 RealTimeMuxer 产物是否有效。
        //    绕过 OPPO/ColorOS 等设备的原生 MPEG4Writer 在 stop() 时偶发"未落盘"导致
        //    只剩 3KB 头部（val:-1011 / "track is not started"）的问题：有效文件应远大于头部。
        //    该回退仅在 RealTimeMuxer 产物无效时触发，Chaturbate 等 MediaMuxer 正常成功的不受影响。
        val MIN_VALID_SIZE = 50 * 1024L
        val realtimeValid = realtimeSuccess &&
                session.outputFile.exists() && session.outputFile.length() > MIN_VALID_SIZE

        var finalSuccess = realtimeValid
        if (!realtimeValid) {
            if (session.videoFile.exists() && session.videoFile.length() > MIN_VALID_SIZE) {
                Log.w(TAG, "[$taskId] RealTimeMuxer 产物无效(${session.outputFile.length()}B)，回退 FFmpeg 合成")
                try {
                    finalSuccess = if (session.audioFile != null && session.audioFile.exists() && session.audioFile.length() > 1024) {
                        muxer.mux(session.videoFile, session.audioFile, session.outputFile, 0L, session.videoFirstPdtMs, session.audioFirstPdtMs)
                    } else {
                        muxer.muxVideoOnly(session.videoFile, session.outputFile)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[$taskId] PipeMuxer 回退失败", e)
                    finalSuccess = false
                }
            } else {
                Log.e(TAG, "[$taskId] RealTimeMuxer 失败且无可用原始分片(videoFile=${session.videoFile.length()}B)，无法回退")
            }
        }

        // 4. 清理协程域和临时文件（回退已用完 videoFile/audioFile，这里再删）
        session.scope.cancel()
        session.videoFile.delete()
        session.audioFile?.delete()
        globalProgressCounters.remove(taskId)

        if (finalSuccess && session.outputFile.exists() && session.outputFile.length() > 0) {
            val finalSize = session.outputFile.length()
            Log.w(TAG, "[$taskId] ===== RECORDING SUCCESS =====")
            Log.w(TAG, "[$taskId] Output: ${session.outputFile.absolutePath}")
            Log.w(TAG, "[$taskId] File size: $finalSize bytes (${finalSize / 1024} KB, ${finalSize / 1024 / 1024} MB)")
            session.outputFile
        } else {
            Log.e(TAG, "[$taskId] ===== RECORDING FAILED =====")
            Log.e(TAG, "[$taskId] finalSuccess=$finalSuccess, exists=${session.outputFile.exists()}, size=${session.outputFile.length()}")
            null
        }
    }

    suspend fun cancelRecording(taskId: String) {
        val session = activeSessions.remove(taskId) ?: return
        session.isStopped = true
        session.videoJob?.cancel()
        session.audioJob?.cancel()
        session.scope.cancel()
        session.videoFile.delete()
        session.audioFile?.delete()
        globalProgressCounters.remove(taskId)
    }

    private suspend fun appendSegments(
        segments: List<Segment>,
        parts: List<Part>,
        session: RecordingSession,
        isVideo: Boolean,
        onProgress: (Int, String) -> Unit
    ) {
        if (session.isStopped) return

        val trackLabel = if (isVideo) "VIDEO" else "AUDIO"
        Log.d(TAG, "[${session.taskId}] $trackLabel appendSegments called: ${segments.size} segments, ${parts.size} parts received")

        val newSegments = segments.filter { session.downloadedSegments.add(it.uri) }
        if (newSegments.isEmpty()) {
            Log.v(TAG, "[${session.taskId}] $trackLabel all segments already downloaded, skipping")
            return
        }
        Log.d(TAG, "[${session.taskId}] $trackLabel new segments to download: ${newSegments.size} (total downloaded=${session.downloadedSegments.size})")

        // 按 sequenceNumber 索引 part 分块，供整段下载失败时回退。
        // LL-HLS 直播边缘：CDN 此时只提供 #EXT-X-PART 分块，整段(#EXTINF)文件尚未落地会 404；
        // PART 分块一定可下。此回退不影响 Chaturbate——其整段本就成功，不会进入回退分支。
        val partsBySeq = parts.groupBy { it.sequenceNumber }

        // 在下载前先收集该轨首个有 PDT 的 segment 墙钟，供跨轨对齐协调协程判定 align 点。
        // （比在下载完成回调里记录更早，避免 appendSegments 与 align 协调互相等待死锁）
        for (s in newSegments) {
            val pdt = s.programDateTimeMs ?: continue
            if (isVideo && session.videoFirstPdtMs == null) {
                session.videoFirstPdtMs = pdt
                Log.d(TAG, "[${session.taskId}] 首段 video PDT = $pdt ms")
            } else if (!isVideo && session.audioFirstPdtMs == null) {
                session.audioFirstPdtMs = pdt
                Log.d(TAG, "[${session.taskId}] 首段 audio PDT = $pdt ms")
            }
            break
        }

        val pd = if (session.isStripchat) stripchatPartDownloader else partDownloader
        Log.d(TAG, "[${session.taskId}] $trackLabel starting download of ${newSegments.size} segments (stripchat=${session.isStripchat})")

        if (session.isStripchat) {
            // Stripchat / Doppio CDN：整段 URI（无 _partN 后缀）在源站是占位/不存在对象，
            // 只有 _partN 分块（与 _init_）是真实可下对象（真机日志 + 浏览器 devtools 印证：
            // init 与 _partN 返回 200，整段 404 为 nginx 10 字节 Not Found）。
            // 因此直接下载 PART 分块，跳过整段 404 重试风暴；仅当某 segment 完全没有 part 时，
            // 才回退下载其整段 URI（极少触发）。每批节约 ~整段重试耗时，避免直播边缘落后。
            val segsWithParts = newSegments.filter { partsBySeq[it.sequenceNumber].orEmpty().isNotEmpty() }
            val segsWithoutParts = newSegments.filter { partsBySeq[it.sequenceNumber].orEmpty().isEmpty() }

            val freshParts = segsWithParts.flatMap { seg ->
                partsBySeq[seg.sequenceNumber].orEmpty().sortedBy { it.partIndex }
            }.distinctBy { it.uri }.filter { session.downloadedParts.add(it.uri) }

            if (freshParts.isNotEmpty()) {
                Log.d(TAG, "[${session.taskId}] $trackLabel Stripchat: 直接下载 ${freshParts.size} 个 PART 分块（跳过整段 404）")
                val fbStart = System.currentTimeMillis()
                pd.downloadParallelOrdered(
                    taskId = session.taskId,
                    items = freshParts.mapIndexed { i, p ->
                        PartDownloader.DownloadItem(i, p.uri, p.programDateTimeMs)
                    },
                    authQuery = session.segmentAuthQuery
                ) { index, data, pdtMs ->
                    if (session.isStopped) return@downloadParallelOrdered
                    val pUri = freshParts.getOrNull(index)?.uri?.takeLast(50) ?: "?"
                    if (data != null) {
                        writeFragment(isVideo, session, data, pdtMs, index, pUri, onProgress)
                    } else {
                        Log.w(TAG, "[${session.taskId}] $trackLabel PART #$index ($pUri) FAILED, skipping")
                    }
                }
                Log.d(TAG, "[${session.taskId}] $trackLabel PART 直下 done: ${freshParts.size} parts, ${System.currentTimeMillis() - fbStart}ms")
            }

            if (segsWithoutParts.isNotEmpty()) {
                Log.w(TAG, "[${session.taskId}] $trackLabel Stripchat: ${segsWithoutParts.size} 个 segment 无 part，回退整段下载")
                val fbStart = System.currentTimeMillis()
                pd.downloadParallelOrdered(
                    taskId = session.taskId,
                    items = segsWithoutParts.mapIndexed { i, s ->
                        PartDownloader.DownloadItem(i, s.uri, s.programDateTimeMs)
                    },
                    authQuery = session.segmentAuthQuery
                ) { index, data, pdtMs ->
                    if (session.isStopped) return@downloadParallelOrdered
                    val sUri = segsWithoutParts.getOrNull(index)?.uri?.takeLast(50) ?: "?"
                    if (data != null) {
                        writeFragment(isVideo, session, data, pdtMs, index, sUri, onProgress)
                    } else {
                        Log.w(TAG, "[${session.taskId}] $trackLabel 整段回退 #$index ($sUri) FAILED, skipping")
                    }
                }
                Log.d(TAG, "[${session.taskId}] $trackLabel 整段回退 done: ${segsWithoutParts.size} segments, ${System.currentTimeMillis() - fbStart}ms")
            }
        } else {
            // 第一步：优先下载整段（#EXTINF）。Chaturbate / VOD 走这里且成功。
            val failedSegments = mutableListOf<Segment>()
            val batchStartMs = System.currentTimeMillis()
            pd.downloadParallelOrdered(
                taskId = session.taskId,
                items = newSegments.mapIndexed { i, s ->
                    PartDownloader.DownloadItem(i, s.uri, s.programDateTimeMs)
                },
                authQuery = session.segmentAuthQuery
            ) { index, data, pdtMs ->
                if (session.isStopped) return@downloadParallelOrdered
                val seg = newSegments.getOrNull(index)
                val segUri = seg?.uri?.takeLast(50) ?: "?"
                if (data != null) {
                    writeFragment(isVideo, session, data, pdtMs, index, segUri, onProgress)
                } else {
                    // 整段下载失败（如 LL-HLS 直播边缘 404），记录待回退到 PART 分块
                    Log.w(TAG, "[${session.taskId}] $trackLabel segment #$index ($segUri) download FAILED, will try PART fallback")
                    seg?.let { failedSegments.add(it) }
                }
            }

            val batchElapsed = System.currentTimeMillis() - batchStartMs
            Log.d(TAG, "[${session.taskId}] $trackLabel batch done: ${newSegments.size} segments, ${batchElapsed}ms")

            // 第二步：对整段失败的，回退下载其 PART 分块（按 partIndex 排序，保证顺序）
            if (failedSegments.isNotEmpty()) {
                val fallbackParts = failedSegments.flatMap { seg ->
                    partsBySeq[seg.sequenceNumber].orEmpty().sortedBy { it.partIndex }
                }.distinctBy { it.uri }.filter { session.downloadedParts.add(it.uri) }

                if (fallbackParts.isNotEmpty()) {
                    Log.w(TAG, "[${session.taskId}] $trackLabel PART fallback: ${fallbackParts.size} parts for ${failedSegments.size} failed segments")
                    val fbStart = System.currentTimeMillis()
                    pd.downloadParallelOrdered(
                        taskId = session.taskId,
                        items = fallbackParts.mapIndexed { i, p ->
                            PartDownloader.DownloadItem(i, p.uri, p.programDateTimeMs)
                        },
                        authQuery = session.segmentAuthQuery
                    ) { index, data, pdtMs ->
                        if (session.isStopped) return@downloadParallelOrdered
                        val pUri = fallbackParts.getOrNull(index)?.uri?.takeLast(50) ?: "?"
                        if (data != null) {
                            writeFragment(isVideo, session, data, pdtMs, index, pUri, onProgress)
                        } else {
                            Log.w(TAG, "[${session.taskId}] $trackLabel PART fallback #$index ($pUri) FAILED, skipping")
                        }
                    }
                    Log.d(TAG, "[${session.taskId}] $trackLabel PART fallback done: ${fallbackParts.size} parts, ${System.currentTimeMillis() - fbStart}ms")
                } else {
                    Log.e(TAG, "[${session.taskId}] $trackLabel segment(s) failed AND no PART available for fallback (seq=${failedSegments.map { it.sequenceNumber }})")
                }
            }
        }
    }

    /**
     * 把一段已下载的分片(part / segment)数据送入合成器并刷新进度。
     * 抽出来供「整段优先 / PART 回退」两条路径共用。
     */
    private fun writeFragment(
        isVideo: Boolean,
        session: RecordingSession,
        data: ByteArray,
        pdtMs: Long?,
        index: Int,
        fragUri: String,
        onProgress: (Int, String) -> Unit
    ) {
        val trackLabel = if (isVideo) "VIDEO" else "AUDIO"
        Log.v(TAG, "[${session.taskId}] $trackLabel fragment #$index ($fragUri, ${data.size} bytes, pdt=$pdtMs) -> muxer")
        if (isVideo) {
            session.realTimeMuxer.addVideoFragment(data, pdtMs)
            // 缓存原始分片到 videoFile（FFmpeg 回退用，仅在 RealTimeMuxer 失败时使用）
            try { session.videoFile.appendBytes(data) } catch (e: Exception) { Log.v(TAG, "[${session.taskId}] append video chunk failed: ${e.message}") }
            if (session.videoFirstPdtMs == null && pdtMs != null) {
                session.videoFirstPdtMs = pdtMs
                Log.d(TAG, "[${session.taskId}] VIDEO first PDT recorded: $pdtMs ms")
            }
        } else {
            session.realTimeMuxer.addAudioFragment(data, pdtMs)
            try { session.audioFile?.appendBytes(data) } catch (e: Exception) { Log.v(TAG, "[${session.taskId}] append audio chunk failed: ${e.message}") }
            if (session.audioFirstPdtMs == null && pdtMs != null) {
                session.audioFirstPdtMs = pdtMs
                Log.d(TAG, "[${session.taskId}] AUDIO first PDT recorded: $pdtMs ms")
            }
        }
        // 更新进度
        val cnt = globalProgressCounters.compute(session.taskId) { _, v -> (v ?: 0) + 1 } ?: 1
        onProgress(minOf(95, 5 + cnt), "录制中...片段 #$cnt")
    }

    fun pauseRecording(taskId: String) {
        activeSessions[taskId]?.let {
            it.videoJob?.cancel(); it.audioJob?.cancel()
        }
    }

    fun hasActiveSession(taskId: String): Boolean = activeSessions.containsKey(taskId)
}
