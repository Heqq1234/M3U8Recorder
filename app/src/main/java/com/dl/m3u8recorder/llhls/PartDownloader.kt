package com.dl.m3u8recorder.llhls

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.TreeMap

/**
 * Partial Segment 下载器
 * 用于下载 LL-HLS 的 EXT-X-PART 片段
 * 请求头由 client 的 interceptor 统一设置
 */
class PartDownloader(
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "PartDownloader"
        private const val MAX_RETRIES = 3
        private const val MAX_CONCURRENT_DOWNLOADS = 128  // 全局最大并发：支持多任务同时下载

        // 全局信号量，限制所有任务的并发下载总数
        private val globalSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    }

    /**
     * 下载片段数据
     * @param uri 片段 URI
     * @return 片段数据，失败返回 null
     */
    suspend fun download(uri: String): ByteArray? = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                // 请求头由 client 的 interceptor 统一设置
                val request = Request.Builder()
                    .url(uri)
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        Log.v(TAG, "Downloaded: $uri (${bytes.size} bytes), attempt ${attempt + 1}")
                        return@withContext bytes
                    }
                } else {
                    Log.w(TAG, "HTTP ${response.code}: $uri, attempt ${attempt + 1}")
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Download exception attempt ${attempt + 1}: $uri", e)
            }

            // 指数退避重试，但更快
            if (attempt < MAX_RETRIES - 1) {
                Thread.sleep(100L * (attempt + 1)) // 100ms, 200ms, 300ms
            }
        }

        Log.e(TAG, "Failed to download after $MAX_RETRIES attempts: $uri", lastException)
        null
    }

    /**
     * 下载初始化片段 (EXT-X-MAP)
     */
    suspend fun downloadInitSegment(uri: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(uri)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    Log.d(TAG, "Downloaded init segment: $uri (${bytes.size} bytes)")
                    return@withContext bytes
                }
            }

            Log.e(TAG, "Failed to download init segment: $uri, HTTP ${response.code}")
            null
        } catch (e: IOException) {
            Log.e(TAG, "Exception downloading init segment: $uri", e)
            null
        }
    }

    /**
     * 检查片段是否可用（HEAD 请求）
     */
    suspend fun checkAvailable(uri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(uri)
                .head()
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 并行下载多个片段
     * @param uris 片段 URI 列表
     * @return 片段数据列表（与输入顺序对应，失败为 null）
     */
    suspend fun downloadParallel(uris: List<String>): List<ByteArray?> = coroutineScope {
        uris.map { uri ->
            async(Dispatchers.IO) { downloadWithLimit(uri) }
        }.awaitAll()
    }

    /**
     * 带并发限制的下载
     */
    private suspend fun downloadWithLimit(uri: String): ByteArray? =
        globalSemaphore.withPermit { download(uri) }

    /**
     * 并行下载多个片段，并按顺序写入（用于需要保证顺序的场景）
     * @param parts 部分片段列表（带序号）
     * @param onWrite 写入回调，保证顺序
     */
    suspend fun downloadParallelOrdered(
        parts: List<Pair<Int, String>>,
        onWrite: suspend (index: Int, data: ByteArray?) -> Unit
    ) = coroutineScope {
        if (parts.isEmpty()) return@coroutineScope

        // 有序缓冲区：序号 -> 数据
        val buffer = TreeMap<Int, ByteArray?>()
        var nextWriteIndex = 0

        // 并行下载
        parts.map { (index, uri) ->
            async(Dispatchers.IO) {
                val data = downloadWithLimit(uri)
                synchronized(buffer) {
                    buffer[index] = data
                    // 尝试按顺序写入
                    while (buffer.containsKey(nextWriteIndex)) {
                        val toWrite = buffer.remove(nextWriteIndex)
                        onWrite(nextWriteIndex, toWrite)
                        nextWriteIndex++
                    }
                }
            }
        }.awaitAll()
    }
}
