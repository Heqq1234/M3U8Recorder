package com.dl.m3u8recorder.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dl.m3u8recorder.llhls.StripchatApi
import kotlinx.coroutines.launch

/**
 * Stripchat / 白标站点 连接对话框
 *
 * 通过 OkHttp 获取页面 HTML，提取 __PRELOADED_STATE__，
 * 拼接 m3u8 URL，然后解析 Master Playlist 得到实际流地址。
 */
@Composable
fun StripchatConnectDialog(
    roomInfo: StripchatApi.RoomInfo,
    onDismiss: () -> Unit,
    onStreamUrlObtained: (videoUrl: String, audioUrl: String?, resolution: String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var statusText by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var detailText by remember { mutableStateOf("") }

    Dialog(onDismissRequest = { if (!isLoading) onDismiss() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Stripchat: ${roomInfo.slug}",
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 14.sp
                )
                Text(
                    text = "站点: ${roomInfo.baseUrl}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (statusText.isNotBlank()) {
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        color = if (isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (detailText.isNotBlank() && showDetails) {
                    Text(
                        text = detailText,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧：详情切换按钮
                    if (detailText.isNotBlank()) {
                        TextButton(
                            onClick = { showDetails = !showDetails },
                            enabled = !isLoading
                        ) {
                            Text(
                                if (showDetails) "隐藏详情" else "显示详情",
                                fontSize = 11.sp
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = onDismiss,
                            enabled = !isLoading
                        ) {
                            Text("关闭")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    isError = false
                                    statusText = "正在获取页面数据..."
                                    detailText = ""

                                    // Step 1: 获取页面，提取 __PRELOADED_STATE__
                                    val result = StripchatApi.fetchStreamUrl(roomInfo)

                                    if (!result.isSuccess) {
                                        isLoading = false
                                        isError = true
                                        statusText = result.errorMessage ?: "获取失败"
                                        return@launch
                                    }

                                    statusText = "解析 Master Playlist..."
                                    detailText = "Master M3U8:\n${result.m3u8Url}"

                                    // Step 2: 解析 Master Playlist → Media Playlist
                                    val refererDomain = roomInfo.baseUrl
                                    val resolved = StripchatApi.resolveMasterPlaylist(
                                        result.m3u8Url,
                                        refererDomain
                                    )

                                    isLoading = false
                                    if (resolved.isSuccess) {
                                        statusText = "获取成功！开始录制..."
                                        detailText = "Master M3U8:\n${result.m3u8Url}\n\n" +
                                            "Video: ${resolved.videoPlaylistUrl}"
                                        if (resolved.audioPlaylistUrl != null) {
                                            detailText += "\nAudio: ${resolved.audioPlaylistUrl}"
                                        }
                                        onStreamUrlObtained(
                                            resolved.videoPlaylistUrl,
                                            resolved.audioPlaylistUrl,
                                            resolved.resolution
                                        )
                                    } else {
                                        isError = true
                                        statusText = "解析失败: ${resolved.errorMessage}"
                                    }
                                }
                            },
                            enabled = !isLoading
                        ) {
                            Text("获取流地址")
                        }
                    }
                }
            }
        }
    }
}
