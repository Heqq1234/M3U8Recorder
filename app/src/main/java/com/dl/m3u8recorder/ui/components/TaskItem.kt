import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dl.m3u8recorder.model.DownloadTask
@Composable
fun TaskItem(
    task: DownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    progress: Int,
    statusMessage: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 显示文件名和其他任务信息
            Text("文件名: ${task.fileName}")
            Text("链接: ${task.url}")
            Text("合并方式: ${if (task.realtimeMerge) "边下载边合并" else "下载完再合并"}")
            Text("是否直播: ${if (task.isLive) "是" else "否"}")

            // 状态信息，显示 task.statusMessage 或自定义状态
            Text("状态: ${
                task.statusMessage?.ifBlank {
                    when {
                        task.isCancelled -> "已取消"
                        task.isPaused -> "已暂停"
                        else -> "下载中"
                    }
                } ?: "未知状态"
            }")

            // 显示下载进度
            if (task.progress in 0..100) {
                LinearProgressIndicator(
                    progress = task.progress / 100f,  // 进度显示在 0 到 1 的范围内
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }

            // 操作按钮
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // 显示恢复/暂停按钮
                if (task.isPaused) {
                    Button(onClick = onResume) { Text("恢复") }
                } else if (!task.isCancelled) {
                    Button(onClick = onPause) { Text("暂停") }
                }
                // 取消按钮
                Button(onClick = onCancel) { Text("取消") }
                // 重试按钮
                if (task.isCancelled || task.statusMessage == "下载失败") {
                    Button(onClick = onRetry) { Text("重试") }
                }
            }
        }
    }
}
