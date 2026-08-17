package com.dl.m3u8recorder.parser

import android.util.Log

/**
 * M3U8 解析器实现
 * 支持 Master Playlist, Media Playlist, LL-HLS
 */
class M3U8ParserImpl : M3U8ParserInterface {

    companion object {
        private const val TAG = "M3U8Parser"
    }

    override fun parse(content: String, baseUrl: String): M3U8Playlist {
        return if (isMasterPlaylist(content)) {
            parseMasterPlaylist(content, baseUrl)
        } else {
            parseMediaPlaylist(content, baseUrl)
        }
    }

    override fun isMasterPlaylist(content: String): Boolean {
        return content.contains("#EXT-X-STREAM-INF:") ||
               content.contains("#EXT-X-I-FRAME-STREAM-INF:")
    }

    override fun selectBestVariant(variants: List<VariantStream>): VariantStream {
        if (variants.isEmpty()) {
            throw IllegalArgumentException("变体列表不能为空")
        }
        // 优先 H.264/AVC 变体：Android MediaMuxer 对 AV1/HEVC 封装支持差。
        // 若池中无 AVC 变体（如仅 AV1），则退化为全局最高带宽。
        val avc = variants.filter {
            val c = it.codecs ?: ""
            c.contains("avc", ignoreCase = true)
                    || c.contains("h264", ignoreCase = true)
                    || c.contains("h.264", ignoreCase = true)
        }
        val pool = if (avc.isNotEmpty()) avc else variants
        return pool.maxByOrNull { it.bandwidth } ?: pool.first()
    }

    override fun parseResolution(resString: String): Resolution? {
        return try {
            val parts = resString.split("x")
            if (parts.size == 2) {
                Resolution(
                    width = parts[0].trim().toInt(),
                    height = parts[1].trim().toInt()
                )
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "无法解析分辨率: $resString", e)
            null
        }
    }

