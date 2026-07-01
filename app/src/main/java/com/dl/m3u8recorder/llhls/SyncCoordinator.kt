package com.dl.m3u8recorder.llhls

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 音视频同步协调器
 *
 * 分别追踪视频和音频的累积时长（来自 #EXTINF duration），
 * 在复用前判断是否需要补齐短轨。
 *
 * 核心原则：不能用 mediaSequence 对两个轨道做同步，
 * 因为音视频 playlists 有各自的独立编号。
 * 唯一可靠的对照基准是累积时间（秒）。
 *
 * 新增分片序列号对齐机制：
 * - 记录音视频首个下载分片的序列号
 * - 若首分片序列号不一致，计算缺失分片的总时长作为 startMismatchMs
 * - 该值用于在 PipeMuxer 中修正 DTS 基准偏差计算
 */
class SyncCoordinator {

    companion object {
        private const val TAG = "SyncCoordinator"
    }

    enum class StreamType { VIDEO, AUDIO }

    // 每个轨道已确认追加的总时长（毫秒）
    @Volatile
    private var videoDurationMs: Long = 0L

    @Volatile
    private var audioDurationMs: Long = 0L

    // 每个轨道最终确定的时长（tracker 退出时记录）
    @Volatile
    private var videoFinalizedMs: Long = 0L

    @Volatile
    private var audioFinalizedMs: Long = 0L

    // ---- 分片序列号追踪 ----

    /** 视频轨道：seq -> duration(ms) */
    private val videoSegmentDurations = ConcurrentHashMap<Long, Long>()

    /** 音频轨道：seq -> duration(ms) */
    private val audioSegmentDurations = ConcurrentHashMap<Long, Long>()

    /** 首个视频分片序列号，null 表示尚未下载 */
    @Volatile
    private var firstVideoSeq: Long? = null

    /** 首个音频分片序列号，null 表示尚未下载 */
    @Volatile
    private var firstAudioSeq: Long? = null

    /**
     * 追加了一个 segment，记录它的时长和序列号
     */
    fun addSegment(type: StreamType, durationMs: Long, sequenceNumber: Long) {
        when (type) {
            StreamType.VIDEO -> {
                videoDurationMs += durationMs
                videoSegmentDurations[sequenceNumber] = durationMs
                if (firstVideoSeq == null) {
                    firstVideoSeq = sequenceNumber
                    Log.d(TAG, "First video seq: $sequenceNumber")
                }
            }
            StreamType.AUDIO -> {
                audioDurationMs += durationMs
                audioSegmentDurations[sequenceNumber] = durationMs
                if (firstAudioSeq == null) {
                    firstAudioSeq = sequenceNumber
                    Log.d(TAG, "First audio seq: $sequenceNumber")
                }
            }
        }
    }

    /**
     * 获取当前累积时长
     */
    fun getDurationMs(type: StreamType): Long {
        return when (type) {
            StreamType.VIDEO -> videoDurationMs
            StreamType.AUDIO -> audioDurationMs
        }
    }

    /**
     * 当 tracker 结束（正常退出或被取消），调用此方法记录最终时长
     */
    fun finalize(type: StreamType) {
        when (type) {
            StreamType.VIDEO -> {
                videoFinalizedMs = videoDurationMs
                Log.d(TAG, "Video finalized: ${videoFinalizedMs}ms")
            }
            StreamType.AUDIO -> {
                audioFinalizedMs = audioDurationMs
                Log.d(TAG, "Audio finalized: ${audioFinalizedMs}ms")
            }
        }
    }

    /**
     * 两个 tracker 都 finalize 后，检查哪个轨道短、短多少
     * @return Pair(短的那个轨道, 需要补齐的毫秒数)，如果等长则返回 null
     */
    fun getPadInfo(): Pair<StreamType, Long>? {
        if (videoFinalizedMs == 0L || audioFinalizedMs == 0L) {
            if (videoFinalizedMs == 0L) {
                Log.w(TAG, "Video not finalized yet, skip padding")
            }
            if (audioFinalizedMs == 0L) {
                Log.w(TAG, "Audio not finalized yet, skip padding")
            }
            return null
        }
        return when {
            videoFinalizedMs < audioFinalizedMs -> {
                StreamType.VIDEO to (audioFinalizedMs - videoFinalizedMs)
            }
            audioFinalizedMs < videoFinalizedMs -> {
                StreamType.AUDIO to (videoFinalizedMs - audioFinalizedMs)
            }
            else -> null
        }
    }

    /**
     * 计算因首分片序列号不一致导致的时间差（毫秒）
     *
     * 场景：视频从 seq=5 开始下载，音频从 seq=7 开始下载，
     * 则音频实际缺失了 seq=5,6 对应的时长。
     *
     * 计算方式：
     * - 找到两者公共的起点 seq = max(firstVideoSeq, firstAudioSeq)
     * - 对于起点更早的流，累加从它的 firstSeq 到公共起点之间的所有 segment 时长
     * - 正值表示音频比视频少了的时长（音频起晚了），负值表示视频比音频少了的时长
     *
     * @return 正数 = 音频缺失的时长(ms)，负数 = 视频缺失的时长(ms)，0 = 对齐
     */
    fun getStartMismatchMs(): Long {
        val vFirst = firstVideoSeq ?: return 0L
        val aFirst = firstAudioSeq ?: return 0L

        if (vFirst == aFirst) {
            Log.d(TAG, "Start seq aligned: video=$vFirst audio=$aFirst, mismatch=0ms")
            return 0L
        }

        // 公共起点：取较大的序列号
        val commonStart = maxOf(vFirst, aFirst)
        var missingMs = 0L

        if (vFirst < aFirst) {
            // 视频起得更早，音频缺失了 [vFirst, aFirst) 的分片
            for (seq in vFirst until aFirst) {
                val dur = videoSegmentDurations[seq] ?: continue
                missingMs += dur
            }
            Log.d(TAG, "Audio started later: videoFirst=$vFirst audioFirst=$aFirst " +
                    "missingAudio=${missingMs}ms")
            // 音频缺失了这段时间 → 返回正值，后续从 offset 中减去
            return missingMs
        } else {
            // 音频起得更早，视频缺失了 [aFirst, vFirst) 的分片
            for (seq in aFirst until vFirst) {
                val dur = audioSegmentDurations[seq] ?: continue
                missingMs += dur
            }
            Log.d(TAG, "Video started later: videoFirst=$vFirst audioFirst=$aFirst " +
                    "missingVideo=${missingMs}ms")
            // 视频缺失了这段时间 → 返回负值
            return -missingMs
        }
    }

    /**
     * 获取当前漂移量（ms），正值 = 视频比音频多
     */
    fun getDriftMs(): Long = videoDurationMs - audioDurationMs

    override fun toString(): String {
        return "SyncCoordinator(video=${videoDurationMs}ms, audio=${audioDurationMs}ms, " +
                "finalVideo=${videoFinalizedMs}ms, finalAudio=${audioFinalizedMs}ms, " +
                "firstVideoSeq=$firstVideoSeq, firstAudioSeq=$firstAudioSeq)"
    }
}