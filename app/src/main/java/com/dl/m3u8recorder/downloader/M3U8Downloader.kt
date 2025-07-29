package com.dl.m3u8recorder.downloader

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.dl.m3u8recorder.merger.FFmpegStreamMerger
import com.dl.m3u8recorder.model.DownloadTask
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

    suspend fun startDownloadWithRealtimeMerge(
        task: DownloadTask,
        merger: FFmpegStreamMerger,
        onProgress: ProgressCallback
    ) = withContext(Dispatchers.IO) {
        try {
            onProgress(0, "解析M3U8链接...")
            val tsUrls = parseM3U8(task.url)
            if (tsUrls.isEmpty()) {
                Log.e(TAG, "M3U8 无有效分片")
                onProgress(0, "M3U8解析失败或无分片")
                return@withContext
            }

            merger.startMerge()
            onProgress(0, "开始实时合并...")

            val totalTsCount = tsUrls.size
            var downloadedTsCount = 0

            for ((index, tsUrl) in tsUrls.withIndex()) {
                if (!isActive || task.isCancelled || task.isPaused) {
                    Log.w(TAG, "任务被取消或暂停，停止实时下载")
                    onProgress(task.progress, if (task.isCancelled) "已取消" else "已暂停")
                    break
                }

                try {
                    val tsData = downloadTs(tsUrl)
                    if (tsData != null) {
                        merger.feed(tsData)
                        downloadedTsCount++
                        val currentProgress = ((downloadedTsCount * 100.0) / totalTsCount).roundToInt()
                        onProgress(
                            currentProgress.coerceIn(0, 99),
                            "下载分片 ${downloadedTsCount}/${totalTsCount}"
                        )
                        Log.d(TAG, "下载完成分片[$index]: $tsUrl")
                    } else {
                        Log.w(TAG, "分片下载失败或为空: $tsUrl")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "下载分片异常: $tsUrl", e)
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

    private suspend fun downloadTs(url: String): ByteArray? = withContext(Dispatchers.IO) {
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
            Log.e(TAG, "下载 TS 异常: $url", e)
            return@withContext null
        }
    }

    suspend fun downloadAllTsThenMerge(
        task: DownloadTask,
        outputFile: File,
        onProgress: ProgressCallback
    ) = withContext(Dispatchers.IO) {
        onProgress(0, "解析M3U8链接...")
        val tsUrls = try {
            parseM3U8(task.url)
        } catch (e: Exception) {
            onProgress(0, "M3U8解析失败")
            Log.e(TAG, "M3U8 解析失败", e)
            return@withContext
        }

        if (tsUrls.isEmpty()) {
            onProgress(0, "M3U8无有效分片")
            return@withContext
        }

        val tempDir = File(outputFile.parentFile, "temp_${task.id}")
        if (!tempDir.exists()) tempDir.mkdirs()
        val tsFiles = mutableListOf<File>()

        val totalTsCount = tsUrls.size
        var downloadedTsCount = 0

        for ((index, tsUrl) in tsUrls.withIndex()) {
            if (!isActive || task.isCancelled || task.isPaused) {
                Log.w(TAG, "任务被取消或暂停，停止分片下载")
                onProgress(task.progress, if (task.isCancelled) "已取消" else "已暂停")
                break
            }

            try {
                val tsData = downloadTs(tsUrl)
                if (tsData != null) {
                    val tsFile = File(tempDir, "part_$index.ts")
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
                    Log.w(TAG, "分片下载失败或为空: $tsUrl")
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存分片异常: $tsUrl", e)
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

        val command = "-y -f concat -safe 0 -i ${concatFile.absolutePath} -c copy ${outputFile.absolutePath}"
        Log.d(TAG, "执行 FFmpeg 合并命令: $command")

        try {
            val session = FFmpegKit.execute(command)

            if (session.returnCode.isSuccess) {
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
}