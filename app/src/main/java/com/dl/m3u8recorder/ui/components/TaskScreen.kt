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
import com.dl.m3u8recorder.service.LiveRecordingService // 确保已导入 LiveRecordingService
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

    // 获取应用专属下载目录作为默认路径
    val appDownloadsDir = Mp4OutputHelper.getAppSpecificDownloadsDir(context)
    var downloadDirectoryUri by remember { mutableStateOf(appDownloadsDir.toUri()) }
    var customDownloadPath by remember { mutableStateOf(appDownloadsDir.absolutePath) }

    // 用于选择下载目录的 ActivityResultLauncher
    val openDirectoryLauncher = rememberLauncherForActivityResult(
        contract = object : ActivityResultContracts.OpenDocumentTree() {
            override fun createIntent(context: Context, input: Uri?): android.content.Intent {
                val intent = super.createIntent(context, input)
                // 尝试设置初始 URI，以便用户从上次选择的目录开始浏览
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
            // 获取持久化 URI 权限，以便应用可以长期访问此目录
            contentResolver.takePersistableUriPermission(it,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            downloadDirectoryUri = it
            // 更新显示路径
            customDownloadPath = Mp4OutputHelper.getUriPath(context, it) ?: it.path.toString()
            // 保存到 SharedPreferences，以便下次启动时恢复
            Mp4OutputHelper.setCustomDownloadDirectory(context, it.toString())
            Log.d("TaskScreen", "Selected download directory: ${customDownloadPath}")
        }
    }

    // UI 层面监听任务状态变化的监听器
    val taskScreenListener = remember {
        object : DownloadManager.TaskListener {
            override fun onTaskUpdated(task: DownloadTask) {
                // 当任务更新时，在列表中找到并替换，或者添加新任务
                val index = taskList.indexOfFirst { it.id == task.id }
                if (index != -1) {
                    taskList[index] = task // Replace existing task with updated one
                } else {
                    taskList.add(task) // Add new task if not found
                }
                Log.d("TaskScreen", "UI Listener: Task updated: ${task.id}, progress: ${task.progress}, status: ${task.statusMessage}")
            }

            override fun onQueueChanged(tasks: List<DownloadTask>) {
                // 当任务队列整体变化时，清空并重新填充列表
                taskList.clear()
                taskList.addAll(tasks)
                Log.d("TaskScreen", "UI Listener: Task queue updated. Tasks count: ${tasks.size}")
            }
        }
    }

    // Composable 的生命周期管理：在组件进入和离开组合时注册/取消注册监听器
    DisposableEffect(Unit) {
        DownloadManager.init(context.applicationContext) // 确保 DownloadManager 已初始化
        DownloadManager.addListener(taskScreenListener) // 注册 UI 监听器

        // 恢复上次保存的自定义下载目录
        val savedUriString = Mp4OutputHelper.getCustomDownloadDirectory(context)
        if (savedUriString != null) {
            val savedUri = Uri.parse(savedUriString)
            downloadDirectoryUri = savedUri
            customDownloadPath = Mp4OutputHelper.getUriPath(context, savedUri) ?: savedUri.path.toString()
        }

        Log.d("TaskScreen", "TaskScreen UI listener added.")
        onDispose {
            DownloadManager.removeListener(taskScreenListener) // 在组件销毁时移除监听器，避免内存泄漏
            Log.d("TaskScreen", "TaskScreen UI listener removed onDispose.")
        }
    }

    Column(modifier = modifier.padding(16.dp)) {
        // M3U8链接输入框
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("M3U8链接") },
            modifier = Modifier.fillMaxWidth(),
            //singleLine = true, // 限制单行
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 12.sp // 设置更小的字体大小
            )
        )
        Spacer(modifier = Modifier.height(8.dp)) // 增加间距
        // 文件名输入框
        OutlinedTextField(
            value = filename,
            onValueChange = { filename = it },
            label = { Text("文件名") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true // 限制单行
        )

        // 实时合并与直播录制选项（同行）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.Start // 让内容从左侧开始排列
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = realtimeMerge, onCheckedChange = { realtimeMerge = it })
                Text(text = if (realtimeMerge) "边下载边合并 (实时)" else "下载完再合并", fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.width(16.dp)) // 增加间距以分隔两个选项
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isLive, onCheckedChange = { isLive = it })
                Text(text = "直播流录制", fontSize = 13.sp)
            }
        }

        // 下载目录显示部分 (可点击选择)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clickable {
                    openDirectoryLauncher.launch(downloadDirectoryUri) // 点击打开目录选择器
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
                overflow = TextOverflow.Ellipsis // 路径过长时显示省略号
            )
        }

        // 添加下载任务按钮
        Button(
            onClick = {
                if (url.isNotBlank() && filename.isNotBlank()) {
                    // 添加任务到 DownloadManager
                    DownloadManager.addTask(url, filename, realtimeMerge, isLive, downloadDirectoryUri)
                    // 重置输入框和选项
                    url = ""
                    filename = ""
                    realtimeMerge = true
                    isLive = false
                } else {
                    // 可以添加一个 Toast 提示用户输入完整信息
                    // Toast.makeText(context, "M3U8链接和文件名不能为空！", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier
                .padding(vertical = 8.dp)
                .fillMaxWidth()
        ) {
            Text("添加下载任务")
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp)) // 分隔线

        // 任务列表
        LazyColumn(modifier = Modifier.fillMaxHeight()) {
            items(taskList, key = { it.id }) { task ->
                TaskItem(
                    task = task,
                    onCancel = { taskId -> DownloadManager.cancelTask(taskId) },
                    onRetry = { taskId -> DownloadManager.retryTask(taskId) },
                    onPause = { taskId ->
                        // 根据是否为直播任务调用不同的暂停逻辑
                        if (task.isLive) { LiveRecordingService.pauseService(context, taskId) }
                        else { DownloadManager.pauseTask(taskId) }
                    },
                    onResume = { taskId ->
                        // 根据是否为直播任务调用不同的恢复逻辑
                        if (task.isLive) { LiveRecordingService.resumeService(context, taskId) }
                        else { DownloadManager.resumeTask(taskId) }
                    },
                    onDeleteConfirmed = { taskId ->
                        DownloadManager.deleteTaskAndTsFile(taskId)
                    },
                    // 【关键修改】: 将停止录制动作作为 lambda 参数传递给 TaskItem
                    onStopRecording = { taskId ->
                        LiveRecordingService.stopService(context, taskId)
                    }
                )
            }
        }
    }
}