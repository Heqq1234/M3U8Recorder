package com.dl.m3u8recorder.llhls

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.net.Proxy
import java.net.ProxySelector
import java.util.concurrent.TimeUnit

/**
 * Chaturbate API 工具
 *
 * 调用 Chaturbate 内部 API 获取直播流地址，
 * 以及解析 Master Playlist 得到实际 Media Playlist URL。
 */
object ChaturbateApi {
    private const val TAG = "ChaturbateApi"

    data class StreamResult(
        val m3u8Url: String = "",
        val roomStatus: String? = null,
        val errorMessage: String? = null
    ) {
        val isSuccess: Boolean get() = m3u8Url.isNotBlank()
    }

    data class ResolvedStream(
        val videoPlaylistUrl: String = "",
        val audioPlaylistUrl: String? = null,
        val errorMessage: String? = null
    ) {
        val isSuccess: Boolean get() = videoPlaylistUrl.isNotBlank()
    }

    val ERROR_MAP = mapOf(
        "offline" to "房间当前不在直播",
        "private" to "房间正在进行私密秀",
        "away" to "主播暂时离开",
        "password protected" to "房间需要密码",
        "hidden" to "隐藏会话进行中"
    )

    /**
     * 构建带系统代理检测的 OkHttpClient
     */
    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)

        val proxy = detectSystemProxyFor("chaturbate.com")
        if (proxy != null) {
            builder.proxy(proxy)
        }
        return builder.build()
    }

    /**
     * 调用 Chaturbate API 获取 m3u8 URL
     */
    suspend fun fetchStreamUrl(roomSlug: String): StreamResult = withContext(Dispatchers.IO) {
        try {
            val client = buildClient()
            val apiUrl = "https://chaturbate.com/get_edge_hls_url_ajax/"
            val formBody = FormBody.Builder().add("room_slug", roomSlug).build()

            val request = Request.Builder()
                .url(apiUrl).post(formBody)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "https://chaturbate.com/$roomSlug/")
                .header("Origin", "https://chaturbate.com")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            Log.d(TAG, "获取流地址: room=$roomSlug")
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val isCf = body.contains("cf-browser-verification", ignoreCase = true)
                return@withContext StreamResult(errorMessage = if (isCf) "Cloudflare 拦截" else "HTTP ${response.code}")
            }

            val json = JSONObject(body)
            val m3u8Url = json.optString("url", "")
            val roomStatus = json.optString("room_status", "")

            when {
                m3u8Url.isNotBlank() -> {
                    Log.d(TAG, "成功获取流地址: ${m3u8Url.take(80)}...")
                    StreamResult(m3u8Url = m3u8Url)
                }
                roomStatus.isNotBlank() -> {
                    StreamResult(errorMessage = ERROR_MAP[roomStatus] ?: "状态: $roomStatus", roomStatus = roomStatus)
                }
                else -> StreamResult(errorMessage = "API 返回空结果")
            }
        } catch (e: Exception) {
            Log.e(TAG, "请求失败", e)
            StreamResult(errorMessage = "网络错误: ${e.localizedMessage ?: e.message}")
        }
    }

    /**
     * 解析 Master Playlist → 得到最高画质的 Media Playlist URL
     *
     * API 返回的是 Master Playlist（多分辨率/多音轨），
     * LL-HLS 录制器需要直接的 Media Playlist URL。
     */
    suspend fun resolveMasterPlaylist(m3u8Url: String): ResolvedStream = withContext(Dispatchers.IO) {
        try {
            val client = buildClient()
            val request = Request.Builder()
                .url(m3u8Url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "*/*")
                .header("Referer", "https://chaturbate.com/")
                .build()

            Log.d(TAG, "解析 Master Playlist...")
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext ResolvedStream(errorMessage = "空响应")

            if (!response.isSuccessful) {
                return@withContext ResolvedStream(errorMessage = "HTTP ${response.code}")
            }

            // 手动解析 Master Playlist，选最高画质
            val baseUrl = m3u8Url.substringBeforeLast("/")
            val origin = "${m3u8Url.substringBefore("://")}://${m3u8Url.substringAfter("://").substringBefore("/")}"
            val lines = body.lines()
            var bestBw = -1
            var videoUrl: String? = null
            var audioUrl: String? = null
            var audioGroupId: String? = null

            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val attrs = parseAttributes(line)
                    val bw = attrs["BANDWIDTH"]?.toIntOrNull() ?: 0
                    i++
                    val url = lines.getOrNull(i)?.trim() ?: ""
                    val fullUrl = if (url.startsWith("http")) url
                        else if (url.startsWith("/")) "$origin$url"
                        else "$baseUrl/$url"
                    if (bw > bestBw) {
                        bestBw = bw
                        videoUrl = fullUrl
                        audioGroupId = attrs["AUDIO"]
                    }
                } else if (line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO")) {
                    val attrs = parseAttributes(line)
                    val uri = attrs["URI"]
                    val gid = attrs["GROUP-ID"]
                    if (uri != null) {
                        val fullUrl = if (uri.startsWith("http")) uri
                            else if (uri.startsWith("/")) "$origin$uri"
                            else "$baseUrl/$uri"
                        if (audioUrl == null && (audioGroupId == null || gid == audioGroupId)) {
                            audioUrl = fullUrl
                        }
                    }
                }
                i++
            }

            if (videoUrl != null) {
                Log.d(TAG, "Master 解析完成: video=$videoUrl audio=$audioUrl")
                ResolvedStream(videoPlaylistUrl = videoUrl, audioPlaylistUrl = audioUrl)
            } else {
                ResolvedStream(errorMessage = "未找到视频流")
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析 Master 失败", e)
            ResolvedStream(errorMessage = "解析失败: ${e.message}")
        }
    }

    private fun parseAttributes(line: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val regex = Regex("""([A-Z0-9_-]+)=("([^"]*)"|([^,]*))""")
        for (match in regex.findAll(line.substringAfter(":"))) {
            val key = match.groupValues[1]
            val value = match.groupValues[3].ifBlank { match.groupValues[4] }
            map[key] = value
        }
        return map
    }

    private fun detectSystemProxyFor(host: String): Proxy? {
        try {
            val uri = java.net.URI("https://$host")
            val proxies = ProxySelector.getDefault()?.select(uri) ?: emptyList()
            for (p in proxies) {
                if (p.type() != Proxy.Type.DIRECT) {
                    val addr = p.address() as? java.net.InetSocketAddress
                    if (addr != null) {
                        Log.d(TAG, "检测到系统代理: ${addr.hostString}:${addr.port}")
                        return p
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun isValidRoomUrl(url: String): Boolean {
        return Regex("""https?://(?:[^/]+\.)?chaturbate\.(?:com|eu|global)/(?:fullvideo/?\?.*?\bb=)?([^/?&#]+)""")
            .containsMatchIn(url.trim())
    }

    fun extractRoomSlug(input: String): String? {
        val trimmed = input.trim()
        val urlMatch = Regex("""https?://(?:[^/]+\.)?chaturbate\.(?:com|eu|global)/(?:fullvideo/?\?.*?\bb=)?([^/?&#]+)""")
            .find(trimmed)
        if (urlMatch != null) {
            Log.d(TAG, "从 URL 提取房间名: ${urlMatch.groupValues[1]}")
            return urlMatch.groupValues[1]
        }
        if (Regex("""^[a-zA-Z0-9_]+$""").matches(trimmed)) {
            Log.d(TAG, "纯文本房间名: $trimmed")
            return trimmed
        }
        return null
    }
}