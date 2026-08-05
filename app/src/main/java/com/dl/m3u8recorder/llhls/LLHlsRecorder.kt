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
        .connectionPool(okhttp3.ConnectionPool(32, 5, java.util.concurrent.TimeUnit.MINUTES))
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        })
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
        val outputFile: File,
        val videoFile: File,
        val audioFile: File?,
        val videoTracker: VariantPlaylistTracker?,
        val audioTracker: VariantPlaylistTracker?,
        val scope: CoroutineScope,
        var videoJob: Job?,
        var audioJob: Job?,
        // 各轨实际下载的首个 segment 的墙钟 PDT(ms)，用于事后 PipeMuxer 跨轨对齐
        @Volatile var videoFirstPdtMs: Long? = null,
        @Volatile var audioFirstPdtMs: Long? = null,
        @Volatile var isStopped: Boolean = false,
        // 跨轨首段墙钟对齐点(ms)：两轨开始落盘的统一墙钟。
        // null=无需对齐(无音频轨/无 PDT)；非 null 时各轨只下载 pdt>=align 的 segment。
        val alignDeferred: CompletableDeferred<Long?> = CompletableDeferred(),
        @Volatile var alignPdtMs: Long? = null,
        // 去重：已下载的 segment URI 集合
        val downloadedSegments: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        // 实时合成器：样本级精确对齐
        val realTimeMuxer: RealTimeMuxer = RealTimeMuxer(outputFile, task.id)
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
            val videoFile = File(outputFile.parentFile, "${task.id}_video.mp4")
            val audioFile = audioPlaylistUrl?.let { File(outputFile.parentFile, "${task.id}_audio.mp4") }

            val videoTracker = VariantPlaylistTracker(okHttpClient)
            val audioTracker = audioPlaylistUrl?.let { VariantPlaylistTracker(okHttpClient) }

            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            onProgress(1, "获取播放列表信息...")
            val videoState = videoTracker.fetchOnce(videoPlaylistUrl)
            if (videoState == null) {
                onProgress(0, "无法获取播放列表")
                return@withContext
            }

            val session = RecordingSession(
                taskId = task.id,
                task = task,
                outputFile = outputFile,
                videoFile = videoFile,
                audioFile = audioFile,
                videoTracker = videoTracker,
                audioTracker = audioTracker,
                scope = scope,
                videoJob = null,
                audioJob = null
            )

            activeSessions[task.id] = session

            // 下载并传入 init segment 给 RealTimeMuxer，用于初始化 parser
            if (videoState.initSegment != null) {
                onProgress(2, "下载视频初始化片段...")
                Log.d(TAG, "[${task.id}] Downloading video init segment: ${videoState.initSegment.uri}")
                val videoInit = partDownloader.downloadInitSegment(videoState.initSegment.uri, task.id)
                if (videoInit != null) {
                    Log.d(TAG, "[${task.id}] Video init segment OK (${videoInit.size} bytes), setting to RealTimeMuxer")
                    session.realTimeMuxer.setVideoInitData(videoInit)
                } else {
                    Log.e(TAG, "[${task.id}] Failed to download video init segment")
                }
            } else {
                Log.w(TAG, "[${task.id}] No video init segment in playlist")
            }
            if (audioPlaylistUrl != null) {
                val audioState = audioTracker?.fetchOnce(audioPlaylistUrl)
                if (audioState?.initSegment != null) {
                    onProgress(3, "下载音频初始化片段...")
                    Log.d(TAG, "[${task.id}] Downloading audio init segment: ${audioState.initSegment.uri}")
                    val audioInit = partDownloader.downloadInitSegment(audioState.initSegment.uri, task.id)
                    if (audioInit != null) {
                        Log.d(TAG, "[${task.id}] Audio init segment OK (${audioInit.size} bytes), setting to RealTimeMuxer")
                        session.realTimeMuxer.setAudioInitData(audioInit)
                    } else {
                        Log.e(TAG, "[${task.id}] Failed to download audio init segment")
                    }
                } else {
                    Log.w(TAG, "[${task.id}] No audio init segment, setting no audio track")
                    session.realTimeMuxer.setNoAudioTrack()
                }
            } else {
                Log.d(TAG, "[${task.id}] No audio playlist URL, setting no audio track")
                session.realTimeMuxer.setNoAudioTrack()
            }

            onProgress(5, "开始录制...")

            val videoJob = scope.launch {
                try {
                    Log.w(TAG, "[$task.id] VIDEO tracker 启动 @ ${System.currentTimeMillis()}ms")
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
                        Log.w(TAG, "[$task.id] AUDIO tracker 启动 @ ${System.currentTimeMillis()}ms")
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

            // 直接在 session 上赋值 job，保持 map 里的 session 与 appendSegments 闭包捕获的是同一对象
            // （PDT 等可变字段才能跨阶段传递）。data class copy 会产生新对象，导致可变字段丢失。
            session.videoJob = videoJob
            session.audioJob = audioJob
            activeSessions[task.id] = session
            Log.d(TAG, "LL-HLS recording started: ${task.id}")

            // 跨轨首段墙钟对齐协调：等两轨各自首段 PDT 收集到后(appendSegments 在
            // 每批 segments 到达时写入 videoFirstPdtMs/audioFirstPdtMs)，取 max 作为
            // 两轨统一落盘起点。对齐点下发给 RealTimeMuxer 做样本级精确裁剪。
            // 无音频轨/无 PDT 时 align=null，放行全部。
            val audioJobLocal = audioJob
            Log.d(TAG, "[${task.id}] 跨轨对齐协调启动: hasAudio=${audioJobLocal != null}")
            scope.launch {
                if (audioJobLocal == null) {
                    Log.w(TAG, "[${task.id}] 跨轨对齐放弃: 无 audio 轨, 放行全部")
                    session.alignDeferred.complete(null)
                    return@launch
                }
                val startTime = System.currentTimeMillis()
                val ALIGN_TIMEOUT_MS = 5000L // 5秒超时

                while (isActive) {
                    val vPdt = session.videoFirstPdtMs
                    val aPdt = session.audioFirstPdtMs

                    // 两轨PDT都就绪，计算对齐点
                    if (vPdt != null && aPdt != null) {
                        val alignMs = maxOf(vPdt, aPdt)
                        session.alignPdtMs = alignMs
                        // 关键：把对齐点下发给实时合成器，做帧级裁剪
                        session.realTimeMuxer.setAlignPoint(alignMs)
                        session.alignDeferred.complete(alignMs)
                        Log.d(TAG, "[${task.id}] 跨轨对齐基准: $alignMs ms")
                        return@launch
                    }

                    // 超时兜底：5秒没拿到双轨PDT，放弃精确对齐
                    if (System.currentTimeMillis() - startTime > ALIGN_TIMEOUT_MS) {
                        Log.w(TAG, "[${task.id}] PDT对齐超时，放弃精确对齐")
                        session.alignDeferred.complete(null)
                        return@launch
                    }

                    // 某轨结束仍无PDT，放弃对齐
                    if (session.isStopped ||
                        (!videoJob.isActive && vPdt == null) ||
                        (!audioJobLocal.isActive && aPdt == null)) {
                        session.alignDeferred.complete(null)
                        Log.w(TAG, "[${task.id}] 跨轨对齐放弃: videoPdt=$vPdt audioPdt=$aPdt (某轨无PDT/已结束)")
                        return@launch
                    }
                    delay(50)
                }
            }

            videoJob.join()
            audioJob?.join()
            Log.d(TAG, "LL-HLS recording finished: ${task.id}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LL-HLS recording", e)
            onProgress(0, "启动失败: ${e.message}")
        }
    }

    /**
     * 停止录制 → RealTimeMuxer 实时合成（样本级精确对齐）
     * 如果 RealTimeMuxer 失败，回退到 PipeMuxer 后合并
     */
    suspend fun stopRecording(taskId: String): File? = withContext(Dispatchers.IO) {
        val session = activeSessions.remove(taskId) ?: return@withContext null
        session.isStopped = true
        Log.w(TAG, "[$taskId] ===== STOP RECORDING =====")
        Log.d(TAG, "[$taskId] Downloaded segments: ${session.downloadedSegments.size}")
        Log.d(TAG, "[$taskId] Video first PDT: ${session.videoFirstPdtMs}ms, Audio first PDT: ${session.audioFirstPdtMs}ms")

        // 1. 取消下载协程
        session.videoJob?.cancel()
        session.audioJob?.cancel()
        runCatching { session.videoJob?.join() }
        runCatching { session.audioJob?.join() }
        Log.d(TAG, "[$taskId] Download jobs cancelled")

        // 2. 等待合成器排空所有缓存、写入尾部、释放资源
        val muxStartMs = System.currentTimeMillis()
        val success = session.realTimeMuxer.stop()
        val muxElapsed = System.currentTimeMillis() - muxStartMs
        Log.d(TAG, "[$taskId] RealTimeMuxer.stop() done: success=$success, elapsed=${muxElapsed}ms")

        // 3. 清理协程域和临时文件
        session.scope.cancel()
        session.videoFile.delete()
        session.audioFile?.delete()
        globalProgressCounters.remove(taskId)

        if (success && session.outputFile.exists() && session.outputFile.length() > 0) {
            val finalSize = session.outputFile.length()
            Log.w(TAG, "[$taskId] ===== RECORDING SUCCESS =====")
            Log.w(TAG, "[$taskId] Output: ${session.outputFile.absolutePath}")
            Log.w(TAG, "[$taskId] File size: $finalSize bytes (${finalSize / 1024 / 1024} MB)")
            session.outputFile
        } else {
            Log.e(TAG, "[$taskId] ===== RECORDING FAILED =====")
            Log.e(TAG, "[$taskId] success=$success, exists=${session.outputFile.exists()}, size=${session.outputFile.length()}")
            null
        }
    }

    suspend fun cancelRecording(taskId: String) {
        val session = activeSessions.remove(taskId) ?: return
        session.isStopped = true
        session.videoJob?.cancel()
        session.audioJob?.cancel()
        session.scope.cancel()
        session.videoFile.delete()
        session.audioFile?.delete()
        globalProgressCounters.remove(taskId)
    }

    private suspend fun appendSegments(
        segments: List<Segment>,
        session: RecordingSession,
        isVideo: Boolean,
        onProgress: (Int, String) -> Unit
    ) {
        if (session.isStopped) return

        val trackLabel = if (isVideo) "VIDEO" else "AUDIO"
        Log.d(TAG, "[${session.taskId}] $trackLabel appendSegments called: ${segments.size} segments received")

        val newSegments = segments.filter { session.downloadedSegments.add(it.uri) }
        if (newSegments.isEmpty()) {
            Log.v(TAG, "[${session.taskId}] $trackLabel all segments already downloaded, skipping")
            return
        }
        Log.d(TAG, "[${session.taskId}] $trackLabel new segments to download: ${newSegments.size} (total downloaded=${session.downloadedSegments.size})")

        // 在下载前先收集该轨首个有 PDT 的 segment 墙钟，供跨轨对齐协调协程判定 align 点。
        // （比在下载完成回调里记录更早，避免 appendSegments 与 align 协调互相等待死锁）
        for (s in newSegments) {
            val pdt = s.programDateTimeMs ?: continue
            if (isVideo && session.videoFirstPdtMs == null) {
                session.videoFirstPdtMs = pdt
                Log.d(TAG, "[${session.taskId}] 首段 video PDT = $pdt ms")
            } else if (!isVideo && session.audioFirstPdtMs == null) {
                session.audioFirstPdtMs = pdt
                Log.d(TAG, "[${session.taskId}] 首段 audio PDT = $pdt ms")
            }
            break
        }

        // 所有分片都下载，对齐裁剪交给 RealTimeMuxer 按样本精度处理
        val toDownload = newSegments
        if (toDownload.isEmpty()) return

        val batchStartMs = System.currentTimeMillis()
        Log.d(TAG, "[${session.taskId}] $trackLabel starting download of ${toDownload.size} segments")

        partDownloader.downloadParallelOrdered(
            taskId = session.taskId,
            items = toDownload.mapIndexed { i, s ->
                PartDownloader.DownloadItem(i, s.uri, s.programDateTimeMs)
            }
        ) { index, data, pdtMs ->
            if (session.isStopped) return@downloadParallelOrdered
            val seg = toDownload.getOrNull(index)
            val segUri = seg?.uri?.takeLast(50) ?: "?"
            if (data != null) {
                // 分片数据 + 该分片PDT 直接送入合成器
                Log.v(TAG, "[${session.taskId}] $trackLabel fragment #$index ($segUri, ${data.size} bytes, pdt=$pdtMs) -> muxer")
                if (isVideo) {
                    session.realTimeMuxer.addVideoFragment(data, pdtMs)
                    if (session.videoFirstPdtMs == null && pdtMs != null) {
                        session.videoFirstPdtMs = pdtMs
                        Log.d(TAG, "[${session.taskId}] VIDEO first PDT recorded: $pdtMs ms")
                    }
                } else {
                    session.realTimeMuxer.addAudioFragment(data, pdtMs)
                    if (session.audioFirstPdtMs == null && pdtMs != null) {
                        session.audioFirstPdtMs = pdtMs
                        Log.d(TAG, "[${session.taskId}] AUDIO first PDT recorded: $pdtMs ms")
                    }
                }
                // 更新进度
                val cnt = globalProgressCounters.compute(session.taskId) { _, v -> (v ?: 0) + 1 } ?: 1
                onProgress(minOf(95, 5 + cnt), "录制中...片段 #$cnt")
            } else {
                Log.w(TAG, "[${session.taskId}] $trackLabel fragment #$index ($segUri) download FAILED, skipping")
            }
        }

        val batchElapsed = System.currentTimeMillis() - batchStartMs
        Log.d(TAG, "[${session.taskId}] $trackLabel batch done: ${toDownload.size} segments, ${batchElapsed}ms")
    }

    fun pauseRecording(taskId: String) {
        activeSessions[taskId]?.let {
            it.videoJob?.cancel(); it.audioJob?.cancel()
        }
    }

    fun hasActiveSession(taskId: String): Boolean = activeSessions.containsKey(taskId)
}
