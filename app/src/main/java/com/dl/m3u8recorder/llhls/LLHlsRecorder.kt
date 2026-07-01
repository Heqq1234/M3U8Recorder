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

/**
 * LL-HLS 主录制器
 */
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
    private val muxer = PipeMuxer()

    private data class RecordingSession(
        val taskId: String,
        val task: DownloadTask,
        val syncCoordinator: SyncCoordinator,
        val outputFile: File,
        val videoAppender: CmafAppender?,
        val audioAppender: CmafAppender?,
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

            val outputDir = Mp4OutputHelper.getOutputFile(context, task.fileName, "")
                .parentFile ?: context.cacheDir
            val outputFile = File(outputDir, "${task.fileName}.mp4").apply { parentFile?.mkdirs() }
            val videoFile = File(outputDir, "${task.id}_video.mp4")
            val audioFile = File(outputDir, "${task.id}_audio.mp4")

            val videoTracker = VariantPlaylistTracker(okHttpClient)
            val audioTracker = audioPlaylistUrl?.let { VariantPlaylistTracker(okHttpClient) }

            val videoAppender = CmafAppender(videoFile)
            val audioAppender = audioPlaylistUrl?.let { CmafAppender(audioFile) }

            val videoDeduplicator = FragmentDeduplicator()
            val audioDeduplicator = FragmentDeduplicator()
            val syncCoordinator = SyncCoordinator()

            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            onProgress(1, "获取播放列表信息...")
            val videoState = videoTracker.fetchOnce(videoPlaylistUrl)
            if (videoState == null) {
                onProgress(0, "无法获取播放列表")
                return@withContext
            }

            if (videoState.initSegment != null) {
                onProgress(2, "下载视频初始化片段...")
                partDownloader.downloadInitSegment(videoState.initSegment.uri)?.let {
                    videoAppender.writeInitSegment(it)
                }
            }
            if (audioPlaylistUrl != null) {
                audioTracker?.fetchOnce(audioPlaylistUrl)?.initSegment?.let {
                    onProgress(3, "下载音频初始化片段...")
                    partDownloader.downloadInitSegment(it.uri)?.let { d ->
                        audioAppender?.writeInitSegment(d)
                    }
                }
            }

            val session = RecordingSession(
                taskId = task.id,
                task = task,
                syncCoordinator = syncCoordinator,
                outputFile = outputFile,
                videoAppender = videoAppender,
                audioAppender = audioAppender,
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
                            appendSegments(segments, videoAppender, videoDeduplicator, session, onProgress)
                        }
                    )
                } finally {
                    session.syncCoordinator.finalize(SyncCoordinator.StreamType.VIDEO)
                }
            }

            val audioJob = audioPlaylistUrl?.let { audioUrl ->
                scope.launch {
                    try {
                        audioTracker?.trackPlaylist(
                            playlistUrl = audioUrl,
                            onInitSegment = {},
                            onNewSegments = { segments ->
                                appendSegments(segments, audioAppender, audioDeduplicator, session, onProgress)
                            },
                            isOtherTrackerActive = {
                                val active = videoJob.isActive
                                Log.d(TAG, "audio isOtherTrackerActive check: videoJob.active=$active")
                                active
                            }
                        )
                    } finally {
                        session.syncCoordinator.finalize(SyncCoordinator.StreamType.AUDIO)
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

    /**
     * 停止录制 → mux 音视频（两遍 mux 修正 PTS）
     */
    suspend fun stopRecording(taskId: String): File? = withContext(Dispatchers.IO) {
        val session = activeSessions.remove(taskId) ?: return@withContext null
        session.isStopped = true
        session.videoJob?.let { it.cancel(); it.join() }
        session.audioJob?.let { it.cancel(); it.join() }
        session.scope.cancel()

        session.videoAppender?.flush()
        session.audioAppender?.flush()

        val padInfo = session.syncCoordinator.getPadInfo()
        val startMismatchMs = session.syncCoordinator.getStartMismatchMs()
        Log.d(TAG, "startMismatchMs=$startMismatchMs padInfo=$padInfo")
        Log.d(TAG, "SyncCoordinator: ${session.syncCoordinator}")
        val success = muxer.mux(
            videoFile = session.videoAppender!!.getOutputFile(),
            audioFile = session.audioAppender!!.getOutputFile(),
            outputFile = session.outputFile,
            padInfo = padInfo,
            startMismatchMs = startMismatchMs
        )

        session.videoAppender?.getOutputFile()?.delete()
        session.audioAppender?.getOutputFile()?.delete()
        globalProgressCounters.remove(taskId)

        if (success) session.outputFile else null
    }

    fun cancelRecording(taskId: String) {
        val session = activeSessions.remove(taskId) ?: return
        session.isStopped = true
        session.videoJob?.cancel()
        session.audioJob?.cancel()
        session.scope.cancel()
        session.videoAppender?.flush()
        session.audioAppender?.flush()
        globalProgressCounters.remove(taskId)
    }

    private suspend fun appendSegments(
        segments: List<Segment>,
        appender: CmafAppender?,
        deduplicator: FragmentDeduplicator,
        session: RecordingSession,
        onProgress: (Int, String) -> Unit
    ) {
        if (appender == null || session.isStopped) return
        val newSegments = segments.filter { deduplicator.markSegmentDownloaded(it.uri) }
        if (newSegments.isEmpty()) return

        partDownloader.downloadParallelOrdered(
            newSegments.mapIndexed { i, s -> i to s.uri }
        ) { index, data ->
            if (session.isStopped) return@downloadParallelOrdered
            if (data != null) {
                appender.appendFragment(data)
                val seg = newSegments[index]
                val ms = (seg.duration * 1000).toLong()
                session.syncCoordinator.addSegment(
                    if (appender == session.videoAppender) SyncCoordinator.StreamType.VIDEO
                    else SyncCoordinator.StreamType.AUDIO,
                    ms,
                    seg.sequenceNumber
                )
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