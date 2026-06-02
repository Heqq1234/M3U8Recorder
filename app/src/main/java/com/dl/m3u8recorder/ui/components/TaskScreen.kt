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
    var isLive by remember { mutableStateOf(false) }

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
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 200.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
            maxLines = 10,
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 80.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                maxLines = 2,
            )
            Text(
                text = "提示: 输入 curl 命令中 curl 后面的 URL 作为基础 URL，用于解析相对路径",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // --- 文件名输入框 ---
        OutlinedTextField(
            value = filename,
            onValueChange = { filename = it },
            label = { Text("文件名") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 56.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp)
        )

        // --- Phase 3: 分析分辨率按钮 ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    if (url.isNotBlank()) {
                        scope.launch {
                            isAnalyzingUrl = true
                            analysisError = null
                            availableVariants = emptyList()
                            selectedVariant = null

                            try {
                                // 根据输入类型选择分析方法
                                val variants = if (isM3U8Content) {
                                    if (baseUrlForContent.isNotBlank()) {
                                        DownloadManager.analyzeUrl(url, baseUrlForContent)
                                    } else {
                                        // 尝试从内容自动提取基础 URL
                                        DownloadManager.analyzeUrl(url, null)
                                    }
                                } else {
                                    DownloadManager.analyzeUrl(url)
                                }

                                withContext(Dispatchers.Main) {
                                    availableVariants = variants
                                    if (variants.isNotEmpty() && autoSelectBest) {
                                        selectedVariant = variants.first() // 已按带宽降序
                                        // 提取最佳 URL
                                        if (isM3U8Content && baseUrlForContent.isNotBlank()) {
                                            extractedBestUrl = DownloadManager.extractBestStreamUrl(url, baseUrlForContent)
                                        }
                                    }
                                    isAnalyzingUrl = false
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    analysisError = "分析失败: ${e.message}"
                                    isAnalyzingUrl = false
                                }
                            }
                        }
                    }
                },
                enabled = !isAnalyzingUrl && url.isNotBlank() && (!isM3U8Content || baseUrlForContent.isNotBlank())
            ) {
                if (isAnalyzingUrl) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("分析中...")
                } else {
                    Text(if (isM3U8Content) "解析M3U8内容" else "分析分辨率")
                }
            }

            // 自动选择复选框
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = autoSelectBest,
                    onCheckedChange = {
                        autoSelectBest = it
                        if (it && availableVariants.isNotEmpty()) {
                            selectedVariant = availableVariants.first()
                        }
                    }
                )
                Text("自动最高画质", fontSize = 12.sp)
            }
        }

        // --- Phase 3: 分辨率选择 Chips ---
        if (availableVariants.isNotEmpty()) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = "可用分辨率 (${availableVariants.size}个):",
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 11.sp
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    availableVariants.forEach { variant ->
                        FilterChip(
                            selected = selectedVariant == variant,
                            onClick = {
                                selectedVariant = variant
                                autoSelectBest = false
                            },
                            label = {
                                Column {
                                    Text(
                                        text = variant.resolution?.getShortLabel()
                                            ?: variant.getResolutionLabel(),
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = variant.getBandwidthLabel(),
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            modifier = Modifier.height(36.dp)
                        )
                    }
                }
            }
        }

        // 显示分析错误
        if (analysisError != null) {
            Text(
                text = analysisError ?: "",
                color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // 显示已选择的分辨率
        selectedVariant?.let { variant ->
            Text(
                text = "已选择: ${variant.resolution?.getShortLabel() ?: variant.getResolutionLabel()} (${variant.getBandwidthLabel()})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // --- 设置选项 ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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

        // --- 保存路径 ---
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

        // 显示提取的最佳流 URL
        extractedBestUrl?.let { bestUrl ->
            Text(
                text = "最佳流: $bestUrl",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // --- 添加任务按钮 ---
        Button(
            onClick = {
                if (url.isNotBlank() && filename.isNotBlank()) {
                    // 先保存当前值，再重置状态
                    val currentUrl = url.trim()
                    val currentFilename = filename.trim()
                    val currentIsLive = isLive
                    val currentRealtimeMerge = realtimeMerge
                    val currentDownloadUri = downloadDirectoryUri
                    val currentIsM3U8Content = isM3U8Content
                    val currentBaseUrl = baseUrlForContent.trim()
                    val currentVariant = selectedVariant
                    val currentExtractedBestUrl = extractedBestUrl

                    // 添加日志调试
                    Log.d("TaskScreen", "添加任务: url='$currentUrl', filename='$currentFilename', isM3U8Content=$currentIsM3U8Content")
                    Log.d("TaskScreen", "selectedVariant=$currentVariant, extractedBestUrl=$currentExtractedBestUrl")

                    // 重置状态
                    url = ""
                    filename = ""
                    baseUrlForContent = ""
                    availableVariants = emptyList()
                    selectedVariant = null
                    analysisError = null
                    extractedBestUrl = null
                    realtimeMerge = true
                    isLive = false

                    scope.launch {
                        if (!currentIsM3U8Content) {
                            // URL 模式，使用自动解析
                            Log.d("TaskScreen", "调用 addTask: url='$currentUrl'")
                            DownloadManager.addTask(currentUrl, currentFilename, currentRealtimeMerge, currentIsLive, currentDownloadUri)
                        } else if (currentExtractedBestUrl != null) {
                            // M3U8 内容模式，使用提取的最佳 URL
                            Log.d("TaskScreen", "调用 addTask with extractedBestUrl: $currentExtractedBestUrl")
                            DownloadManager.addTask(currentExtractedBestUrl, currentFilename, currentRealtimeMerge, currentIsLive, currentDownloadUri)
                        } else if (currentVariant != null) {
                            // 使用选定的变体
                            val audioUrl = DownloadManager.getAudioUrlFromContent(currentUrl, currentBaseUrl, currentVariant.audioGroupId)
                            Log.d("TaskScreen", "调用 addTaskWithVariant: ${currentVariant.uri}")
                            DownloadManager.addTaskWithVariant(
                                url = currentVariant.uri,
                                fileName = currentFilename,
                                variant = currentVariant,
                                audioUrl = audioUrl,
                                realtimeMerge = currentRealtimeMerge,
                                isLive = currentIsLive,
                                downloadDirectoryUri = currentDownloadUri
                            )
                        }
                    }
                }
            },
            modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
            enabled = url.isNotBlank() && filename.isNotBlank() && (!isM3U8Content || selectedVariant != null || extractedBestUrl != null)
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
                    onDeleteConfirmed = { taskId -> DownloadManager.deleteTaskAndTsFile(taskId) },
                    onStopRecording = { taskId -> LiveRecordingService.stopService(context, taskId) }
                )
            }
        }
    }
}
