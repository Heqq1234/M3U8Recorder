package com.dl.m3u8recorder.llhls

import android.content.Context
import android.util.Log
import com.dl.m3u8recorder.model.DownloadTask
import com.dl.m3u8recorder.parser.Segment
import com.dl.m3u8recorder.utils.Mp4OutputHelper
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class LLHlsRecorder(
    private val context: Context,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()
) {
    companion object {
        private const val TAG = "LLHlsRecorder"
    }

    private val activeSessions = ConcurrentHashMap<String, RecordingSession>()
    private val globalProgressCounters = ConcurrentHashMap<String, Int>()
    private val partDownloader = PartDownloader(okHttpClient)

    private data class RecordingSession(
        val taskId: String,
        val task: DownloadTask,
        val realTimeMuxer: RealTimeMuxer,
        val videoTracker: VariantPlaylistTracker?,
        val audioTracker: VariantPlaylistTracker?,
        val videoDeduplicator: FragmentDeduplicator,
        val audioDeduplicator: FragmentDeduplicator,
        val scope: CoroutineScope,
        val videoJob: Job?,
        val audioJob: Job?,
        var isStopped: Boolean = false
    )

    suspend fun startRecording(
        task: DownloadTask,
        onProgress: (progress: Int, status: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (activeSessions.containsKey(task.id)) {
            Log.w(TAG, "Task ${task.id} already recording")
            return@withContext
        }

        onProgress(0, "初始化 LL-HLS 录制...")

        try {
            val videoPlaylistUrl = task.getEffectiveUrl()
            val audioPlaylistUrl = task.audioTrackUrl
            Log.d(TAG, "URL debug: effectiveUrl=$videoPlaylistUrl audioUrl=$audioPlaylistUrl")

            val outputFile = Mp4OutputHelper.getOutputFile(context, task.fileName, ".mp4")
            outputFile.parentFile?.mkdirs()

            val realTimeMuxer = RealTimeMuxer(outputFile, task.id)

            val videoTracker = VariantPlaylistTracker(okHttpClient)
            val audioTracker = audioPlaylistUrl?.let { VariantPlaylistTracker(okHttpClient) }

            val videoDeduplicator = FragmentDeduplicator()
            val audioDeduplicator = FragmentDeduplicator()

            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            onProgress(1, "获取播放列表信息...")
            val videoState = videoTracker.fetchOnce(videoPlaylistUrl)
            if (videoState == null) {
                onProgress(0, "无法获取播放列表")
                return@withContext
            }

            if (videoState.initSegment != null) {
                onProgress(2, "下载视频初始化片段...")
                partDownloader.downloadInitSegment(videoState.initSegment.uri)?.let { initData ->
                    realTimeMuxer.setVideoInitData(initData)
                }
            }
            if (audioPlaylistUrl != null) {
                val audioState = audioTracker?.fetchOnce(audioPlaylistUrl)
                if (audioState?.initSegment != null) {
                    onProgress(3, "下载音频初始化片段...")
                    val audioInitData = partDownloader.downloadInitSegment(audioState.initSegment.uri)
                    if (audioInitData != null) {
                        realTimeMuxer.setAudioInitData(audioInitData)
                    } else {
                        realTimeMuxer.setNoAudioTrack()
                    }
                } else {
                    realTimeMuxer.setNoAudioTrack()
                }
            } else {
                realTimeMuxer.setNoAudioTrack()
            }

            val session = RecordingSession(
                taskId = task.id,
                task = task,
                realTimeMuxer = realTimeMuxer,
                videoTracker = videoTracker,
                audioTracker = audioTracker,
                videoDeduplicator = videoDeduplicator,
                audioDeduplicator = audioDeduplicator,
                scope = scope,
                videoJob = null,
                audioJob = null
            )

            activeSessions[task.id] = session
            onProgress(5, "开始录制...")

            val videoJob = scope.launch {
                try {
                    videoTracker.trackPlaylist(
                        playlistUrl = videoPlaylistUrl,
                        onInitSegment = {},
                        onNewSegments = { segments ->
                            appendSegments(segments, session, true, onProgress)
                        }
                    )
                } finally {
                    Log.d(TAG, "Video tracker finished: ${task.id}")
                }
            }

            val audioJob = audioPlaylistUrl?.let { audioUrl ->
                scope.launch {
                    try {
                        audioTracker?.trackPlaylist(
                            playlistUrl = audioUrl,
                            onInitSegment = {},
                            onNewSegments = { segments ->
                                appendSegments(segments, session, false, onProgress)
                            },
                            isOtherTrackerActive = {
                                val active = videoJob.isActive
                                Log.d(TAG, "audio isOtherTrackerActive check: videoJob.active=$active")
                                active
                            }
                        )
                    } finally {
                        Log.d(TAG, "Audio tracker finalized, videoJob.isActive=${videoJob.isActive}")
                    }
                }
            }

            activeSessions[task.id] = session.copy(videoJob = videoJob, audioJob = audioJob)
            Log.d(TAG, "LL-HLS recording started: ${task.id}")

            videoJob.join()
            audioJob?.join()
            Log.d(TAG, "LL-HLS recording finished: ${task.id}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LL-HLS recording", e)
            onProgress(0, "启动失败: ${e.message}")
        }
    }

    suspend fun stopRecording(taskId: String): File? = withContext(Dispatchers.IO) {
        val session = activeSessions.remove(taskId) ?: return@withContext null
        session.isStopped = true
        session.videoJob?.let { it.cancel(); it.join() }
        session.audioJob?.let { it.cancel(); it.join() }
        session.scope.cancel()

        session.realTimeMuxer.flush()
        session.realTimeMuxer.stop()

        val outputFile = session.realTimeMuxer.getOutputFile()

        if (outputFile.exists() && outputFile.length() > 0) {
            Log.d(TAG, "Recording succeeded: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
            globalProgressCounters.remove(taskId)
            outputFile
        } else {
            Log.e(TAG, "Recording output invalid or missing")
            globalProgressCounters.remove(taskId)
            null
        }
    }

    suspend fun cancelRecording(taskId: String) {
        val session = activeSessions.remove(taskId) ?: return
        session.isStopped = true
        session.videoJob?.cancel()
        session.audioJob?.cancel()
        session.scope.cancel()
        session.realTimeMuxer.stop()
        globalProgressCounters.remove(taskId)
    }

    private suspend fun appendSegments(
        segments: List<Segment>,
        session: RecordingSession,
        isVideo: Boolean,
        onProgress: (Int, String) -> Unit
    ) {
        if (session.isStopped) return

        val deduplicator = if (isVideo) session.videoDeduplicator else session.audioDeduplicator
        val newSegments = segments.filter { deduplicator.markSegmentDownloaded(it.uri) }
        if (newSegments.isEmpty()) return

        partDownloader.downloadParallelOrdered(
            taskId = session.taskId,
            parts = newSegments.mapIndexed { i, s -> i to s.uri }
        ) { index, data ->
            if (session.isStopped) return@downloadParallelOrdered
            if (data != null) {
                if (isVideo) {
                    session.realTimeMuxer.addVideoFragment(data)
                } else {
                    session.realTimeMuxer.addAudioFragment(data)
                }
                val cnt = globalProgressCounters.compute(session.taskId) { _, v -> (v ?: 0) + 1 } ?: 1
                onProgress((5 + (cnt % 90)), "下载片段 #$cnt")
            }
        }
    }

    fun pauseRecording(taskId: String) {
        activeSessions[taskId]?.let {
            it.videoJob?.cancel(); it.audioJob?.cancel()
        }
    }

    fun hasActiveSession(taskId: String): Boolean = activeSessions.containsKey(taskId)
}
