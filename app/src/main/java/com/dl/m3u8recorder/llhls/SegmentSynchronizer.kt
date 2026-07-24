package com.dl.m3u8recorder.llhls

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

class SegmentSynchronizer(
    private val videoAppender: CmafAppender,
    private val audioAppender: CmafAppender?,
    private val syncCoordinator: SyncCoordinator,
    private val taskId: String
) {
    companion object {
        private const val TAG = "SegmentSynchronizer"
        private const val MAX_PENDING_SEGMENTS = 30
        private const val SYNC_TIMEOUT_MS = 5000
        private const val TIME_SLOT_MS = 2000
    }

    data class PendingSegment(
        var videoData: ByteArray? = null,
        var audioData: ByteArray? = null,
        var videoDurationMs: Long = 0,
        var audioDurationMs: Long = 0,
        var videoSeq: Long = -1,
        var audioSeq: Long = -1,
        var videoTfdt: Long = -1,
        var audioTfdt: Long = -1,
        var timestamp: Long = System.currentTimeMillis()
    )

    private val pendingSegments = ConcurrentHashMap<Long, PendingSegment>()

    @Volatile
    private var videoTimescale: Long = 1000
    @Volatile
    private var audioTimescale: Long = 1000

    fun setVideoTimescale(timescale: Long) {
        videoTimescale = timescale
        Log.d(TAG, "[$taskId] Video timescale set to $timescale")
    }

    fun setAudioTimescale(timescale: Long) {
        audioTimescale = timescale
        Log.d(TAG, "[$taskId] Audio timescale set to $timescale")
    }

    fun addVideoSegment(data: ByteArray, durationMs: Long, sequenceNumber: Long) {
        val tfdt = parseTfdtFromFragment(data)
        val timeSlot = if (tfdt >= 0) {
            (tfdt * 1000 / videoTimescale) / TIME_SLOT_MS
        } else {
            sequenceNumber / 5
        }

        Log.v(TAG, "[$taskId] Video seq=$sequenceNumber tfdt=$tfdt timeSlot=$timeSlot")

        pendingSegments.compute(timeSlot) { _, current ->
            val existing = current ?: PendingSegment(timestamp = System.currentTimeMillis())
            existing.videoData = data
            existing.videoDurationMs = durationMs
            existing.videoSeq = sequenceNumber
            existing.videoTfdt = tfdt
            existing
        }
        checkAndWrite(timeSlot)
        cleanupOldSegments()
    }

    fun addAudioSegment(data: ByteArray, durationMs: Long, sequenceNumber: Long) {
        val tfdt = parseTfdtFromFragment(data)
        val timeSlot = if (tfdt >= 0) {
            (tfdt * 1000 / audioTimescale) / TIME_SLOT_MS
        } else {
            sequenceNumber / 5
        }

        Log.v(TAG, "[$taskId] Audio seq=$sequenceNumber tfdt=$tfdt timeSlot=$timeSlot")

        pendingSegments.compute(timeSlot) { _, current ->
            val existing = current ?: PendingSegment(timestamp = System.currentTimeMillis())
            existing.audioData = data
            existing.audioDurationMs = durationMs
            existing.audioSeq = sequenceNumber
            existing.audioTfdt = tfdt
            existing
        }
        checkAndWrite(timeSlot)
        cleanupOldSegments()
    }

    private fun checkAndWrite(timeSlot: Long) {
        val pending = pendingSegments[timeSlot] ?: return

        val hasVideo = pending.videoData != null
        val hasAudio = audioAppender != null && pending.audioData != null
        val isAudioOnly = audioAppender == null

        if ((hasVideo && hasAudio) || (hasVideo && isAudioOnly)) {
            try {
                if (hasVideo) {
                    videoAppender.appendFragment(pending.videoData!!)
                    syncCoordinator.addSegment(SyncCoordinator.StreamType.VIDEO, pending.videoDurationMs, pending.videoSeq)
                }
                if (hasAudio) {
                    audioAppender?.appendFragment(pending.audioData!!)
                    syncCoordinator.addSegment(SyncCoordinator.StreamType.AUDIO, pending.audioDurationMs, pending.audioSeq)
                }
                pendingSegments.remove(timeSlot)
                Log.v(TAG, "[$taskId] Synced timeSlot=$timeSlot: videoSeq=${pending.videoSeq}/tfdt=${pending.videoTfdt} audioSeq=${pending.audioSeq}/tfdt=${pending.audioTfdt}")
            } catch (e: Exception) {
                Log.e(TAG, "[$taskId] Failed to write synced timeSlot=$timeSlot", e)
            }
        } else if (hasVideo && !hasAudio && audioAppender != null) {
            val age = System.currentTimeMillis() - pending.timestamp
            if (age > SYNC_TIMEOUT_MS) {
                Log.w(TAG, "[$taskId] Video seq=${pending.videoSeq} tfdt=${pending.videoTfdt} timeout waiting for audio, writing alone")
                writeVideoOnly(timeSlot)
            }
        } else if (hasAudio && !hasVideo) {
            val age = System.currentTimeMillis() - pending.timestamp
            if (age > SYNC_TIMEOUT_MS) {
                Log.w(TAG, "[$taskId] Audio seq=${pending.audioSeq} tfdt=${pending.audioTfdt} timeout waiting for video, dropping")
                pendingSegments.remove(timeSlot)
            }
        }
    }

    private fun writeVideoOnly(timeSlot: Long) {
        val pending = pendingSegments.remove(timeSlot) ?: return
        try {
            videoAppender.appendFragment(pending.videoData!!)
            syncCoordinator.addSegment(SyncCoordinator.StreamType.VIDEO, pending.videoDurationMs, pending.videoSeq)
            Log.v(TAG, "[$taskId] Wrote video-only seq=${pending.videoSeq}")
        } catch (e: Exception) {
            Log.e(TAG, "[$taskId] Failed to write video-only seq=${pending.videoSeq}", e)
        }
    }

    private fun cleanupOldSegments() {
        if (pendingSegments.size <= MAX_PENDING_SEGMENTS) return

        val oldestKey = pendingSegments.entries
            .minByOrNull { it.value.timestamp }
            ?.key

        oldestKey?.let { key ->
            val pending = pendingSegments[key]
            if (pending?.videoData != null) {
                Log.w(TAG, "[$taskId] Buffer full, writing video-only seq=${pending.videoSeq}")
                writeVideoOnly(key)
            } else {
                pendingSegments.remove(key)
                Log.w(TAG, "[$taskId] Buffer full, dropping audio-only seq=${pending?.audioSeq}")
            }
        }
    }

    fun flushRemaining() {
        Log.d(TAG, "[$taskId] Flushing ${pendingSegments.size} remaining segments")
        pendingSegments.forEach { (key, pending) ->
            if (pending.videoData != null) {
                try {
                    videoAppender.appendFragment(pending.videoData!!)
                    syncCoordinator.addSegment(SyncCoordinator.StreamType.VIDEO, pending.videoDurationMs, pending.videoSeq)
                    Log.v(TAG, "[$taskId] Flushed video seq=${pending.videoSeq}")
                } catch (e: Exception) {
                    Log.e(TAG, "[$taskId] Failed to flush video seq=${pending.videoSeq}", e)
                }
            }
            if (audioAppender != null && pending.audioData != null) {
                try {
                    audioAppender.appendFragment(pending.audioData!!)
                    syncCoordinator.addSegment(SyncCoordinator.StreamType.AUDIO, pending.audioDurationMs, pending.audioSeq)
                    Log.v(TAG, "[$taskId] Flushed audio seq=${pending.audioSeq}")
                } catch (e: Exception) {
                    Log.e(TAG, "[$taskId] Failed to flush audio seq=${pending.audioSeq}", e)
                }
            }
        }
        pendingSegments.clear()
    }

    fun getPendingCount(): Int = pendingSegments.size

    private fun parseTfdtFromFragment(data: ByteArray): Long {
        try {
            var pos = 0
            while (pos + 8 <= data.size) {
                val size = readUint32(data, pos)
                if (size < 8 || pos + size > data.size) break
                val type = readFourcc(data, pos + 4)

                if (type == "moof") {
                    val moofEnd = pos + size
                    var trafPos = pos + 8
                    while (trafPos + 8 <= minOf(moofEnd.toInt(), data.size)) {
                        val trafSize = readUint32(data, trafPos)
                        if (trafSize < 8 || trafPos + trafSize > minOf(moofEnd.toInt(), data.size)) break
                        val trafType = readFourcc(data, trafPos + 4)

                        if (trafType == "traf") {
                            val trafEnd = trafPos + trafSize
                            var inner = trafPos + 8
                            while (inner + 8 <= minOf(trafEnd.toInt(), data.size)) {
                                val innerSize = readUint32(data, inner)
                                if (innerSize < 8 || inner + innerSize > minOf(trafEnd.toInt(), data.size)) break
                                val innerType = readFourcc(data, inner + 4)

                                if (innerType == "tfdt" && inner + 12 <= minOf(trafEnd.toInt(), data.size)) {
                                    val version = data[inner + 8].toInt() and 0xFF
                                    return if (version == 0) {
                                        readUint32(data, inner + 12).toLong()
                                    } else {
                                        readUint64(data, inner + 12)
                                    }
                                }
                                inner += innerSize.toInt()
                            }
                        }
                        trafPos += trafSize.toInt()
                    }
                    return -1
                }
                pos += size.toInt()
            }
        } catch (e: Exception) {
            Log.v(TAG, "[$taskId] Failed to parse tfdt from fragment: ${e.message}")
        }
        return -1
    }

    private fun readUint32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
               ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
               ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
               (bytes[offset + 3].toLong() and 0xFF)
    }

    private fun readUint64(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFF) shl 56) or
               ((bytes[offset + 1].toLong() and 0xFF) shl 48) or
               ((bytes[offset + 2].toLong() and 0xFF) shl 40) or
               ((bytes[offset + 3].toLong() and 0xFF) shl 32) or
               ((bytes[offset + 4].toLong() and 0xFF) shl 24) or
               ((bytes[offset + 5].toLong() and 0xFF) shl 16) or
               ((bytes[offset + 6].toLong() and 0xFF) shl 8) or
               (bytes[offset + 7].toLong() and 0xFF)
    }

    private fun readFourcc(bytes: ByteArray, offset: Int): String {
        return String(bytes, offset, 4, Charsets.US_ASCII)
    }
}