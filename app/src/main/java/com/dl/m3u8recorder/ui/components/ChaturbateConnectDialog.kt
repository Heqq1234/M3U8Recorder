package com.dl.m3u8recorder.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dl.m3u8recorder.llhls.ChaturbateApi
import kotlinx.coroutines.launch

/**
 * Chaturbate 连接对话框
 *
 * 直接通过 OkHttp 调用 Chaturbate API 获取流地址。
 */
@Composable
fun ChaturbateConnectDialog(
    roomSlug: String,
    onDismiss: () -> Unit,
    onStreamUrlObtained: (videoUrl: String, audioUrl: String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var statusText by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = { if (!isLoading) onDismiss() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Chaturbate: $roomSlug",
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (statusText.isNotBlank()) {
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        color = if (isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                                statusText = "正在获取流地址..."

                                val result = ChaturbateApi.fetchStreamUrl(roomSlug)

                                isLoading = false
                                if (result.isSuccess) {
                                    statusText = "解析播放列表..."

                                    // API 返回的是 Master Playlist，解析得到最佳画质的 Media Playlist URL
                                    val resolved = ChaturbateApi.resolveMasterPlaylist(result.m3u8Url)
                                    if (resolved.isSuccess) {
                                        statusText = "获取成功，开始录制..."
                                        onStreamUrlObtained(resolved.videoPlaylistUrl, resolved.audioPlaylistUrl)
                                    } else {
                                        isError = true
                                        statusText = "解析失败: ${resolved.errorMessage}"
                                    }
                                } else {
                                    isError = true
                                    statusText = result.errorMessage ?: "获取失败"
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
