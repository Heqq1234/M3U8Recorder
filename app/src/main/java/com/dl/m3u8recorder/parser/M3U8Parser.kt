package com.dl.m3u8recorder.parser

/**
 * M3U8 Playlist 数据模型
 * 支持 Master Playlist 和 Media Playlist (包括 LL-HLS)
 */

/**
 * 播放列表基类
 */
sealed class M3U8Playlist {
    /**
     * Master Playlist - 包含多个变体流
     */
    data class Master(
        val variants: List<VariantStream>,
        val audioRenditions: List<AudioRendition>,
        val subtitleRenditions: List<SubtitleRendition>,
        val iframeStreams: List<IframeStream>
    ) : M3U8Playlist()

    /**
     * Media Playlist - 包含实际的媒体片段
     */
    data class Media(
        val targetDuration: Double,
        val mediaSequence: Long,
        val playlistType: String?,           // VOD, EVENT, 或 null (live)
        val isLive: Boolean,
        val isLLHls: Boolean,                // 是否包含 EXT-X-PART
        val segments: List<Segment>,
        val initSegment: InitSegment?,       // EXT-X-MAP (CMAF/fMP4)
        val parts: List<Part>,               // EXT-X-PART (LL-HLS)
        val preloadHints: List<PreloadHint>, // EXT-X-PRELOAD-HINT
        val serverControl: ServerControl?,   // EXT-X-SERVER-CONTROL
        val partTargetDuration: Double = 0.0 // LL-HLS PART-TARGET
    ) : M3U8Playlist()
}

/**
 * 变体流 - 来自 #EXT-X-STREAM-INF
 */
data class VariantStream(
    val bandwidth: Long,              // 带宽 (bps)
    val resolution: Resolution?,      // 分辨率
    val frameRate: Double?,           // 帧率
    val codecs: String?,              // 编解码器列表
    val uri: String,                  // 变体 playlist URL
    val audioGroupId: String?,        // 关联的音频组 ID
    val subtitleGroupId: String?,     // 关联的字幕组 ID
    val videoGroupId: String?         // 关联的视频组 ID
) {
    /**
     * 获取人类可读的分辨率字符串
     */
    fun getResolutionLabel(): String {
        return resolution?.let { "${it.width}x${it.height}" } ?: "未知"
    }

    /**
     * 获取带宽描述字符串
     */
    fun getBandwidthLabel(): String {
        return when {
            bandwidth >= 1_000_000 -> "%.1f Mbps".format(bandwidth / 1_000_000.0)
            bandwidth >= 1_000 -> "%.0f Kbps".format(bandwidth / 1_000.0)
            else -> "$bandwidth bps"
        }
    }
}

/**
 * 分辨率
 */
data class Resolution(
    val width: Int,
    val height: Int
) {
    override fun toString(): String = "${width}x${height}"

    /**
     * 获取简写的分辨率标签 (如 1080p, 720p, 480p)
     */
    fun getShortLabel(): String {
        return when {
            height >= 2160 -> "4K"
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            height >= 480 -> "480p"
            height >= 360 -> "360p"
            height >= 240 -> "240p"
            else -> "${height}p"
        }
    }
}

/**
 * 音频呈现 - 来自 #EXT-X-MEDIA:TYPE=AUDIO
 */
data class AudioRendition(
    val type: String,                 // 通常是 "AUDIO"
    val groupId: String,              // 组 ID，用于关联变体
    val name: String,                 // 显示名称
    val language: String?,            // 语言代码
    val default: Boolean,             // 是否默认
    val autoselect: Boolean,          // 是否自动选择
    val uri: String?                  // playlist URL (可能为 null 表示包含在视频中)
)

/**
 * 字幕呈现 - 来自 #EXT-X-MEDIA:TYPE=SUBTITLES
 */
data class SubtitleRendition(
    val type: String,
    val groupId: String,
    val name: String,
    val language: String?,
    val default: Boolean,
    val uri: String?
)

/**
 * I-Frame 流 - 来自 #EXT-X-I-FRAME-STREAM-INF
 */
