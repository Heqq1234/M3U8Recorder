package com.dl.m3u8recorder.ui.components

import TaskItem
import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dl.m3u8recorder.manager.DownloadManager
import com.dl.m3u8recorder.model.DownloadTask

@Composable
fun TaskScreen() {
    val context = LocalContext.current
    val taskList = remember { mutableStateListOf<DownloadTask>() }
    var url by remember { mutableStateOf("") }
    var filename by remember { mutableStateOf("") }
    var realtimeMerge by remember { mutableStateOf(true) }
    var isLive by remember { mutableStateOf(false) }  // 新增直播流开关

    // 初始化 DownloadManager 并监听任务状态变化
    LaunchedEffect(Unit) {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.cacheDir
        DownloadManager.init(context, dir, object : DownloadManager.TaskListener {
            override fun onTaskUpdated(task: DownloadTask) {
                val index = taskList.indexOfFirst { it.id == task.id }
                if (index >= 0) taskList[index] = task
            }

            override fun onQueueChanged(tasks: List<DownloadTask>) {
                taskList.clear()
                taskList.addAll(tasks)
            }
        })
    }

    Column(modifier = Modifier.padding(16.dp)) {
        // 输入 M3U8 链接
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("M3U8链接") },
            modifier = Modifier.fillMaxWidth()
        )

        // 输入文件名
        OutlinedTextField(
            value = filename,
            onValueChange = { filename = it },
            label = { Text("文件名") },
            modifier = Modifier.fillMaxWidth()
        )

        // 合并方式选项
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Checkbox(checked = realtimeMerge, onCheckedChange = { realtimeMerge = it })
            Text(text = if (realtimeMerge) "边下载边合并 (实时)" else "下载完再合并")
        }

        // 直播流选项
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Checkbox(checked = isLive, onCheckedChange = { isLive = it })
            Text(text = "直播流录制")
        }

        // 添加下载任务按钮
        Button(
            onClick = {
                if (url.isNotBlank() && filename.isNotBlank()) {
                    DownloadManager.addTask(url, filename, realtimeMerge, isLive)
                    url = ""
                    filename = ""
                    realtimeMerge = true
                    isLive = false
                }
            },
            modifier = Modifier
                .padding(vertical = 8.dp)
                .fillMaxWidth()
        ) {
            Text("添加下载任务")
        }

        // 分隔线
        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // 任务列表
        LazyColumn {
            items(taskList.size) { index ->
                val task = taskList[index]
                TaskItem(
                    task = task,
                    onCancel = { DownloadManager.cancelTask(task.id) },
                    onRetry = { DownloadManager.retryTask(task.id) },
                    onPause = { DownloadManager.pauseTask(task.id) },
                    onResume = { DownloadManager.resumeTask(task.id) }, // 通过按钮恢复下载
                    progress = task.progress, // 显示进度
                    statusMessage = task.statusMessage // 显示状态信息
                )
            }
        }
    }
}
