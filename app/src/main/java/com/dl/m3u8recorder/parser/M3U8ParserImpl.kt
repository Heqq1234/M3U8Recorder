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
        return variants.maxByOrNull { it.bandwidth }
            ?: variants.first()
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

                // #EXT-X-PART (LL-HLS partial segment)
                line.startsWith("#EXT-X-PART:") -> {
                    val attrs = parseAttributes(line.substringAfter(":"))
                    val uri = attrs["URI"]?.let { resolveUrl(it, baseUrl) } ?: continue
                    currentSegmentParts.add(Part(
                        duration = attrs["DURATION"]?.toDoubleOrNull() ?: 0.0,
                        uri = uri,
                        independent = attrs["INDEPENDENT"] == "YES",
                        gap = attrs["GAP"] == "YES",
                        partIndex = currentPartIndex++
                    ))
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

                // #EXTINF (segment duration + URI)
                line.startsWith("#EXTINF:") -> {
                    val durationStr = line.substringAfter(":").substringBefore(",")
                    val duration = durationStr.toDoubleOrNull() ?: 0.0
                    val title = line.substringAfter(",", "").trim().ifEmpty { null }

                    val nextLine = lines.getOrNull(i + 1)?.trim() ?: ""

                    if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                        val uri = resolveUrl(nextLine, baseUrl)

                        // 绑定 part 与当前 segment 的序号和 PDT
                        currentSegmentParts.forEach { part ->
                            parts.add(part.copy(
                                sequenceNumber = currentSeq,
                                programDateTimeMs = pendingPdtMs
                            ))
                        }
                        currentSegmentParts.clear()
                        currentPartIndex = 0

                        segments.add(Segment(
                            duration = duration,
                            uri = uri,
                            title = title,
                            discontinuity = isDiscontinuity,
                            key = currentEncryptionKey,
                            sequenceNumber = currentSeq++,
                            programDateTimeMs = pendingPdtMs
                        ))
                        pendingPdtMs = null
                        isDiscontinuity = false
                    }
                }

                // 直接的 URI 行（没有 EXTINF，兼容性处理）
                !line.startsWith("#") && line.isNotEmpty() -> {
                    val uri = resolveUrl(line, baseUrl)
                    // 避免与 #EXTINF 处理重复添加同一 segment（比较已解析的完整 URI）
                    if (segments.isEmpty() || segments.last().uri != uri) {
                        // 绑定剩余未绑定的 part
                        currentSegmentParts.forEach { part ->
                            parts.add(part.copy(
                                sequenceNumber = currentSeq,
                                programDateTimeMs = pendingPdtMs
                            ))
                        }
                        currentSegmentParts.clear()
                        currentPartIndex = 0

                        segments.add(Segment(
                            duration = targetDuration,
                            uri = uri,
                            discontinuity = isDiscontinuity,
                            key = currentEncryptionKey,
                            sequenceNumber = currentSeq++,
                            programDateTimeMs = pendingPdtMs
                        ))
                        pendingPdtMs = null
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
