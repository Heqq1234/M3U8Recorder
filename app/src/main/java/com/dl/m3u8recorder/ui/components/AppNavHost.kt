package com.dl.m3u8recorder.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument // 导入 navArgument
import androidx.navigation.NavType // 导入 NavType
import android.net.Uri // 导入 Uri
import androidx.core.net.toUri
import androidx.navigation.toRoute // 导入 toRoute

object Routes {
    const val TASK_SCREEN = "task_screen"
    const val DOWNLOADED_FILES_SCREEN = "downloaded_files_screen"
    // 🚀 【新增】视频播放器屏幕路由
    // 使用占位符 {videoUri} 来传递 Uri 参数。注意 Uri 需要编码和解码
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
                // 🚀 【修改】传入 navController 给 DownloadedFilesScreen
                onFileClick = { fileUri ->
                    // 对 Uri 进行 URL 编码，因为 Uri 可能包含特殊字符
                    val encodedUri = Uri.encode(fileUri.toString())
                    navController.navigate("video_player_screen/$encodedUri")
                }
            )
        }
        // 🚀 【新增】VideoPlayerScreen 路由
        composable(
            route = Routes.VIDEO_PLAYER_SCREEN,
            arguments = listOf(navArgument("videoUri") { type = NavType.StringType })
        ) { backStackEntry ->
            val videoUriString = backStackEntry.arguments?.getString("videoUri")
            val videoUri = videoUriString?.let { Uri.decode(it).toUri() } // 解码回 Uri
            if (videoUri != null) {
                VideoPlayerScreen(videoUri = videoUri)
            } else {
                // 如果 URI 为空，可以返回列表页或者显示错误
                // 例如：navController.popBackStack()
            }
        }
    }
}