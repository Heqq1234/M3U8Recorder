package com.dl.m3u8recorder.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(videoUri: Uri, navController: NavHostController) {
    val context = LocalContext.current

    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context)
        val loadControl: LoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 5000, 2500)
            .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS, false)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build().apply {
                setMediaItem(MediaItem.fromUri(videoUri))
                prepare()
                playWhenReady = true
            }
    }

    // 🚀 【关键】这个 DisposableEffect 只负责释放播放器资源
    DisposableEffect(Unit) {
        onDispose {
            // 当页面被销毁时，立即释放播放器，终止视频
            exoPlayer.release()
            Log.d("VideoPlayerScreen", "ExoPlayer released on DisposableEffect onDispose.")
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                setOnClickListener {
                    toggleFullScreen(context)
                }
            }
        }
    )
}

private fun toggleFullScreen(context: Context) {
    val activity = findActivity(context) ?: return
    val window: Window = activity.window
    val decorView: View = window.decorView

    val uiOptions = decorView.systemUiVisibility
    var newUiOptions = uiOptions

    val isLandscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        newUiOptions = newUiOptions or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        newUiOptions = newUiOptions or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
    decorView.systemUiVisibility = newUiOptions
}

private fun findActivity(context: Context): Activity? {
    if (context is Activity) return context
    if (context is ContextWrapper) return findActivity(context.baseContext)
    return null
}