    override fun resolveUrl(url: String, baseUrl: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                // 绝对路径，需要提取 scheme+host
                try {
                    // 去除查询参数，只保留 scheme://authority
                    val cleanBaseUrl = baseUrl.substringBefore("?")
                    val uri = java.net.URI(cleanBaseUrl)
                    val resolved = "${uri.scheme}://${uri.authority}$url"
                    Log.d("M3U8Parser", "解析绝对路径: $url -> $resolved")
                    resolved
                } catch (e: Exception) {
                    Log.e("M3U8Parser", "解析 URL 失败: baseUrl=$baseUrl, url=$url", e)
                    url
                }
            }
            else -> {
                // 相对路径
                val cleanBaseUrl = baseUrl.substringBefore("?").substringBeforeLast("/")
                val resolved = "$cleanBaseUrl/$url"
                Log.d("M3U8Parser", "解析相对路径: $url -> $resolved")
                resolved
            }
        }
    }

    /**
     * 解析 Master Playlist
     */
    private fun parseMasterPlaylist(content: String, baseUrl: String): M3U8Playlist.Master {
        val variants = mutableListOf<VariantStream>()
        val audioRenditions = mutableListOf<AudioRendition>()
        val subtitleRenditions = mutableListOf<SubtitleRendition>()
        val iframeStreams = mutableListOf<IframeStream>()

        val lines = content.lines()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()

            when {
                // 解析 #EXT-X-STREAM-INF
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val attrs = parseAttributes(line.substringAfter(":"))
                    val nextLine = lines.getOrNull(i + 1)?.trim() ?: ""

                    if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                        val uri = resolveUrl(nextLine, baseUrl)
                        variants.add(VariantStream(
                            bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                            resolution = attrs["RESOLUTION"]?.let { parseResolution(it) },
                            frameRate = attrs["FRAME-RATE"]?.toDoubleOrNull(),
                            codecs = attrs["CODECS"],
                            uri = uri,
                            audioGroupId = attrs["AUDIO"],
                            subtitleGroupId = attrs["SUBTITLES"],
                            videoGroupId = attrs["VIDEO"]
                        ))
                    }
                }

                // 解析 #EXT-X-I-FRAME-STREAM-INF
                line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") -> {
                    val attrs = parseAttributes(line.substringAfter(":"))
                    val uri = attrs["URI"]?.let { resolveUrl(it, baseUrl) } ?: continue
                    iframeStreams.add(IframeStream(
                        bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                        resolution = attrs["RESOLUTION"]?.let { parseResolution(it) },
                        uri = uri
                    ))
                }

                // 解析 #EXT-X-MEDIA:TYPE=AUDIO
                line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO") -> {
                    val attrs = parseAttributes(line.substringAfter(":"))
                    audioRenditions.add(AudioRendition(
                        type = attrs["TYPE"] ?: "AUDIO",
                        groupId = attrs["GROUP-ID"] ?: "",
                        name = attrs["NAME"] ?: "",
                        language = attrs["LANGUAGE"],
                        default = attrs["DEFAULT"] == "YES",
                        autoselect = attrs["AUTOSELECT"] == "YES",
                        uri = attrs["URI"]?.let { resolveUrl(it, baseUrl) }
                    ))
                }

                // 解析 #EXT-X-MEDIA:TYPE=SUBTITLES
                line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=SUBTITLES") -> {
                    val attrs = parseAttributes(line.substringAfter(":"))
                    subtitleRenditions.add(SubtitleRendition(
                        type = attrs["TYPE"] ?: "SUBTITLES",
                        groupId = attrs["GROUP-ID"] ?: "",
                        name = attrs["NAME"] ?: "",
                        language = attrs["LANGUAGE"],
                        default = attrs["DEFAULT"] == "YES",
                        uri = attrs["URI"]?.let { resolveUrl(it, baseUrl) }
                    ))
                }
            }

            i++
        }

        Log.d(TAG, "解析 Master Playlist: ${variants.size} 个变体, ${audioRenditions.size} 个音频轨")

        return M3U8Playlist.Master(
            variants = variants,
            audioRenditions = audioRenditions,
            subtitleRenditions = subtitleRenditions,
            iframeStreams = iframeStreams
        )
    }

    /**
     * 解析 Media Playlist
     */
    private fun parseMediaPlaylist(content: String, baseUrl: String): M3U8Playlist.Media {
        val segments = mutableListOf<Segment>()
        val parts = mutableListOf<Part>()
        val preloadHints = mutableListOf<PreloadHint>()

        var targetDuration = 0.0
        var mediaSequence = 0L
        var playlistType: String? = null
        var isLive = true
        var initSegment: InitSegment? = null
        var serverControl: ServerControl? = null
        var currentEncryptionKey: EncryptionKey? = null
        var isDiscontinuity = false
        var pendingPdtMs: Long? = null  // 待挂载到下一个 segment 的墙钟时间(毫秒)

        // Stripchat / Doppio CDN 自定义标签：真实分片 URI 藏在 #EXT-X-MOUFLON:URI: 里，
        // 而标准 #EXT-X-PART / #EXTINF 的 URI 只是 media.mp4 占位符。
        // 解析到 MOUFLON 时暂存真实 URI，交给紧随其后的 PART / 分片 URI 行消费。
        // Stripchat / Doppio 真实分片 URI 来源：#EXT-X-MOUFLON 给出的「整段基址」（去 .mp4），
        // 用于推导 _partN 分块；或 MOUFLON 直接给出的带 _partN 的 part URI。
        // 二者皆为空时回退 playlist 标准 URI（Chaturbate / VOD 正常路径，无 MOUFLON 故零影响）。
        var pendingSegmentMouflonBase: String? = null
        var pendingPartMouflonUri: String? = null
        // 已 flush 进 parts 列表、待随后 MOUFLON 修正的诱饵 part 数量（MOUFLON 出现在整段 URI 行之后时用到）
        var pendingDecoyPartTailCount = 0
        // 注意：MOUFLON 里的真实分片 URI 是干净的绝对 URL、不带任何查询参数。
        // 浏览器/HLS 播放器对 media 分片请求也用的是裸 URL（已用 devtools 抓包证实：
        // 成功响应 :path 无 ?pkey），鉴权靠的是 Referer/Origin 请求头而非 URL 参数。
        // 因此这里【不要】给 URI 补 playlist 查询参数（补了反而与浏览器不一致）。
        // 仅作用于 #EXT-X-MOUFLON 标签（Stripchat/Doppio 专属），Chaturbate 无此标签故完全不受影响。
        // 因 #EXTINF 与真实 URI 行之间可能隔着 MOUFLON，故 EXTINF 先存待定时长/PDT，
        // 待真正的 URI 行到达时再生成 Segment。
        var pendingExtinfDuration: Double? = null
        var pendingExtinfPdt: Long? = null

        // 从变体 playlist URL 提取画质标记(如 720p_h264 / 960p60_av1)，用于补齐 MOUFLON 真实 URI 缺失的画质段。
        // 例：.../228961321_720p_h264.m3u8?... -> "720p_h264"。仅作用于 MOUFLON(Stripchat 专属)，Chaturbate 无 MOUFLON 故不影响。
        val quality = extractQuality(baseUrl)
        if (quality != null) {
            Log.d(TAG, "MOUFLON 画质标记(从变体 URL 提取): $quality")
        }
        // 最近创建的 segment / part 是否仍是诱饵 URI(待随后的 MOUFLON 覆盖)。
        // 处理"MOUFLON 在 segment/part 诱饵行之后"的顺序(Sample: pl.m3u8 为 MOUFLON 在前，部分真机 playlist 为 MOUFLON 在后)。
        var lastSegmentWasDecoy = false
        var lastPartWasDecoy = false

        val lines = content.lines()

        // 检测是否为 LL-HLS
        val isLLHls = content.contains("#EXT-X-PART") ||
                      content.contains("#EXT-X-SERVER-CONTROL") ||
                      content.contains("#EXT-X-PRELOAD-HINT")

        var i = 0
        var currentSeq = mediaSequence // 动态序列号计数器
        // 收集当前 segment 对应的 part，遇到 segment 时统一绑定序号和 PDT
        val currentSegmentParts = mutableListOf<Part>()
        var currentPartIndex = 0
        var partTargetDuration = 0.0

        while (i < lines.size) {
            val line = lines[i].trim()

            when {
                // #EXT-X-TARGETDURATION
                line.startsWith("#EXT-X-TARGETDURATION:") -> {
                    targetDuration = line.substringAfter(":").toDoubleOrNull() ?: 0.0
                }

                // #EXT-X-MEDIA-SEQUENCE
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(":").toLongOrNull() ?: 0L
                    currentSeq = mediaSequence // 同步计数器
                }

                // #EXT-X-PLAYLIST-TYPE
                line.startsWith("#EXT-X-PLAYLIST-TYPE:") -> {
                    playlistType = line.substringAfter(":")
                    isLive = playlistType != "VOD"
                }

                // #EXT-X-ENDLIST (VOD 标记)
                line == "#EXT-X-ENDLIST" -> {
                    isLive = false
                }

                // #EXT-X-KEY (加密)
                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = parseAttributes(line.substringAfter(":"))
                    currentEncryptionKey = EncryptionKey(
                        method = attrs["METHOD"] ?: "NONE",
                        uri = attrs["URI"],
                        iv = attrs["IV"],
                        keyformat = attrs["KEYFORMAT"],
                        keyformatVersions = attrs["KEYFORMATVERSIONS"]
                    )
                }

                // #EXT-X-MAP (init segment for CMAF/fMP4)
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = parseAttributes(line.substringAfter(":"))
                    val uri = attrs["URI"]?.let { resolveUrl(it, baseUrl) }
                    if (uri != null) {
                        initSegment = InitSegment(
                            uri = uri,
                            byteRange = attrs["BYTERANGE"]
                        )
                    }
                }

                // #EXT-X-PART-INF (LL-HLS)
                line.startsWith("#EXT-X-PART-INF:") -> {
                    val attrs = parseAttributes(line.substringAfter(":"))
                    partTargetDuration = attrs["PART-TARGET"]?.toDoubleOrNull() ?: 0.0
                }

                // #EXT-X-MOUFLON (Stripchat / Doppio CDN 自定义标签：携带真实分片 URI 或 PSCH 令牌)
                line.startsWith("#EXT-X-MOUFLON:") -> {
                    val body = line.substringAfter("#EXT-X-MOUFLON:")
                    if (body.startsWith("URI:")) {
                        val raw = body.substringAfter("URI:").trim()
                        if (raw.isNotEmpty()) {
                            // v2 (PSCH) 加密：MOUFLON URI 的 token 段是密文，直接请求必 404。
                            // 先用 pkey 对应密钥解密 token 替换为真实地址（无密钥/解密失败时原样返回，
                            // 回退为现状行为）；解密后的 URI 已是完整可下载形态（实测下载 200）。
                            // 归一化：补齐画质标记(从变体 URL 提取)与 .mp4 后缀。devtools 实测真实分片 URL 形如
                            // {room}_{seq}_{token}_{ts}[_partN].mp4；MOUFLON 给的真实 URI 可能是整段基址(无 _partN)。
                            val resolved = resolveUrl(raw, baseUrl)
                            val decrypted = MouflonCrypto.decryptUri(resolved, baseUrl)
                            if (decrypted != resolved) {
                                Log.d(TAG, "MOUFLON token 解密成功: ${decrypted.substringAfterLast('/').take(80)}")
                            }
                            val normalized = normalizeMouflonUri(decrypted, quality)
                            Log.d(TAG, "MOUFLON 捕获真实分片 URI: ${normalized.take(90)}")
                            if (isPartUri(normalized)) {
                                // MOUFLON 直接给出带 _partN 的 part URI：留给紧随其后的 #EXT-X-PART 消费
                                pendingPartMouflonUri = normalized
                                pendingSegmentMouflonBase = null
                            } else {
                                // MOUFLON 给出整段基址（如 …_6875_<token>_<ts>，ts 段是服务端真实值，
                                // 通常就是该 segment 的 PDT 墙钟时间戳；注意日志用 take(90) 会把长 ts 截断成
                                // 看似 "_1" 的假象，实际 normalized 变量是完整字符串）。
                                // 真实 PART 分块 URL = 基址在 .mp4 前插入 "_partN"。
                                // 优先用 MOUFLON 基址推导，可规避 playlist 的 #EXT-X-PART URI 偶尔是
                                // media.mp4 占位符的情况；与直接用 PART URI 属性等价（二者 ts 一致）。
                                val base = normalized.removeSuffix(".mp4")
                                pendingSegmentMouflonBase = base
                                pendingPartMouflonUri = null
                                // MOUFLON-after（出现在整段 URI 行之后）：修正已 flush 的最后一个诱饵 segment
                                if (segments.isNotEmpty() && lastSegmentWasDecoy) {
                                    val idx = segments.lastIndex
                                    segments[idx] = segments[idx].copy(uri = "$base.mp4")
                                    lastSegmentWasDecoy = false
                                }
                                // MOUFLON-after（出现在 parts 之后、整段 URI 行之前）：修正已缓存的全部诱饵 part
                                if (currentSegmentParts.isNotEmpty() && lastPartWasDecoy) {
                                    val fixed = currentSegmentParts.map { p ->
                                        p.copy(uri = derivePartUri(base, p.partIndex))
                                    }
                                    currentSegmentParts.clear()
                                    currentSegmentParts.addAll(fixed)
                                    lastPartWasDecoy = false
                                }
                                // MOUFLON-after（出现在整段 URI 行之后）：parts 列表尾部已是本 segment 的诱饵 part，统一修正
                                if (pendingDecoyPartTailCount > 0 && parts.size >= pendingDecoyPartTailCount) {
                                    val start = parts.size - pendingDecoyPartTailCount
                                    for (i in start until parts.size) {
                                        parts[i] = parts[i].copy(uri = derivePartUri(base, parts[i].partIndex))
                                    }
                                    pendingDecoyPartTailCount = 0
                                }
                            }
                        }
                    }
                    // PSCH:v2:xxx 等子类型忽略
                }

                // #EXT-X-PART (LL-HLS partial segment)
                line.startsWith("#EXT-X-PART:") -> {
                    val attrs = parseAttributes(line.substringAfter(":"))
                    val partIndex = currentPartIndex
                    // 优先用 MOUFLON 真实 URI：① 直接给的带 _partN 的 part URI；② 整段基址推导 _partN；
                    // 否则回退 playlist 的 #EXT-X-PART URI（LL-HLS 下通常即真实 part 地址，ts 与 MOUFLON 一致；
                    // 仅当它是 media.mp4 占位符时才靠上面 MOUFLON-after 修正）。
                    val uri = pendingPartMouflonUri
                        ?: (pendingSegmentMouflonBase?.let { derivePartUri(it, partIndex) })
                        ?: attrs["URI"]?.let { resolveUrl(it, baseUrl) }
                        ?: continue
                    val usedReal = pendingPartMouflonUri != null || pendingSegmentMouflonBase != null
                    pendingPartMouflonUri = null
                    lastPartWasDecoy = !usedReal
                    currentSegmentParts.add(Part(
                        duration = attrs["DURATION"]?.toDoubleOrNull() ?: 0.0,
                        uri = uri,
                        independent = attrs["INDEPENDENT"] == "YES",
                        gap = attrs["GAP"] == "YES",
                        partIndex = partIndex
                    ))
                    currentPartIndex++
                }

                // #EXT-X-PRELOAD-HINT (LL-HLS)
                line.startsWith("#EXT-X-PRELOAD-HINT:") -> {
                    val attrs = parseAttributes(line.substringAfter(":"))
                    val uri = attrs["URI"]?.let { resolveUrl(it, baseUrl) } ?: continue
                    preloadHints.add(PreloadHint(
                        type = attrs["TYPE"] ?: "PART",
                        uri = uri,
                        byterangeStart = attrs["BYTERANGE-START"]?.toLongOrNull(),
                        byterangeLength = attrs["BYTERANGE-LENGTH"]?.toLongOrNull()
                    ))
                }

                // #EXT-X-SERVER-CONTROL (LL-HLS)
                line.startsWith("#EXT-X-SERVER-CONTROL:") -> {
                    val attrs = parseAttributes(line.substringAfter(":"))
                    serverControl = ServerControl(
                        canBlockReload = attrs["CAN-BLOCK-RELOAD"] == "YES",
                        canSkipUntil = attrs["CAN-SKIP-UNTIL"]?.toDoubleOrNull(),
                        holdBack = attrs["HOLD-BACK"]?.toDoubleOrNull(),
                        partHoldBack = attrs["PART-HOLD-BACK"]?.toDoubleOrNull()
                    )
                }

                // #EXT-X-DISCONTINUITY
                line == "#EXT-X-DISCONTINUITY" -> {
                    isDiscontinuity = true
                }

                // #EXT-X-PROGRAM-DATE-TIME (墙钟锚，挂到紧随其后的 segment)
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> {
                    pendingPdtMs = parsePdtToMillis(line.substringAfter(":").trim())
                }

                // #EXTINF (segment duration + URI；真实 URI 可能在后续 MOUFLON 之后，故先存待定)
                line.startsWith("#EXTINF:") -> {
                    val durationStr = line.substringAfter(":").substringBefore(",")
                    val duration = durationStr.toDoubleOrNull() ?: 0.0
                    pendingExtinfDuration = duration
                    pendingExtinfPdt = pendingPdtMs
                    pendingPdtMs = null
                    // 真正的分片 URI 行（可能隔着 MOUFLON）在下方 URI 分支处理
                }

                // 直接的 URI 行（#EXTINF 之后的真实分片 URI，或兼容无 EXTINF 的情况）
                !line.startsWith("#") && line.isNotEmpty() -> {
                    // 优先用 MOUFLON 整段基址（Stripchat 真实取片地址）；否则用本行 URI（Chaturbate/VOD 正常路径）。
                    val realUri = (pendingSegmentMouflonBase?.let { "$it.mp4" })
                        ?: pendingPartMouflonUri
                        ?: resolveUrl(line, baseUrl)
                    val usedReal = pendingSegmentMouflonBase != null || pendingPartMouflonUri != null
                    pendingSegmentMouflonBase = null
                    pendingPartMouflonUri = null
                    // 若用的是诱饵 URI(非 MOUFLON)，标记待随后的 MOUFLON 覆盖
                    lastSegmentWasDecoy = !usedReal
                    val duration = pendingExtinfDuration ?: targetDuration
                    val pdt = pendingExtinfPdt ?: pendingPdtMs
                    pendingExtinfDuration = null
                    pendingExtinfPdt = null
                    pendingPdtMs = null
                    // 避免与 #EXTINF 处理重复添加同一 segment（比较已解析的完整 URI）
                    if (segments.isEmpty() || segments.last().uri != realUri) {
                        // 绑定剩余未绑定的 part
                        currentSegmentParts.forEach { part ->
                            parts.add(part.copy(
                                sequenceNumber = currentSeq,
                                programDateTimeMs = pdt
                            ))
                        }
                        // 记录本 segment 的 part 数，若它们是诱饵（MOUFLON 在 URI 行之后才到），
                        // 待随后 MOUFLON 出现时修正 parts 列表尾部。
                        pendingDecoyPartTailCount = if (lastPartWasDecoy) currentSegmentParts.size else 0
                        lastPartWasDecoy = false
                        currentSegmentParts.clear()
                        currentPartIndex = 0

                        segments.add(Segment(
                            duration = duration,
                            uri = realUri,
                            discontinuity = isDiscontinuity,
                            key = currentEncryptionKey,
                            sequenceNumber = currentSeq++,
                            programDateTimeMs = pdt
                        ))
                        isDiscontinuity = false
                    }
                }
            }

            i++
        }

        Log.d(TAG, "解析 Media Playlist: ${segments.size} 个片段, ${parts.size} 个 partial, isLive=$isLive, isLLHls=$isLLHls")

        return M3U8Playlist.Media(
            targetDuration = targetDuration,
            mediaSequence = mediaSequence,
            playlistType = playlistType,
            isLive = isLive,
            isLLHls = isLLHls,
            segments = segments,
            initSegment = initSegment,
            parts = parts,
            preloadHints = preloadHints,
            serverControl = serverControl,
            partTargetDuration = partTargetDuration
        )
    }

    /**
     * 解析 #EXT-X-PROGRAM-DATE-TIME 值为墙钟毫秒时间戳
     * 支持格式如 "2026-07-24T19:04:11.851+00:00" / "...Z"
     * 纯手写解析，兼容 API 24+。
     * 失败返回 null。
     */
    private fun parsePdtToMillis(value: String): Long? {
        return try {
            val m = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})?""")
                .find(value) ?: return null
            val (y, mo, d, h, mi, s, frac, tz) = m.destructured
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt())
            var ms = cal.timeInMillis
            if (frac.isNotEmpty()) {
                val padded = (frac + "000000").substring(0, 3)
                ms += padded.toInt()
            }
            if (tz.isNotEmpty() && tz != "Z") {
                val sign = if (tz[0] == '+') 1 else -1
                val tzh = tz.substring(1, 3).toInt()
                val tzm = tz.substring(4, 6).toInt()
                ms -= sign * (tzh * 3600 + tzm * 60) * 1000L
            }
            ms
        } catch (e: Exception) {
            Log.w(TAG, "无法解析 PROGRAM-DATE-TIME: $value", e)
            null
        }
    }

    /**
     * 解析属性字符串
     * 格式: KEY=VALUE,KEY="VALUE WITH SPACES",...
     */
    private fun parseAttributes(attrString: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 0
        val len = attrString.length

        while (i < len) {
            // 跳过前导逗号和空格
            while (i < len && (attrString[i] == ',' || attrString[i] == ' ')) i++
            if (i >= len) break

            // 读取 KEY
            val keyStart = i
            while (i < len && attrString[i] != '=') i++
            if (i >= len) break
            val key = attrString.substring(keyStart, i)
            i++ // 跳过 '='

            if (i >= len) break

            // 读取 VALUE
            val value: String
            if (attrString[i] == '"') {
                // 引号包围的值
                i++ // 跳过开始引号
                val valueStart = i
                while (i < len && attrString[i] != '"') i++
                value = attrString.substring(valueStart, i)
                i++ // 跳过结束引号
            } else {
                // 非引号的值，读到逗号或结束
                val valueStart = i
                while (i < len && attrString[i] != ',') i++
                value = attrString.substring(valueStart, i).trim()
            }

            result[key] = value
        }

        return result
    }

    /**
     * 从变体 playlist URL 提取画质标记(如 720p_h264 / 960p60_av1 / 240p)。
     * 例：https://media-hls.doppiocdn.net/b-hls-20/228961321/228961321_720p_h264.m3u8?... -> "720p_h264"
     * 仅用于 MOUFLON 真实 URI 的画质段补齐(Stripchat/Doppio CDN)。无则返回 null。
     */
    private fun extractQuality(baseUrl: String): String? {
        // 画质标记：2~4 位数字 + 'p' + 可选帧率数字 + 可选 "_编解码器"
        val m = Regex("""(\d{2,4}p\d*(?:_[a-zA-Z0-9]+)?)""").find(baseUrl) ?: return null
        return m.groupValues[1].takeIf { it.contains("p") }
    }

    /**
     * 判断归一化后的 URI 是否为 partial segment(带 _partN)。
     */
    private fun isPartUri(uri: String): Boolean {
        return Regex(""".*_part\d+(?:\.mp4)?$""").matches(uri)
    }

    /**
     * 由整段基址(去 .mp4)推导 partial segment URL：在 .mp4 前插入 _partN。
     * 仅用于 Stripchat / Doppio（MOUFLON 给出整段基址、PART 分块需自行拼接）。
     */
    private fun derivePartUri(base: String, partIndex: Int): String {
        val b = base.removeSuffix(".mp4")
        return "${b}_part${partIndex}.mp4"
    }

    /**
     * 归一化 MOUFLON 真实分片 URI：
     *  1) 若缺画质标记(如 720p_h264 / 960p60_av1)，在房间号之后注入(从变体 URL 提取的 quality)；
     *  2) 补 .mp4 后缀(真实分片必须有扩展名)。
     * devtools 实测真实 URL 形如 {room}_{quality}_{seq}_{token}_{ts}[_partN].mp4。
     * 仅作用于 MOUFLON(Stripchat 专属)，Chaturbate 无 MOUFLON 故完全不受影响。
     */
    private fun normalizeMouflonUri(uri: String, quality: String?): String {
        var result = uri.trim()
        val filename = result.substringAfterLast("/")
        // 1) 注入画质标记
        if (quality != null && !filename.contains(Regex("""\d{2,4}p"""))) {
            val room = filename.substringBefore("_")            // 房间号, 如 256819146
            val needle = "${room}_"
            if (filename.startsWith(needle) && !filename.startsWith("${room}_$quality")) {
                val newFilename = filename.replaceFirst(needle, "${room}_${quality}_")
                result = "${result.substringBeforeLast("/")}/$newFilename"
            }
        }
        // 2) 补 .mp4 后缀
        if (!result.endsWith(".mp4")) {
            result = "$result.mp4"
        }
        return result
    }

    /**
     * 从 Master Playlist 构建解析后的流信息
     * @param master Master Playlist
     * @param preferredResolution 首选分辨率高度 (null = 自动选择最高)
     * @return 解析后的流信息
     */
    fun resolveMasterPlaylist(
        master: M3U8Playlist.Master,
        preferredResolution: Int? = null
    ): ResolvedStream {
        val selectedVariant = if (preferredResolution != null) {
            // 找最接近首选分辨率的变体
            master.variants.minByOrNull { variant ->
                variant.resolution?.let { Math.abs(it.height - preferredResolution) }
                    ?: Int.MAX_VALUE
            } ?: selectBestVariant(master.variants)
        } else {
            // 自动选择最高带宽
            selectBestVariant(master.variants)
        }

        // 查找关联的音频轨
        val audioUrl = selectedVariant.audioGroupId?.let { groupId ->
            master.audioRenditions.find { it.groupId == groupId && it.uri != null }?.uri
        }

        return ResolvedStream(
            videoPlaylistUrl = selectedVariant.uri,
            audioPlaylistUrl = audioUrl,
            isMasterPlaylist = true,
            isLLHls = false, // 将在解析子 playlist 时确定
            selectedVariant = selectedVariant,
            availableVariants = master.variants
        )
    }
}
