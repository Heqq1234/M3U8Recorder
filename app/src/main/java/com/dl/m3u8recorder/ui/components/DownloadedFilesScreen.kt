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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadedFilesScreen(
    onFileClick: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val downloadedFiles = remember { mutableStateListOf<File>() }
    val selectedFiles = remember { mutableStateListOf<File>() }
    var inSelectionMode by remember { mutableStateOf(false) }

    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val loadFiles: () -> Unit = {
        val appDownloadsDir = Mp4OutputHelper.getAppSpecificDownloadsDir(context)
        Log.d("DownloadedFilesScreen", "Scanning directory: ${appDownloadsDir.absolutePath}")

        val files = appDownloadsDir.listFiles()?.filter {
            it.extension.equals("mp4", ignoreCase = true) ||
                    it.extension.equals("ts", ignoreCase = true)
        } ?: emptyList()

        downloadedFiles.clear()
        downloadedFiles.addAll(files.sortedByDescending { it.lastModified() })
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
                            selectedFiles.clear()
                            inSelectionMode = false
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "取消")
                        }
                    },
                    actions = {
                        if (selectedFiles.isNotEmpty()) {
                            IconButton(onClick = {
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
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 8.dp)) { // 缩小左右内边距
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
                                    .padding(vertical = 2.dp) // 缩小任务条之间的垂直间距
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
                                Column(modifier = Modifier.padding(8.dp)) { // 缩小卡片内部的内边距
                                    Text("文件名: ${file.name}", fontSize = 12.sp) // 缩小字体
                                    Spacer(modifier = Modifier.height(2.dp)) // 缩小间距

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("大小: ${"%.2f MB".format(file.length() / (1024.0 * 1024.0))}", fontSize = 10.sp, modifier = Modifier.weight(0.4f)) // 缩小字体
                                        Text(
                                            "时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))}",
                                            fontSize = 10.sp, // 缩小字体
                                            modifier = Modifier.weight(0.6f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showDeleteConfirmationDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showDeleteConfirmationDialog = false
                    },
                    title = { Text("确认删除？") },
                    text = { Text("您确定要删除选中的 ${selectedFiles.size} 个文件吗？此操作不可撤销。") },
                    confirmButton = {
                        Button(
                            onClick = {
                                showDeleteConfirmationDialog = false
                                isDeleting = true
                                coroutineScope.launch {
                                    var deletedCount = 0
                                    withContext(Dispatchers.IO) {
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
                                    withContext(Dispatchers.Main) {
                                        isDeleting = false
                                        Toast.makeText(context, "成功删除 $deletedCount 个文件。", Toast.LENGTH_SHORT).show()
                                        loadFiles()
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

            if (isDeleting) {
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
                            modifier = Modifier.padding(top = 80.dp)
                        )
                    }
                }
            }
        }
    }
}