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
import com.dl.m3u8recorder.parser.VariantStream
import com.dl.m3u8recorder.service.LiveRecordingService
import com.dl.m3u8recorder.utils.Mp4OutputHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dl.m3u8recorder.llhls.ChaturbateApi
import com.dl.m3u8recorder.llhls.StripchatApi
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun NumberPicker(
    value: Int,
    onValueChange: (Int) -> Unit,
    minValue: Int,
    maxValue: Int
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
                if (value > minValue) {
                    onValueChange(value - 1)
                }
            },
            modifier = Modifier.size(32.dp),
            contentPadding = PaddingValues(0.dp),
            enabled = value > minValue
        ) {
            Text("-", fontSize = 16.sp)
        }
        Text(
            text = String.format("%02d", value),
            fontSize = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(48.dp)
        )
        Button(
            onClick = {
                if (value < maxValue) {
                    onValueChange(value + 1)
                }
            },
            modifier = Modifier.size(32.dp),
            contentPadding = PaddingValues(0.dp),
            enabled = value < maxValue
        ) {
            Text("+", fontSize = 16.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val taskList = remember { mutableStateListOf<DownloadTask>() }

    // 基础输入
    var url by remember { mutableStateOf("") }
    var filename by remember { mutableStateOf("") }
    var realtimeMerge by remember { mutableStateOf(true) }
    var isLive by remember { mutableStateOf(true) }

    // M3U8 内容粘贴支持
    var isM3U8Content by remember { mutableStateOf(false) }
    var baseUrlForContent by remember { mutableStateOf("") }
    var extractedBestUrl by remember { mutableStateOf<String?>(null) }

    // Phase 3: 分辨率选择状态
    var availableVariants by remember { mutableStateOf<List<VariantStream>>(emptyList()) }
    var selectedVariant by remember { mutableStateOf<VariantStream?>(null) }
    var isAnalyzingUrl by remember { mutableStateOf(false) }
    var autoSelectBest by remember { mutableStateOf(true) }
    var analysisError by remember { mutableStateOf<String?>(null) }

    // Phase 5: Chaturbate 房间模式
    val chaturbateRoomSlug = remember(url) { ChaturbateApi.extractRoomSlug(url) }
    var showChaturbateDialog by remember { mutableStateOf(false) }

    // Stripchat / 白标站点房间模式
    val stripchatRoomInfo = remember(url) { StripchatApi.extractRoomSlug(url) }
    var showStripchatDialog by remember { mutableStateOf(false) }

    // 定时任务相关状态
    var isScheduled by remember { mutableStateOf(false) }
    var selectedHour by remember { mutableStateOf(0) }
    var selectedMinute by remember { mutableStateOf(0) }
    var showTimePicker by remember { mutableStateOf(false) }

    // 检测输入是否为 M3U8 内容
    LaunchedEffect(url) {
        isM3U8Content = DownloadManager.isM3U8Content(url)
        if (!isM3U8Content) {
            extractedBestUrl = null
        }
    }

    // 下载路径
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
        // --- M3U8链接/内容输入框 ---
        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                // URL 改变时重置分辨率选择
                availableVariants = emptyList()
                selectedVariant = null
                analysisError = null
                extractedBestUrl = null
            },
            label = { Text(if (isM3U8Content) "M3U8内容 (已检测)" else "M3U8链接") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 100.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
            maxLines = 3,
        )

        // 如果检测到是 M3U8 内容，显示基础 URL 输入框
        if (isM3U8Content) {
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = baseUrlForContent,
                onValueChange = {
                    baseUrlForContent = it
                    extractedBestUrl = null
                },
                label = { Text("基础URL (curl命令中的URL)") },
                placeholder = { Text("例如: https://example.com/stream.m3u8", fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 56.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                maxLines = 1,
            )
            Text(
                text = "提示: 输入 curl 命令中 curl 后面的 URL 作为基础 URL，用于解析相对路径",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // --- Phase 5: Chaturbate 房间模式 ---
        chaturbateRoomSlug?.let { room ->
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "Chaturbate 房间: $room",
                        style = MaterialTheme.typography.titleSmall,
                        fontSize = 12.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = "App 将通过 WebView 连接房间，自动获取直播流地址",
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "文件名",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                                OutlinedTextField(
                                    value = filename,
                                    onValueChange = { filename = it },
                                    placeholder = { Text("文件名", fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f).heightIn(min = 36.dp),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (filename.isNotBlank()) {
                                            showChaturbateDialog = true
                                        }
                                    },
                            enabled = filename.isNotBlank()
                        ) {
                            Text(if (isScheduled) "定时录制" else "连接并录制", fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
        }

        // --- Stripchat 房间模式 ---
        stripchatRoomInfo?.let { room ->
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "Stripchat 房间: ${room.slug}",
                        style = MaterialTheme.typography.titleSmall,
                        fontSize = 12.sp,
                        lineHeight = 15.sp
                    )
                    Text(
                        text = "站点: ${room.baseUrl}",
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "自动获取直播流地址，支持官方和合作站点",
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "文件名",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                                OutlinedTextField(
                                    value = filename,
                                    onValueChange = { filename = it },
                                    placeholder = { Text("文件名", fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f).heightIn(min = 36.dp),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (filename.isNotBlank()) {
                                            showStripchatDialog = true
                                        }
                                    },
                            enabled = filename.isNotBlank()
                        ) {
                            Text(if (isScheduled) "定时录制" else "连接并录制", fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
        }

        // --- 保存路径（所有模式下可见） ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clickable { openDirectoryLauncher.launch(downloadDirectoryUri) }
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(text = "保存路径:", style = MaterialTheme.typography.labelLarge)
            Text(
                text = customDownloadPath,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // --- 定时任务 + 合并选项（所有模式下可见） ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isScheduled, onCheckedChange = { isScheduled = it })
                Text(text = "定时下载", fontSize = 13.sp)
            }
            if (isScheduled) {
                OutlinedButton(
                    onClick = { showTimePicker = true },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(
                        text = String.format("%02d:%02d", selectedHour, selectedMinute),
                        fontSize = 12.sp
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = realtimeMerge, onCheckedChange = { realtimeMerge = it })
                Text(text = if (realtimeMerge) "边下载边合并" else "下载完再合并", fontSize = 13.sp)
            }
        }

        // 非 Chaturbate/Stripchat 模式才显示标准控件
        if (chaturbateRoomSlug == null && stripchatRoomInfo == null) {
            Spacer(modifier = Modifier.height(4.dp))

            // --- 文件名输入框 ---
        OutlinedTextField(
            value = filename,
            onValueChange = { filename = it },
            label = { Text("文件名") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 48.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp)
        )

        // --- 添加任务按钮 ---
        Button(
            onClick = {
                if (url.isNotBlank() && filename.isNotBlank()) {
                    val currentUrl = url.trim()
                    val currentFilename = filename.trim()
                    val currentIsLive = isLive
                    val currentRealtimeMerge = realtimeMerge
                    val currentDownloadUri = downloadDirectoryUri
                    val currentIsScheduled = isScheduled
                    val currentScheduledHour = selectedHour
                    val currentScheduledMinute = selectedMinute

                    Log.d("TaskScreen", "添加任务: url='$currentUrl', filename='$currentFilename', isScheduled=$currentIsScheduled")

                    url = ""
                    filename = ""
                    realtimeMerge = true
                    isLive = true
                    isScheduled = false
                    selectedHour = 0
                    selectedMinute = 0

                    scope.launch {
                        val scheduledStartTime = if (currentIsScheduled) {
                            val calendar = Calendar.getInstance()
                            calendar.set(Calendar.HOUR_OF_DAY, currentScheduledHour)
                            calendar.set(Calendar.MINUTE, currentScheduledMinute)
                            calendar.set(Calendar.SECOND, 0)
                            calendar.set(Calendar.MILLISECOND, 0)
                            if (calendar.timeInMillis < System.currentTimeMillis()) {
                                calendar.add(Calendar.DAY_OF_YEAR, 1)
                            }
                            calendar.timeInMillis
                        } else {
                            0L
                        }

                        val task = DownloadTask(
                            id = "task_${System.currentTimeMillis()}",
                            url = currentUrl,
                            fileName = currentFilename,
                            realtimeMerge = currentRealtimeMerge,
                            isLive = currentIsLive,
                            customDownloadUri = currentDownloadUri?.toString(),
                            isScheduled = currentIsScheduled,
                            scheduledStartTime = scheduledStartTime,
                            // 直播流必须走 LL-HLS 录制器(OkHttp 处理 https)，否则 ffmpeg 直录分支因
                            // 未启用 openssl 无法处理 https URL → Protocol not found 失败
                            isLLHls = currentIsLive
                        )

                        if (currentIsScheduled) {
                            DownloadManager.addScheduledTask(task)
                        } else {
                            DownloadManager.addTask(currentUrl, currentFilename, currentRealtimeMerge, currentIsLive, currentDownloadUri, audioTrackUrl = null)
                        }
                    }
                }
            },
            modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
            enabled = url.isNotBlank() && filename.isNotBlank()
        ) {
            Text(if (isScheduled) "添加定时任务" else "添加下载任务")
        }

        } // 结束 if (chaturbateRoomSlug == null)

        // --- Chaturbate 连接对话框 ---
        if (showChaturbateDialog && chaturbateRoomSlug != null) {
            val currentIsScheduled = isScheduled
            val currentScheduledHour = selectedHour
            val currentScheduledMinute = selectedMinute
            val currentDownloadUri = downloadDirectoryUri
            val currentFilename = filename

            ChaturbateConnectDialog(
                roomSlug = chaturbateRoomSlug,
                onDismiss = { showChaturbateDialog = false },
                onStreamUrlObtained = { videoUrl, audioUrl ->
                    showChaturbateDialog = false
                    Log.d("TaskScreen", "Chaturbate 获取到流地址，isScheduled=$currentIsScheduled")

                    val scheduledStartTime = if (currentIsScheduled) {
                        val calendar = Calendar.getInstance()
                        calendar.set(Calendar.HOUR_OF_DAY, currentScheduledHour)
                        calendar.set(Calendar.MINUTE, currentScheduledMinute)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)
                        if (calendar.timeInMillis < System.currentTimeMillis()) {
                            calendar.add(Calendar.DAY_OF_YEAR, 1)
                        }
                        calendar.timeInMillis
                    } else {
                        0L
                    }

                    val task = DownloadTask(
                        id = "task_${System.currentTimeMillis()}",
                        url = videoUrl,
                        fileName = currentFilename.ifBlank { chaturbateRoomSlug },
                        realtimeMerge = true,
                        isLive = true,
                        customDownloadUri = currentDownloadUri?.toString(),
                        audioTrackUrl = audioUrl,
                        isScheduled = currentIsScheduled,
                        scheduledStartTime = scheduledStartTime,
                        // 直播流走 LL-HLS 录制器(OkHttp https)，避免 ffmpeg 直录分支 https 失败
                        isLLHls = true,
                        // 定时任务存平台+房间号，到期可重新取流(主播未开播重试)
                        platform = "chaturbate",
                        roomSlug = chaturbateRoomSlug
                    )

                    if (currentIsScheduled) {
                        DownloadManager.addScheduledTask(task)
                    } else {
                        DownloadManager.addTask(
                            url = videoUrl,
                            fileName = currentFilename.ifBlank { chaturbateRoomSlug },
                            realtimeMerge = true,
                            isLive = true,
                            downloadDirectoryUri = currentDownloadUri,
                            audioTrackUrl = audioUrl,
                            platform = "chaturbate",
                            roomSlug = chaturbateRoomSlug
                        )
                    }

                    filename = ""
                    isScheduled = false
                    selectedHour = 0
                    selectedMinute = 0
                }
            )
        }

        // --- Stripchat 连接对话框 ---
        if (showStripchatDialog && stripchatRoomInfo != null) {
            val currentIsScheduled = isScheduled
            val currentScheduledHour = selectedHour
            val currentScheduledMinute = selectedMinute
            val currentDownloadUri = downloadDirectoryUri
            val currentFilename = filename
            val currentRoomInfo = stripchatRoomInfo

            StripchatConnectDialog(
                roomInfo = currentRoomInfo,
                onDismiss = { showStripchatDialog = false },
                onStreamUrlObtained = { videoUrl, audioUrl, _ ->
                    showStripchatDialog = false
                    Log.d("TaskScreen", "Stripchat 获取到流地址，isScheduled=$currentIsScheduled")

                    val scheduledStartTime = if (currentIsScheduled) {
                        val calendar = Calendar.getInstance()
                        calendar.set(Calendar.HOUR_OF_DAY, currentScheduledHour)
                        calendar.set(Calendar.MINUTE, currentScheduledMinute)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)
                        if (calendar.timeInMillis < System.currentTimeMillis()) {
                            calendar.add(Calendar.DAY_OF_YEAR, 1)
                        }
                        calendar.timeInMillis
                    } else {
                        0L
                    }

                    val task = DownloadTask(
                        id = "task_${System.currentTimeMillis()}",
                        url = videoUrl,
                        fileName = currentFilename.ifBlank { currentRoomInfo.slug },
                        realtimeMerge = true,
                        isLive = true,
                        customDownloadUri = currentDownloadUri?.toString(),
                        audioTrackUrl = audioUrl,
                        isScheduled = currentIsScheduled,
                        scheduledStartTime = scheduledStartTime,
                        // 直播流走 LL-HLS 录制器(OkHttp https)，避免 ffmpeg 直录分支 https 失败
                        isLLHls = true,
                        // 定时任务存平台+房间号，到期可重新取流(主播未开播重试)
                        platform = "stripchat",
                        roomSlug = currentRoomInfo.slug,
                        roomBaseUrl = currentRoomInfo.baseUrl
                    )

                    if (currentIsScheduled) {
                        DownloadManager.addScheduledTask(task)
                    } else {
                        DownloadManager.addTask(
                            url = videoUrl,
                            fileName = currentFilename.ifBlank { currentRoomInfo.slug },
                            realtimeMerge = true,
                            isLive = true,
                            downloadDirectoryUri = currentDownloadUri,
                            audioTrackUrl = audioUrl,
                            platform = "stripchat",
                            roomSlug = currentRoomInfo.slug,
                            roomBaseUrl = currentRoomInfo.baseUrl
                        )
                    }

                    filename = ""
                    isScheduled = false
                    selectedHour = 0
                    selectedMinute = 0
                }
            )
        }

        // --- 定时时间选择对话框 ---
        if (showTimePicker) {
            AlertDialog(
                onDismissRequest = { showTimePicker = false },
                title = { Text("选择定时时间") },
                text = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "小时:", fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            NumberPicker(
                                value = selectedHour,
                                onValueChange = { selectedHour = it.coerceIn(0, 23) },
                                minValue = 0,
                                maxValue = 23
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "分钟:", fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            NumberPicker(
                                value = selectedMinute,
                                onValueChange = { selectedMinute = it.coerceIn(0, 59) },
                                minValue = 0,
                                maxValue = 59
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showTimePicker = false }) {
                        Text("确认")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showTimePicker = false }) {
                        Text("取消")
                    }
                }
            )
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(taskList, key = { it.id }) { task ->
                TaskItem(
                    task = task,
                    onCancel = { taskId -> DownloadManager.cancelTask(taskId) },
                    onRetry = { taskId -> DownloadManager.retryTask(taskId) },
                    onDeleteConfirmed = { taskId -> DownloadManager.deleteTaskAndTsFile(taskId) },
                    onStopRecording = { taskId -> LiveRecordingService.stopService(context, taskId) }
                )
            }
        }
    }
}
