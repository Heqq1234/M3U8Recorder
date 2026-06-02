package com.dl.m3u8recorder.llhls

import android.content.Context
import android.util.Log
import com.dl.m3u8recorder.manager.DownloadManager
import com.dl.m3u8recorder.model.DownloadTask
import com.dl.m3u8recorder.parser.InitSegment
import com.dl.m3u8recorder.parser.Part
import com.dl.m3u8recorder.parser.Segment
import com.dl.m3u8recorder.utils.Mp4OutputHelper
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * LL-HLS 主录制器
 * 编排所有 LL-HLS 录制组件
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

    // 活跃的录制会话
    private val activeSessions = ConcurrentHashMap<String, RecordingSession>()

    // 组件
    private val partDownloader = PartDownloader(okHttpClient)
    private val streamMuxer = StreamMuxer()

    /**
     * 录制会话
     */
    private data class RecordingSession(
        val taskId: String,
        val task: DownloadTask,
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

    /**
     * 开始 LL-HLS 录制
     */
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
            // 确定视频和音频 playlist URL
            val videoPlaylistUrl = task.getEffectiveUrl()
            val audioPlaylistUrl = task.audioTrackUrl

            // 创建输出文件
            val outputDir = Mp4OutputHelper.getOutputFile(context, task.fileName, "")
                .parentFile ?: context.cacheDir

            val videoFile = File(outputDir, "${task.id}_video.mp4")
            val audioFile = File(outputDir, "${task.id}_audio.mp4")

            // 初始化组件
            val videoTracker = VariantPlaylistTracker(okHttpClient)
            val audioTracker = audioPlaylistUrl?.let { VariantPlaylistTracker(okHttpClient) }

            val videoAppender = CmafAppender(videoFile)
            val audioAppender = audioPlaylistUrl?.let { CmafAppender(audioFile) }

            val videoDeduplicator = FragmentDeduplicator()
            val audioDeduplicator = FragmentDeduplicator()

            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            onProgress(1, "获取播放列表信息...")

            // 获取初始播放列表状态
            val videoState = videoTracker.fetchOnce(videoPlaylistUrl)

            if (videoState == null) {
                onProgress(0, "无法获取播放列表")
                return@withContext
            }

            // 下载初始化片段
            if (videoState.initSegment != null) {
                onProgress(2, "下载视频初始化片段...")
                val initData = partDownloader.downloadInitSegment(videoState.initSegment.uri)
                if (initData != null) {
                    videoAppender.writeInitSegment(initData)
                }
            }

            // 创建录制会话
            val session = RecordingSession(
                taskId = task.id,
                task = task,
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

            // 启动视频轨道录制
            val videoJob = scope.launch {
                videoTracker.trackPlaylist(
                    playlistUrl = videoPlaylistUrl,
                    onInitSegment = { initSeg ->
                        downloadAndWriteInit(videoAppender, initSeg)
                    },
                    onNewSegments = { segments ->
                        downloadAndAppendSegments(
                            segments,
                            videoAppender,
                            videoDeduplicator,
                            session,
                            onProgress
                        )
                    },
                    onNewParts = { parts ->
                        downloadAndAppendParts(
                            parts,
                            videoAppender,
                            videoDeduplicator,
                            session,
                            onProgress
                        )
                    }
                )
            }

            // 启动音频轨道录制（如果有）
            val audioJob = audioPlaylistUrl?.let { audioUrl ->
                scope.launch {
                    audioTracker?.trackPlaylist(
                        playlistUrl = audioUrl,
                        onInitSegment = { initSeg ->
                            audioAppender?.let { downloadAndWriteInit(it, initSeg) }
                        },
                        onNewSegments = { segments ->
                            downloadAndAppendSegments(
                                segments,
                                audioAppender,
                                audioDeduplicator,
                                session,
                                onProgress
                            )
                        },
                        onNewParts = { parts ->
                            downloadAndAppendParts(
                                parts,
                                audioAppender,
                                audioDeduplicator,
                                session,
                                onProgress
                            )
                        }
                    )
                }
            }

            // 更新会话中的 Job
            activeSessions[task.id] = session.copy(
                videoJob = videoJob,
                audioJob = audioJob
            )

            Log.d(TAG, "LL-HLS recording started: ${task.id}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LL-HLS recording", e)
            onProgress(0, "启动失败: ${e.message}")
        }
    }

    /**
     * 停止录制并生成最终文件
     */
    suspend fun stopRecording(taskId: String, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        val session = activeSessions.remove(taskId)

        if (session == null) {
            Log.w(TAG, "No active session for task: $taskId")
            return@withContext false
        }

        session.isStopped = true

        // 取消协程
        session.videoJob?.cancel()
        session.audioJob?.cancel()
        session.scope.cancel()

        // 刷新追加器
        session.videoAppender?.flush()
        session.audioAppender?.flush()

        // 合成最终文件
        val success = if (session.audioAppender != null) {
            // 有独立音频轨，需要合成
            streamMuxer.muxWithTimestampFix(
                videoFile = session.videoAppender!!.getOutputFile(),
                audioFile = session.audioAppender!!.getOutputFile(),
                outputFile = outputFile
            )
        } else {
            // 只有视频轨
            streamMuxer.copySingleStream(
                videoFile = session.videoAppender!!.getOutputFile(),
                outputFile = outputFile
            )
        }

        // 清理临时文件
        session.videoAppender?.getOutputFile()?.delete()
        session.audioAppender?.getOutputFile()?.delete()

        Log.d(TAG, "LL-HLS recording stopped: $taskId, success=$success")
        success
    }

    /**
     * 下载并写入初始化片段
     */
    private suspend fun downloadAndWriteInit(
        appender: CmafAppender,
        initSegment: InitSegment
    ) {
        val data = partDownloader.downloadInitSegment(initSegment.uri)
        if (data != null) {
            appender.writeInitSegment(data)
        }
    }

    /**
     * 下载并追加完整片段
     */
    private suspend fun downloadAndAppendSegments(
        segments: List<Segment>,
        appender: CmafAppender?,
        deduplicator: FragmentDeduplicator,
        session: RecordingSession,
        onProgress: (Int, String) -> Unit
    ) {
        if (appender == null || session.isStopped) return

        for (segment in segments) {
            if (session.isStopped) break

            // 去重
            if (!deduplicator.markSegmentDownloaded(segment.uri)) {
                continue
            }

            val data = partDownloader.download(segment.uri)
            if (data != null) {
                appender.appendFragment(data)

                val count = appender.getFragmentCount()
                onProgress(
                    (5 + count % 90),
                    "下载片段 #$count"
                )
            }
        }
    }

    /**
     * 下载并追加部分片段
     */
    private suspend fun downloadAndAppendParts(
        parts: List<Part>,
        appender: CmafAppender?,
        deduplicator: FragmentDeduplicator,
        session: RecordingSession,
        onProgress: (Int, String) -> Unit
    ) {
        if (appender == null || session.isStopped) return

        for (part in parts) {
            if (session.isStopped) break

            // 去重
            if (!deduplicator.markPartDownloaded(part.uri)) {
                continue
            }

            val data = partDownloader.download(part.uri)
            if (data != null) {
                appender.appendFragment(data)

                val count = appender.getFragmentCount()
                onProgress(
                    (5 + count % 90),
                    "下载部分片段 #$count"
                )
            }
        }
    }

    /**
     * 暂停录制
     */
    fun pauseRecording(taskId: String) {
        activeSessions[taskId]?.let { session ->
            session.videoJob?.cancel()
            session.audioJob?.cancel()
            session.videoAppender?.flush()
            session.audioAppender?.flush()
        }
    }

    /**
     * 恢复录制
     */
    suspend fun resumeRecording(taskId: String, onProgress: (Int, String) -> Unit) {
        activeSessions[taskId]?.let { session ->
            // 重新启动跟踪
            // 注：完整实现需要保存和恢复状态
            startRecording(session.task, onProgress)
        }
    }

    /**
     * 检查是否有活跃会话
     */
    fun hasActiveSession(taskId: String): Boolean = activeSessions.containsKey(taskId)
}
