package com.dl.m3u8recorder.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dl.m3u8recorder.model.DownloadTask

@Composable
fun TaskItem(
    task: DownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onPause: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("文件名: ${task.fileName}")
            Text("链接: ${task.url}")
            Text("合并方式: ${if (task.realtimeMerge) "边下载边合并" else "下载完再合并"}")
            Text("是否直播: ${if (task.isLive) "是" else "否"}")
            Text("状态: ${
                task.statusMessage?.ifBlank { when {
                    task.isCancelled -> "已取消"
                    task.isPaused -> "已暂停"
                    else -> "下载中或等待中"
                }}
            }")

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = onPause) { Text("暂停") }
                Button(onClick = onCancel) { Text("取消") }
                Button(onClick = onRetry) { Text("重试") }
            }
        }
    }
}