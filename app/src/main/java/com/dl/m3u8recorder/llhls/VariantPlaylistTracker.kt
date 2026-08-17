package com.dl.m3u8recorder.llhls

import android.util.Log
import com.dl.m3u8recorder.parser.InitSegment
import com.dl.m3u8recorder.parser.M3U8ParserImpl
import com.dl.m3u8recorder.parser.M3U8Playlist
import com.dl.m3u8recorder.parser.Part
import com.dl.m3u8recorder.parser.Segment
import com.dl.m3u8recorder.parser.ServerControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * LL-HLS 变体播放列表跟踪器
 * 支持 LL-HLS 阻塞请求 (CAN-BLOCK-RELOAD) 和实时部分片段追踪
 */
class VariantPlaylistTracker(
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "VariantPlaylistTracker"
        private const val DEFAULT_PART_TARGET = 0.8 // 默认部分片段目标时长
        private const val MAX_IDLE_MS = 60000 // 最大空闲时间（60秒没有新片段则认为流已结束）
    }

    private val parser = M3U8ParserImpl()

    // 追踪状态
    private var lastMediaSequence: Long = -1
    private var lastSegmentIndex: Int = -1  // 最后的片段序号（仅日志用）
    private var lastKnownSegmentCount: Int = 0  // 上次 playlists 的片段数量，用于 detectNewSegments
    private var currentInitSegment: InitSegment? = null
    private var serverControl: ServerControl? = null
    private var partTarget: Double = DEFAULT_PART_TARGET
    private var lastSegmentTime: Long = System.currentTimeMillis() // 上次收到新片段的时间

    // 已打印过结构诊断的 URL（每个 URL 仅首次抓到时打印一次原始内容，避免轮询刷屏）
    private val diagnosedUrls = java.util.Collections.synchronizedSet(HashSet<String>())

    // 创建专门用于阻塞请求的客户端（超时时间更长）
    // 继承原始 client 的 interceptor（包含请求头）
    private val blockingClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(10, TimeUnit.SECONDS)
            // 关键：复用原 client 的连接池，避免新建连接
            .connectionPool(client.connectionPool)
            .build()
    }

    /**
     * 播放列表状态
     */
    data class PlaylistState(
        val mediaSequence: Long,
        val targetDuration: Double,
        val segments: List<Segment>,
        val parts: List<Part>,
        val initSegment: InitSegment?,
        val isLive: Boolean,
        val serverControl: ServerControl?,
        val preloadHints: List<String> // 预加载提示 URI
    )

    /**
     * 跟踪播放列表并回调新片段
     * 支持 LL-HLS 阻塞请求
     *
     * @param isOtherTrackerActive 可选的检查函数，如果返回 true 表示其他轨道的 tracker 还在运行，
     *                             此时即使达到最大错误数也不会停止（因为流还活着）
     */
    suspend fun trackPlaylist(
        playlistUrl: String,
        onNewSegments: suspend (List<Segment>, List<Part>) -> Unit,
        onInitSegment: suspend (InitSegment) -> Unit,
        isOtherTrackerActive: () -> Boolean = { false }
    ) = withContext(Dispatchers.IO) {
        Log.w(TAG, "══════ VariantPlaylistTracker 启动 ══════")
        Log.w(TAG, "跟踪 LL-HLS Media Playlist: $playlistUrl")

        var consecutiveErrors = 0
        val maxErrors = 10  // 连续 10 次失败则认为流已结束

        while (isActive) {
            try {
                val requestUrl = buildBlockingRequestUrl(playlistUrl)
                val state = fetchAndParse(requestUrl)

                consecutiveErrors = 0

                if (!state.isLive) {
                    Log.d(TAG, "播放列表包含 ENDLIST 标记，直播流已结束")
                    if (state.segments.isNotEmpty()) {
                        onNewSegments(state.segments, state.parts)
                    }
                    return@withContext
                }

                state.serverControl?.let {
                    serverControl = it
                    Log.d(TAG, "服务器控制: CAN-BLOCK-RELOAD=${it.canBlockReload}, PART-HOLD-BACK=${it.partHoldBack}")
                }

                if (state.initSegment != null && state.initSegment != currentInitSegment) {
                    currentInitSegment = state.initSegment
                    Log.d(TAG, "新初始化片段: ${state.initSegment.uri}")
                    onInitSegment(state.initSegment)
                }

                val newSegments = detectNewSegments(state)
                if (newSegments.isNotEmpty()) {
                    // 收集这些新片段对应的 PART 分块（LL-HLS 直播边缘整段可能尚未落地而 404，
                    // PART 分块一定可用，供下载层在整段失败时回退）。
                    val newSeqs = newSegments.map { it.sequenceNumber }.toSet()
                    val newParts = state.parts.filter { it.sequenceNumber in newSeqs }
                    Log.w(TAG, "发现 ${newSegments.size} 个新完整片段 (mediaSequence=${state.mediaSequence}, segments=${state.segments.size}), 关联 PART=${newParts.size}")
                    // 打印每个新片段的 URI
                    newSegments.forEachIndexed { idx, seg ->
                        Log.d(TAG, "  片段[$idx]: uri=${seg.uri.take(80)} duration=${seg.duration}s " +
                                "pdt=${seg.programDateTimeMs?.let { java.util.Date(it) } ?: "无"}")
                    }
                    onNewSegments(newSegments, newParts)
                    lastSegmentTime = System.currentTimeMillis()
                }

                updateTrackingState(state)

                val idleMs = System.currentTimeMillis() - lastSegmentTime
                if (idleMs > MAX_IDLE_MS) {
                    Log.e(TAG, "超过 ${MAX_IDLE_MS / 1000} 秒没有新片段，直播流已结束")
                    return@withContext
                }

                if (serverControl?.canBlockReload != true) {
                    val interval = calculateRefreshInterval(state)
                    delay(interval)
                }

            } catch (e: Exception) {
                consecutiveErrors++
                Log.e(TAG, "获取播放列表失败 (${consecutiveErrors}/${maxErrors}): ${e.message}")

                if (consecutiveErrors >= maxErrors) {
                    val otherActive = isOtherTrackerActive()
                    Log.d(TAG, "连续 $maxErrors 次获取失败，isOtherTrackerActive=$otherActive")
                    if (otherActive) {
                        Log.w(TAG, "连续 $maxErrors 次获取失败，但其他轨道还在运行，继续重试...")
                        consecutiveErrors = maxErrors / 2
                    } else {
                        Log.e(TAG, "连续 $maxErrors 次获取失败，直播流已结束，停止跟踪")
                        return@withContext
                    }
                }

                delay(1000)
            }
        }
    }

    /**
     * 构建阻塞请求 URL
     * LL-HLS 使用 _HLS_msn 和 _HLS_part 参数
     * _HLS_msn = 媒体序列号（片段序号）
     * _HLS_part = 部分片段索引
     */
    private fun buildBlockingRequestUrl(baseUrl: String): String {
        // 如果服务器不支持阻塞请求，直接返回原始 URL
        if (serverControl?.canBlockReload != true) {
            return baseUrl
        }

        // 如果还没有追踪状态，不添加阻塞参数
        if (lastMediaSequence < 0) {
            return baseUrl
        }

        val urlBuilder = StringBuilder(baseUrl)
        val separator = if (baseUrl.contains("?")) "&" else "?"

        // 下一个期望的片段序号 = 首个片段的媒体序列号 + 已知片段总数
        // 按 LL-HLS 协议，_HLS_msn 是媒体序列号，不是文件名里的编号
        val nextMsn = lastMediaSequence + lastKnownSegmentCount
        urlBuilder.append("${separator}_HLS_msn=$nextMsn")

        Log.v(TAG, "阻塞请求: mediaSeq=$lastMediaSequence count=$lastKnownSegmentCount msn=$nextMsn")

        return urlBuilder.toString()
    }

    /**
     * 检测新的完整片段
     *
     * 音视频 trackers 各自独立运行，不在此函数内做跨轨道同步。
     * 同步在 LLHlsRecorder 层通过 SyncCoordinator 处理。
     */
    private fun detectNewSegments(state: PlaylistState): List<Segment> {
        if (lastMediaSequence < 0) {
            // 首次获取，返回所有片段
            return state.segments
        }

        if (state.mediaSequence > lastMediaSequence) {
            // 媒体序列增加了，计算新增的片段
            val sequenceDiff = (state.mediaSequence - lastMediaSequence).toInt()

            return if (sequenceDiff <= state.segments.size) {
                state.segments.takeLast(sequenceDiff)
            } else {
                // 序列号跳了太多（阻塞请求返回时窗口已滑过），全部返回，下游去重
                state.segments
            }
        }

        // state.mediaSequence == lastMediaSequence
        // 某些 CDN 不改变 mediaSequence 也会追加新片段（滑动窗口末尾增加）
        if (state.segments.size > lastKnownSegmentCount) {
            val newCount = state.segments.size - lastKnownSegmentCount
            return state.segments.takeLast(newCount)
        }

        return emptyList()
    }

    /**
     * 从 URI 中提取片段序号
     * 例如: seg_0_5032_video_xxx.m4s -> 5032
     */
    private fun extractSegmentIndex(uri: String): Int? {
        // 匹配: seg_0_5032_video_xxx.m4s
        val segMatch = Regex("""seg_\d+_(\d+)_""").find(uri)
        return segMatch?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * 更新追踪状态
     */
    private fun updateTrackingState(state: PlaylistState) {
        lastMediaSequence = state.mediaSequence
        lastKnownSegmentCount = state.segments.size

        // 从完整片段中更新最后的片段序号（仅用于日志）
        if (state.segments.isNotEmpty()) {
            val lastSegment = state.segments.last()
            extractSegmentIndex(lastSegment.uri)?.let {
                if (it > lastSegmentIndex) {
                    lastSegmentIndex = it
                    Log.d(TAG, "更新片段序号: $lastSegmentIndex")
                }
            }
        }
    }

    /**
     * 一次性获取播放列表状态
     */
    suspend fun fetchOnce(playlistUrl: String): PlaylistState? = withContext(Dispatchers.IO) {
        try {
            fetchAndParse(playlistUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch playlist: $playlistUrl", e)
            null
        }
    }

    /**
     * 获取并解析播放列表
     * 优化：非阻塞请求使用普通 client（连接复用更好），阻塞请求才用 blockingClient
     */
    private suspend fun fetchAndParse(url: String, depth: Int = 0): PlaylistState = withContext(Dispatchers.IO) {
        // 请求头由 client 的 interceptor 统一设置
        val request = Request.Builder()
            .url(url)
            .build()

        // 判断是否是阻塞请求：URL 带 _HLS_msn 参数
        val isBlockingRequest = url.contains("_HLS_msn=")
        // 非阻塞请求用普通 client（连接复用），阻塞请求用 blockingClient（长超时）
        val httpClient = if (isBlockingRequest) blockingClient else client
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = try { response.body?.string() } catch (_: Exception) { null }
            Log.e(TAG, "HTTP ${response.code}: ${errorBody ?: "无响应体"}")
            throw Exception("HTTP ${response.code}: ${errorBody?.take(200) ?: url}")
        }

        val content = response.body?.string() ?: throw Exception("Empty response")

        // 诊断：每个 URL 首次抓到时打印 playlist 结构，重点确认是否带 #EXT-X-PROGRAM-DATE-TIME
        if (diagnosedUrls.add(url)) {
            logPlaylistStructure(url, content)
        }

        // 检测部分片段目标时长
        val partTargetMatch = Regex("""#EXT-X-PART-INF:PART-TARGET=([\d.]+)""").find(content)
        partTargetMatch?.let {
            partTarget = it.groupValues[1].toDoubleOrNull() ?: DEFAULT_PART_TARGET
        }

        val playlist = parser.parse(content, url)

        when (playlist) {
            is M3U8Playlist.Media -> {
                PlaylistState(
                    mediaSequence = playlist.mediaSequence,
                    targetDuration = playlist.targetDuration,
                    segments = playlist.segments,
                    parts = playlist.parts,
                    initSegment = playlist.initSegment,
                    isLive = playlist.isLive,
                    serverControl = playlist.serverControl,
                    preloadHints = playlist.preloadHints.map { it.uri }
                )
            }
            is M3U8Playlist.Master -> {
                // Master Playlist 自动选择最佳变体重新获取（最多一层递归）
                if (playlist.variants.isEmpty()) {
                    throw Exception("Master Playlist has no variants")
                }
                if (depth >= 1) {
                    throw Exception("Max recursion depth reached resolving Master Playlist")
                }
                val bestVariant = parser.selectBestVariant(playlist.variants)
                Log.w(TAG, "传入 URL 是 Master Playlist (${playlist.variants.size} 变体)，自动选择最佳变体: " +
                        "${bestVariant.resolution} (${bestVariant.bandwidth / 1000}kbps) url=${bestVariant.uri}")
                // 递归获取最佳变体 Media Playlist（最多一层）
                fetchAndParse(bestVariant.uri, depth + 1)
            }
        }
    }

    /**
     * 诊断：打印 playlist 结构，重点确认是否带 #EXT-X-PROGRAM-DATE-TIME
     * 用于决定音视频跨轨对齐走 PDT 还是 EXTINF 时长推算
     */
    private fun logPlaylistStructure(url: String, content: String) {
        val lines = content.lines()
        val shortUrl = url.substringBefore("?") // 截掉查询参数，避免日志里带 token

        val hasPdt = content.contains("#EXT-X-PROGRAM-DATE-TIME:")
        val pdtCount = content.split("#EXT-X-PROGRAM-DATE-TIME:").size - 1
        val hasPartInf = content.contains("#EXT-X-PART-INF")
        val hasServerControl = content.contains("#EXT-X-SERVER-CONTROL")
        val hasEndList = content.contains("#EXT-X-ENDLIST")
        val hasMap = content.contains("#EXT-X-MAP")
        val mediaSeq = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") } ?: "(无)"
        val targetDur = lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") } ?: "(无)"
        val partTargetLine = lines.firstOrNull { it.startsWith("#EXT-X-PART-INF:") } ?: "(无)"

        // 提取 init segment URI
        val initSegMatch = Regex("""#EXT-X-MAP:URI="([^"]+)"""").find(content)
        val initSegUri = initSegMatch?.groupValues?.get(1)

        Log.w(TAG, "══════ Playlist 结构诊断 ══════")
        Log.w(TAG, "  URL: $shortUrl")
        Log.w(TAG, "  类型: ${if (lines.any { it.startsWith("#EXT-X-STREAM-INF:") }) "Master" else "Media"} | " +
                "LL-HLS=${hasPartInf && hasServerControl} | ENDLIST=$hasEndList")
        Log.w(TAG, "  $mediaSeq")
        Log.w(TAG, "  $targetDur")
        Log.w(TAG, "  $partTargetLine")
        Log.w(TAG, "  segments=${lines.count { it.startsWith("#EXTINF:") }} | " +
                "parts=${lines.count { it.startsWith("#EXT-X-PART:") }} | " +
                "PDT=$hasPdt (${pdtCount}次)")

        if (initSegUri != null) {
            Log.w(TAG, "  Init Segment: $initSegUri")
        }

        if (hasPdt) {
            val pdtSamples = Regex("""#EXT-X-PROGRAM-DATE-TIME:(.+)""")
                .findAll(content)
                .take(2)
                .map { it.groupValues[1].trim() }
                .toList()
            Log.w(TAG, "  PDT 样本: $pdtSamples")
        }

        // 打印所有 segment URI（截断显示）
        val segUris = lines.filter { !it.startsWith("#") && it.isNotBlank() }
        if (segUris.isNotEmpty()) {
            Log.w(TAG, "  Segment URIs (前${kotlin.math.min(segUris.size, 5)}个):")
            segUris.take(5).forEachIndexed { idx, uri ->
                Log.w(TAG, "    [$idx] ${uri.take(100)}")
            }
            if (segUris.size > 5) {
                Log.w(TAG, "    ... 共 ${segUris.size} 个")
            }
        }

        // 打印所有 part URI
        val partUris = lines.filter { it.startsWith("#EXT-X-PART:") }
        if (partUris.isNotEmpty()) {
            Log.w(TAG, "  Part 片段 (前${kotlin.math.min(partUris.size, 3)}个):")
            partUris.take(3).forEachIndexed { idx, part ->
                val uriMatch = Regex("""URI="([^"]+)"""").find(part)
                Log.w(TAG, "    [$idx] ${uriMatch?.groupValues?.get(1)?.take(100) ?: part.take(80)}")
            }
            if (partUris.size > 3) {
                Log.w(TAG, "    ... 共 ${partUris.size} 个")
            }
        }

        Log.w(TAG, "════════════════════════════════")
    }

    /**
     * 计算刷新间隔
     */
    private fun calculateRefreshInterval(state: PlaylistState): Long {
        // 对于 LL-HLS，使用部分片段目标时长作为基础间隔
        var interval = (partTarget * 1000 * 0.8).toLong() // 80% 的部分片段时长

        // 优先使用服务器控制的 part-hold-back
        state.serverControl?.let { control ->
            if (control.partHoldBack != null) {
                interval = minOf(interval, (control.partHoldBack * 1000 * 0.5).toLong())
            }
        }

        // 限制在 200ms - 2000ms 之间（LL-HLS 需要更快的刷新）
        return interval.coerceIn(200, 2000)
    }

    /**
     * 重置状态
     */
    fun reset() {
        lastMediaSequence = -1
        lastSegmentIndex = -1
        lastKnownSegmentCount = 0
        currentInitSegment = null
        serverControl = null
        diagnosedUrls.clear()
        Log.d(TAG, "VariantPlaylistTracker reset")
    }
}
