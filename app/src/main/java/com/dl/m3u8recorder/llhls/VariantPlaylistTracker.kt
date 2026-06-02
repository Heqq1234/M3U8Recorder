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
    }

    private val parser = M3U8ParserImpl()

    // 追踪状态
    private var lastMediaSequence: Long = -1
    private var lastSegmentIndex: Int = -1  // 最后的片段序号
    private var lastPartIndex: Int = -1     // 最后的部分片段索引
    private var currentInitSegment: InitSegment? = null
    private var serverControl: ServerControl? = null
    private var partTarget: Double = DEFAULT_PART_TARGET

    // 创建专门用于阻塞请求的客户端（超时时间更长）
    // 继承原始 client 的 interceptor（包含请求头）
    private val blockingClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(10, TimeUnit.SECONDS)
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
     */
    suspend fun trackPlaylist(
        playlistUrl: String,
        onNewParts: suspend (List<Part>) -> Unit,
        onNewSegments: suspend (List<Segment>) -> Unit,
        onInitSegment: suspend (InitSegment) -> Unit
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, "开始跟踪 LL-HLS 播放列表: $playlistUrl")

        while (isActive) {
            try {
                // 构建请求 URL（支持阻塞请求）
                val requestUrl = buildBlockingRequestUrl(playlistUrl)

                val state = fetchAndParse(requestUrl)

                // 更新服务器控制信息
                state.serverControl?.let {
                    serverControl = it
                    Log.d(TAG, "服务器控制: CAN-BLOCK-RELOAD=${it.canBlockReload}, PART-HOLD-BACK=${it.partHoldBack}")
                }

                // 处理初始化片段 (#EXT-X-MAP)
                if (state.initSegment != null && state.initSegment != currentInitSegment) {
                    currentInitSegment = state.initSegment
                    Log.d(TAG, "新初始化片段: ${state.initSegment.uri}")
                    onInitSegment(state.initSegment)
                }

                // 检测新的完整片段
                val newSegments = detectNewSegments(state)
                if (newSegments.isNotEmpty()) {
                    Log.d(TAG, "发现 ${newSegments.size} 个新完整片段")
                    onNewSegments(newSegments)
                }

                // 检测新的部分片段
                val newParts = detectNewParts(state)
                if (newParts.isNotEmpty()) {
                    Log.d(TAG, "发现 ${newParts.size} 个新部分片段")
                    onNewParts(newParts)
                }

                // 更新追踪状态
                updateTrackingState(state)

                // 如果服务器不支持阻塞请求，使用延迟
                if (serverControl?.canBlockReload != true) {
                    val interval = calculateRefreshInterval(state)
                    delay(interval)
                } else {
                    // 阻塞请求模式下，短暂延迟避免过快请求
                    delay(100)
                }

            } catch (e: Exception) {
                Log.e(TAG, "跟踪播放列表错误: ${e.message}", e)
                delay(1000) // 出错后等待 1 秒重试
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
        if (lastSegmentIndex < 0) {
            return baseUrl
        }

        val urlBuilder = StringBuilder(baseUrl)
        val separator = if (baseUrl.contains("?")) "&" else "?"

        // 下一个期望的片段序号
        val nextMsn = lastSegmentIndex + 1
        urlBuilder.append("${separator}_HLS_msn=$nextMsn")

        // 如果有部分片段索引，添加 part 参数
        if (lastPartIndex >= 0) {
            val nextPart = lastPartIndex + 1
            urlBuilder.append("&_HLS_part=$nextPart")
        }

        Log.v(TAG, "阻塞请求: msn=$nextMsn, part=${if (lastPartIndex >= 0) lastPartIndex + 1 else "none"}")

        return urlBuilder.toString()
    }

    /**
     * 检测新的完整片段
     */
    private fun detectNewSegments(state: PlaylistState): List<Segment> {
        if (lastMediaSequence < 0) {
            // 首次获取，返回所有片段
            return state.segments
        }

        if (state.mediaSequence > lastMediaSequence) {
            // 新的媒体序列，计算新增的片段
            val sequenceDiff = (state.mediaSequence - lastMediaSequence).toInt()

            // 如果序列号增加，表示有新片段
            // LL-HLS 中，每个媒体序列通常对应一个完整片段
            return if (sequenceDiff <= state.segments.size) {
                state.segments.takeLast(sequenceDiff)
            } else {
                state.segments
            }
        }

        return emptyList()
    }

    /**
     * 检测新的部分片段
     */
    private fun detectNewParts(state: PlaylistState): List<Part> {
        val parts = state.parts

        if (parts.isEmpty()) {
            return emptyList()
        }

        if (lastSegmentIndex < 0) {
            // 首次获取，返回所有独立的部分片段
            Log.d(TAG, "首次获取，返回 ${parts.filter { it.independent }.size} 个独立部分片段")
            return parts.filter { it.independent }
        }

        // 找到上次之后的部分片段
        val newParts = mutableListOf<Part>()

        for (part in parts) {
            val info = extractPartInfo(part.uri)
            if (info != null) {
                // 比较 (segmentIndex, partIndex) 元组
                if (info.segmentIndex > lastSegmentIndex ||
                    (info.segmentIndex == lastSegmentIndex && info.partIndex > lastPartIndex)) {
                    newParts.add(part)
                }
            }
        }

        return newParts
    }

    /**
     * 从 URI 中提取部分片段信息
     * 例如: part_0_5036_0_video_xxx.m4s -> PartInfo(segmentIndex=5036, partIndex=0)
     */
    private data class PartInfo(val segmentIndex: Int, val partIndex: Int)

    private fun extractPartInfo(uri: String): PartInfo? {
        // 匹配: part_0_5036_0_video_xxx.m4s
        // 格式: part_<track>_<segment>_<part>_video_...
        val partMatch = Regex("""part_\d+_(\d+)_(\d+)_""").find(uri)
        return partMatch?.let {
            val segmentIndex = it.groupValues[1].toIntOrNull() ?: return null
            val partIndex = it.groupValues[2].toIntOrNull() ?: return null
            PartInfo(segmentIndex, partIndex)
        }
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

        // 从完整片段中更新最后的片段序号
        if (state.segments.isNotEmpty()) {
            val lastSegment = state.segments.last()
            extractSegmentIndex(lastSegment.uri)?.let {
                if (it > lastSegmentIndex) {
                    lastSegmentIndex = it
                    lastPartIndex = -1 // 新片段开始，重置部分索引
                    Log.d(TAG, "更新片段序号: $lastSegmentIndex")
                }
            }
        }

        // 更新最后的部分片段索引
        if (state.parts.isNotEmpty()) {
            val lastPart = state.parts.last()
            val info = extractPartInfo(lastPart.uri)
            if (info != null) {
                // 更新片段序号和部分索引
                if (info.segmentIndex > lastSegmentIndex || lastSegmentIndex < 0) {
                    lastSegmentIndex = info.segmentIndex
                }
                lastPartIndex = info.partIndex
                Log.d(TAG, "更新部分片段: segment=$lastSegmentIndex, part=$lastPartIndex")
            }
        }

        // 更新部分片段目标时长
        if (state.parts.isNotEmpty()) {
            partTarget = state.parts.first().duration.coerceIn(0.2, 2.0)
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
     */
    private suspend fun fetchAndParse(url: String): PlaylistState = withContext(Dispatchers.IO) {
        // 请求头由 client 的 interceptor 统一设置
        val request = Request.Builder()
            .url(url)
            .build()

        val response = blockingClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: $url")
        }

        val content = response.body?.string() ?: throw Exception("Empty response")

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
                throw Exception("Expected Media Playlist but got Master Playlist")
            }
        }
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
        lastPartIndex = -1
        currentInitSegment = null
        serverControl = null
        Log.d(TAG, "VariantPlaylistTracker reset")
    }
}
