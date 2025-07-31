package com.dl.m3u8recorder.ui.components

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.dl.m3u8recorder.utils.Mp4OutputHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers // 导入 Dispatchers
import kotlinx.coroutines.launch // 导入 launch
import kotlinx.coroutines.withContext // 导入 withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadedFilesScreen(
    onFileClick: (Uri) -> Unit,
    // onConvertToMp4: (File) -> Unit // 如果暂时不需要这个功能，可以先注释掉
) {
    val context = LocalContext.current
    val downloadedFiles = remember { mutableStateListOf<File>() }
    val selectedFiles = remember { mutableStateListOf<File>() }
    var inSelectionMode by remember { mutableStateOf(false) }

    // 🚀 【新增状态】控制确认删除对话框的显示
    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }
    // 🚀 【新增状态】控制加载指示器的显示
    var isDeleting by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope() // 获取协程作用域

    // 加载文件列表的函数
    val loadFiles: () -> Unit = {
        val appDownloadsDir = Mp4OutputHelper.getAppSpecificDownloadsDir(context)
        Log.d("DownloadedFilesScreen", "Scanning directory: ${appDownloadsDir.absolutePath}")

        val files = appDownloadsDir.listFiles()?.filter {
            it.extension.equals("mp4", ignoreCase = true) ||
                    it.extension.equals("ts", ignoreCase = true)
        } ?: emptyList()

        downloadedFiles.clear()
        downloadedFiles.addAll(files.sortedByDescending { it.lastModified() })
        // 刷新列表时清空选中状态
        selectedFiles.clear()
        inSelectionMode = false
        Log.d("DownloadedFilesScreen", "Found ${downloadedFiles.size} .mp4 and .ts files.")
    }

    LaunchedEffect(Unit) {
        loadFiles()
    }

    Scaffold(
        topBar = {
            if (inSelectionMode) {
                TopAppBar(
                    title = { Text("已选中 ${selectedFiles.size} 项") },
                    navigationIcon = {
                        IconButton(onClick = {
                            // 取消选择模式
                            selectedFiles.clear()
                            inSelectionMode = false
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "取消")
                        }
                    },
                    actions = {
                        if (selectedFiles.isNotEmpty()) {
                            IconButton(onClick = {
                                // 🚀 【修改】点击删除时显示确认对话框
                                showDeleteConfirmationDialog = true
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除选中文件")
                            }
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("已下载/转换的文件") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) { // 使用 Box 包装内容，方便覆盖加载指示器
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                if (downloadedFiles.isEmpty()) {
                    Text("没有找到已下载或已转换的视频文件。", modifier = Modifier.padding(top = 16.dp))
                } else {
                    LazyColumn {
                        items(downloadedFiles, key = { it.absolutePath }) { file ->
                            val isSelected = selectedFiles.contains(file)
                            val cardBackgroundColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .combinedClickable(
                                        onClick = {
                                            if (inSelectionMode) {
                                                if (isSelected) {
                                                    selectedFiles.remove(file)
                                                } else {
                                                    selectedFiles.add(file)
                                                }
                                                if (selectedFiles.isEmpty()) {
                                                    inSelectionMode = false
                                                }
                                            } else {
                                                val uri = file.toUri()
                                                onFileClick(uri)
                                                Log.d("DownloadedFilesScreen", "Clicked file: ${file.name}, URI: $uri")
                                            }
                                        },
                                        onLongClick = {
                                            if (!inSelectionMode) {
                                                inSelectionMode = true
                                            }
                                            if (!isSelected) {
                                                selectedFiles.add(file)
                                            }
                                        }
                                    ),
                                elevation = CardDefaults.cardElevation(2.dp),
                                colors = CardDefaults.cardColors(containerColor = cardBackgroundColor)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("文件名: ${file.name}", fontSize = 14.sp)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 🚀 【修改：移除文件类型显示】
                                        // Text("类型: ${file.extension.uppercase()}", fontSize = 12.sp, modifier = Modifier.weight(0.3f))
                                        Text("大小: ${"%.2f MB".format(file.length() / (1024.0 * 1024.0))}", fontSize = 12.sp, modifier = Modifier.weight(0.4f)) // 调整权重
                                        Text(
                                            "时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))}",
                                            fontSize = 12.sp,
                                            modifier = Modifier.weight(0.6f) // 调整权重
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 🚀 【删除确认对话框】
            if (showDeleteConfirmationDialog) {
                AlertDialog(
                    onDismissRequest = {
                        // 点击对话框外部或按返回键时关闭对话框
                        showDeleteConfirmationDialog = false
                    },
                    title = { Text("确认删除？") },
                    text = { Text("您确定要删除选中的 ${selectedFiles.size} 个文件吗？此操作不可撤销。") },
                    confirmButton = {
                        Button(
                            onClick = {
                                showDeleteConfirmationDialog = false
                                isDeleting = true // 🚀 【显示加载指示器】
                                coroutineScope.launch {
                                    var deletedCount = 0
                                    withContext(Dispatchers.IO) { // 在IO线程执行文件删除操作
                                        selectedFiles.forEach { fileToDelete ->
                                            if (fileToDelete.exists()) {
                                                if (fileToDelete.delete()) {
                                                    deletedCount++
                                                    Log.d("DownloadedFilesScreen", "Deleted file: ${fileToDelete.name}")
                                                } else {
                                                    Log.e("DownloadedFilesScreen", "Failed to delete file: ${fileToDelete.name}")
                                                }
                                            }
                                        }
                                    }
                                    withContext(Dispatchers.Main) { // 回到主线程更新UI
                                        isDeleting = false // 🚀 【隐藏加载指示器】
                                        Toast.makeText(context, "成功删除 $deletedCount 个文件。", Toast.LENGTH_SHORT).show()
                                        loadFiles() // 刷新列表
                                    }
                                }
                            }
                        ) {
                            Text("删除")
                        }
                    },
                    dismissButton = {
                        Button(
                            onClick = {
                                showDeleteConfirmationDialog = false
                            }
                        ) {
                            Text("取消")
                        }
                    }
                )
            }

            // 🚀 【加载指示器】
            if (isDeleting) {
                // 半透明背景，防止用户点击其他地方
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.5f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "正在删除文件...",
                            color = Color.White,
                            modifier = Modifier.padding(top = 80.dp) // 调整位置
                        )
                    }
                }
            }
        }
    }
}