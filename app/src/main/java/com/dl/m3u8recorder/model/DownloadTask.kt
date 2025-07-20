package com.dl.m3u8recorder.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class DownloadTask(
    val id: String,
    val url: String,
    val fileName: String,
    val realtimeMerge: Boolean = false,
    val isLive: Boolean = false,
    var isCancelled: Boolean = false,
    var isPaused: Boolean = false,
    var statusMessage: String? = null
) : Parcelable