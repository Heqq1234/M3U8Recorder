package com.dl.m3u8recorder.llhls

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

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

            // 短暂延迟后重试
            if (attempt < MAX_RETRIES - 1) {
                Thread.sleep(500L * (attempt + 1))
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
}
