package com.dl.m3u8recorder.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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

sealed class Screen(val route: String, val icon: ImageVector, val label: String) {
    object Task : Screen(Routes.TASK_SCREEN, Icons.Default.MailOutline, "任务")
    object Downloads : Screen(Routes.DOWNLOADED_FILES_SCREEN, Icons.Default.List, "下载")
}

class TaskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val navController = rememberNavController()
                val items = listOf(Screen.Task, Screen.Downloads)
                val navBackStackEntry by navController.currentBackStackEntryAsState()

                // 🚀 【关键修改】根据当前路由动态更新 showBottomBar 状态
                val currentRoute = navBackStackEntry?.destination?.route
                val showBottomBar = currentRoute != Routes.VIDEO_PLAYER_SCREEN

                Scaffold(
                    bottomBar = {
                        if (showBottomBar) {
                            NavigationBar {
                                val currentDestination = navBackStackEntry?.destination

                                items.forEach { screen ->
                                    NavigationBarItem(
                                        icon = { Icon(screen.icon, contentDescription = null) },
                                        label = { Text(screen.label) },
                                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                                        onClick = {
                                            navController.navigate(screen.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = false
                                                }
                                                launchSingleTop = true
                                                restoreState = false
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    // 🚀 【关键修改】使用 Box 包装 AppNavHost，并根据路由动态应用 padding
                    Box(modifier = Modifier.padding(innerPadding)) {
                        AppNavHost(navController = navController)
                    }
                }
            }
        }
    }
}