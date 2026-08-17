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
import okhttp3.Response
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
     * @param uri 片段 URI（裸 URL，与浏览器 HLS 播放器一致）
     * @param taskId 任务 ID，用于日志追踪
     * @param authQuery 可选：playlist 的鉴权查询参数（pkey/psch/playlistType 等）。
     *                  当裸 URL 404 时，补回该查询参数重试一次（实验性，仅 Stripchat 传入）。
     * @return 片段数据，失败返回 null
     */
    private fun downloadInternal(
        uri: String,
        taskId: String = "",
        authQuery: String? = null
    ): ByteArray? {
        val tagPrefix = if (taskId.isNotEmpty()) "[$taskId] " else ""

        // 第一轮：裸 URL（与浏览器 HLS 播放器一致）
        val bare = tryDownload(uri, tagPrefix, emitDiagnostics = true)
        if (bare != null) return bare

        // 第二轮：404 时补回 playlist 鉴权查询参数重试。
        // 浏览器能 200 是因为 CloudFront 边缘缓存命中；手机上对源站的全新请求可能需 pkey 授权。
        // 仅当 authQuery 存在且裸 URL 本身无查询参数时尝试（平台隔离：Chaturbate 不传 authQuery）。
        if (authQuery != null && !uri.contains("?")) {
            val authedUri = "$uri?$authQuery"
            Log.w(TAG, "${tagPrefix}404 → 补 pkey 重试: ${authedUri.takeLast(90)}")
            val authed = tryDownload(authedUri, tagPrefix, emitDiagnostics = true)
            if (authed != null) {
                Log.w(TAG, "${tagPrefix}★ 补 pkey 后成功 ✓ ${authedUri.takeLast(90)}")
                return authed
            }
        }

        return null
    }

    /**
     * 实际执行下载（含重试与退避）。on 404 时若 emitDiagnostics 且尚未打过，输出完整响应诊断。
     * @param maxRetries 重试次数。
     */
    private fun tryDownload(
        targetUri: String,
        tagPrefix: String,
        emitDiagnostics: Boolean,
        maxRetries: Int = MAX_RETRIES
    ): ByteArray? {
        val startMs = System.currentTimeMillis()
        var lastException: Exception? = null
        var diagEmitted = false
        repeat(maxRetries) { attempt ->
            try {
                // 请求头由 client 的 interceptor 统一设置（Referer/Origin/Sec-Fetch 等）
                val request = Request.Builder()
                    .url(targetUri)
                    .build()

                val response = client.newCall(request).execute()
                val elapsed = System.currentTimeMillis() - startMs

                if (response.isSuccessful) {
                    val ct = response.header("Content-Type") ?: ""
                    val bytes = response.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        Log.d(TAG, "${tagPrefix}Downloaded: ${targetUri.takeLast(60)} (${bytes.size} bytes, ${elapsed}ms), attempt ${attempt + 1}")
                        return bytes
                    } else {
                        Log.w(TAG, "${tagPrefix}Empty body: ${targetUri.takeLast(60)}, attempt ${attempt + 1}")
                    }
                } else if (response.code in 400..499) {
                    // 404/429 可能是临时的（live edge 还没准备好 / 限流），重试
                    // 其他 4xx（400, 401, 403, 405+）永久跳过
                    if (response.code == 404 || response.code == 429) {
                        if (emitDiagnostics && !diagEmitted) {
                            diagEmitted = true
                            logSegmentDiagnostics(tagPrefix, response)
                        }
                        Log.w(TAG, "${tagPrefix}HTTP ${response.code}: ${targetUri.takeLast(60)}, 可能临时错误，重试 (${attempt + 1}/${maxRetries})")
                        lastException = IOException("HTTP ${response.code}")
                    } else {
                        Log.w(TAG, "${tagPrefix}HTTP ${response.code}: ${targetUri.takeLast(60)}, 跳过 (永久 4xx 错误)")
                        return null
                    }
                } else {
                    Log.w(TAG, "${tagPrefix}HTTP ${response.code}: ${targetUri.takeLast(60)}, attempt ${attempt + 1}, elapsed=${elapsed}ms")
                }
            } catch (e: Exception) {
                lastException = e
                val elapsed = System.currentTimeMillis() - startMs
                Log.w(TAG, "${tagPrefix}Download exception attempt ${attempt + 1}: ${targetUri.takeLast(60)}, elapsed=${elapsed}ms", e)
            }

            // 指数退避重试，但更快
            if (attempt < maxRetries - 1) {
                Thread.sleep(100L * (attempt + 1)) // 100ms, 200ms, 300ms
            }
        }
        return null
    }

    /**
     * 分片 404 全量诊断：打出【实际发出的请求头（interceptor 之后）】+ 完整响应头 + 响应体前 1KB。
     * 请求头能确认 Referer/Origin 等是否真的按预期带出（用于核对反盗链域名），
     * 响应头常说明 404 根因（NoSuchKey / Missing Token / Geo Blocked 等）。
     * 注意：必须用 response.request（经过拦截器后的网络请求），原始 Request 此时还没有任何头。
     */
    private fun logSegmentDiagnostics(tagPrefix: String, response: Response) {
        val sb = StringBuilder()
        sb.appendLine("${tagPrefix}═══ SEGMENT 404 DIAGNOSTICS ═══")
        sb.appendLine("REQUEST URL: ${response.request.url}")
        sb.appendLine("--- request headers (sent, AFTER interceptors) ---")
        val reqHdrs = response.request.headers
        if (reqHdrs.size == 0) {
            sb.appendLine("  (none — 说明该请求没有任何头；若期望有 Referer/Origin，请检查下载所用 client 的拦截器是否真的挂上了)")
        }
        for (i in 0 until reqHdrs.size) {
            sb.appendLine("  ${reqHdrs.name(i)}: ${reqHdrs.value(i)}")
        }
        sb.appendLine("--- response headers ---")
        for (i in 0 until response.headers.size) {
            sb.appendLine("  ${response.headers.name(i)}: ${response.headers.value(i)}")
        }
        val body = runCatching { response.peekBody(1024).string() }.getOrNull() ?: "<unavailable>"
        sb.appendLine("--- response body (≤1024B) ---")
        sb.appendLine(body)
        Log.e(TAG, sb.toString())
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
        authQuery: String? = null,
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
                val data = downloadInternal(item.uri, taskId, authQuery)
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
