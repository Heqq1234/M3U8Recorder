package com.dl.m3u8recorder.llhls

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *
 * 优化策略：
 * 1. 复用 OkHttp 的连接池和 Dispatcher，不再额外加 Semaphore 限流
 * 2. 下载完成立即回调（流式处理），不等整批完成
 * 3. 连接复用：同一 host 的请求共享连接（OkHttp ConnectionPool 自动处理）
 */
class PartDownloader(
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "PartDownloader"
        private const val MAX_RETRIES = 3

        // 注意：不再使用自定义 Semaphore 限流，直接依赖 OkHttp Dispatcher
        // DownloadManager 中已配置：maxRequests=128, maxRequestsPerHost=20
    }

    /** 一个待下载项：序号、URL、所属 segment 的墙钟 PDT(可空) */
    data class DownloadItem(val index: Int, val uri: String, val pdtMs: Long? = null)

    /**
     * 下载片段数据（内部使用，支持快速失败和连接复用）
     * 注意：调用方应已在 Dispatchers.IO 上，不再包裹 withContext
     * @param uri 片段 URI
     * @param taskId 任务 ID，用于日志追踪
     * @return 片段数据，失败返回 null
     */
    private fun downloadInternal(uri: String, taskId: String = ""): ByteArray? {
        val startMs = System.currentTimeMillis()
        var lastException: Exception? = null
        val tagPrefix = if (taskId.isNotEmpty()) "[$taskId] " else ""

        repeat(MAX_RETRIES) { attempt ->
            try {
                // 请求头由 client 的 interceptor 统一设置
                val request = Request.Builder()
                    .url(uri)
                    .build()

                val response = client.newCall(request).execute()
                val elapsed = System.currentTimeMillis() - startMs

                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        Log.d(TAG, "${tagPrefix}Downloaded: ${uri.takeLast(60)} (${bytes.size} bytes, ${elapsed}ms), attempt ${attempt + 1}")
                        return bytes
                    } else {
                        Log.w(TAG, "${tagPrefix}Empty body: ${uri.takeLast(60)}, attempt ${attempt + 1}")
                    }
                } else if (response.code in 400..499) {
                    // 404/429 可能是临时的（live edge 还没准备好 / 限流），重试
                    // 其他 4xx（400, 401, 403, 405+）永久跳过
                    if (response.code == 404 || response.code == 429) {
                        Log.w(TAG, "${tagPrefix}HTTP ${response.code}: ${uri.takeLast(60)}, 可能临时错误，重试 (${attempt + 1}/${MAX_RETRIES})")
                        lastException = IOException("HTTP ${response.code}")
                    } else {
                        Log.w(TAG, "${tagPrefix}HTTP ${response.code}: ${uri.takeLast(60)}, 跳过 (永久 4xx 错误)")
                        return null
                    }
                } else {
                    Log.w(TAG, "${tagPrefix}HTTP ${response.code}: ${uri.takeLast(60)}, attempt ${attempt + 1}, elapsed=${elapsed}ms")
                }
            } catch (e: Exception) {
                lastException = e
                val elapsed = System.currentTimeMillis() - startMs
                Log.w(TAG, "${tagPrefix}Download exception attempt ${attempt + 1}: ${uri.takeLast(60)}, elapsed=${elapsed}ms", e)
            }

            // 指数退避重试，但更快
            if (attempt < MAX_RETRIES - 1) {
                Thread.sleep(100L * (attempt + 1)) // 100ms, 200ms, 300ms
            }
        }

        val totalElapsed = System.currentTimeMillis() - startMs
        Log.e(TAG, "${tagPrefix}Failed to download after $MAX_RETRIES attempts (${totalElapsed}ms): ${uri.takeLast(60)}", lastException)
        return null
    }

    /**
     * 下载初始化片段 (EXT-X-MAP)
     */
    suspend fun downloadInitSegment(uri: String, taskId: String = ""): ByteArray? = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val tagPrefix = if (taskId.isNotEmpty()) "[$taskId] " else ""
        try {
            val request = Request.Builder()
                .url(uri)
                .build()

            val response = client.newCall(request).execute()
            val elapsed = System.currentTimeMillis() - startMs

            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    Log.d(TAG, "${tagPrefix}Downloaded init segment: ${uri.takeLast(60)} (${bytes.size} bytes, ${elapsed}ms)")
                    return@withContext bytes
                } else {
                    Log.w(TAG, "${tagPrefix}Init segment empty body: ${uri.takeLast(60)}")
                }
            }

            Log.e(TAG, "${tagPrefix}Failed to download init segment: ${uri.takeLast(60)}, HTTP ${response.code}, elapsed=${elapsed}ms")
            null
        } catch (e: IOException) {
            val elapsed = System.currentTimeMillis() - startMs
            Log.e(TAG, "${tagPrefix}Exception downloading init segment: ${uri.takeLast(60)}, elapsed=${elapsed}ms", e)
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
     * 并行下载多个片段（已废弃，保留兼容）
     * 直接使用 OkHttp Dispatcher 管理并发，不再额外限流
     */
    suspend fun downloadParallel(taskId: String, uris: List<String>): List<ByteArray?> = coroutineScope {
        if (uris.isEmpty()) return@coroutineScope emptyList<ByteArray?>()
        val startMs = System.currentTimeMillis()
        Log.d(TAG, "[$taskId] Parallel download start: ${uris.size} uris")

        val results = uris.map { uri ->
            async(Dispatchers.IO) {
                downloadInternal(uri, taskId)
            }
        }.awaitAll()

        val successCount = results.count { it != null }
        val totalBytes = results.filterNotNull().sumOf { it.size }
        val elapsed = System.currentTimeMillis() - startMs
        Log.d(TAG, "[$taskId] Parallel download done: $successCount/${uris.size} success, ${totalBytes} bytes, ${elapsed}ms")
        results
    }

    /**
     * 并行下载多个片段，并按顺序流式回调
     *
     * 优化点：
     * 1. 去掉 Semaphore，直接依赖 OkHttp Dispatcher（maxRequestsPerHost=20）
     * 2. 下载完成立即回调 onWrite，不等整批完成（流式处理）
     * 3. 连接复用：同一 host 的请求自动复用连接（OkHttp ConnectionPool）
     *
     * @param taskId 任务 ID，用于日志追踪
     * @param items 待下载项（带序号、URI、PDT）
     * @param onWrite 写入回调（保证顺序），pdtMs 为该项所属 segment 的墙钟 PDT，失败时 data 为 null
     */
    suspend fun downloadParallelOrdered(
        taskId: String,
        items: List<DownloadItem>,
        onWrite: suspend (index: Int, data: ByteArray?, pdtMs: Long?) -> Unit
    ) = coroutineScope {
        if (items.isEmpty()) return@coroutineScope

        val batchStartMs = System.currentTimeMillis()
        Log.d(TAG, "[$taskId] Ordered download batch start: ${items.size} items, first=${items.first().uri.takeLast(40)}, last=${items.last().uri.takeLast(40)}")

        // index -> (data, pdtMs)
        val buffer = TreeMap<Int, Pair<ByteArray?, Long?>>()
        var nextWriteIndex = 0
        var writtenCount = 0
        var failedCount = 0
        val mutex = Mutex()

        items.map { item ->
            async(Dispatchers.IO) {
                val itemStartMs = System.currentTimeMillis()
                val data = downloadInternal(item.uri, taskId)
                val itemElapsed = System.currentTimeMillis() - itemStartMs

                if (data == null) {
                    Log.w(TAG, "[$taskId] Download FAILED: ${item.uri.takeLast(60)}, elapsed=${itemElapsed}ms")
                }

                mutex.withLock {
                    buffer[item.index] = data to item.pdtMs
                    if (data == null) failedCount++

                    // 按顺序写入：只要 nextWriteIndex 有了就立即回调
                    // 这是流式处理的关键：下载完成一个就尝试顺序写入，不等整批
                    while (buffer.containsKey(nextWriteIndex)) {
                        val (toWrite, pdt) = buffer.remove(nextWriteIndex)!!
                        onWrite(nextWriteIndex, toWrite, pdt)
                        nextWriteIndex++
                        writtenCount++
                    }
                }
            }
        }.awaitAll()

        val elapsed = System.currentTimeMillis() - batchStartMs
        Log.d(TAG, "[$taskId] Ordered download batch done: written=$writtenCount failed=$failedCount/${items.size}, ${elapsed}ms")
    }
}
