package com.dl.m3u8recorder.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class DownloadTask(
    val id: String,
    val url: String,
    val fileName: String,
    val realtimeMerge: Boolean = false, // 是否实时合并
    val isLive: Boolean = false, // 是否直播流
    var isCancelled: Boolean = false, // 任务是否被取消
    var isPaused: Boolean = false, // 任务是否被暂停
    var onUpdate: ((DownloadTask) -> Unit)? = null, // 进度更新回调
    var progress: Int = 0, // 下载进度 0 - 100
    var statusMessage: String = "准备中" // 任务状态信息
) : Parcelable
