package com.dl.m3u8recorder.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dl.m3u8recorder.ui.components.TaskScreen
import com.dl.m3u8recorder.ui.theme.AppTheme

class TaskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                TaskScreen()
            }
        }
    }
}