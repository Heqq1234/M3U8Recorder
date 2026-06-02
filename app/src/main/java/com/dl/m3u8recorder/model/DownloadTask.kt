package com.dl.m3u8recorder.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State

@Parcelize
data class DownloadTask(
    // ========== 核心字段 (现有) ==========
    val id: String,
    val url: String,
    val fileName: String,
    val realtimeMerge: Boolean,
    val isLive: Boolean,
    val customDownloadUri: String? = null,

    // ========== Phase 2: Master Playlist 支持 ==========
    /** 是否为 Master Playlist */
    val isMasterPlaylist: Boolean = false,

    /** 选中的变体 Playlist URL (从 Master 中选择) */
    val selectedVariantUrl: String? = null,

    /** 选中的分辨率字符串 (如 "1920x1080") */
    val selectedResolution: String? = null,

    /** 选中的带宽 (bps) */
    val selectedBandwidth: Long = 0,

    /** 独立的音频轨 URL (分离音视频流) */
    val audioTrackUrl: String? = null,

    /** 选中的变体显示名称 (如 "1080p") */
    val selectedVariantLabel: String? = null,

    // ========== Phase 4: LL-HLS 支持 ==========
    /** 是否为 LL-HLS 流 */
    val isLLHls: Boolean = false,

    /** 视频初始化片段 URL (EXT-X-MAP) */
    val videoInitSegmentUrl: String? = null,

    /** 音频初始化片段 URL */
    val audioInitSegmentUrl: String? = null,

    /** 上次的 Media Sequence (用于断点恢复) */
    val lastMediaSequence: Long = 0,

    /** 上次的 Part Index (用于断点恢复) */
    val lastPartIndex: Int = 0,

    /** 已下载的片段数 */
    val downloadedFragmentCount: Int = 0

) : Parcelable {

    // ========== 运行时状态 (不参与 Parcelize) ==========

    // 进度 (0-100)
    val _progress: MutableState<Int> = mutableStateOf(0)
    val _statusMessage: MutableState<String> = mutableStateOf("准备中")
    val _isPaused: MutableState<Boolean> = mutableStateOf(false)
    val _isCancelled: MutableState<Boolean> = mutableStateOf(false)
    val _downloadedSize: MutableState<Long> = mutableStateOf(0L)
    val _elapsedTime: MutableState<Long> = mutableStateOf(0L)

    // Phase 4: LL-HLS 双轨进度
    val _videoProgress: MutableState<Int> = mutableStateOf(0)
    val _audioProgress: MutableState<Int> = mutableStateOf(0)
    val _currentBitrate: MutableState<Long> = mutableStateOf(0L)

    // ========== 公共访问器 ==========

    var progress: Int
        get() = _progress.value
        set(value) { _progress.value = value }

    var statusMessage: String
        get() = _statusMessage.value
        set(value) { _statusMessage.value = value }

    var isPaused: Boolean
        get() = _isPaused.value
        set(value) { _isPaused.value = value }

    var isCancelled: Boolean
        get() = _isCancelled.value
        set(value) { _isCancelled.value = value }

    // 只读 State 供 Compose 观察
    val downloadedSize: State<Long> get() = _downloadedSize
    val elapsedTime: State<Long> get() = _elapsedTime

    // Phase 4: LL-HLS 进度状态
    val videoProgress: State<Int> get() = _videoProgress
    val audioProgress: State<Int> get() = _audioProgress
    val currentBitrate: State<Long> get() = _currentBitrate

    /**
     * 获取实际下载的 URL (变体 URL 或原始 URL)
     */
    fun getEffectiveUrl(): String {
        return selectedVariantUrl ?: url
    }

    /**
     * 获取分辨率的简短标签
     */
    fun getResolutionLabel(): String {
        return selectedResolution?.let { res ->
            val height = res.substringAfter("x").toIntOrNull() ?: return res
            when {
                height >= 2160 -> "4K"
                height >= 1080 -> "1080p"
                height >= 720 -> "720p"
                height >= 480 -> "480p"
                height >= 360 -> "360p"
                else -> "${height}p"
            }
        } ?: selectedVariantLabel ?: ""
    }
}
