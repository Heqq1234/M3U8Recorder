package com.dl.m3u8recorder.ui.components

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
        modifier = modifier
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
                // 🚀 【关键】正确地将 navController 作为参数传递给 VideoPlayerScreen
                VideoPlayerScreen(videoUri = videoUri, navController = navController)
            } else {
                navController.popBackStack()
            }
        }
    }
}