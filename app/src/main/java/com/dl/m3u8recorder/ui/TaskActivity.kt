package com.dl.m3u8recorder.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dl.m3u8recorder.ui.components.AppNavHost
import com.dl.m3u8recorder.ui.components.Routes
import com.dl.m3u8recorder.ui.theme.AppTheme

// 定义一个密封类，用于表示底部导航栏的每个项目
sealed class Screen(val route: String, val icon: ImageVector, val label: String) {
    object Task : Screen(Routes.TASK_SCREEN, Icons.Default.MailOutline, "任务")
    object Downloads : Screen(Routes.DOWNLOADED_FILES_SCREEN, Icons.Default.List, "下载")
}

class TaskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val navController = rememberNavController() // 创建一个 NavController 实例
                val items = listOf(Screen.Task, Screen.Downloads) // 定义底部导航栏的项目

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            val navBackStackEntry by navController.currentBackStackEntryAsState()
                            val currentDestination = navBackStackEntry?.destination

                            items.forEach { screen ->
                                NavigationBarItem(
                                    icon = { Icon(screen.icon, contentDescription = null) },
                                    label = { Text(screen.label) },
                                    selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                                    onClick = {
                                        // 🚀 【关键修改】禁用状态恢复，确保视频页面被销毁
                                        navController.navigate(screen.route) {
                                            // 弹出到导航图的起始目的地
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = false // 不保存状态
                                            }
                                            // 避免在重新选择同一项时创建同一目的地的多个副本
                                            launchSingleTop = true
                                            // 禁用状态恢复
                                            restoreState = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    AppNavHost(navController = navController, modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}