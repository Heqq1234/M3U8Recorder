package com.dl.m3u8recorder.llhls

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

// TODO: 已废弃 — 功能简单，可内联到 LLHlsRecorder。保留代码供参考，后续可删除。
/*
/**
 * Fragment 去重器
 * LL-HLS Playlist 会重复返回旧的 fragments，需要去重避免视频损坏
 */
class FragmentDeduplicator {
    companion object {
        private const val TAG = "FragmentDeduplicator"
    }

    private val downloadedSegments = ConcurrentHashMap<String, Boolean>()
    private val downloadedParts = ConcurrentHashMap<String, Boolean>()
    private val downloadedBySequence = ConcurrentHashMap<String, Boolean>()

    /**
     * 检查并标记 segment 为已下载
     * @param uri segment URI
     * @return true 如果是新 segment，false 如果已存在
     */
    fun markSegmentDownloaded(uri: String): Boolean {
        val key = normalizeUri(uri)
        val isNew = downloadedSegments.putIfAbsent(key, true) == null

        if (!isNew) {
            Log.v(TAG, "Duplicate segment detected: $key")
        }

        return isNew
    }

    /**
     * 检查并标记 part (partial segment) 为已下载
     * @param uri part URI
     * @return true 如果是新 part，false 如果已存在
     */
    fun markPartDownloaded(uri: String): Boolean {
        val key = normalizeUri(uri)
        val isNew = downloadedParts.putIfAbsent(key, true) == null

        if (!isNew) {
            Log.v(TAG, "Duplicate part detected: $key")
        }

        return isNew
    }

    /**
     * 基于 media sequence 和 part index 检查去重
     * 这种方式比 URI 更可靠（某些 CDN 会给同一内容不同 URL）
     */
    fun isNewBySequence(mediaSequence: Long, partIndex: Int?): Boolean {
        val key = if (partIndex != null) {
            "$mediaSequence:$partIndex"
        } else {
            "$mediaSequence"
        }

        val isNew = downloadedBySequence.putIfAbsent(key, true) == null

        if (!isNew) {
            Log.v(TAG, "Duplicate by sequence: $key")
        }

        return isNew
    }

    /**
     * 获取已下载的片段统计
     */
    fun getStats(): FragmentStats {
        return FragmentStats(
            totalSegments = downloadedSegments.size,
            totalParts = downloadedParts.size,
            totalBySequence = downloadedBySequence.size
        )
    }

    /**
     * 重置所有状态（用于重新开始录制）
     */
    fun reset() {
        downloadedSegments.clear()
        downloadedParts.clear()
        downloadedBySequence.clear()
        Log.d(TAG, "FragmentDeduplicator reset")
    }

    /**
     * 规范化 URI（去除查询参数等变化部分）
     */
    private fun normalizeUri(uri: String): String {
        // 去除查询参数中可能变化的 token 等
        return uri.substringBefore("?")
    }

    /**
     * 片段统计信息
     */
    data class FragmentStats(
        val totalSegments: Int,
        val totalParts: Int,
        val totalBySequence: Int
    ) {
        override fun toString(): String {
            return "Segments: $totalSegments, Parts: $totalParts, BySeq: $totalBySequence"
        }
    }
}
*/