data class IframeStream(
    val bandwidth: Long,
    val resolution: Resolution?,
    val uri: String
)

/**
 * 媒体片段 - 来自 #EXTINF
 */
data class Segment(
    val duration: Double,             // 片段时长 (秒)
    val uri: String,                  // 片段 URL
    val title: String? = null,        // 片段标题 (EXTINF 后的标题)
    val byteRange: String? = null,    // 字节范围
    val discontinuity: Boolean = false, // 是否有不连续标记
    val key: EncryptionKey? = null,   // 加密密钥
    val mapUri: String? = null,       // 该片段专用的 init segment
    val sequenceNumber: Long = 0L,    // 分片序列号 (基于 #EXT-X-MEDIA-SEQUENCE)
    val programDateTimeMs: Long? = null  // 来自 #EXT-X-PROGRAM-DATE-TIME 的墙钟(毫秒)，跨轨对齐用
)

/**
 * 初始化片段 - 来自 #EXT-X-MAP (CMAF/fMP4)
 */
data class InitSegment(
    val uri: String,                  // init segment URL
    val byteRange: String? = null     // 字节范围
)

/**
 * 部分片段 - 来自 #EXT-X-PART (LL-HLS)
 */
data class Part(
    val duration: Double,             // 时长 (秒)
    val uri: String,                  // 部分 URL
    val independent: Boolean = false, // 是否可独立解码
    val gap: Boolean = false,         // 是否有间隙
    val sequenceNumber: Long = 0L,    // 所属完整分片的序号
    val partIndex: Int = 0,           // 在分片中的序号
    val programDateTimeMs: Long? = null // 继承自所属分片的墙钟时间
)

/**
 * 预加载提示 - 来自 #EXT-X-PRELOAD-HINT (LL-HLS)
 */
data class PreloadHint(
    val type: String,                 // PART 或 MAP
    val uri: String,
    val byterangeStart: Long? = null,
    val byterangeLength: Long? = null
)

/**
 * 服务器控制 - 来自 #EXT-X-SERVER-CONTROL (LL-HLS)
 */
data class ServerControl(
    val canBlockReload: Boolean = false,
    val canSkipUntil: Double? = null,
    val holdBack: Double? = null,
    val partHoldBack: Double? = null
)

/**
 * 加密密钥 - 来自 #EXT-X-KEY
 */
data class EncryptionKey(
    val method: String,               // NONE, AES-128, SAMPLE-AES 等
    val uri: String?,                 // 密钥 URL
    val iv: String?,                  // 初始化向量
    val keyformat: String?,           // 密钥格式
    val keyformatVersions: String?    // 密钥格式版本
)

/**
 * 解析后的流信息 - 用于下载
 */
data class ResolvedStream(
    val videoPlaylistUrl: String?,    // 视频播放列表 URL
    val audioPlaylistUrl: String?,    // 独立音频播放列表 URL (可选)
    val isMasterPlaylist: Boolean,    // 原始是否为 Master Playlist
    val isLLHls: Boolean,             // 是否为 LL-HLS
    val selectedVariant: VariantStream?, // 选中的变体信息
    val availableVariants: List<VariantStream> // 所有可用变体
)

/**
 * 解析器接口
 */
interface M3U8ParserInterface {
    /**
     * 解析 M3U8 内容
     * @param content M3U8 文件内容
     * @param baseUrl 基础 URL，用于解析相对路径
     * @return 解析后的播放列表
     */
    fun parse(content: String, baseUrl: String): M3U8Playlist

    /**
     * 判断是否为 Master Playlist
     */
    fun isMasterPlaylist(content: String): Boolean

    /**
     * 从 Master Playlist 选择最佳变体
     * @param variants 变体列表
     * @return 带宽最高的变体
     */
    fun selectBestVariant(variants: List<VariantStream>): VariantStream

    /**
     * 解析分辨率字符串
     */
    fun parseResolution(resString: String): Resolution?

    /**
     * 解析 URL (处理相对路径)
     */
    fun resolveUrl(url: String, baseUrl: String): String
}
