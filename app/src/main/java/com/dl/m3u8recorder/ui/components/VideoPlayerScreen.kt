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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(videoUri: Uri) {
    val context = LocalContext.current
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current

    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context)

        val loadControl: LoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15000,
                /* maxBufferMs = */ 50000,
                /* bufferForPlaybackMs = */ 5000,
                /* bufferForPlaybackAfterRebufferMs = */ 2500
            )
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

    // 🚀 【关键修改】优化生命周期处理，只在 onDispose 中释放播放器
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    // 暂停播放，但不释放
                    exoPlayer.pause()
                    Log.d("VideoPlayerScreen", "ExoPlayer paused on ON_PAUSE event.")
                }
                Lifecycle.Event.ON_RESUME -> {
                    // 恢复播放
                    exoPlayer.play()
                    Log.d("VideoPlayerScreen", "ExoPlayer played on ON_RESUME event.")
                }
                // 【移除】ON_STOP 中不再调用 release()
                // Lifecycle.Event.ON_STOP -> {
                //     exoPlayer.release()
                //     Log.d("VideoPlayerScreen", "ExoPlayer released on ON_STOP event.")
                // }
                else -> { /* Do nothing */ }
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            // 🚀 【关键修改】确保只在 Composable 离开组合时释放播放器
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
            Log.d("VideoPlayerScreen", "ExoPlayer released on DisposableEffect onDispose.")
        }
    }

    // ✅ 使用 AndroidView 显示 PlayerView
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

// ✅ 全屏切换逻辑
private fun toggleFullScreen(context: Context) {
    val activity = findActivity(context) ?: return
    val window: Window = activity.window
    val decorView: View = window.decorView

    val uiOptions = decorView.systemUiVisibility
    var newUiOptions = uiOptions

    val isLandscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        // 横屏模式，启用全屏和隐藏导航栏
        newUiOptions = newUiOptions or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // 保持屏幕常亮
    } else {
        // 竖屏模式，启用全屏和隐藏导航栏
        newUiOptions = newUiOptions or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    // 更新 decorView 的 UI 状态
    decorView.systemUiVisibility = newUiOptions
}


// ✅ 查找 Activity 的辅助函数
private fun findActivity(context: Context): Activity? {
    if (context is Activity) return context
    if (context is ContextWrapper) return findActivity(context.baseContext)
    return null
}