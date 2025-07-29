package com.dl.m3u8recorder.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State // Import State for the public getters

@Parcelize
data class DownloadTask(
    val id: String,
    val url: String,
    val fileName: String,
    val realtimeMerge: Boolean,
    val isLive: Boolean,
    val customDownloadUri: String? = null
    // IMPORTANT: Remove _progress, _statusMessage, etc. from here!
    // They are runtime state and should not be directly Parcelized.
) : Parcelable {

    // Move the MutableState properties OUTSIDE the primary constructor.
    // They are now regular properties of the class, not constructor parameters.
    val _progress: MutableState<Int> = mutableStateOf(0)
    val _statusMessage: MutableState<String> = mutableStateOf("准备中")
    val _isPaused: MutableState<Boolean> = mutableStateOf(false)
    val _isCancelled: MutableState<Boolean> = mutableStateOf(false)
    val _downloadedSize: MutableState<Long> = mutableStateOf(0L) // Added back
    val _elapsedTime: MutableState<Long> = mutableStateOf(0L)     // Added back

    // These public var and val properties will continue to work correctly
    // as they delegate to the MutableState objects.
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

    // Make sure these public getters are still available for Compose
    val downloadedSize: State<Long> get() = _downloadedSize
    val elapsedTime: State<Long> get() = _elapsedTime
}