package com.dl.m3u8recorder.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color // 导入 Color，用于进度条颜色
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dl.m3u8recorder.model.DownloadTask
import java.text.DecimalFormat // 导入 DecimalFormat，用于文件大小格式化
import java.util.concurrent.TimeUnit // 导入 TimeUnit，用于时间格式化

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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp), // 增加卡片垂直间距，让卡片之间更分明
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) { // 增加卡片内部填充
            Text(
                text = "文件名: ${task.fileName}",
                fontSize = 16.sp, // 稍微增大字体
                style = MaterialTheme.typography.titleMedium // 应用主题中的标题样式
            )
            Spacer(modifier = Modifier.height(4.dp))

            // --- 合并方式和是否直播放在一行 ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "合并方式: ${if (task.realtimeMerge) "边下载边合并" else "下载完再合并"}",
                    fontSize = 13.sp, // 稍微增大字体
                    style = MaterialTheme.typography.bodySmall // 应用主题中的正文小字样式
                )
                Spacer(modifier = Modifier.width(16.dp)) // 增加两个信息之间的间距
                Text(
                    text = "是否直播: ${if (task.isLive) "是" else "否"}",
                    fontSize = 13.sp, // 稍微增大字体
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(4.dp))

            // 优化状态显示
            Text(
                text = "状态: ${currentStatusMessage.takeIf { it.isNotBlank() && it != "准备中" }
                    ?: when {
                        currentIsCancelled -> "已取消"
                        currentIsPaused -> "已暂停"
                        currentProgress == 100 -> "下载完成"
                        currentProgress in 1..99 -> "下载中"
                        else -> "准备中"
                    }}",
                fontSize = 13.sp, // 稍微增大字体
                style = MaterialTheme.typography.labelMedium, // 应用主题中的标签样式
                color = MaterialTheme.colorScheme.secondary // 状态文本使用次要颜色
            )
            Spacer(modifier = Modifier.height(8.dp)) // 增加间距

            // --- 进度条优化：停止时满格，正在下载时无限加载 ---
            if (currentStatusMessage == "下载中" && currentProgress < 100 && !currentIsPaused && !currentIsCancelled) {
                // 正在下载时显示无限加载进度条
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp), // 进度条高度变细
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                // 其他状态（完成、暂停、取消等）显示固定进度条，根据情况是否为100%
                val progressValue = when {
                    currentProgress == 100 -> 1f
                    currentIsCancelled -> 1f // 已取消，进度条也显示满
                    currentIsPaused -> 1f // 已暂停，进度条也显示满
                    else -> currentProgress / 100f
                }
                LinearProgressIndicator(
                    progress = { progressValue },
                    modifier = Modifier.fillMaxWidth().height(4.dp), // 进度条高度变细
                    color = if (currentIsCancelled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary, // 取消时使用 outline 颜色
                    trackColor = MaterialTheme.colorScheme.surfaceVariant // 进度条背景色
                )
            }
            Spacer(modifier = Modifier.height(4.dp)) // 增加间距

            // --- 进度条尾部：已下载文件大小及时间 ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${currentProgress}%",
                    fontSize = 11.sp, // 字体略大一点
                    style = MaterialTheme.typography.labelSmall // 标签小字样式
                )
                Text(
                    text = "${formatFileSize(downloadedSize)} / ${formatElapsedTime(elapsedTime)}",
                    fontSize = 11.sp, // 字体略大一点
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(modifier = Modifier.height(8.dp)) // 增加间距

            // --- 按钮行优化：使用 FlowRow 实现自动换行和更好的间距控制 ---
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp), // 按钮之间的水平间距
                verticalArrangement = Arrangement.spacedBy(8.dp) // 按钮之间的垂直间距（如果换行）
            ) {
                // 统一的按钮样式
                val buttonModifier = Modifier.height(36.dp) // 统一设置按钮高度
                val buttonContentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp) // 更舒适的内边距
                val buttonTextSize = 13.sp // 统一按钮文本字体大小

                // 始终显示暂停/恢复/取消/停止录制按钮（如果适用）
                if (currentIsCancelled) {
                    Button(
                        onClick = { onRetry(task.id) },
                        modifier = buttonModifier,
                        contentPadding = buttonContentPadding
                    ) { Text("重试", fontSize = buttonTextSize) }

                    // OutlinedButton( // 删除按钮现在统一放在最后
                    //     onClick = { showDeleteDialog = true },
                    //     modifier = buttonModifier,
                    //     contentPadding = buttonContentPadding
                    // ) { Text("删除", fontSize = buttonTextSize) }
                } else if (currentIsPaused) {
                    Button(
                        onClick = { onResume(task.id) },
                        modifier = buttonModifier,
                        contentPadding = buttonContentPadding
                    ) { Text("恢复", fontSize = buttonTextSize) }

                    // OutlinedButton( // 删除按钮现在统一放在最后
                    //     onClick = { showDeleteDialog = true },
                    //     modifier = buttonModifier,
                    //     contentPadding = buttonContentPadding
                    // ) { Text("取消/删除", fontSize = buttonTextSize) }
                } else if (currentProgress < 100 && currentStatusMessage == "下载中") {
                    // 正在下载中
                    Button(
                        onClick = { onPause(task.id) },
                        modifier = buttonModifier,
                        contentPadding = buttonContentPadding
                    ) { Text("暂停", fontSize = buttonTextSize) }

                    // OutlinedButton( // 删除按钮现在统一放在最后
                    //     onClick = { showDeleteDialog = true },
                    //     modifier = buttonModifier,
                    //     contentPadding = buttonContentPadding
                    // ) { Text("取消/删除", fontSize = buttonTextSize) }

                    if (task.isLive) {
                        OutlinedButton( // 停止录制也用 OutlinedButton
                            onClick = { onStopRecording(task.id) },
                            modifier = buttonModifier,
                            contentPadding = buttonContentPadding
                        ) { Text("停止录制", fontSize = buttonTextSize) }
                    }
                } else if (currentProgress == 100) { // 进度达到 100% (下载完成)
                    // 🚀 【修改】使用 Box 包裹并居中，解决 Modifier.align 错误
                    Box(
                        modifier = Modifier.fillMaxWidth(), // 让 Box 占据 FlowRow 的全部宽度
                        contentAlignment = Alignment.CenterStart // Box 的内容从左侧开始排列
                    ) {
                        Text(
                            text = "任务完成",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary
                            // 移除这里的 paddingEnd，让删除按钮靠近
                        )
                    }
                }

                // --- 统一的删除按钮 ---
                // 不管任务处于什么状态，只要它在列表中就应该可以被删除
                // 只有当任务状态不是“已完成”时，才显示为“取消/删除”
                // 否则显示为“删除”
                val deleteButtonText = if (currentProgress < 100 && !currentIsCancelled) "取消/删除" else "删除"
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    modifier = buttonModifier,
                    contentPadding = buttonContentPadding
                ) { Text(deleteButtonText, fontSize = buttonTextSize) }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除任务?") },
            text = { Text("您确定要删除任务 '${task.fileName}' 吗？此操作将停止正在进行的下载/录制，并删除已下载的临时 .ts 文件。已转换的 .mp4 文件不会被删除。") }, // 更新提示信息
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
                OutlinedButton( // 取消按钮使用 OutlinedButton
                    onClick = { showDeleteDialog = false },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("取消")
                }
            }
        )
    }
}

// 辅助函数：格式化文件大小，始终显示为 MB
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0.00 MB" // 确保 0 字节时也显示为 MB
    val megabytes = bytes / (1024.0 * 1024.0) // 将字节转换为 MB
    return DecimalFormat("#,##0.00").format(megabytes) + " MB" // 格式化为两位小数并加上 MB 单位
}

// 辅助函数：格式化已用时间 (HH:mm:ss)
fun formatElapsedTime(milliseconds: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(milliseconds)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds) % 60
    return String.format("%02d:%02d:%02d", hours, minutes, seconds)
}