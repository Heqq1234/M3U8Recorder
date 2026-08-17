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
 * Stripchat / 白标站点 API 工具
 *
 * 从 Stripchat 及其白标站点（如 zh.hotzcam.com）获取直播流地址。
 *
 * 流程：
 *   1. 请求房间页面 HTML
 *   2. 提取 window.__PRELOADED_STATE__ JSON
 *   3. 获取 model.id + hlsStreamHost
 *   4. 拼接 m3u8 URL: https://edge-hls.{host}/hls/{id}/master/{id}_auto.m3u8
 *   5. 解析 Master Playlist 得到实际 Media Playlist URL
 *
 * 参考: yt-dlp stripchat extractor
 */
object StripchatApi {
    private const val TAG = "StripchatApi"

    /**
     * 跨请求共享的会话 CookieJar。
     * Stripchat 的真实直播 pkey 往往只在"带会话"的请求里出现于 Master 的
     * #EXT-X-MOUFLON 行；匿名请求会被降级为预览片。把页面请求设置的 cookie
     * 复用到 m3u8 请求，才能从 Master 里取到 pkey。
     */
    // 内部可见：供 LLHlsRecorder 的分片下载客户端复用同一会话 Cookie
    // (Doppio CDN 在带 pkey 的 Media Playlist 响应里下发的会话 Cookie 必须随 media 分片请求回传，否则 404)
    internal val sharedCookieJar = object : okhttp3.CookieJar {
        private val store = java.util.concurrent.CopyOnWriteArrayList<okhttp3.Cookie>()
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            store.removeIf { c -> cookies.any { it.name == c.name } }
            store.addAll(cookies)
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
            return store.filter { it.matches(url) }
        }
    }

    /**
     * 房间信息
     */
    data class RoomInfo(
        val slug: String,
        val baseUrl: String  // e.g., "https://stripchat.com" or "https://zh.hotzcam.com"
    )

    data class StreamResult(
        override val m3u8Url: String = "",
        val roomStatus: String? = null,
        override val errorMessage: String? = null
    ) : StreamFetchResult {
        override val isSuccess: Boolean get() = m3u8Url.isNotBlank()
    }

    data class ResolvedStream(
        override val videoPlaylistUrl: String = "",
        override val audioPlaylistUrl: String? = null,
        /** 选中的变体分辨率字符串 (如 "1920x1080")，供下游录制修正建轨宽高 */
        val resolution: String? = null,
        override val errorMessage: String? = null
    ) : StreamResolveResult {
        override val isSuccess: Boolean get() = videoPlaylistUrl.isNotBlank()
    }

    // Stripchat 官方域名模式
    private val STRIPCHAT_URL_REGEX = Regex(
        """https?://(?:[^/]+\.)?stripchat\.(?:com|eu|global)/([^/?&#]+)""",
        RegexOption.IGNORE_CASE
    )

    // 白标站点域名模式（泛匹配，排除已知非 Stripchat 站点）
    private val WHITELABEL_URL_REGEX = Regex(
        """(https?://[^/]+)/([^/?&#]+)""",
        RegexOption.IGNORE_CASE
    )

    // 已知的非白标站点（避免误判）
    private val EXCLUDED_DOMAINS = setOf(
        "chaturbate.com", "www.chaturbate.com",
        "youtube.com", "www.youtube.com", "youtu.be",
        "twitch.tv", "www.twitch.tv",
        "google.com", "github.com", "facebook.com",
        "twitter.com", "x.com", "instagram.com"
    )

    // HLS 路径模式
    private val HLS_PATH_REGEX = Regex("""/hls/(\d+)/master/(\d+)_auto\.m3u8""")

    /**
     * 构建带系统代理检测的 OkHttpClient
     */
    private fun buildClient(host: String): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .cookieJar(sharedCookieJar)

        val proxy = detectSystemProxyFor(host)
        if (proxy != null) {
            builder.proxy(proxy)
        }
        return builder.build()
    }

    /**
     * 判断是否为 Stripchat 或其白标站点的房间 URL
     */
    fun isValidRoomUrl(url: String): Boolean {
        val trimmed = url.trim()

        // 官方 Stripchat 域名
        if (STRIPCHAT_URL_REGEX.matches(trimmed)) return true

        // 检查是否为已知白标站点或泛匹配
        val info = extractRoomSlug(trimmed)
        return info != null
    }

    /**
     * 从 URL 中提取房间名和基础域名
     *
     * 支持格式:
     *   - https://stripchat.com/RoomName
     *   - https://zh.hotzcam.com/RoomName
     *   - https://xxx.example.com/RoomName (白标站点)
     */
    fun extractRoomSlug(input: String): RoomInfo? {
        val trimmed = input.trim()

        // 纯文本房间名（仅字母数字下划线）
        if (Regex("""^[a-zA-Z0-9_-]+$""").matches(trimmed)) {
            Log.d(TAG, "纯文本房间名: $trimmed")
            return RoomInfo(slug = trimmed, baseUrl = "https://stripchat.com")
        }

        // 尝试 Stripchat 官方 URL
        val scMatch = STRIPCHAT_URL_REGEX.find(trimmed)
        if (scMatch != null) {
            val slug = scMatch.groupValues[1]
            val scheme = if (trimmed.startsWith("https")) "https" else "http"
            val domain = trimmed.substringAfter("://").substringBefore("/")
            Log.d(TAG, "Stripchat 官方 URL: slug=$slug, domain=$domain")
            return RoomInfo(slug = slug, baseUrl = "$scheme://$domain")
        }

        // 尝试泛匹配白标 URL: https?://domain/path
        val wlMatch = Regex("""^(https?://([^/]+))/([^/?&#]+)""").find(trimmed)
        if (wlMatch != null) {
            val domain = wlMatch.groupValues[2].lowercase()
            val slug = wlMatch.groupValues[3]
            val baseUrl = wlMatch.groupValues[1]

            // 排除已知非 Stripchat 站点
            val rootDomain = domain.substringAfterLast(".").let { tld ->
                domain.substringBeforeLast(".").substringAfterLast(".") + "." + tld
            }
            if (domain in EXCLUDED_DOMAINS || rootDomain in EXCLUDED_DOMAINS) {
                Log.d(TAG, "排除已知非 Stripchat 域名: $domain")
                return null
            }

            // 只接受看起来像房间名的 slug（不含常见文件扩展名）
            if (slug.contains(".") && !slug.endsWith(".html") && !slug.endsWith(".php")) {
                Log.d(TAG, "slug 包含扩展名，跳过: $slug")
                return null
            }

            Log.d(TAG, "白标站点 URL: slug=$slug, baseUrl=$baseUrl, domain=$domain")
            return RoomInfo(slug = sanitizeSlug(slug), baseUrl = baseUrl)
        }

        return null
    }

    /**
     * 清理房间名（去掉可能的查询参数残留）
     */
    private fun sanitizeSlug(slug: String): String {
        return slug.replace(Regex("""[?&#].*$"""), "").trim()
    }

    /**
     * 调用 Stripchat API 获取 m3u8 URL
     *
     * @param roomInfo 房间信息（slug + baseUrl）
     * @return StreamResult 包含 Master M3U8 URL
     */
    suspend fun fetchStreamUrl(roomInfo: RoomInfo): StreamResult = withContext(Dispatchers.IO) {
        Log.w(TAG, "══════ Stripchat 取流开始 ══════")
        Log.w(TAG, "[1/4] 直播间地址: ${roomInfo.baseUrl}/${roomInfo.slug}")
        try {
            val pageUrl = "${roomInfo.baseUrl}/${roomInfo.slug}"
            val host = roomInfo.baseUrl.substringAfter("://")

            val client = buildClient(host)

            Log.d(TAG, "[2/4] 请求页面: $pageUrl")
            val request = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Cache-Control", "no-cache")
                .build()

            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "[2/4] HTTP ${response.code}: ${html.take(200)}")
                val isCf = html.contains("cf-browser-verification", ignoreCase = true)
                    || html.contains("captcha", ignoreCase = true)
                return@withContext StreamResult(
                    errorMessage = if (isCf) "被 Cloudflare 拦截，请稍后重试" else "HTTP ${response.code}"
                )
            }

            Log.d(TAG, "[2/4] 页面获取成功，长度: ${html.length}")

            // 提取 window.__PRELOADED_STATE__
            val preloadedJson = extractPreloadedState(html)
            if (preloadedJson == null) {
                Log.e(TAG, "[2/4] ✗ 未找到 __PRELOADED_STATE__")
                return@withContext StreamResult(errorMessage = "无法解析页面数据（未找到预加载状态）")
            }

            Log.d(TAG, "[2/4] 提取到 __PRELOADED_STATE__，长度: ${preloadedJson.length}")

            // 解析 JSON
            val data = JSONObject(preloadedJson)
            val viewCam = data.optJSONObject("viewCam")
            if (viewCam == null) {
                Log.e(TAG, "[2/4] ✗ viewCam 字段不存在")
                return@withContext StreamResult(errorMessage = "页面数据格式异常（缺少 viewCam）")
            }

            // 说明: viewCam.show 字段在公开直播与私密直播中都存在(描述当前场次元信息),
            // 不能仅凭其非空就判定为私密秀, 否则公开房间会被误杀。
            // 私密秀改由下方 model.status == "private" 统一判定。
            // 检查直播状态
            val model = viewCam.optJSONObject("model")
            if (model == null) {
                Log.e(TAG, "[2/4] ✗ 无法获取主播信息")
                return@withContext StreamResult(errorMessage = "无法获取主播信息")
            }

            val isLive = model.optBoolean("isLive", false)
            if (!isLive) {
                val status = model.optString("status", "offline")
                Log.w(TAG, "[2/4] ✗ 房间不在直播: $status")
                return@withContext StreamResult(
                    errorMessage = when (status) {
                        "offline" -> "房间当前不在直播"
                        "away" -> "主播暂时离开"
                        "private" -> "房间正在进行私密秀"
                        else -> "房间不在直播 (状态: $status)"
                    },
                    roomStatus = status
                )
            }

            val liveStatus = model.optString("status", "unknown")
            Log.d(TAG, "[2/4] ✓ 房间正在直播 (status=$liveStatus), 进入取流流程")

            // 获取 model_id
            val modelId = model.optLong("id", 0)
            if (modelId == 0L) {
                val modelIdInt = model.optInt("id", 0)
                if (modelIdInt == 0) {
                    Log.e(TAG, "[2/4] ✗ 无法获取主播 ID")
                    return@withContext StreamResult(errorMessage = "无法获取主播 ID")
                }
                return@withContext buildM3u8Url(data, modelIdInt.toLong(), roomInfo)
            }

            return@withContext buildM3u8Url(data, modelId, roomInfo)
        } catch (e: Exception) {
            Log.e(TAG, "[2/4] ✗ 请求失败", e)
            StreamResult(errorMessage = "网络错误: ${e.localizedMessage ?: e.message}")
        }
    }

    /**
     * 从 __PRELOADED_STATE__ 中提取 HLS 服务器地址并拼接 m3u8 URL
     */
    private fun buildM3u8Url(data: JSONObject, modelId: Long, roomInfo: RoomInfo): StreamResult {
        Log.w(TAG, "[3/4] 构建 Master M3U8 URL...")
        // 按优先级查找 HLS 服务器地址
        val hlsHost = findHlsHost(data)
        if (hlsHost == null) {
            Log.e(TAG, "[3/4] ✗ 未找到 HLS 服务器地址")
            return StreamResult(errorMessage = "无法获取流媒体服务器地址")
        }

        Log.d(TAG, "[3/4] modelId=$modelId, hlsHost=$hlsHost")

        // 拼接 m3u8 URL
        // 格式: https://edge-hls.{host}/hls/{model_id}/master/{model_id}_auto.m3u8
        val m3u8Url = "https://edge-hls.$hlsHost/hls/$modelId/master/$modelId" + "_auto.m3u8"
        Log.w(TAG, "[3/4] ✓ Master M3U8 URL: $m3u8Url")

        return StreamResult(m3u8Url = m3u8Url)
    }

    /**
     * 按优先级从 __PRELOADED_STATE__ 查找 HLS 服务器地址
     *
     * 查找顺序:
     *   1. config.data.hlsStreamHost
     *   2. config.data.features.hlsFallback.fallbackDomains[]
     *   3. configV3.static.hlsStreamHost
     *   4. configV3.static.featuresV2.hlsFallback.fallbackDomains[]
     */
    private fun findHlsHost(data: JSONObject): String? {
        // 路径1: config.data.hlsStreamHost
        data.optJSONObject("config")?.optJSONObject("data")?.let { configData ->
            configData.optString("hlsStreamHost", null)?.let {
                if (it.isNotBlank()) {
                    Log.d(TAG, "HLS host from config.data.hlsStreamHost: $it")
                    return it
                }
            }
            // 路径2: config.data.features.hlsFallback.fallbackDomains
            configData.optJSONObject("features")?.optJSONObject("hlsFallback")?.let { fallback ->
                val domains = fallback.optJSONArray("fallbackDomains")
                if (domains != null && domains.length() > 0) {
                    val domain = domains.optString(0, null)
                    if (!domain.isNullOrBlank()) {
                        Log.d(TAG, "HLS host from config.data.features.hlsFallback.fallbackDomains[0]: $domain")
                        return domain
                    }
                }
            }
        }

        // 路径3 & 4: configV3.static
        data.optJSONObject("configV3")?.optJSONObject("static")?.let { configV3 ->
            configV3.optString("hlsStreamHost", null)?.let {
                if (it.isNotBlank()) {
                    Log.d(TAG, "HLS host from configV3.static.hlsStreamHost: $it")
                    return it
                }
            }
            configV3.optJSONObject("featuresV2")?.optJSONObject("hlsFallback")?.let { fallback ->
                val domains = fallback.optJSONArray("fallbackDomains")
                if (domains != null && domains.length() > 0) {
                    val domain = domains.optString(0, null)
                    if (!domain.isNullOrBlank()) {
                        Log.d(TAG, "HLS host from configV3.static.featuresV2.hlsFallback.fallbackDomains[0]: $domain")
                        return domain
                    }
                }
            }
        }

        // 兜底：尝试从 config.data 的 data 字段直接遍历
        return try {
            val config = data.optJSONObject("config")
            val configData = config?.optJSONObject("data")
            // 深度搜索 hlsStreamHost
            deepFindString(configData ?: config ?: data, "hlsStreamHost")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 深度搜索 JSON 中的字符串字段（用于兜底查找）
     */
    private fun deepFindString(obj: Any?, key: String): String? {
        if (obj == null) return null
        when (obj) {
            is JSONObject -> {
                val direct = obj.optString(key, null)
                if (!direct.isNullOrBlank()) return direct
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val result = deepFindString(obj.opt(keys.next()), key)
                    if (result != null) return result
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until obj.length()) {
                    val result = deepFindString(obj.opt(i), key)
                    if (result != null) return result
                }
            }
        }
        return null
    }

    /**
     * 从 HTML 中提取 window.__PRELOADED_STATE__
     *
     * 支持两种格式：
     *   1. window.__PRELOADED_STATE__ = {...};
     *   2. window.__PRELOADED_STATE__ = JSON.parse("...");
     */
    private fun extractPreloadedState(html: String): String? {
        // 格式1: window.__PRELOADED_STATE__ = {...};
        val regex1 = Regex(
            """window\.__PRELOADED_STATE__\s*=\s*(\{.+?\});""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        regex1.find(html)?.let { match ->
            Log.d(TAG, "格式1: 直接 JSON 对象，长度: ${match.groupValues[1].length}")
            return match.groupValues[1]
        }

        // 格式2: window.__PRELOADED_STATE__ = JSON.parse("...");
        val regex2 = Regex(
            """window\.__PRELOADED_STATE__\s*=\s*JSON\.parse\("((?:[^"\\]|\\.)*)"\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        regex2.find(html)?.let { match ->
            val escaped = match.groupValues[1]
            val unescaped = escaped
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
            Log.d(TAG, "格式2: JSON.parse，长度: ${unescaped.length}")
            return unescaped
        }

        // 兜底：更宽松的正则
        val regex3 = Regex(
            """window\[["']__PRELOADED_STATE__["']\]\s*=\s*(\{.+?\});""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        regex3.find(html)?.let { match ->
            Log.d(TAG, "格式3: 方括号语法")
            return match.groupValues[1]
        }

        return null
    }

    /**
     * 解析 Master Playlist → 得到最高画质的 Media Playlist URL
     *
     * @param m3u8Url Master Playlist URL
     * @param refererDomain 用于 Referer 请求头的域名（白标站点的域名）
     */
    suspend fun resolveMasterPlaylist(m3u8Url: String, refererDomain: String? = null): ResolvedStream =
        withContext(Dispatchers.IO) {
            Log.w(TAG, "[4/4] 解析 Master Playlist...")
            Log.w(TAG, "  ↳ Master M3U8 URL: $m3u8Url")
            try {
                val host = m3u8Url.substringAfter("://").substringBefore("/")
                val client = buildClient(host)

                val referer = if (refererDomain != null) {
                    if (refererDomain.startsWith("http")) refererDomain else "https://$refererDomain"
                } else {
                    "https://stripchat.com/"
                }

                val request = Request.Builder()
                    .url(m3u8Url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "*/*")
                    .header("Referer", referer)
                    .build()

                Log.d(TAG, "  请求 Master Playlist (referer=$referer)")
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                    ?: return@withContext ResolvedStream(errorMessage = "空响应")

                if (!response.isSuccessful) {
                    Log.e(TAG, "  ✗ Master Playlist 下载失败: HTTP ${response.code}")
                    return@withContext ResolvedStream(errorMessage = "HTTP ${response.code}")
                }

                // 手动解析 Master Playlist，选最高画质
                val baseUrl = m3u8Url.substringBeforeLast("/")
                val origin = "${m3u8Url.substringBefore("://")}://${m3u8Url.substringAfter("://").substringBefore("/")}"
                val lines = body.lines()

                // 提取真实直播 pkey：Stripchat 的直播流地址必须带 pkey(+playlistType=lowLatency)
                // 参数，否则 CDN 只回一段循环的预览/宣传片(ENDLIST + 6 个 cpa/v2 静态分片)。
                // pkey 位于 Master Playlist 顶部自定义标签: #EXT-X-MOUFLON:PSCH:v2:<pkey>
                val mouflonMatch = Regex("""#EXT-X-MOUFLON:PSCH:(v\d+):([^\s\r\n]+)""").find(body)
                val psch = mouflonMatch?.groupValues?.getOrNull(1)
                val pkey = mouflonMatch?.groupValues?.getOrNull(2)
                if (pkey != null) {
                    Log.w(TAG, "  [MOUFLON] 在 Master 中发现真实直播 pkey: ${pkey.take(8)}..., psch=${psch ?: "v2"}")
                } else {
                    Log.w(TAG, "  [MOUFLON] Master 中未发现 pkey 行 (匿名/无会话请求可能被降级为预览片)")
                }

                // 给变体拼接直播参数(pkey 来自上方 MOUFLON 行)。无 pkey 时原样返回。
                fun buildLiveUrl(base: String): String {
                    if (pkey == null) return base
                    // Master 变体 URI 可能已自带 ?playlistType=standard 等查询参数。
                    // 若直接追加 &playlistType=lowLatency，会产生 playlistType 双值冲突
                    // (playlistType=standard&playlistType=lowLatency)，服务器取首个 standard
                    // → 返回非 LL-HLS 列表(无 #EXT-X-PART) → 只能下整段 → Doppio 源站整段不存在 → 全 404。
                    // 故丢弃变体自带查询，统一用直播参数重建为干净的 ?playlistType=lowLatency&...
                    val path = base.substringBefore("?")
                    return "${path}?playlistType=lowLatency&psch=${psch ?: "v2"}&preferredVideoCodec=avc1&pkey=$pkey"
                }

                var bestBw = -1
                var videoUrl: String? = null
                var bestRes: String? = null
                var audioUrl: String? = null
                var audioGroupId: String? = null

                // 优先 H.264/AVC：Android MediaMuxer 对 AV1/HEVC 封装支持差，选到会录坏。
                // 单独记录 AVC 池里的最高带宽变体，最后若有 AVC 则优先采用。
                var avcBw = -1
                var avcVideoUrl: String? = null
                var avcBestRes: String? = null
                var avcAudioGroupId: String? = null

                // 真实"直播变体"池：带 playlistType=lowLatency / pkey / _HLS_msn 或 _NNNp 后缀的才是真直播，
                // 裸 base room m3u8(预览/回放 VOD)会被排除，避免录到宣传广告。
                var liveBw = -1
                var liveVideoUrl: String? = null
                var liveRes: String? = null
                var liveAudioGroupId: String? = null
                var avcLiveBw = -1
                var avcLiveVideoUrl: String? = null
                var avcLiveBestRes: String? = null
                var avcLiveAudioGroupId: String? = null

                var i = 0
                while (i < lines.size) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXT-X-STREAM-INF:")) {
                        val attrs = parseAttributes(line)
                        val bw = attrs["BANDWIDTH"]?.toIntOrNull() ?: 0
                        val resolution = attrs["RESOLUTION"]  // e.g., "1920x1080"
                        val codecs = attrs["CODECS"] ?: ""
                        val isAvc = codecs.contains("avc", ignoreCase = true)
                                || codecs.contains("h264", ignoreCase = true)
                                || codecs.contains("h.264", ignoreCase = true)
                        i++
                        val url = lines.getOrNull(i)?.trim() ?: ""
                        var fullUrl = resolveUrl(url, origin, baseUrl)
                        // 若 Master 携带真实直播 pkey，则为每个变体追加直播参数，
                        // 否则 CDN 只会回预览/宣传片(ENDLIST + cpa/v2 静态分片)。
                        if (pkey != null) {
                            fullUrl = buildLiveUrl(fullUrl)
                        }

                        // 标签内容日志：打印完整原始标签行与解析出的全部属性
                        Log.d(TAG, "  [标签] $line")
                        Log.d(TAG, "  [标签属性] BANDWIDTH=${attrs["BANDWIDTH"]} RESOLUTION=${attrs["RESOLUTION"]} " +
                                "CODECS=${attrs["CODECS"]} FRAME-RATE=${attrs["FRAME-RATE"]} AUDIO=${attrs["AUDIO"]}")
                        Log.d(TAG, "  候选变体: ${resolution ?: "?"} ${bw / 1000}kbps codec=${codecs.take(40)} -> $fullUrl")
                        // 选最高带宽（全局）
                        if (bw > bestBw) {
                            bestBw = bw
                            videoUrl = fullUrl
                            bestRes = resolution
                            audioGroupId = attrs["AUDIO"]
                            Log.d(TAG, "    ★ 当前全局最佳: ${resolution ?: "?"} ${bw / 1000}kbps")
                        }
                        // 仅记录 AVC 池里的最高带宽
                        if (isAvc && bw > avcBw) {
                            avcBw = bw
                            avcVideoUrl = fullUrl
                            avcBestRes = resolution
                            avcAudioGroupId = attrs["AUDIO"]
                            Log.d(TAG, "    ★ 当前 AVC 最佳: ${resolution ?: "?"} ${bw / 1000}kbps")
                        }
                        // 是否"真实直播变体"：拿到 pkey 即认为 Master 里所有变体都是直播流
                        // (已通过 buildLiveUrl 拼好 pkey 参数)；拿不到 pkey 则一律视为预览/回放，
                        // 避免录到宣传广告。
                        val live = pkey != null
                        Log.d(TAG, "  [变体类型] ${if (live) "直播(LL-HLS,pkey)" else "预览/回放(VOD)"} $fullUrl")
                        if (live) {
                            if (bw > liveBw) {
                                liveBw = bw
                                liveVideoUrl = fullUrl
                                liveRes = resolution
                                liveAudioGroupId = attrs["AUDIO"]
                                Log.d(TAG, "    ★ 当前直播最佳: ${resolution ?: "?"} ${bw / 1000}kbps")
                            }
                            if (isAvc && bw > avcLiveBw) {
                                avcLiveBw = bw
                                avcLiveVideoUrl = fullUrl
                                avcLiveBestRes = resolution
                                avcLiveAudioGroupId = attrs["AUDIO"]
                                Log.d(TAG, "    ★ 当前 AVC 直播最佳: ${resolution ?: "?"} ${bw / 1000}kbps")
                            }
                        }
                    } else if (line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO")) {
                        val attrs = parseAttributes(line)
                        val uri = attrs["URI"]
                        val gid = attrs["GROUP-ID"]
                        val language = attrs["LANGUAGE"] ?: "?"
                        if (uri != null) {
                            val fullUrl = resolveUrl(uri, origin, baseUrl)
                            Log.d(TAG, "  [音轨标签] $line")
                            Log.d(TAG, "  音轨: lang=$language group=$gid uri=$uri -> $fullUrl")
                            // 优先匹配与视频流相同的 GROUP-ID，否则选第一个
                            if (audioUrl == null || (audioGroupId != null && gid == audioGroupId)) {
                                audioUrl = fullUrl
                            }
                        }
                    }
                    i++
                }

                // 优先真实直播变体(带 playlistType=lowLatency / pkey 的 LL-HLS 变体)；
                // 明确拒绝预览片(VOD, 裸 base room m3u8)，避免录到宣传广告。
                if (avcLiveVideoUrl != null) {
                    Log.w(TAG, "  ◆ 优先选择 AVC 直播变体 (真实直播流, 避免 AV1 无法封装): ${avcLiveBestRes ?: "?"} ${avcLiveBw / 1000}kbps")
                    videoUrl = avcLiveVideoUrl
                    bestRes = avcLiveBestRes
                    audioGroupId = avcLiveAudioGroupId
                } else if (liveVideoUrl != null) {
                    Log.w(TAG, "  ◆ 选择直播变体 (真实直播流): ${liveRes ?: "?"} ${liveBw / 1000}kbps")
                    videoUrl = liveVideoUrl
                    bestRes = liveRes
                    audioGroupId = liveAudioGroupId
                } else {
                    // 拿到的全是预览片/回放(VOD)，没有真实直播变体 → 明确报错，不录广告
                    if (videoUrl == null && avcVideoUrl == null) {
                        Log.e(TAG, "  ✗ 未找到任何视频流变体(播放列表并非 Master)")
                        return@withContext ResolvedStream(errorMessage = "未找到视频流")
                    }
                    if (pkey == null) {
                        // Master 里没有 #EXT-X-MOUFLON 行 = 没拿到真实直播 pkey。
                        // 通常是匿名/无会话请求被降级；需带有效会话(登录 cookie)或后续从页面 JSON 取 pkey。
                        Log.e(TAG, "  ✗ 未获取到真实直播 pkey：Master 中无 #EXT-X-MOUFLON 行（匿名/无会话请求被降级为预览片）")
                        Log.e(TAG, "  ✗ 预览变体示例: ${videoUrl ?: avcVideoUrl}")
                        return@withContext ResolvedStream(
                            errorMessage = "未获取到真实直播流 pkey（Master 无 MOUFLON 行；可能需带会话 cookie，或后续从页面 JSON 取 pkey）"
                        )
                    }
                    Log.e(TAG, "  ✗ 未发现真实直播变体：解析到的变体均为预览片/回放(VOD)，拒绝录制宣传广告")
                    Log.e(TAG, "  ✗ 预览变体示例: ${videoUrl ?: avcVideoUrl}")
                    return@withContext ResolvedStream(errorMessage = "未获取到真实直播流（仅预览片/回放，房间可能未真正开播）")
                }

                if (videoUrl != null) {
                    Log.w(TAG, "══════ Stripchat 取流完成 ══════")
                    Log.w(TAG, "  ✓ 最终视频流地址 (分辨率=${bestRes ?: "未知"}): $videoUrl")
                    if (audioUrl != null) {
                        Log.w(TAG, "  ✓ 音频流地址: $audioUrl")
                    } else {
                        Log.w(TAG, "  ○ 无独立音频轨")
                    }
                    Log.w(TAG, "  ○ Master 回看: $m3u8Url")
                    Log.w(TAG, "═════════════════════════════════════")
                    ResolvedStream(
                        videoPlaylistUrl = videoUrl,
                        audioPlaylistUrl = audioUrl,
                        resolution = bestRes
                    )
                } else {
                    Log.e(TAG, "  ✗ 未找到视频流")
                    ResolvedStream(errorMessage = "未找到视频流")
                }
            } catch (e: Exception) {
                Log.e(TAG, "  ✗ 解析 Master 失败", e)
                ResolvedStream(errorMessage = "解析失败: ${e.message}")
            }
        }

    /**
     * 从当前 m3u8 URL 获取 Referer 域名
     */
    fun extractRefererDomain(m3u8Url: String): String {
        return try {
            val uri = java.net.URI(m3u8Url)
            "${uri.scheme}://${uri.host}/"
        } catch (_: Exception) {
            "https://stripchat.com/"
        }
    }

    private fun resolveUrl(url: String, origin: String, basePath: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("/") -> "$origin$url"
            else -> "$basePath/$url"
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
}
