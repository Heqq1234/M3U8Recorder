package com.dl.m3u8recorder.llhls

import android.util.Log

/**
 * LL-HLS 直播边缘跟踪器
 * 用于确定直播的实时边缘位置，控制刷新频率
 */
class LiveEdgeTracker {
    companion object {
        private const val TAG = "LiveEdgeTracker"
    }

    // 当前跟踪状态
    private var lastMediaSequence: Long = 0
    private var lastPartCount: Int = 0
    private var lastTargetDuration: Double = 3.0
    private var lastHoldBack: Double = 3.0
    private var lastPartHoldBack: Double = 1.0

    // 刷新间隔 (毫秒)
    private var refreshInterval: Long = 1000L

    /**
     * 更新跟踪状态
     * @param mediaSequence 当前媒体序列号
     * @param partCount 部分片段数量
     * @param targetDuration 目标时长
     * @param holdBack 服务器建议的 hold-back 时间
     * @param partHoldBack 部分片段的 hold-back 时间
     */
    fun update(
        mediaSequence: Long,
        partCount: Int,
        targetDuration: Double,
        holdBack: Double? = null,
        partHoldBack: Double? = null
    ) {
        lastMediaSequence = mediaSequence
        lastPartCount = partCount
        lastTargetDuration = targetDuration

        holdBack?.let { lastHoldBack = it }
        partHoldBack?.let { lastPartHoldBack = it }

        // 计算刷新间隔: 取 targetDuration 和 holdBack 的较小值，但至少 0.5 秒
        refreshInterval = (minOf(lastTargetDuration, lastHoldBack, lastPartHoldBack) * 1000 / 2)
            .toLong()
            .coerceIn(500, 3000)

        Log.v(TAG, "Updated: seq=$mediaSequence, parts=$partCount, refresh=${refreshInterval}ms")
    }

    /**
     * 检测是否有新的部分片段
     */
    fun hasNewParts(mediaSequence: Long, partCount: Int): Boolean {
        return mediaSequence > lastMediaSequence || partCount > lastPartCount
    }

    /**
     * 计算新片段的索引范围
     * @return Pair(开始索引, 结束索引)，如果没有新片段返回 null
     */
    fun getNewPartRange(currentPartCount: Int): Pair<Int, Int>? {
        if (currentPartCount <= lastPartCount) {
            return null
        }
        return Pair(lastPartCount, currentPartCount - 1)
    }

    /**
     * 获取推荐的刷新间隔
     */
    fun getRefreshInterval(): Long = refreshInterval

    /**
     * 检测是否接近直播边缘
     * @param currentParts 当前部分片段数
     * @param expectedParts 每个完整片段预期的部分片段数
     */
    fun isNearLiveEdge(currentParts: Int, expectedParts: Int): Boolean {
        return currentParts >= expectedParts - 1
    }

    /**
     * 重置状态
     */
    fun reset() {
        lastMediaSequence = 0
        lastPartCount = 0
        refreshInterval = 1000L
        Log.d(TAG, "LiveEdgeTracker reset")
    }

    /**
     * 获取当前状态摘要
     */
    fun getStateSummary(): String {
        return "seq=$lastMediaSequence, parts=$lastPartCount, refresh=${refreshInterval}ms"
    }
}
