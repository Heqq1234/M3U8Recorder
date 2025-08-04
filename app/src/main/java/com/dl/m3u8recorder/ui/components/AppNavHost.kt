package com.dl.m3u8recorder.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.NavType
import android.net.Uri
import androidx.core.net.toUri

object Routes {
    const val TASK_SCREEN = "task_screen"
    const val DOWNLOADED_FILES_SCREEN = "downloaded_files_screen"
    const val VIDEO_PLAYER_SCREEN = "video_player_screen/{videoUri}"
}

@Composable
fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = Routes.TASK_SCREEN,
        modifier = modifier,
        // 🚀 【关键修改】禁用或自定义动画过渡
        enterTransition = { fadeIn(animationSpec = tween(0)) },
        exitTransition = { fadeOut(animationSpec = tween(0)) },
        popEnterTransition = { fadeIn(animationSpec = tween(0)) },
        popExitTransition = { fadeOut(animationSpec = tween(0)) }
    ) {
        composable(Routes.TASK_SCREEN) {
            TaskScreen()
        }
        composable(Routes.DOWNLOADED_FILES_SCREEN) {
            DownloadedFilesScreen(
                onFileClick = { fileUri ->
                    val encodedUri = Uri.encode(fileUri.toString())
                    navController.navigate("video_player_screen/$encodedUri")
                }
            )
        }
        composable(
            route = Routes.VIDEO_PLAYER_SCREEN,
            arguments = listOf(navArgument("videoUri") { type = NavType.StringType })
        ) { backStackEntry ->
            val videoUriString = backStackEntry.arguments?.getString("videoUri")
            val videoUri = videoUriString?.let { Uri.decode(it).toUri() }
            if (videoUri != null) {
                VideoPlayerScreen(videoUri = videoUri, navController = navController)
            } else {
                navController.popBackStack()
            }
        }
    }
}