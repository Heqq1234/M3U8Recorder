package com.dl.m3u8recorder.downloader

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.dl.m3u8recorder.merger.FFmpegStreamMerger
import com.dl.m3u8recorder.model.DownloadTask
import com.dl.m3u8recorder.parser.M3U8ParserImpl
import com.dl.m3u8recorder.parser.M3U8Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

typealias ProgressCallback = (progress: Int, statusMessage: String) -> Unit

class M3U8Downloader(
    private val client: OkHttpClient = OkHttpClient()
) {
    private val TAG = "M3U8Downloader"
    private val parser = M3U8ParserImpl()

    suspend fun startDownloadWithRealtimeMerge(
        task: DownloadTask,
        merger: FFmpegStreamMerger,
        onProgress: ProgressCallback
    ) = withContext(Dispatchers.IO) {
        try {
            // Phase 2: 使用有效 URL（变体 URL 或原始 URL）
            val effectiveUrl = task.getEffectiveUrl()
            onProgress(0, "解析M3U8链接...")

            val playlist = fetchAndParsePlaylist(effectiveUrl)

            val segments = when (playlist) {
                is M3U8Playlist.Media -> playlist.segments
                is M3U8Playlist.Master -> {
                    // 如果还是 Master，说明解析过程中出了问题
                    onProgress(0, "无法解析媒体播放列表")
                    return@withContext
                }
            }

            if (segments.isEmpty()) {
                Log.e(TAG, "M3U8 无有效分片")
                onProgress(0, "M3U8解析失败或无分片")
                return@withContext
            }

            merger.startMerge()
            onProgress(0, "开始实时合并...")

            val totalTsCount = segments.size
            var downloadedTsCount = 0

            for ((index, segment) in segments.withIndex()) {
                if (!isActive || task.isCancelled || task.isPaused) {
                    Log.w(TAG, "任务被取消或暂停，停止实时下载")
                    onProgress(task.progress, if (task.isCancelled) "已取消" else "已暂停")
                    break
                }

                try {
                    val tsData = downloadSegment(segment.uri)
                    if (tsData != null) {
                        merger.feed(tsData)
                        downloadedTsCount++
                        val currentProgress = ((downloadedTsCount * 100.0) / totalTsCount).roundToInt()
                        onProgress(
                            currentProgress.coerceIn(0, 99),
                            "下载分片 ${downloadedTsCount}/${totalTsCount}"
                        )
                        Log.d(TAG, "下载完成分片[$index]: ${segment.uri}")
                    } else {
                        Log.w(TAG, "分片下载失败或为空: ${segment.uri}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "下载分片异常: ${segment.uri}", e)
                    onProgress(task.progress, "下载分片失败: ${index + 1}/${totalTsCount}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "实时下载或解析过程异常", e)
            onProgress(task.progress, "下载错误: ${e.localizedMessage ?: "未知错误"}")
        } finally {
            merger.finish()
            Log.d(TAG, "实时合并结束")
        }
    }

    suspend fun downloadAllTsThenMerge(
        task: DownloadTask,
        outputFile: File,
        onProgress: ProgressCallback
    ) = withContext(Dispatchers.IO) {
        // Phase 2: 使用有效 URL
        val effectiveUrl = task.getEffectiveUrl()
        onProgress(0, "解析M3U8链接...")

        val playlist = try {
            fetchAndParsePlaylist(effectiveUrl)
        } catch (e: Exception) {
            onProgress(0, "M3U8解析失败")
            Log.e(TAG, "M3U8 解析失败", e)
            return@withContext
        }

        val segments = when (playlist) {
            is M3U8Playlist.Media -> playlist.segments
            is M3U8Playlist.Master -> {
                onProgress(0, "无法解析媒体播放列表")
                return@withContext
            }
        }

        if (segments.isEmpty()) {
            onProgress(0, "M3U8无有效分片")
            return@withContext
        }

        val tempDir = File(outputFile.parentFile, "temp_${task.id}")
        if (!tempDir.exists()) tempDir.mkdirs()
        val tsFiles = mutableListOf<File>()

        val totalTsCount = segments.size
        var downloadedTsCount = 0

        for ((index, segment) in segments.withIndex()) {
            if (!isActive || task.isCancelled || task.isPaused) {
                Log.w(TAG, "任务被取消或暂停，停止分片下载")
                onProgress(task.progress, if (task.isCancelled) "已取消" else "已暂停")
                break
            }

            try {
                val tsData = downloadSegment(segment.uri)
                if (tsData != null) {
                    val extension = getExtension(segment.uri)
                    val tsFile = File(tempDir, "part_$index$extension")
                    tsFile.writeBytes(tsData)
                    tsFiles.add(tsFile)
                    downloadedTsCount++

                    val downloadProgress = ((downloadedTsCount * 90.0) / totalTsCount).roundToInt()
                    onProgress(
                        downloadProgress.coerceIn(0, 90),
                        "下载分片 ${downloadedTsCount}/${totalTsCount}"
                    )
                    Log.d(TAG, "下载完成分片并保存: ${tsFile.name}")
                } else {
                    Log.w(TAG, "分片下载失败或为空: ${segment.uri}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存分片异常: ${segment.uri}", e)
                onProgress(task.progress, "保存分片失败: ${index + 1}/${totalTsCount}")
            }
        }

        if (tsFiles.isEmpty() || task.isCancelled || task.isPaused) {
            Log.w(TAG, "无分片可合并或任务被中断")
            if (tempDir.exists()) tempDir.deleteRecursively()
            return@withContext
        }

        onProgress(90, "开始合并分片...")

        val concatFile = File(tempDir, "concat.txt").apply {
            writeText(tsFiles.joinToString("\n") { "file '${it.absolutePath}'" })
        }

        // Phase 1: 添加时间戳修正参数解决音画不同步问题
        val command = listOf(
            "-y",
            "-fflags", "+genpts",
            "-f", "concat",
            "-safe", "0",
            "-i", concatFile.absolutePath,
            "-avoid_negative_ts", "make_zero",
            "-c", "copy",
            "-movflags", "+faststart",
            outputFile.absolutePath
        ).joinToString(" ")
        Log.d(TAG, "执行 FFmpeg 合并命令: $command")

        try {
            val session = FFmpegKit.execute(command)

            if (ReturnCode.isSuccess(session.returnCode)) {
                onProgress(100, "合并成功")
                Log.d(TAG, "合并成功，输出: ${outputFile.absolutePath}")
            } else {
                val failLog = session.logsAsString
                onProgress(task.progress, "合并失败")
                Log.e(TAG, "合并失败: ${session.failStackTrace}\nLog: $failLog")
                throw IOException("FFmpeg 合并失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "FFmpeg 合并异常", e)
            onProgress(task.progress, "合并异常: ${e.localizedMessage ?: "未知错误"}")
            throw e
        } finally {
            concatFile.delete()
            tsFiles.forEach { it.delete() }
            tempDir.deleteRecursively()
            Log.d(TAG, "临时文件清理完毕")
        }
    }

    /**
     * 获取并解析播放列表
     */
    private suspend fun fetchAndParsePlaylist(url: String): M3U8Playlist = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val content = response.body?.string() ?: throw IOException("M3U8响应体为空")
        parser.parse(content, url)
    }

    /**
     * 下载片段
     */
    private suspend fun downloadSegment(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                return@withContext response.body?.bytes()
            } else {
                Log.w(TAG, "请求失败: $url code=${response.code}")
                return@withContext null
            }
        } catch (e: IOException) {
            Log.e(TAG, "下载片段异常: $url", e)
            return@withContext null
        }
    }

    /**
     * 获取文件扩展名
     */
    private fun getExtension(url: String): String {
        return when {
            url.contains(".m4s", ignoreCase = true) -> ".m4s"
            url.contains(".mp4", ignoreCase = true) -> ".mp4"
            url.contains(".m4a", ignoreCase = true) -> ".m4a"
            url.contains(".aac", ignoreCase = true) -> ".aac"
            else -> ".ts"
        }
    }

    // 保留旧方法以兼容
    private suspend fun parseM3U8(m3u8Url: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(m3u8Url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw IOException("M3U8响应体为空")

            val baseUrl = m3u8Url.substringBeforeLast("/") + "/"

            return@withContext body.lines()
                .filter { !it.startsWith("#") && it.trim().isNotEmpty() }
                .map {
                    if (it.startsWith("http")) it.trim()
                    else baseUrl + it.trim()
                }
        } catch (e: IOException) {
            Log.e(TAG, "解析 M3U8 失败", e)
            throw e
        }
    }

    private suspend fun downloadTs(url: String): ByteArray? = downloadSegment(url)
}
