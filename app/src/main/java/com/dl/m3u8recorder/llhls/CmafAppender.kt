package com.dl.m3u8recorder.llhls

import android.util.Log
import java.io.*

/**
 * CMAF/fMP4 片段追加器
 * 用于将 init.mp4 和 m4s fragments 追加成完整的 fragmented MP4
 */
class CmafAppender(
    private val outputFile: File,
    private val bufferSize: Int = 256 * 1024
) {
    companion object {
        private const val TAG = "CmafAppender"
        private const val FLUSH_INTERVAL_FRAGMENTS = 5
        private const val FLUSH_INTERVAL_BYTES = 1024 * 1024
    }

    private var outputStream: BufferedOutputStream? = null
    private var initSegmentWritten = false
    private var fragmentCount = 0
    private var totalBytesWritten = 0L
    private var bytesSinceLastFlush = 0L

    /**
     * 写入初始化片段 (来自 EXT-X-MAP)
     * 必须在任何媒体片段之前调用
     */
    @Synchronized
    fun writeInitSegment(data: ByteArray): Boolean {
        if (initSegmentWritten) {
            Log.w(TAG, "Init segment already written, skipping")
            return true
        }

        return try {
            outputFile.parentFile?.mkdirs()

            outputStream = BufferedOutputStream(
                FileOutputStream(outputFile, false),
                bufferSize
            )

            outputStream?.write(data)
            outputStream?.flush()

            initSegmentWritten = true
            totalBytesWritten = data.size.toLong()

            Log.d(TAG, "Init segment written: ${data.size} bytes to ${outputFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write init segment", e)
            false
        }
    }

    /**
     * 追加媒体片段 (m4s)
     */
    @Synchronized
    fun appendFragment(data: ByteArray): Boolean {
        if (!initSegmentWritten) {
            Log.e(TAG, "Init segment must be written first!")
            return false
        }

        return try {
            outputStream?.write(data)
            fragmentCount++
            totalBytesWritten += data.size
            bytesSinceLastFlush += data.size

            // 每 5 个片段或每 1MB 刷新一次，平衡延迟和 IO 效率
            if (fragmentCount % FLUSH_INTERVAL_FRAGMENTS == 0 || bytesSinceLastFlush >= FLUSH_INTERVAL_BYTES) {
                outputStream?.flush()
                bytesSinceLastFlush = 0
            }

            Log.v(TAG, "Fragment #$fragmentCount appended: ${data.size} bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append fragment #$fragmentCount", e)
            false
        }
    }

    /**
     * 刷新并关闭输出流
     */
    @Synchronized
    fun flush() {
        try {
            outputStream?.flush()
            outputStream?.close()
            outputStream = null

            Log.d(TAG, "CmafAppender flushed. Total: $fragmentCount fragments, $totalBytesWritten bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing CmafAppender", e)
        }
    }

    /**
     * 获取已写入的片段数
     */
    fun getFragmentCount(): Int = fragmentCount

    /**
     * 获取已写入的总字节数
     */
    fun getTotalBytes(): Long = totalBytesWritten

    /**
     * 检查是否已初始化
     */
    fun isInitialized(): Boolean = initSegmentWritten

    /**
     * 获取输出文件
     */
    fun getOutputFile(): File = outputFile
}
