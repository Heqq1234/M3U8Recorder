package com.dl.m3u8recorder.llhls

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

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

        private const val MAX_GLOBAL_CONCURRENT = 24
        private const val MAX_PER_TASK_CONCURRENT = 6

        private val globalSemaphore = Semaphore(MAX_GLOBAL_CONCURRENT)
    }

    private val taskSemaphores = ConcurrentHashMap<String, Semaphore>()

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
                } else if (response.code in 400..499) {
                    // 404/429 可能是临时的（live edge 还没准备好 / 限流），重试
                    // 其他 4xx（400, 401, 403, 405+）永久跳过
                    if (response.code == 404 || response.code == 429) {
                        Log.w(TAG, "HTTP ${response.code}: $uri, 可能临时错误，重试 (${attempt + 1}/${MAX_RETRIES})")
                        lastException = IOException("HTTP ${response.code}")
                    } else {
                        Log.w(TAG, "HTTP ${response.code}: $uri, 跳过 (永久 4xx 错误)")
                        return@withContext null
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
     * @param taskId 任务 ID，用于每任务并发限制
     * @param uris 片段 URI 列表
     * @return 片段数据列表（与输入顺序对应，失败为 null）
     */
    suspend fun downloadParallel(taskId: String, uris: List<String>): List<ByteArray?> = coroutineScope {
        val taskSemaphore = taskSemaphores.computeIfAbsent(taskId) {
            Semaphore(MAX_PER_TASK_CONCURRENT)
        }
        uris.map { uri ->
            async(Dispatchers.IO) {
                taskSemaphore.withPermit {
                    globalSemaphore.withPermit {
                        download(uri)
                    }
                }
            }
        }.awaitAll()
    }

    /**
     * 并行下载多个片段，并按顺序写入（用于需要保证顺序的场景）
     * @param taskId 任务 ID，用于每任务并发限制
     * @param parts 部分片段列表（带序号）
     * @param onWrite 写入回调，保证顺序
     */
    suspend fun downloadParallelOrdered(
        taskId: String,
        parts: List<Pair<Int, String>>,
        onWrite: suspend (index: Int, data: ByteArray?) -> Unit
    ) = coroutineScope {
        if (parts.isEmpty()) return@coroutineScope

        val buffer = TreeMap<Int, ByteArray?>()
        var nextWriteIndex = 0
        val mutex = Mutex()
        val taskSemaphore = taskSemaphores.computeIfAbsent(taskId) {
            Semaphore(MAX_PER_TASK_CONCURRENT)
        }

        parts.map { (index, uri) ->
            async(Dispatchers.IO) {
                val data = taskSemaphore.withPermit {
                    globalSemaphore.withPermit {
                        download(uri)
                    }
                }
                mutex.withLock {
                    buffer[index] = data
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
