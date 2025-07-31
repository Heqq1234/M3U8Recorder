package com.dl.m3u8recorder.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dl.m3u8recorder.model.DownloadTask
import java.text.DecimalFormat
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskItem(
    task: DownloadTask,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onDeleteConfirmed: (String) -> Unit,
    onStopRecording: (String) -> Unit
) {
    val currentProgress by task._progress
    val currentStatusMessage by task._statusMessage
    val currentIsPaused by task._isPaused
    val currentIsCancelled by task._isCancelled
    val downloadedSize by task._downloadedSize
    val elapsedTime by task._elapsedTime

    var showDeleteDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
                .clickable { expanded = !expanded }
                .padding(8.dp)
        ) {
            // --- 简略视图 ---
            Text(
                text = "文件名: ${task.fileName}",
                fontSize = 14.sp,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))

            // 进度条和已下载大小放在同一行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val progressValue = when {
                    currentProgress == 100 -> 1f
                    currentIsCancelled -> 1f
                    currentIsPaused -> 1f
                    else -> currentProgress / 100f
                }

                if (currentProgress < 100 && !currentIsPaused && !currentIsCancelled && currentStatusMessage == "下载中") {
                    LinearProgressIndicator(
                        modifier = Modifier.weight(1f).height(3.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progressValue },
                        modifier = Modifier.weight(1f).height(3.dp),
                        color = if (currentIsCancelled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                Text(
                    text = "${formatFileSize(downloadedSize)}",
                    fontSize = 10.sp,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }


            // --- 详细视图 ---
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                // 第一行：状态、合并方式、是否直播
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "状态: ${currentStatusMessage.takeIf { it.isNotBlank() && it != "准备中" }
                            ?: when {
                                currentIsCancelled -> "已取消"
                                currentIsPaused -> "已暂停"
                                currentProgress == 100 -> "下载完成"
                                currentProgress in 1..99 -> "下载中"
                                else -> "准备中"
                            }}",
                        fontSize = 12.sp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "合并方式: ${if (task.realtimeMerge) "实时" else "后合"}",
                        fontSize = 11.sp,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "是否直播: ${if (task.isLive) "是" else "否"}",
                        fontSize = 11.sp,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 第二行：已用时间、已下载大小
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "已用时间: ${formatElapsedTime(elapsedTime)}",
                        fontSize = 11.sp,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = "已下载: ${formatFileSize(downloadedSize)}",
                        fontSize = 11.sp,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 第三行：所有按钮
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val buttonModifier = Modifier.height(32.dp)
                    val buttonContentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    val buttonTextSize = 12.sp

                    if (currentIsCancelled) {
                        Button(
                            onClick = { onRetry(task.id) },
                            modifier = buttonModifier,
                            contentPadding = buttonContentPadding
                        ) { Text("重试", fontSize = buttonTextSize) }
                    } else if (currentIsPaused) {
                        Button(
                            onClick = { onResume(task.id) },
                            modifier = buttonModifier,
                            contentPadding = buttonContentPadding
                        ) { Text("恢复", fontSize = buttonTextSize) }
                    } else if (currentProgress < 100 && currentStatusMessage == "下载中") {
                        Button(
                            onClick = { onPause(task.id) },
                            modifier = buttonModifier,
                            contentPadding = buttonContentPadding
                        ) { Text("暂停", fontSize = buttonTextSize) }
                        if (task.isLive) {
                            OutlinedButton(
                                onClick = { onStopRecording(task.id) },
                                modifier = buttonModifier,
                                contentPadding = buttonContentPadding
                            ) { Text("停止录制", fontSize = buttonTextSize) }
                        }
                    } else if (currentProgress == 100) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = "任务完成",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    val deleteButtonText = if (currentProgress < 100 && !currentIsCancelled) "取消/删除" else "删除"
                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        modifier = buttonModifier,
                        contentPadding = buttonContentPadding
                    ) { Text(deleteButtonText, fontSize = buttonTextSize) }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除任务?") },
            text = { Text("您确定要删除任务 '${task.fileName}' 吗？此操作将停止正在进行的下载/录制，并删除已下载的临时 .ts 文件。已转换的 .mp4 文件不会被删除。") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteConfirmed(task.id)
                        showDeleteDialog = false
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteDialog = false },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("取消")
                }
            }
        )
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0.00 MB"
    val megabytes = bytes / (1024.0 * 1024.0)
    return DecimalFormat("#,##0.00").format(megabytes) + " MB"
}

fun formatElapsedTime(milliseconds: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(milliseconds)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds) % 60
    return String.format("%02d:%02d:%02d", hours, minutes, seconds)
}