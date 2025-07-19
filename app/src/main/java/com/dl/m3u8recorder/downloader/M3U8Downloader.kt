package com.dl.m3u8recorder.downloader

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.dl.m3u8recorder.merger.FFmpegStreamMerger
import com.dl.m3u8recorder.model.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

class M3U8Downloader(
    private val client: OkHttpClient = OkHttpClient()
) {
    private val TAG = "M3U8Downloader"

    suspend fun startDownloadWithRealtimeMerge(
        task: DownloadTask,
        merger: FFmpegStreamMerger
    ) = withContext(Dispatchers.IO) {
        try {
            val tsUrls = parseM3U8(task.url)
            if (tsUrls.isEmpty()) {
                Log.e(TAG, "M3U8 无有效分片")
                return@withContext
            }

            merger.startMerge()

            for ((index, tsUrl) in tsUrls.withIndex()) {
                if (task.isCancelled) {
                    Log.w(TAG, "任务被取消")
                    break
                }

                try {
                    val tsData = downloadTs(tsUrl)
                    if (tsData != null) {
                        merger.feed(tsData)
                        Log.d(TAG, "下载完成分片[$index]: $tsUrl")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "下载失败: $tsUrl", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "解析或下载过程异常", e)
        } finally {
            merger.finish()
        }
    }

    private suspend fun parseM3U8(m3u8Url: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(m3u8Url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()

            val baseUrl = m3u8Url.substringBeforeLast("/") + "/"

            return@withContext body.lines()
                .filter { !it.startsWith("#") && it.trim().isNotEmpty() }
                .map {
                    if (it.startsWith("http")) it.trim()
                    else baseUrl + it.trim()
                }
        } catch (e: IOException) {
            Log.e(TAG, "解析 M3U8 失败", e)
            return@withContext emptyList()
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
    suspend fun downloadAllTsThenMerge(task: DownloadTask, outputFile: File) = withContext(Dispatchers.IO) {
        val tsUrls = parseM3U8(task.url)
        if (tsUrls.isEmpty()) return@withContext

        val tempDir = File(outputFile.parentFile, task.id)
        if (!tempDir.exists()) tempDir.mkdirs()
        val tsFiles = mutableListOf<File>()

        for ((index, tsUrl) in tsUrls.withIndex()) {
            if (task.isCancelled || task.isPaused) break

            val tsData = downloadTs(tsUrl) ?: continue
            val tsFile = File(tempDir, "part_$index.ts")
            tsFile.writeBytes(tsData)
            tsFiles.add(tsFile)
        }

        if (tsFiles.isEmpty()) return@withContext

        // 创建 concat.txt
        val concatFile = File(tempDir, "concat.txt").apply {
            writeText(tsFiles.joinToString("\n") { "file '${it.absolutePath}'" })
        }

        // 构造 FFmpeg 命令
        val command = "-y -f concat -safe 0 -i ${concatFile.absolutePath} -c copy ${outputFile.absolutePath}"
        Log.d("M3U8Downloader", "执行 FFmpeg 合并命令: $command")

        // 执行合并
        val session = FFmpegKit.execute(command)

        if (session.returnCode.isSuccess) {
            Log.d("M3U8Downloader", "合并成功，输出: ${outputFile.absolutePath}")
        } else {
            Log.e("M3U8Downloader", "合并失败: ${session.failStackTrace}")
        }

        // 清理临时文件
        concatFile.delete()
        tsFiles.forEach { it.delete() }
        tempDir.deleteRecursively()
    }
}