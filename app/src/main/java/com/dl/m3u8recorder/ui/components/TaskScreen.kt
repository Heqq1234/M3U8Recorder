package com.dl.m3u8recorder.ui.components

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.dl.m3u8recorder.manager.DownloadManager
import com.dl.m3u8recorder.model.DownloadTask
import com.dl.m3u8recorder.service.LiveRecordingService
import com.dl.m3u8recorder.utils.Mp4OutputHelper

@Composable
fun TaskScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val taskList = remember { mutableStateListOf<DownloadTask>() }
    var url by remember { mutableStateOf("") }
    var filename by remember { mutableStateOf("") }
    var realtimeMerge by remember { mutableStateOf(true) }
    var isLive by remember { mutableStateOf(false) }

    val appDownloadsDir = Mp4OutputHelper.getAppSpecificDownloadsDir(context)
    var downloadDirectoryUri by remember { mutableStateOf(appDownloadsDir.toUri()) }
    var customDownloadPath by remember { mutableStateOf(appDownloadsDir.absolutePath) }

    val openDirectoryLauncher = rememberLauncherForActivityResult(
        contract = object : ActivityResultContracts.OpenDocumentTree() {
            override fun createIntent(context: Context, input: Uri?): android.content.Intent {
                val intent = super.createIntent(context, input)
                downloadDirectoryUri.let { uri ->
                    if (uri != Uri.EMPTY) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
                            Log.d("TaskScreen", "Setting initial URI for picker: $uri")
                        }
                    }
                }
                return intent
            }
        }
    ) { uri: Uri? ->
        uri?.let {
            val contentResolver = context.contentResolver
            contentResolver.takePersistableUriPermission(it,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            downloadDirectoryUri = it
            customDownloadPath = Mp4OutputHelper.getUriPath(context, it) ?: it.path.toString()
            Mp4OutputHelper.setCustomDownloadDirectory(context, it.toString())
            Log.d("TaskScreen", "Selected download directory: ${customDownloadPath}")
        }
    }

    val taskScreenListener = remember {
        object : DownloadManager.TaskListener {
            override fun onTaskUpdated(task: DownloadTask) {
                val index = taskList.indexOfFirst { it.id == task.id }
                if (index != -1) {
                    taskList[index] = task
                } else {
                    taskList.add(task)
                }
                Log.d("TaskScreen", "UI Listener: Task updated: ${task.id}, progress: ${task.progress}, status: ${task.statusMessage}")
            }

            override fun onQueueChanged(tasks: List<DownloadTask>) {
                taskList.clear()
                taskList.addAll(tasks)
                Log.d("TaskScreen", "UI Listener: Task queue updated. Tasks count: ${tasks.size}")
            }
        }
    }

    DisposableEffect(Unit) {
        DownloadManager.init(context.applicationContext)
        DownloadManager.addListener(taskScreenListener)

        val savedUriString = Mp4OutputHelper.getCustomDownloadDirectory(context)
        if (savedUriString != null) {
            val savedUri = Uri.parse(savedUriString)
            downloadDirectoryUri = savedUri
            customDownloadPath = Mp4OutputHelper.getUriPath(context, savedUri) ?: savedUri.path.toString()
        }

        Log.d("TaskScreen", "TaskScreen UI listener added.")
        onDispose {
            DownloadManager.removeListener(taskScreenListener)
            Log.d("TaskScreen", "TaskScreen UI listener removed onDispose.")
        }
    }

    Column(modifier = modifier.padding(16.dp)) {
        // --- M3U8链接输入框 (修改后) ---
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("M3U8链接") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 120.dp), // 调整最小高度和最大高度
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 12.sp
            ),
            maxLines = 5, // 允许最大 5 行，超过则可滚动
        )
        // --- 减小间距 ---
        Spacer(modifier = Modifier.height(4.dp))
        // --- 文件名输入框 (修改后) ---
        OutlinedTextField(
            value = filename,
            onValueChange = { filename = it },
            label = { Text("文件名") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 56.dp), // 调整高度
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 12.sp
            )
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = realtimeMerge, onCheckedChange = { realtimeMerge = it })
                Text(text = if (realtimeMerge) "边下载边合并 (实时)" else "下载完再合并", fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isLive, onCheckedChange = { isLive = it })
                Text(text = "直播流录制", fontSize = 13.sp)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clickable {
                    openDirectoryLauncher.launch(downloadDirectoryUri)
                }
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "保存路径:",
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = customDownloadPath,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Button(
            onClick = {
                if (url.isNotBlank() && filename.isNotBlank()) {
                    DownloadManager.addTask(url, filename, realtimeMerge, isLive, downloadDirectoryUri)
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

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        LazyColumn(modifier = Modifier.fillMaxHeight()) {
            items(taskList, key = { it.id }) { task ->
                TaskItem(
                    task = task,
                    onCancel = { taskId -> DownloadManager.cancelTask(taskId) },
                    onRetry = { taskId -> DownloadManager.retryTask(taskId) },
                    onDeleteConfirmed = { taskId ->
                        DownloadManager.deleteTaskAndTsFile(taskId)
                    },
                    onStopRecording = { taskId ->
                        LiveRecordingService.stopService(context, taskId)
                    }
                )
            }
        }
    }
}