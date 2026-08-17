package com.dl.m3u8recorder.llhls

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.min

class RealTimeMuxer(
    private val outputFile: File,
    private val taskId: String
) {
    companion object {
        private const val TAG = "RealTimeMuxer"
        private const val MAX_SAMPLE_QUEUE_SIZE = 2000
        // 最大允许单轨超前 500ms，防止网络抖动导致时间差越拉越大
        private const val MAX_LEAD_US = 500_000L
        // 首帧关键帧最大丢弃帧数，超过则强制以非关键帧起始
        private const val MAX_DROP_VIDEO_FRAMES = 300
        // AAC LC 标准 priming 采样数 (48kHz 下约 42.7ms)
        private const val AAC_PRIMING_SAMPLES = 2048
        private const val AUDIO_TIMESCALE = 48000
        private val PRIMING_OFFSET_US = AAC_PRIMING_SAMPLES * 1_000_000L / AUDIO_TIMESCALE // 42666μs ≈ 42.7ms
    }

    @Volatile private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null

    private var audioResolved = false
    @Volatile private var isStarted = false
    @Volatile private var isStopped = false

    // 视频分辨率覆盖值（形如 "1920x1080"）。非空时覆盖 init segment 解析出的宽高。
    // 仅 Stripchat 等"各变体共用同一 init、其 tkhd 占位分辨率与真实变体不符"的场景会设置；
    // Chaturbate 不设此值，行为完全不变。
    @Volatile private var videoResolutionOverride: String? = null

    // 视频首帧丢弃计数
    private var droppedVideoFrames = 0

    private var videoParser: Fmp4FragmentParser? = null
    private var audioParser: Fmp4FragmentParser? = null

    // 视频 NAL 长度前缀字节数 (lengthSizeMinusOne + 1)，来自 init segment 的 avcC/hvcC。
    // fMP4 样本为 AVCC(长度前缀) 格式，写入 MediaMuxer 前必须转成 Annex-B(起始码) 格式。
    private var videoNalLengthSize = 4

    /** 公开给外部使用的 sample 数据结构 */
    data class QueuedSample(
        val pts: Long,
        val data: ByteArray,
        val isKeyFrame: Boolean
    )

    /** 待解析的 fragment，携带它所属 segment 的 PDT（跨轨墙钟对齐用） */
    private data class PendingFragment(val data: ByteArray, val pdtMs: Long?)

    // 关键：视频样本必须按【解码顺序】写入 MediaMuxer（H.264 含 B 帧时解码顺序 ≠ 显示顺序）。
    // 若用 PriorityQueue 按 pts(显示时间) 排序，B 帧会被排到它所引用的参考帧之前写盘，
    // 解码器解不出参考帧 -> 周期性花屏，到下一个 IDR 才恢复。因此视频轨必须用保序的 FIFO。
    // MediaMuxer 会依据各样本的真实 presentationTimeUs 自行写入 ctts 表达显示顺序。
    private val videoSampleQueue = ConcurrentLinkedQueue<QueuedSample>()
    // 音频(AAC)无 B 帧，pts 天然单调，FIFO 与按 pts 排序等价；统一用 FIFO 避免任何重排。
    private val audioSampleQueue = ConcurrentLinkedQueue<QueuedSample>()

    // 诊断：每条轨首个 sample 的墙钟 PTS 与收到时的墙钟，用于验证跨轨是否对齐
    @Volatile private var firstVideoPts: Long = Long.MIN_VALUE
    @Volatile private var firstVideoWallMs: Long = 0
    @Volatile private var firstAudioPts: Long = Long.MIN_VALUE
    @Volatile private var firstAudioWallMs: Long = 0

    // 写入 MediaMuxer 时的相对 PTS 基准(微秒)：用两条轨首样墙钟 PTS 的较小值。
    // 队列里仍存绝对墙钟 PTS 保证跨轨交错正确；写入时减去此 base，让 MediaMuxer
    // 收到从 0 附近起的相对 PTS（绝对墙钟值如 1.78e15us 会让 MPEG4Writer native write 失败）。
    @Volatile private var ptsBaseUs: Long = Long.MIN_VALUE

    // 跨轨对齐基准：早于该时间的样本直接丢弃（样本级精确裁剪）
    @Volatile private var alignPdtUs: Long? = null

    // 首帧关键帧保护：没找到第一个关键帧之前，所有视频帧直接丢弃
    @Volatile private var hasFoundFirstKeyFrame = false
    @Volatile private var basePtsUs: Long = Long.MIN_VALUE

    private val videoFragmentQueue = ConcurrentLinkedQueue<PendingFragment>()
    private val audioFragmentQueue = ConcurrentLinkedQueue<PendingFragment>()

    // Mutex 保护视频/音频样本队列的并发访问（ConcurrentLinkedQueue 本身线程安全，Mutex 用于保证跨多个操作的一致性）
    private val videoQueueMutex = Mutex()
    private val audioQueueMutex = Mutex()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var muxJob: Job? = null
    private var parseJob: Job? = null
    private val buffer = ByteBuffer.allocate(2 * 1024 * 1024)

    /**
     * 设置视频分辨率覆盖（形如 "1920x1080"）。
     * 必须在 setVideoInitData 之前调用。非空时，用真实变体分辨率覆盖从 init segment
     * 解析出的宽高（部分站点 tkhd 是占位分辨率，与真实变体不符，导致 MediaMuxer 建轨
     * 宽高不匹配、native writer 拒写 -> 录制出 3KB 空文件）。
     * 不设置（null）时完全沿用原逻辑。
     */
    fun setVideoResolutionOverride(resolution: String?) {
        videoResolutionOverride = resolution
        Log.d(TAG, "[$taskId] Video resolution override: ${resolution ?: "null(沿用 init 分辨率)"}")
    }

    @Synchronized
    fun setVideoInitData(data: ByteArray) {
        videoFormat = extractMediaFormat(data)
        val videoTimescale = Fmp4FragmentParser.parseTimescaleFromInitSegment(data)
        videoParser = Fmp4FragmentParser(taskId, videoTimescale)
        videoNalLengthSize = Fmp4FragmentParser.parseNalLengthSizeFromInitSegment(data)
        Log.d(TAG, "[$taskId] Video NAL length size = $videoNalLengthSize (from init segment)")
        if (videoFormat != null) {
            val mime = videoFormat!!.getString(MediaFormat.KEY_MIME) ?: "?"
            var w = videoFormat!!.getInteger(MediaFormat.KEY_WIDTH)
            var h = videoFormat!!.getInteger(MediaFormat.KEY_HEIGHT)
            // 用真实变体分辨率覆盖 init 占位分辨率（仅 Stripchat 等设置了覆盖值的路径触发）
            videoResolutionOverride?.let { res ->
                val parts = res.split("x")
                if (parts.size == 2) {
                    val ow = parts[0].toIntOrNull()
                    val oh = parts[1].toIntOrNull()
                    if (ow != null && oh != null && ow > 0 && oh > 0) {
                        videoFormat!!.setInteger(MediaFormat.KEY_WIDTH, ow)
                        videoFormat!!.setInteger(MediaFormat.KEY_HEIGHT, oh)
                        Log.w(TAG, "[$taskId] ★ 用真实分辨率覆盖 init 占位分辨率: ${w}x${h} -> ${ow}x${oh}")
                        w = ow; h = oh
                    }
                }
            }
            Log.d(TAG, "[$taskId] Video format: $mime ${w}x${h}, timescale=$videoTimescale")
        } else {
            Log.e(TAG, "[$taskId] Failed to extract video format")
        }
        checkAndStart()
    }

    @Synchronized
    fun setAudioInitData(data: ByteArray) {
        audioFormat = extractMediaFormat(data)
        val audioTimescale = Fmp4FragmentParser.parseTimescaleFromInitSegment(data)
        audioParser = Fmp4FragmentParser(taskId, audioTimescale)
        if (audioFormat != null) {
            val mime = audioFormat!!.getString(MediaFormat.KEY_MIME) ?: "?"
            val sr = audioFormat!!.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = audioFormat!!.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            Log.d(TAG, "[$taskId] Audio format: $mime ${sr}Hz ${ch}ch, timescale=$audioTimescale")
        } else {
            Log.e(TAG, "[$taskId] Failed to extract audio format")
        }
        audioResolved = true
        checkAndStart()
    }

    @Synchronized
    fun setNoAudioTrack() {
        audioResolved = true
        Log.d(TAG, "[$taskId] No audio track")
        checkAndStart()
    }

    private var videoFragmentCount = 0
    private var audioFragmentCount = 0

    fun addVideoFragment(data: ByteArray, pdtMs: Long? = null) {
        if (isStopped) {
            Log.v(TAG, "[$taskId] addVideoFragment ignored: already stopped")
            return
        }
        videoFragmentCount++
        val cnt = videoFragmentCount
        Log.v(TAG, "[$taskId] addVideoFragment #$cnt (${data.size} bytes, pdt=$pdtMs), queueSize=${videoFragmentQueue.size + 1}")
        videoFragmentQueue.offer(PendingFragment(data, pdtMs))
    }

    fun addAudioFragment(data: ByteArray, pdtMs: Long? = null) {
        if (isStopped) {
            Log.v(TAG, "[$taskId] addAudioFragment ignored: already stopped")
            return
        }
        audioFragmentCount++
        val cnt = audioFragmentCount
        Log.v(TAG, "[$taskId] addAudioFragment #$cnt (${data.size} bytes, pdt=$pdtMs), queueSize=${audioFragmentQueue.size + 1}")
        audioFragmentQueue.offer(PendingFragment(data, pdtMs))
    }

    @Synchronized
    fun flush() {
        Log.d(TAG, "[$taskId] Flushing: videoQueue=${videoFragmentQueue.size} audioQueue=${audioFragmentQueue.size} videoSamples=${videoSampleQueue.size} audioSamples=${audioSampleQueue.size}")
    }

    /**
     * 设置跨轨对齐基准墙钟时间（毫秒）
     * 早于该时间的样本会被直接丢弃
     */
    fun setAlignPoint(alignPdtMs: Long) {
        this.alignPdtUs = alignPdtMs * 1000L
        Log.d(TAG, "[$taskId] 对齐基准设置: ${alignPdtMs}ms")
    }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        isStopped = true
        Log.w(TAG, "[$taskId] ===== REALTIME MUXER STOP START =====")
        Log.d(TAG, "[$taskId] Stats before stop: videoFrags=$videoFragmentCount audioFrags=$audioFragmentCount totalWritten=(V=$totalVideoWritten A=$totalAudioWritten)")
        Log.d(TAG, "[$taskId] Queues before stop: videoFrag=${videoFragmentQueue.size} audioFrag=${audioFragmentQueue.size} videoSample=${videoSampleQueue.size} audioSample=${audioSampleQueue.size}")

        // 1. 等待解析协程把所有待解析分片处理完
        val parseWaitStart = System.currentTimeMillis()
        parseJob?.join()
        Log.d(TAG, "[$taskId] Parse job joined, elapsed=${System.currentTimeMillis() - parseWaitStart}ms")

        // 2. 等待复用协程结束（它会处理完所有剩余样本）
        val muxWaitStart = System.currentTimeMillis()
        muxJob?.join()
        Log.d(TAG, "[$taskId] Mux job joined, elapsed=${System.currentTimeMillis() - muxWaitStart}ms")

        // 3. 最后再排空一次（确保没有遗漏）
        drainSampleQueues()
        val remainingVideo = videoQueueMutex.run { videoSampleQueue.size }
        val remainingAudio = audioQueueMutex.run { audioSampleQueue.size }
        if (remainingVideo > 0 || remainingAudio > 0) {
            Log.w(TAG, "[$taskId] Stop drain: remaining V=$remainingVideo A=$remainingAudio")
        }

        // 4. 停止并释放 MediaMuxer
        release()

        val finalSize = outputFile.length()
        Log.w(TAG, "[$taskId] ===== REALTIME MUXER STOP END =====")
        Log.w(TAG, "[$taskId] Final output: ${outputFile.absolutePath}")
        Log.w(TAG, "[$taskId] Final size: $finalSize bytes (${finalSize / 1024} KB, ${finalSize / 1024 / 1024} MB)")
        Log.w(TAG, "[$taskId] Total written: V=$totalVideoWritten A=$totalAudioWritten, droppedPreKeyFrame=$droppedVideoFrames")
        true
    }

    @Synchronized
    fun release() {
        try {
            val beforeSize = outputFile.length()
            Log.d(TAG, "[$taskId] Before MediaMuxer.stop(): ${beforeSize} bytes")
            mediaMuxer?.stop()
            mediaMuxer?.release()
            val afterSize = outputFile.length()
            Log.d(TAG, "[$taskId] MediaMuxer released, output size=${afterSize} bytes")
            mediaMuxer = null
        } catch (e: Exception) {
            Log.e(TAG, "[$taskId] Failed to release MediaMuxer", e)
        }
    }

    // 暴露的入队方法（带Mutex保护）
    suspend fun addVideoSample(sample: QueuedSample) {
        videoQueueMutex.withLock {
            if (videoSampleQueue.size < MAX_SAMPLE_QUEUE_SIZE) {
                videoSampleQueue.offer(sample)
            }
        }
    }

    suspend fun addAudioSample(sample: QueuedSample) {
        audioQueueMutex.withLock {
            if (audioSampleQueue.size < MAX_SAMPLE_QUEUE_SIZE) {
                audioSampleQueue.offer(sample)
            }
        }
    }

    suspend fun peekVideoSample(): QueuedSample? {
        return videoQueueMutex.withLock {
            videoSampleQueue.peek()
        }
    }

    suspend fun peekAudioSample(): QueuedSample? {
        return audioQueueMutex.withLock {
            audioSampleQueue.peek()
        }
    }

    fun getOutputFile(): File = outputFile

    private fun extractMediaFormat(initData: ByteArray): MediaFormat? {
        // 用 MediaExtractor 获取基础格式（优先 temp file，回退 ByteArrayDataSource）
        val baseFormat = extractWithMediaExtractor(initData)

        if (baseFormat != null) {
            // 关键：确保 csd-0/csd-1 存在。MediaExtractor 只解析 init segment 时，
            // 很多设备上不会填充 codec-specific data，导致 MediaMuxer.start() 内部
            // 初始化失败 → writeSampleData 报 "pushBuffer called before start"
            val hasCsd = baseFormat.containsKey("csd-0")
            Log.d(TAG, "[$taskId] MediaExtractor format: mime=${baseFormat.getString(MediaFormat.KEY_MIME)}, hasCsd0=$hasCsd")

            if (!hasCsd) {
                Log.w(TAG, "[$taskId] csd-0 missing from MediaExtractor format, injecting from init segment...")
                injectCsdFromInitSegment(baseFormat, initData)
            }
            return baseFormat
        }

        // 回退：全部手动构建
        Log.w(TAG, "[$taskId] MediaExtractor failed, building format manually")
        return buildMediaFormatFromInitSegment(initData)
    }

    /**
     * 尝试用 MediaExtractor 解析 init segment
     */
    private fun extractWithMediaExtractor(initData: ByteArray): MediaFormat? {
        // 策略 1: temp file
        var extractor: MediaExtractor? = null
        var tempFile: java.io.File? = null
        try {
            tempFile = java.io.File.createTempFile("init_", ".mp4", outputFile.parentFile)
            tempFile!!.writeBytes(initData)

            extractor = MediaExtractor()
            extractor.setDataSource(tempFile.absolutePath)
            if (extractor.trackCount > 0) {
                val format = extractor.getTrackFormat(0)
                Log.d(TAG, "[$taskId] MediaExtractor(temp file): mime=${format.getString(MediaFormat.KEY_MIME)}, trackCount=${extractor.trackCount}")
                return format
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$taskId] MediaExtractor(temp file) failed: ${e.message}")
        } finally {
            extractor?.release()
            tempFile?.delete()
        }

        // 策略 2: ByteArrayDataSource
        extractor = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(ByteArrayDataSource(initData))
            if (extractor.trackCount > 0) {
                val format = extractor.getTrackFormat(0)
                Log.d(TAG, "[$taskId] MediaExtractor(memory): mime=${format.getString(MediaFormat.KEY_MIME)}")
                return format
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$taskId] MediaExtractor(memory) failed: ${e.message}")
        } finally {
            extractor?.release()
        }

        return null
    }

    /**
     * 从 init segment 中提取 csd-0/csd-1 并注入到 MediaFormat
     * 这是解决 "pushBuffer called before start" 的关键
     */
    private fun injectCsdFromInitSegment(format: MediaFormat, initData: ByteArray) {
        try {
            val csdMap = parseCsdFromInitSegment(initData)
            for ((key, value) in csdMap) {
                if (value.isEmpty()) continue
                // 关键：不能用 containsKey 判断，MediaExtractor 经常返回 key 存在
                // 但 buffer 为空的 csd-0，导致 MediaMuxer 无法初始化编码器
                val existingBuf = format.getByteBuffer(key)
                val needsInjection = existingBuf == null || existingBuf.remaining() == 0
                if (needsInjection) {
                    val newBuf = java.nio.ByteBuffer.allocateDirect(value.size)
                    newBuf.put(value)
                    newBuf.flip()
                    format.setByteBuffer(key, newBuf)
                    Log.d(TAG, "[$taskId] Injected $key (${value.size} bytes, was ${if (existingBuf == null) "missing" else "empty"})")
                }
            }
            // 补充分辨率
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                try { format.getInteger(MediaFormat.KEY_WIDTH) } catch (e: Exception) { format.setInteger(MediaFormat.KEY_WIDTH, 1920) }
                try { format.getInteger(MediaFormat.KEY_HEIGHT) } catch (e: Exception) { format.setInteger(MediaFormat.KEY_HEIGHT, 1080) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$taskId] Failed to inject csd: ${e.message}")
        }
    }

    /**
     * 从 fMP4 init segment 解析 codec-specific data
     * 返回 Map，key 为 "csd-0"/"csd-1"/"width"/"height"
     */
    private fun parseCsdFromInitSegment(initData: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        var pos = 0

        while (pos + 8 <= initData.size) {
            val size = ((initData[pos].toLong() and 0xFF) shl 24) or
                    ((initData[pos + 1].toLong() and 0xFF) shl 16) or
                    ((initData[pos + 2].toLong() and 0xFF) shl 8) or
                    (initData[pos + 3].toLong() and 0xFF)
            if (size <= 0 || pos + size.toInt() > initData.size) break
            val type = String(initData, pos + 4, 4, Charsets.US_ASCII)

            if (type == "moov") {
                findCsdInMoov(initData, pos + 8, pos + size.toInt(), result)
            }
            pos += size.toInt()
        }
        return result
    }

    private fun findCsdInMoov(data: ByteArray, start: Int, end: Int, result: MutableMap<String, ByteArray>) {
        var pos = start
        while (pos + 8 <= end && pos + 8 <= data.size) {
            val size = ((data[pos].toLong() and 0xFF) shl 24) or
                    ((data[pos + 1].toLong() and 0xFF) shl 16) or
                    ((data[pos + 2].toLong() and 0xFF) shl 8) or
                    (data[pos + 3].toLong() and 0xFF)
            if (size <= 0) break
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)

            if (type == "trak") {
                findCsdInTrak(data, pos + 8, pos + size.toInt(), result)
                if (result.containsKey("csd-0")) return // 找到一个就够了
            }
            pos += size.toInt()
        }
    }

    private fun findCsdInTrak(data: ByteArray, start: Int, end: Int, result: MutableMap<String, ByteArray>) {
        var pos = start
        while (pos + 8 <= end && pos + 8 <= data.size) {
            val size = ((data[pos].toLong() and 0xFF) shl 24) or
                    ((data[pos + 1].toLong() and 0xFF) shl 16) or
                    ((data[pos + 2].toLong() and 0xFF) shl 8) or
                    (data[pos + 3].toLong() and 0xFF)
            if (size <= 0) break
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)

            when (type) {
                "tkhd" -> {
                    // 提取 width/height (16.16 fixed point)
                    if (pos + 92 <= data.size) {
                        val version = data[pos + 8].toInt() and 0xFF
                        val matrixStart = pos + 8 + (if (version == 0) 48 else 60)
                        val wOffset = matrixStart + 24
                        val hOffset = wOffset + 4
                        if (hOffset + 4 <= data.size) {
                            result["width"] = data.copyOfRange(wOffset, wOffset + 4)
                            result["height"] = data.copyOfRange(hOffset, hOffset + 4)
                        }
                    }
                }
                "stsd" -> findCsdInStsd(data, pos + 8, pos + size.toInt(), result)
            }
            pos += size.toInt()
        }
    }

    private fun findCsdInStsd(data: ByteArray, start: Int, end: Int, result: MutableMap<String, ByteArray>) {
        if (start + 8 > data.size || start + 8 > end) return
        // stsd: version(1) + flags(3) + entry_count(4) = 8 bytes header
        val entryCount = ((data[start + 4].toInt() and 0xFF) shl 24) or
                ((data[start + 5].toInt() and 0xFF) shl 16) or
                ((data[start + 6].toInt() and 0xFF) shl 8) or
                (data[start + 7].toInt() and 0xFF)
        if (entryCount == 0) return

        var pos = start + 8
        if (pos + 8 > data.size || pos + 8 > end) return
        val entrySize = ((data[pos].toLong() and 0xFF) shl 24) or
                ((data[pos + 1].toLong() and 0xFF) shl 16) or
                ((data[pos + 2].toLong() and 0xFF) shl 8) or
                (data[pos + 3].toLong() and 0xFF)
        val entryType = String(data, pos + 4, 4, Charsets.US_ASCII)
        val entryEnd = minOf(pos + entrySize.toInt(), end, data.size)

        // 遍历 sample entry 内的子 box，找 codec config
        var childPos = pos + 8 // 跳过 entry header (size+type)
        while (childPos + 8 <= entryEnd) {
            val childSize = ((data[childPos].toLong() and 0xFF) shl 24) or
                    ((data[childPos + 1].toLong() and 0xFF) shl 16) or
                    ((data[childPos + 2].toLong() and 0xFF) shl 8) or
                    (data[childPos + 3].toLong() and 0xFF)
            if (childSize <= 0 || childPos + childSize.toInt() > entryEnd) break
            val childType = String(data, childPos + 4, 4, Charsets.US_ASCII)

            when (childType) {
                "avcC" -> {
                    // AVCDecoderConfigurationRecord = avcC box content (skip 8-byte box header)
                    val dStart = childPos + 8
                    val dEnd = childPos + childSize.toInt()
                    if (dEnd <= data.size && dStart < dEnd) {
                        result["csd-0"] = data.copyOfRange(dStart, dEnd)
                        Log.d(TAG, "[$taskId] Found avcC: ${dEnd - dStart} bytes")
                    }
                }
                "hvcC" -> {
                    // HEVCDecoderConfigurationRecord
                    val dStart = childPos + 8
                    val dEnd = childPos + childSize.toInt()
                    if (dEnd <= data.size && dStart < dEnd) {
                        result["csd-0"] = data.copyOfRange(dStart, dEnd)
                        Log.d(TAG, "[$taskId] Found hvcC: ${dEnd - dStart} bytes")
                    }
                }
                "esds" -> {
                    // 从 ES_Descriptor 提取 DecoderSpecificInfo (AAC)
                    val dsi = extractDsiFromEsds(data, childPos + 8, childPos + childSize.toInt())
                    if (dsi != null) {
                        result["csd-0"] = dsi
                        Log.d(TAG, "[$taskId] Found AAC DecoderSpecificInfo: ${dsi.size} bytes")
                    }
                }
                "dOps" -> {
                    val dStart = childPos + 8
                    val dEnd = childPos + childSize.toInt()
                    if (dEnd <= data.size && dStart < dEnd) {
                        result["csd-0"] = data.copyOfRange(dStart, dEnd)
                        Log.d(TAG, "[$taskId] Found dOps: ${dEnd - dStart} bytes")
                    }
                }
            }

            childPos += childSize.toInt()
        }
    }

    /**
     * 从 esds box 提取 DecoderSpecificInfo
     * esds -> ES_Descriptor(tag=0x03) -> DecoderConfigDescriptor(tag=0x04) -> DecoderSpecificInfo(tag=0x05)
     */
    private fun extractDsiFromEsds(data: ByteArray, start: Int, end: Int): ByteArray? {
        try {
            var pos = start
            // ES_Descriptor
            if (pos >= end || data[pos] != 0x03.toByte()) return null
            val (esLenBytes, esLen) = readDescLen(data, pos + 1)
            pos += 1 + esLenBytes + 1 // tag + length field + tag byte
            pos += 3 // ES_ID(2) + flags(1)

            // DecoderConfigDescriptor
            if (pos >= end || data[pos] != 0x04.toByte()) return null
            val (dcLenBytes, dcLen) = readDescLen(data, pos + 1)
            pos += 1 + dcLenBytes + 1 // tag + length field + tag byte
            pos += 13 // objectType(1) + streamType(1) + bufSize(3) + maxBitrate(4) + avgBitrate(4)

            // DecoderSpecificInfo
            if (pos >= end || data[pos] != 0x05.toByte()) return null
            val (dsiLenBytes, dsiLen) = readDescLen(data, pos + 1)
            pos += 1 + dsiLenBytes + 1 // tag + length field + tag byte

            val dsiStart = pos - dsiLen
            val dsiEnd = dsiStart + dsiLen
            if (dsiStart >= 0 && dsiEnd <= end && dsiEnd <= data.size && dsiLen > 0) {
                return data.copyOfRange(dsiStart, dsiEnd)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$taskId] Failed to extract DSI from esds: ${e.message}")
        }
        return null
    }

    private fun readDescLen(data: ByteArray, start: Int): Pair<Int, Int> {
        var len = 0
        var bytes = 0
        var pos = start
        while (pos < data.size && bytes < 4) {
            val b = data[pos].toInt() and 0xFF
            len = (len shl 7) or (b and 0x7F)
            bytes++
            pos++
            if ((b and 0x80) == 0) break
        }
        return Pair(bytes, len)
    }

    /**
     * 手动从 fMP4 init segment 构建 MediaFormat
     * 解析 ftyp + moov/trak/mdia/minf/stbl/stsd 获取编解码信息
     */
    private fun buildMediaFormatFromInitSegment(initData: ByteArray): MediaFormat? {
        try {
            var pos = 0
            var mimeType: String? = null
            var width = 0
            var height = 0
            var sampleRate = 0
            var channelCount = 0
            var csd0: ByteArray? = null
            var csd1: ByteArray? = null
            var isVideo = false
            var isAudio = false

            while (pos + 8 <= initData.size) {
                val size = ((initData[pos].toLong() and 0xFF) shl 24) or
                        ((initData[pos + 1].toLong() and 0xFF) shl 16) or
                        ((initData[pos + 2].toLong() and 0xFF) shl 8) or
                        (initData[pos + 3].toLong() and 0xFF)
                val type = String(initData, pos + 4, 4, Charsets.US_ASCII)

                if (type == "ftyp") {
                    // 读取 major brand 判断类型
                    if (pos + 12 <= initData.size) {
                        val brand = String(initData, pos + 8, 4, Charsets.US_ASCII)
                        Log.d(TAG, "[$taskId] ftyp brand: $brand")
                    }
                } else if (type == "moov") {
                    parseMoovForFormat(initData, pos + 8, (pos + size).toInt()).let { info ->
                        if (info != null) {
                            mimeType = info.mime
                            width = info.width
                            height = info.height
                            sampleRate = info.sampleRate
                            channelCount = info.channelCount
                            csd0 = info.csd0
                            csd1 = info.csd1
                            isVideo = info.isVideo
                            isAudio = info.isAudio
                        }
                    }
                }
                pos += size.toInt()
            }

            if (mimeType == null) {
                Log.e(TAG, "[$taskId] Could not determine mime type from init segment")
                return null
            }

            val format = MediaFormat()
            format.setString(MediaFormat.KEY_MIME, mimeType)

            if (isVideo) {
                if (width > 0 && height > 0) {
                    format.setInteger(MediaFormat.KEY_WIDTH, width)
                    format.setInteger(MediaFormat.KEY_HEIGHT, height)
                } else {
                    // 默认分辨率 fallback
                    format.setInteger(MediaFormat.KEY_WIDTH, 1920)
                    format.setInteger(MediaFormat.KEY_HEIGHT, 1080)
                    Log.w(TAG, "[$taskId] Video dimensions not found, using default 1920x1080")
                }
            }

            if (isAudio) {
                if (sampleRate > 0) format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                if (channelCount > 0) format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
            }

            if (csd0 != null) {
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd0))
            }
            if (csd1 != null) {
                format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(csd1))
            }

            Log.d(TAG, "[$taskId] Manual format: mime=$mimeType video=$isVideo audio=$isAudio ${width}x${height} ${sampleRate}Hz ${channelCount}ch csd0=${csd0?.size ?: 0} csd1=${csd1?.size ?: 0}")
            return format
        } catch (e: Exception) {
            Log.e(TAG, "[$taskId] buildMediaFormatFromInitSegment failed", e)
            return null
        }
    }

    private data class InitSegmentInfo(
        val mime: String,
        val isVideo: Boolean,
        val isAudio: Boolean,
        val width: Int,
        val height: Int,
        val sampleRate: Int,
        val channelCount: Int,
        val csd0: ByteArray?,
        val csd1: ByteArray?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as InitSegmentInfo
            return mime == other.mime && isVideo == other.isVideo && isAudio == other.isAudio &&
                    width == other.width && height == other.height &&
                    sampleRate == other.sampleRate && channelCount == other.channelCount &&
                    csd0.contentEquals(other.csd0 ?: ByteArray(0)) &&
                    csd1.contentEquals(other.csd1 ?: ByteArray(0))
        }

        override fun hashCode(): Int {
            var result = mime.hashCode()
            result = 31 * result + isVideo.hashCode()
            result = 31 * result + isAudio.hashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + sampleRate
            result = 31 * result + channelCount
            result = 31 * result + (csd0?.contentHashCode() ?: 0)
            result = 31 * result + (csd1?.contentHashCode() ?: 0)
            return result
        }
    }

    private fun parseMoovForFormat(data: ByteArray, start: Int, end: Int): InitSegmentInfo? {
        var pos = start
        var videoInfo: InitSegmentInfo? = null
        var audioInfo: InitSegmentInfo? = null

        while (pos + 8 <= end && pos + 8 <= data.size) {
            val size = ((data[pos].toLong() and 0xFF) shl 24) or
                    ((data[pos + 1].toLong() and 0xFF) shl 16) or
                    ((data[pos + 2].toLong() and 0xFF) shl 8) or
                    (data[pos + 3].toLong() and 0xFF)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)

            if (type == "trak") {
                val trakInfo = parseTrak(data, pos + 8, (pos + size).toInt())
                if (trakInfo != null) {
                    if (trakInfo.isVideo) videoInfo = trakInfo
                    else if (trakInfo.isAudio) audioInfo = trakInfo
                }
            }

            pos += size.toInt()
        }

        // 优先返回视频 track 信息（视频是必须的）
        return videoInfo ?: audioInfo
    }

    private fun parseTrak(data: ByteArray, start: Int, end: Int): InitSegmentInfo? {
        var pos = start
        var tkhdWidth = 0
        var tkhdHeight = 0
        var mime: String? = null
        var isVideo = false
        var isAudio = false
        var sampleRate = 0
        var channelCount = 0
        var csd0: ByteArray? = null
        var csd1: ByteArray? = null

        while (pos + 8 <= end && pos + 8 <= data.size) {
            val size = ((data[pos].toLong() and 0xFF) shl 24) or
                    ((data[pos + 1].toLong() and 0xFF) shl 16) or
                    ((data[pos + 2].toLong() and 0xFF) shl 8) or
                    (data[pos + 3].toLong() and 0xFF)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)

            when (type) {
                "tkhd" -> {
                    // tkhd version check
                    if (pos + 8 <= data.size) {
                        val version = data[pos + 8].toInt() and 0xFF
                        val matrixOffset = if (version == 0) 48 else 60
                        // width and height are fixed-point 16.16 at specific offsets
                        val wOffset = pos + 8 + matrixOffset + 24
                        val hOffset = wOffset + 4
                        if (hOffset + 4 <= data.size) {
                            tkhdWidth = ((data[wOffset].toInt() and 0xFF) shl 24) or
                                    ((data[wOffset + 1].toInt() and 0xFF) shl 16) or
                                    ((data[wOffset + 2].toInt() and 0xFF) shl 8) or
                                    (data[wOffset + 3].toInt() and 0xFF)
                            tkhdHeight = ((data[hOffset].toInt() and 0xFF) shl 24) or
                                    ((data[hOffset + 1].toInt() and 0xFF) shl 16) or
                                    ((data[hOffset + 2].toInt() and 0xFF) shl 8) or
                                    (data[hOffset + 3].toInt() and 0xFF)
                            // Convert from 16.16 fixed point to integer
                            tkhdWidth = tkhdWidth shr 16
                            tkhdHeight = tkhdHeight shr 16
                        }
                    }
                }
                "mdia" -> {
                    val mdiaResult = parseMdiaForCodec(data, pos + 8, (pos + size).toInt())
                    if (mdiaResult != null) {
                        mime = mdiaResult.first
                        isVideo = mdiaResult.second
                        isAudio = mdiaResult.third
                        sampleRate = mdiaResult.fourth
                        channelCount = mdiaResult.fifth
                        // csd data is extracted from stsd inside minf/stbl
                    }
                    // Now parse deeper for stsd/codec config
                    parseMdiaForCsd(data, pos + 8, (pos + size).toInt())?.let { csd ->
                        if (csd.csdb0 != null) csd0 = csd.csdb0
                        if (csd.csdb1 != null) csd1 = csd.csdb1
                        if (csd.width > 0) tkhdWidth = csd.width
                        if (csd.height > 0) tkhdHeight = csd.height
                        if (csd.sampleRate > 0) sampleRate = csd.sampleRate
                        if (csd.channelCount > 0) channelCount = csd.channelCount
                    }
                }
            }

            pos += size.toInt()
        }

        if (mime == null) return null

        return InitSegmentInfo(
            mime = mime,
            isVideo = isVideo,
            isAudio = isAudio,
            width = tkhdWidth,
            height = tkhdHeight,
            sampleRate = sampleRate,
            channelCount = channelCount,
            csd0 = csd0,
            csd1 = csd1
        )
    }

    private fun parseMdiaForCodec(data: ByteArray, start: Int, end: Int): FiveTuple? {
        var pos = start
        var handlerType: String? = null

        while (pos + 8 <= end && pos + 8 <= data.size) {
            val size = ((data[pos].toLong() and 0xFF) shl 24) or
                    ((data[pos + 1].toLong() and 0xFF) shl 16) or
                    ((data[pos + 2].toLong() and 0xFF) shl 8) or
                    (data[pos + 3].toLong() and 0xFF)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)

            if (type == "hdlr" && pos + 16 <= data.size) {
                // handler_type at offset 8 within hdlr box
                handlerType = String(data, pos + 16, 4, Charsets.US_ASCII)
            } else if (type == "mdhd" && pos + 24 <= data.size) {
                // Could extract timescale and duration here
            } else if (type == "minf") {
                val minfResult = parseMinfForCodec(data, pos + 8, (pos + size).toInt())
                if (minfResult != null && handlerType != null) {
                    val isVideo = handlerType == "vide"
                    val isAudio = handlerType == "soun"
                    return FiveTuple(minfResult.first, isVideo, isAudio, minfResult.second, minfResult.third)
                }
            }

            pos += size.toInt()
        }
        return null
    }

    private data class FiveTuple(
        val first: String,
        val second: Boolean,
        val third: Boolean,
        val fourth: Int,
        val fifth: Int
    )

    private data class CsdResult(
        val csdb0: ByteArray?,
        val csdb1: ByteArray?,
        val width: Int,
        val height: Int,
        val sampleRate: Int,
        val channelCount: Int
    )

    private fun parseMdiaForCsd(data: ByteArray, start: Int, end: Int): CsdResult? {
        var pos = start
        while (pos + 8 <= end && pos + 8 <= data.size) {
            val size = ((data[pos].toLong() and 0xFF) shl 24) or
                    ((data[pos + 1].toLong() and 0xFF) shl 16) or
                    ((data[pos + 2].toLong() and 0xFF) shl 8) or
                    (data[pos + 3].toLong() and 0xFF)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)

            if (type == "minf") {
                return parseStblForCsd(data, pos + 8, (pos + size).toInt())
            }

            pos += size.toInt()
        }
        return null
    }

    private fun parseMinfForCodec(data: ByteArray, start: Int, end: Int): Triple<String, Int, Int>? {
        var pos = start
        while (pos + 8 <= end && pos + 8 <= data.size) {
            val size = ((data[pos].toLong() and 0xFF) shl 24) or
                    ((data[pos + 1].toLong() and 0xFF) shl 16) or
                    ((data[pos + 2].toLong() and 0xFF) shl 8) or
                    (data[pos + 3].toLong() and 0xFF)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)

            if (type == "stbl") {
                return parseStblForCodec(data, pos + 8, (pos + size).toInt())
            }

            pos += size.toInt()
        }
        return null
    }

    private fun parseStblForCodec(data: ByteArray, start: Int, end: Int): Triple<String, Int, Int>? {
        var pos = start
        while (pos + 8 <= end && pos + 8 <= data.size) {
            val size = ((data[pos].toLong() and 0xFF) shl 24) or
                    ((data[pos + 1].toLong() and 0xFF) shl 16) or
                    ((data[pos + 2].toLong() and 0xFF) shl 8) or
                    (data[pos + 3].toLong() and 0xFF)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)

            if (type == "stsd") {
                return parseStsdForCodec(data, pos + 8, (pos + size).toInt())
            }

            pos += size.toInt()
        }
        return null
    }

    private fun parseStblForCsd(data: ByteArray, start: Int, end: Int): CsdResult? {
        var pos = start
        while (pos + 8 <= end && pos + 8 <= data.size) {
            val size = ((data[pos].toLong() and 0xFF) shl 24) or
                    ((data[pos + 1].toLong() and 0xFF) shl 16) or
                    ((data[pos + 2].toLong() and 0xFF) shl 8) or
                    (data[pos + 3].toLong() and 0xFF)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)

            if (type == "stsd") {
                return parseStsdForCsd(data, pos + 8, (pos + size).toInt())
            }

            pos += size.toInt()
        }
        return null
    }

    private fun parseStsdForCodec(data: ByteArray, start: Int, end: Int): Triple<String, Int, Int>? {
        // stsd: version(1) + flags(3) + entry_count(4)
        if (start + 8 > data.size || start + 8 > end) return null
        val entryCount = ((data[start + 4].toInt() and 0xFF) shl 24) or
                ((data[start + 5].toInt() and 0xFF) shl 16) or
                ((data[start + 6].toInt() and 0xFF) shl 8) or
                (data[start + 7].toInt() and 0xFF)
        if (entryCount == 0) return null

        var pos = start + 8
        if (pos + 8 > data.size || pos + 8 > end) return null
        val sampleEntrySize = ((data[pos].toLong() and 0xFF) shl 24) or
                ((data[pos + 1].toLong() and 0xFF) shl 16) or
                ((data[pos + 2].toLong() and 0xFF) shl 8) or
                (data[pos + 3].toLong() and 0xFF)
        val sampleEntryType = String(data, pos + 4, 4, Charsets.US_ASCII)

        val mime: String
        var sampleRate: Int
        var channelCount: Int

        when (sampleEntryType) {
            "avc1", "avc3" -> mime = MediaFormat.MIMETYPE_VIDEO_AVC
            "hvc1", "hev1" -> mime = MediaFormat.MIMETYPE_VIDEO_HEVC
            "mp4a" -> mime = MediaFormat.MIMETYPE_AUDIO_AAC
            "Opus" -> mime = MediaFormat.MIMETYPE_AUDIO_OPUS
            "ec-3" -> mime = MediaFormat.MIMETYPE_AUDIO_EAC3
            "ac-3" -> mime = MediaFormat.MIMETYPE_AUDIO_AC3
            else -> {
                // Try to determine if video or audio from handler type
                if (sampleEntryType.length == 4 && sampleEntryType[0] in 'a'..'z') {
                    // Generic approach: check for known patterns
                    mime = "video/${sampleEntryType}"
                } else {
                    return null
                }
            }
        }

        // For audio: extract sample rate and channels from mp4a descriptor
        sampleRate = 0
        channelCount = 0

        if (sampleEntryType == "mp4a" && pos + 28 <= end && pos + 28 <= data.size) {
            // Skip to mp4a box structure
            // After sample entry header (8 bytes), reserved(6), data_ref_index(2),
            // reserved(8), channelcount(2), samplesize(2), reserved(4), samplerate(4)
            val audioOffset = pos + 8 + 6 + 2 + 8
            if (audioOffset + 8 <= data.size) {
                channelCount = ((data[audioOffset].toInt() and 0xFF) shl 8) or
                        (data[audioOffset + 1].toInt() and 0xFF)
                sampleRate = ((data[audioOffset + 6].toInt() and 0xFF) shl 8) or
                        (data[audioOffset + 7].toInt() and 0xFF)
            }
        }

        return Triple(mime, sampleRate, channelCount)
    }

    private fun parseStsdForCsd(data: ByteArray, start: Int, end: Int): CsdResult? {
        if (start + 8 > data.size || start + 8 > end) return null
        val entryCount = ((data[start + 4].toInt() and 0xFF) shl 24) or
                ((data[start + 5].toInt() and 0xFF) shl 16) or
                ((data[start + 6].toInt() and 0xFF) shl 8) or
                (data[start + 7].toInt() and 0xFF)
        if (entryCount == 0) return null

        var pos = start + 8
        if (pos + 8 > data.size || pos + 8 > end) return null
        val sampleEntrySize = ((data[pos].toLong() and 0xFF) shl 24) or
                ((data[pos + 1].toLong() and 0xFF) shl 16) or
                ((data[pos + 2].toLong() and 0xFF) shl 8) or
                (data[pos + 3].toLong() and 0xFF)
        val sampleEntryType = String(data, pos + 4, 4, Charsets.US_ASCII)

        val sampleEntryEnd = minOf(pos + sampleEntrySize.toInt(), end, data.size)

        var csd0: ByteArray? = null
        var csd1: ByteArray? = null
        var width = 0
        var height = 0
        var sampleRate = 0
        var channelCount = 0

        when (sampleEntryType) {
            "avc1", "avc3" -> {
                val avccOffset = findAvccInSampleEntry(data, pos, sampleEntryEnd)
                if (avccOffset != null) {
                    val avccSize = ((data[avccOffset].toLong() and 0xFF) shl 24) or
                            ((data[avccOffset + 1].toLong() and 0xFF) shl 16) or
                            ((data[avccOffset + 2].toLong() and 0xFF) shl 8) or
                            (data[avccOffset + 3].toLong() and 0xFF)
                    // 跳过 avcC box header (8 bytes: size+type)，只取 AVCDecoderConfigurationRecord
                    val dataStart = avccOffset + 8
                    val dataEnd = avccOffset + avccSize.toInt()
                    if (dataEnd <= data.size && dataStart < dataEnd) {
                        csd0 = data.copyOfRange(dataStart, dataEnd)
                    }
                }
                width = extractWidthFromSampleEntry(data, pos, sampleEntryEnd)
                height = extractHeightFromSampleEntry(data, pos, sampleEntryEnd)
            }
            "hvc1", "hev1" -> {
                val hvccOffset = findHvccInSampleEntry(data, pos, sampleEntryEnd)
                if (hvccOffset != null) {
                    val hvccSize = ((data[hvccOffset].toLong() and 0xFF) shl 24) or
                            ((data[hvccOffset + 1].toLong() and 0xFF) shl 16) or
                            ((data[hvccOffset + 2].toLong() and 0xFF) shl 8) or
                            (data[hvccOffset + 3].toLong() and 0xFF)
                    // 跳过 hvcC box header (8 bytes)，只取 HEVCDecoderConfigurationRecord
                    val dataStart = hvccOffset + 8
                    val dataEnd = hvccOffset + hvccSize.toInt()
                    if (dataEnd <= data.size && dataStart < dataEnd) {
                        csd0 = data.copyOfRange(dataStart, dataEnd)
                    }
                }
                width = extractWidthFromSampleEntry(data, pos, sampleEntryEnd)
                height = extractHeightFromSampleEntry(data, pos, sampleEntryEnd)
            }
            "mp4a" -> {
                val audioOffset = pos + 8 + 6 + 2 + 8
                if (audioOffset + 8 <= data.size) {
                    channelCount = ((data[audioOffset].toInt() and 0xFF) shl 8) or
                            (data[audioOffset + 1].toInt() and 0xFF)
                    sampleRate = ((data[audioOffset + 6].toInt() and 0xFF) shl 8) or
                            (data[audioOffset + 7].toInt() and 0xFF)
                }
                val esdsOffset = findEsdsInSampleEntry(data, pos, sampleEntryEnd)
                if (esdsOffset != null) {
                    val decoderSpecificInfo = extractDecoderSpecificInfoFromEsds(data, esdsOffset,
                        minOf(esdsOffset + 128, sampleEntryEnd))
                    if (decoderSpecificInfo != null) {
                        csd0 = decoderSpecificInfo
                    }
                }
            }
            "Opus" -> {
                val dopsOffset = findDopsInSampleEntry(data, pos, sampleEntryEnd)
                if (dopsOffset != null) {
                    val dopsSize = ((data[dopsOffset].toLong() and 0xFF) shl 24) or
                            ((data[dopsOffset + 1].toLong() and 0xFF) shl 16) or
                            ((data[dopsOffset + 2].toLong() and 0xFF) shl 8) or
                            (data[dopsOffset + 3].toLong() and 0xFF)
                    // 跳过 dOps box header (8 bytes)，只取 OpusSpecificBox 数据
                    val dataStart = dopsOffset + 8
                    val dataEnd = dopsOffset + dopsSize.toInt()
                    if (dataEnd <= data.size && dataStart < dataEnd) {
                        csd0 = data.copyOfRange(dataStart, dataEnd)
                    }
                }
            }
        }

        return CsdResult(csd0, csd1, width, height, sampleRate, channelCount)
    }

    private fun findAvccInSampleEntry(data: ByteArray, start: Int, end: Int): Int? {
        var pos = start + 8 // skip sample entry header
        while (pos + 8 <= end && pos + 8 <= data.size) {
            val size = ((data[pos].toLong() and 0xFF) shl 24) or
                    ((data[pos + 1].toLong() and 0xFF) shl 16) or
                    ((data[pos + 2].toLong() and 0xFF) shl 8) or
                    (data[pos + 3].toLong() and 0xFF)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)
            if (type == "avcC" && size > 0 && size < 1024 * 1024) return pos
            // Only recurse into known container boxes
            if (type == "rinf" || type == "schi" || type == "sinf") {
                val inner = findAvccInSampleEntry(data, pos + 8, minOf(pos + size.toInt(), end))
                if (inner != null) return inner
            }
            pos += size.toInt()
        }
        return null
    }

    private fun findHvccInSampleEntry(data: ByteArray, start: Int, end: Int): Int? {
        var pos = start + 8
        while (pos + 8 <= end && pos + 8 <= data.size) {
            val size = ((data[pos].toLong() and 0xFF) shl 24) or
                    ((data[pos + 1].toLong() and 0xFF) shl 16) or
                    ((data[pos + 2].toLong() and 0xFF) shl 8) or
                    (data[pos + 3].toLong() and 0xFF)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)
            if (type == "hvcC" && size > 0 && size < 1024 * 1024) return pos
            if (type == "rinf" || type == "schi" || type == "sinf") {
                val inner = findHvccInSampleEntry(data, pos + 8, minOf(pos + size.toInt(), end))
                if (inner != null) return inner
            }
            pos += size.toInt()
        }
        return null
    }

    private fun findEsdsInSampleEntry(data: ByteArray, start: Int, end: Int): Int? {
        var pos = start + 8
        while (pos + 8 <= end && pos + 8 <= data.size) {
            val size = ((data[pos].toLong() and 0xFF) shl 24) or
                    ((data[pos + 1].toLong() and 0xFF) shl 16) or
                    ((data[pos + 2].toLong() and 0xFF) shl 8) or
                    (data[pos + 3].toLong() and 0xFF)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)
            if (type == "esds") return pos + 8 // return offset to esds data
            if (type == "rinf" || type == "schi" || type == "sinf") {
                val inner = findEsdsInSampleEntry(data, pos + 8, minOf(pos + size.toInt(), end))
                if (inner != null) return inner
            }
            pos += size.toInt()
        }
        return null
    }

    private fun extractDecoderSpecificInfoFromEsds(data: ByteArray, start: Int, end: Int): ByteArray? {
        var pos = start
        // ES_Descriptor: tag(1) + length(variable) + ES_ID(2) + flags(1)
        if (pos + 4 > end || pos + 4 > data.size) return null
        if (data[pos] != 0x03.toByte()) return null // ES_Descriptor tag
        val esLen = readDescriptorLength(data, pos + 1)
        var next = pos + 1 + esLen.first + 1 // skip tag + length field
        next += 3 // skip ES_ID(2) + flags(1)

        // DecoderConfigDescriptor: tag(1) + length(variable) + objectType(1) + streamType(1) + ...
        if (next + 2 > end || next + 2 > data.size) return null
        if (data[next] != 0x04.toByte()) return null // DecoderConfigDescriptor tag
        val dcLen = readDescriptorLength(data, next + 1)
        next += 1 + dcLen.first + 1
        next += 2 + 1 + 3 + 4 + 4 // skip objectType + streamType + bufSize + maxBitRate + avgBitRate

        // DecoderSpecificInfo: tag(1) + length(variable)
        if (next + 2 > end || next + 2 > data.size) return null
        if (data[next] != 0x05.toByte()) return null
        val dsiLen = readDescriptorLength(data, next + 1)
        next += 1 + dsiLen.first + 1
        val dsiStart = next - dsiLen.second
        if (dsiStart + dsiLen.second <= end && dsiStart + dsiLen.second <= data.size && dsiLen.second > 0) {
            return data.copyOfRange(dsiStart, dsiStart + dsiLen.second)
        }
        return null
    }

    private fun readDescriptorLength(data: ByteArray, start: Int): Pair<Int, Int> {
        var length = 0
        var bytesRead = 0
        var pos = start
        while (pos < data.size && bytesRead < 4) {
            val b = data[pos].toInt() and 0xFF
            length = (length shl 7) or (b and 0x7F)
            bytesRead++
            pos++
            if ((b and 0x80) == 0) break
        }
        return Pair(bytesRead, length)
    }

    private fun findDopsInSampleEntry(data: ByteArray, start: Int, end: Int): Int? {
        var pos = start + 8
        while (pos + 8 <= end && pos + 8 <= data.size) {
            val size = ((data[pos].toLong() and 0xFF) shl 24) or
                    ((data[pos + 1].toLong() and 0xFF) shl 16) or
                    ((data[pos + 2].toLong() and 0xFF) shl 8) or
                    (data[pos + 3].toLong() and 0xFF)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)
            if (type == "dOps") return pos
            if (type == "rinf" || type == "schi" || type == "sinf") {
                val inner = findDopsInSampleEntry(data, pos + 8, minOf(pos + size.toInt(), end))
                if (inner != null) return inner
            }
            pos += size.toInt()
        }
        return null
    }

    private fun extractOpusCsd(data: ByteArray, dopsOffset: Int, end: Int): ByteArray? {
        val size = ((data[dopsOffset].toLong() and 0xFF) shl 24) or
                ((data[dopsOffset + 1].toLong() and 0xFF) shl 16) or
                ((data[dopsOffset + 2].toLong() and 0xFF) shl 8) or
                (data[dopsOffset + 3].toLong() and 0xFF)
        if (size > 0 && dopsOffset + size.toInt() <= end && dopsOffset + size.toInt() <= data.size) {
            return data.copyOfRange(dopsOffset, dopsOffset + size.toInt())
        }
        return null
    }

    private fun extractWidthFromSampleEntry(data: ByteArray, start: Int, end: Int): Int {
        // Width is at offset 24 (after size+type+6reserved+2dref) + 8 = 32 from start
        val wOffset = start + 32
        if (wOffset + 2 <= data.size && wOffset + 2 <= end) {
            return ((data[wOffset].toInt() and 0xFF) shl 8) or
                    (data[wOffset + 1].toInt() and 0xFF)
        }
        return 0
    }

    private fun extractHeightFromSampleEntry(data: ByteArray, start: Int, end: Int): Int {
        val hOffset = start + 34
        if (hOffset + 2 <= data.size && hOffset + 2 <= end) {
            return ((data[hOffset].toInt() and 0xFF) shl 8) or
                    (data[hOffset + 1].toInt() and 0xFF)
        }
        return 0
    }

    private fun checkAndStart() {
        Log.d(TAG, "[$taskId] checkAndStart: isStarted=$isStarted videoFormat=${videoFormat != null} audioResolved=$audioResolved")
        if (isStarted || videoFormat == null || !audioResolved) return

        try {
            Log.d(TAG, "[$taskId] Creating MediaMuxer: ${outputFile.absolutePath}")
            outputFile.parentFile?.mkdirs()
            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            Log.d(TAG, "[$taskId] Adding video track...")
            videoTrackIndex = mediaMuxer!!.addTrack(videoFormat!!)
            Log.d(TAG, "[$taskId] Video track index: $videoTrackIndex")

            audioTrackIndex = if (audioFormat != null) {
                Log.d(TAG, "[$taskId] Adding audio track...")
                val idx = mediaMuxer!!.addTrack(audioFormat!!)
                Log.d(TAG, "[$taskId] Audio track index: $idx")
                idx
            } else {
                Log.d(TAG, "[$taskId] No audio track")
                -1
            }

            Log.d(TAG, "[$taskId] Starting MediaMuxer...")
            mediaMuxer!!.start()
            isStarted = true
            Log.d(TAG, "[$taskId] MediaMuxer started: video=$videoTrackIndex audio=$audioTrackIndex")

            startWorkerLoops()
        } catch (e: Exception) {
            Log.e(TAG, "[$taskId] Failed to start MediaMuxer: ${e.message}", e)
        }
    }

    private fun startWorkerLoops() {
        // parseJob: 解析 fragment → sample
        // 停止时继续处理队列中剩余的 fragment，避免尾部数据丢失
        parseJob = scope.launch {
            while (!isStopped || videoFragmentQueue.isNotEmpty() || audioFragmentQueue.isNotEmpty()) {
                parseFragments()
                delay(5)
            }
        }

        // muxJob: 延迟启动，等 native MediaMuxer (MPEG4Writer) 完全初始化
        // 部分设备上 MediaMuxer.start() 返回后 native writer 还未就绪，
        // 此时 writeSampleData 会报 "pushBuffer called before start"
        muxJob = scope.launch {
            delay(300) // 等 native writer 初始化
            while (!isStopped) {
                drainSampleQueues()
                delay(5)
            }
            // 停止后再排空所有剩余样本（循环直到为空或超时）
            val stopDrainStart = System.currentTimeMillis()
            var drainCount = 0
            while (System.currentTimeMillis() - stopDrainStart < 2000) {
                val vEmpty = videoQueueMutex.run { videoSampleQueue.isEmpty() }
                val aEmpty = audioQueueMutex.run { audioSampleQueue.isEmpty() }
                if (vEmpty && aEmpty) break
                drainSampleQueues()
                drainCount++
                delay(5)
            }
            Log.d(TAG, "[$taskId] MuxJob final drain: $drainCount rounds")
        }
    }

    @Synchronized
    private fun parseFragments() {
        var parsed = 0
        var videoFragsParsed = 0
        var audioFragsParsed = 0

        // ═══════════════════════════════════════════════════════════════
        // 视频分片解析：每个分片独立计算墙钟，不依赖全局基准
        // ═══════════════════════════════════════════════════════════════
        while (videoFragmentQueue.isNotEmpty()) {
            val frag = videoFragmentQueue.poll() ?: break
            videoFragsParsed++
            val samples = videoParser?.parseFragment(frag.data) ?: emptyList()

            // 获取该分片的独立基准
            val parser = videoParser
            val fragmentTfdtTick = parser?.baseMediaDecodeTime ?: 0L
            val timescale = parser?.trackTimescale ?: 0
            val fragmentTfdtUs = if (timescale > 0) {
                fragmentTfdtTick * 1_000_000L / timescale
            } else fragmentTfdtTick
            val fragmentPdtMs = frag.pdtMs

            // 日志：每个分片的关键信息
            if (samples.isNotEmpty()) {
                Log.d(TAG, "[$taskId] VIDEO fragment parsed: tfdt=${fragmentTfdtTick}tick(${fragmentTfdtUs}us) PDT=${fragmentPdtMs}ms samples=${samples.size} firstPts=${samples.first().pts}us keyFrames=${samples.count { it.isKeyFrame }}")
            } else {
                Log.w(TAG, "[$taskId] VIDEO fragment parsed 0 samples! dataSize=${frag.data.size} pdt=$fragmentPdtMs")
            }

            var droppedByAlign = 0
            for (sample in samples) {
                // 每个分片独立计算墙钟：PDT + (样本PTS - 分片首DTS)
                val wallPtsUs = if (fragmentPdtMs != null) {
                    fragmentPdtMs * 1000L + (sample.pts - fragmentTfdtUs)
                } else {
                    // 无PDT时退化为相对时间
                    sample.pts
                }

                // 对齐裁剪：早于对齐点的样本丢弃
                val align = alignPdtUs
                if (align != null && wallPtsUs < align) {
                    droppedByAlign++
                    continue
                }

                // 加锁入队，溢出时丢弃最旧非关键帧
                videoQueueMutex.run {
                    if (videoSampleQueue.size >= MAX_SAMPLE_QUEUE_SIZE) {
                        val iterator = videoSampleQueue.iterator()
                        while (iterator.hasNext()) {
                            if (!iterator.next().isKeyFrame) {
                                iterator.remove()
                                break
                            }
                        }
                    }
                    if (videoSampleQueue.size < MAX_SAMPLE_QUEUE_SIZE) {
                        videoSampleQueue.offer(QueuedSample(wallPtsUs, sample.data, sample.isKeyFrame))
                    }
                }

                if (firstVideoPts == Long.MIN_VALUE) {
                    firstVideoPts = wallPtsUs
                    firstVideoWallMs = System.currentTimeMillis()
                    logFirstSample("VIDEO", wallPtsUs, firstVideoWallMs)
                }
            }
            if (droppedByAlign > 0) {
                Log.d(TAG, "[$taskId] VIDEO fragment dropped $droppedByAlign samples by align (align=${alignPdtUs}us)")
            }
            parsed += samples.size
        }

        // ═══════════════════════════════════════════════════════════════
        // 音频分片解析：每个分片独立计算墙钟
        // AAC Priming 补偿只在音频轨首个分片做一次整体偏移
        // ═══════════════════════════════════════════════════════════════
        while (audioFragmentQueue.isNotEmpty() && audioTrackIndex >= 0) {
            val frag = audioFragmentQueue.poll() ?: break
            audioFragsParsed++
            val samples = audioParser?.parseFragment(frag.data) ?: emptyList()

            // 获取该分片的独立基准
            val aParser = audioParser
            val fragmentTfdtTick = aParser?.baseMediaDecodeTime ?: 0L
            val aTimescale = aParser?.trackTimescale ?: 0
            val fragmentTfdtUs = if (aTimescale > 0) {
                fragmentTfdtTick * 1_000_000L / aTimescale
            } else fragmentTfdtTick
            val fragmentPdtMs = frag.pdtMs

            // 判断是否是音频轨首个分片（用于一次性 Priming 补偿）
            val isFirstAudioFragment = firstAudioPts == Long.MIN_VALUE

            if (samples.isNotEmpty()) {
                Log.d(TAG, "[$taskId] AUDIO fragment parsed: tfdt=${fragmentTfdtTick}tick(${fragmentTfdtUs}us) PDT=${fragmentPdtMs}ms samples=${samples.size} firstPts=${samples.first().pts}us firstFragment=$isFirstAudioFragment")
            } else {
                Log.w(TAG, "[$taskId] AUDIO fragment parsed 0 samples! dataSize=${frag.data.size} pdt=$fragmentPdtMs")
            }

            var droppedByAlign = 0
            for (sample in samples) {
                // 每个分片独立计算墙钟
                var wallPtsUs = if (fragmentPdtMs != null) {
                    fragmentPdtMs * 1000L + (sample.pts - fragmentTfdtUs)
                } else {
                    sample.pts
                }

                // AAC Priming 补偿：只在音频轨首个分片整体前移
                // Priming 是编码器初始化时的一次预填充，不是每个分片都有
                if (isFirstAudioFragment) {
                    wallPtsUs -= PRIMING_OFFSET_US
                }

                // 对齐裁剪：早于对齐点的样本丢弃
                val align = alignPdtUs
                if (align != null && wallPtsUs < align) {
                    droppedByAlign++
                    continue
                }

                // 加锁入队
                audioQueueMutex.run {
                    if (audioSampleQueue.size < MAX_SAMPLE_QUEUE_SIZE) {
                        audioSampleQueue.offer(QueuedSample(wallPtsUs, sample.data, sample.isKeyFrame))
                    }
                }

                if (firstAudioPts == Long.MIN_VALUE) {
                    firstAudioPts = wallPtsUs
                    firstAudioWallMs = System.currentTimeMillis()
                    logFirstSample("AUDIO", wallPtsUs, firstAudioWallMs)
                }
            }
            if (droppedByAlign > 0) {
                Log.d(TAG, "[$taskId] AUDIO fragment dropped $droppedByAlign samples by align (align=${alignPdtUs}us)")
            }
            parsed += samples.size
        }

        if (parsed > 0 || videoFragsParsed > 0 || audioFragsParsed > 0) {
            Log.d(TAG, "[$taskId] Parse cycle: $parsed samples, videoFrags=$videoFragsParsed audioFrags=$audioFragsParsed, videoQueue=${videoSampleQueue.size} audioQueue=${audioSampleQueue.size}")
        }
    }

    /**
     * 诊断：记录某条轨首个 sample 的墙钟 PTS，并在两条轨都到齐后打印对比。
     *
     * 经 PDT 平移后，firstPts 已是墙钟 PTS。两条轨首样墙钟 PTS 差应≈收到墙钟差，
     * 两者都小且接近 → 跨轨对齐成功；若 PTS 差仍很大 → PDT 平移未生效。
     */
    private fun logFirstSample(stream: String, pts: Long, wallMs: Long) {
        Log.w(TAG, "[$taskId] 首样 $stream: 墙钟PTS=${pts}us (${pts / 1000}ms) wall=$wallMs")
        if (firstVideoPts != Long.MIN_VALUE && firstAudioPts != Long.MIN_VALUE) {
            val ptsDiffMs = (firstVideoPts - firstAudioPts) / 1000
            val wallDiffMs = firstVideoWallMs - firstAudioWallMs
            Log.w(TAG, "[$taskId] ═══ 首样对比 ═══ video墙钟PTS=${firstVideoPts}us audio墙钟PTS=${firstAudioPts}us " +
                    "PTS差(视频-音频)=${ptsDiffMs}ms  收到墙钟差(视频-音频)=${wallDiffMs}ms")
            if (kotlin.math.abs(ptsDiffMs) > 1000) {
                Log.w(TAG, "[$taskId] ⚠ 两轨首样墙钟 PTS 差 ${ptsDiffMs}ms 仍很大 → PDT 平移可能未生效，" +
                        "检查 fragment 是否带上了 segment 的 programDateTimeMs")
            } else {
                Log.w(TAG, "[$taskId] ✓ 跨轨墙钟 PTS 对齐正常 (差 ${ptsDiffMs}ms)")
            }
        }
    }

    @Synchronized
    private fun drainSampleQueues() {
        if (!isStarted || mediaMuxer == null) {
            if (videoSampleQueue.isNotEmpty() || audioSampleQueue.isNotEmpty()) {
                Log.v(TAG, "[$taskId] drainSampleQueues skipped: isStarted=$isStarted muxer=${mediaMuxer != null}")
            }
            return
        }

        var written = 0
        var dropped = 0
        var videoWritten = 0
        var audioWritten = 0
        val drainStartMs = System.currentTimeMillis()

        while (true) {
            // 加锁获取队首样本
            val videoSample = videoQueueMutex.run { videoSampleQueue.peek() }
            val audioSample = audioQueueMutex.run { audioSampleQueue.peek() }

            // 两队都为空则退出
            if (videoSample == null && audioSample == null) break

            // ===== 停止保护：如果已停止，且一轨为空另一轨有数据，丢弃尾部 =====
            // 这是关键修复：直播中断时两轨数据量可能不一致，必须以较短的轨道为准截断，
            // 否则 MediaMuxer 停止时两轨长度不同，生成的 moov box 损坏，文件黑屏
            if (isStopped) {
                if (videoSample == null && audioSample != null) {
                    // 视频已空，丢弃剩余音频尾部
                    audioQueueMutex.run { audioSampleQueue.poll() }
                    dropped++
                    continue
                }
                if (audioSample == null && videoSample != null && audioTrackIndex >= 0) {
                    // 音频已空（且有音频轨），丢弃剩余视频尾部
                    videoQueueMutex.run { videoSampleQueue.poll() }
                    dropped++
                    continue
                }
            }

            // ===== 1. 等待首个视频关键帧 =====
            if (!hasFoundFirstKeyFrame) {
                if (videoSample == null) break
                if (!videoSample.isKeyFrame) {
                    videoQueueMutex.run { videoSampleQueue.poll() }
                    droppedVideoFrames++
                    dropped++
                    // 兜底：超过最大丢弃帧数后强制起始
                    if (droppedVideoFrames >= MAX_DROP_VIDEO_FRAMES) {
                        hasFoundFirstKeyFrame = true
                        basePtsUs = videoSample.pts
                        ptsBaseUs = basePtsUs
                        Log.w(TAG, "[$taskId] No key frame found for $MAX_DROP_VIDEO_FRAMES frames, force start with non-key frame, basePts=$basePtsUs")
                    }
                    continue
                }
                // 锁定基准时间：以首个视频关键帧的 PTS 为全局基准
                hasFoundFirstKeyFrame = true
                basePtsUs = videoSample.pts
                ptsBaseUs = basePtsUs
                Log.w(TAG, "[$taskId] Lock first video key frame, base pts=$basePtsUs, droppedPre=$droppedVideoFrames")
            }

            // ===== 2. 裁剪音频：丢弃早于基准时间的音频 =====
            if (audioSample != null && audioSample.pts < basePtsUs) {
                audioQueueMutex.run { audioSampleQueue.poll() }
                dropped++
                continue
            }

            // ===== 3. 跨轨水位保护 + 正常写入：谁PTS小写谁 =====
            val writeVideo = when {
                videoSample == null -> false
                audioSample == null -> true
                else -> videoSample.pts <= audioSample.pts
            }

            if (writeVideo && videoSample != null) {
                videoQueueMutex.run { videoSampleQueue.poll() }
                if (writeSampleToMuxer(videoTrackIndex, videoSample)) {
                    written++
                    videoWritten++
                } else {
                    dropped++
                }
            } else if (audioSample != null && audioTrackIndex >= 0) {
                audioQueueMutex.run { audioSampleQueue.poll() }
                if (writeSampleToMuxer(audioTrackIndex, audioSample)) {
                    written++
                    audioWritten++
                } else {
                    dropped++
                }
            } else {
                break
            }
        }

        val elapsed = System.currentTimeMillis() - drainStartMs
        if (written > 0 || dropped > 0) {
            Log.d(TAG, "[$taskId] Drain: wrote=$written (V=$videoWritten A=$audioWritten) dropped=$dropped, queues=(V=${videoSampleQueue.size} A=${audioSampleQueue.size}), ${elapsed}ms")
        }
    }

    private var muxerReady = false

    private var totalVideoWritten = 0
    private var totalAudioWritten = 0

    /**
     * 将单个样本从 AVCC(长度前缀) 转换为 Annex-B(起始码 00 00 00 01) 格式。
     *
     * fMP4 mdat 里的样本是 [NAL长度(nalLengthSize字节)][NALU]...，
     * 而 MediaMuxer/MPEG4Writer 只认 Annex-B 起始码：它内部用 getNextNALUnit 按
     * 起始码扫描、剥离后再重新加长度前缀。直接把 AVCC 喂给它，NAL 会被错误切分 ->
     * 解码器解不出任何 NAL -> 全程黑屏。因此必须在写入前完成转换。
     *
     * @param src 原始 AVCC 样本
     * @param nalLengthSize NAL 长度前缀字节数 (2/3/4)
     * @return Annex-B 格式样本；若本身已是起始码格式则原样返回（幂等保护）
     */
    private fun avccSampleToAnnexB(src: ByteArray, nalLengthSize: Int): ByteArray {
        if (nalLengthSize < 1 || nalLengthSize > 4) return src
        // 幂等保护：若样本开头已是起始码，直接返回（避免重复转换）
        if (src.size >= 4 &&
            src[0] == 0.toByte() && src[1] == 0.toByte() &&
            (src[2] == 1.toByte() || (src[2] == 0.toByte() && src[3] == 1.toByte()))) {
            return src
        }
        // 输出上界：nalLengthSize==4 时等长；更小时每 NAL 多 (4-nalLengthSize) 字节
        val out = ByteArray(src.size + 4 * (src.size / (nalLengthSize + 1) + 1))
        var outPos = 0
        var pos = 0
        while (pos + nalLengthSize <= src.size) {
            var len = 0
            for (i in 0 until nalLengthSize) {
                len = (len shl 8) or (src[pos + i].toInt() and 0xFF)
            }
            pos += nalLengthSize
            if (pos + len > src.size) break
            // 写入 4 字节起始码 00 00 00 01
            out[outPos++] = 0; out[outPos++] = 0; out[outPos++] = 0; out[outPos++] = 1
            System.arraycopy(src, pos, out, outPos, len)
            outPos += len
            pos += len
        }
        return if (outPos == out.size) out else out.copyOf(outPos)
    }

    private fun writeSampleToMuxer(trackIndex: Int, sample: QueuedSample): Boolean {
        try {
            val isVideo = trackIndex == videoTrackIndex
            // 关键修复：fMP4 样本是 AVCC(长度前缀) 格式，而 MediaMuxer 期望 Annex-B(起始码)。
            // 直接喂 AVCC 会导致 NAL 被错误切分 -> 黑屏。这里转换成起始码格式。
            val outData = if (isVideo) avccSampleToAnnexB(sample.data, videoNalLengthSize) else sample.data

            if (outData.size > buffer.capacity()) {
                Log.w(TAG, "[$taskId] Sample too large: ${outData.size} > ${buffer.capacity()}")
                return false
            }

            // 写入相对 PTS：墙钟 PTS - ptsBaseUs。两条轨减同一 base，跨轨差值不变（交错仍正确），
            // 但 MediaMuxer 收到从 0 附近起的值，避免绝对墙钟值导致 native write 失败。
            val relPts = if (ptsBaseUs != Long.MIN_VALUE) sample.pts - ptsBaseUs else sample.pts
            if (relPts < 0) {
                // 早于基准的样本（理论上不应出现，因 base 取最小值），丢弃避免负 PTS
                Log.w(TAG, "[$taskId] Negative relPts=${relPts}us (wall=${sample.pts} base=${ptsBaseUs}), dropping sample")
                return false
            }

            buffer.clear()
            buffer.put(outData)
            buffer.flip()

            val info = MediaCodec.BufferInfo().apply {
                offset = 0
                size = outData.size
                presentationTimeUs = relPts
                flags = if (sample.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            }

            mediaMuxer?.writeSampleData(trackIndex, buffer, info)

            if (isVideo) totalVideoWritten++ else totalAudioWritten++

            if (!muxerReady) {
                muxerReady = true
                Log.w(TAG, "[$taskId] ===== MUXER READY ===== first write: track=${if (isVideo) "V" else "A"} relPts=${relPts}us key=${sample.isKeyFrame}")
            }
            return true
        } catch (e: IllegalStateException) {
            if (!muxerReady) {
                // Native writer 还没初始化完，这次不记 error log，等重试
                Log.v(TAG, "[$taskId] Muxer not ready yet, will retry (pts=${sample.pts})")
            } else {
                Log.e(TAG, "[$taskId] writeSampleData error after muxer was ready, track=$trackIndex, pts=${sample.pts}", e)
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "[$taskId] Failed to write sample, track=$trackIndex, pts=${sample.pts}", e)
            return false
        }
    }

    private class ByteArrayDataSource(private val data: ByteArray) : android.media.MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            val pos = position.toInt()
            if (pos >= data.size) return -1
            val bytesRead = minOf(size, data.size - pos)
            System.arraycopy(data, pos, buffer, offset, bytesRead)
            return bytesRead
        }

        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }
}
