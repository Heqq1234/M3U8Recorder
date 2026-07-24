package com.dl.m3u8recorder.llhls

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentLinkedQueue

class RealTimeMuxer(
    private val outputFile: File,
    private val taskId: String
) {
    companion object {
        private const val TAG = "RealTimeMuxer"
        private const val MAX_SAMPLE_QUEUE_SIZE = 2000
    }

    @Volatile private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null

    private var audioResolved = false
    @Volatile private var isStarted = false
    @Volatile private var isStopped = false

    private var videoParser: Fmp4FragmentParser? = null
    private var audioParser: Fmp4FragmentParser? = null

    private data class QueuedSample(
        val pts: Long,
        val data: ByteArray,
        val isKeyFrame: Boolean
    )

    private val videoSampleQueue = PriorityQueue<QueuedSample> { a, b -> a.pts.compareTo(b.pts) }
    private val audioSampleQueue = PriorityQueue<QueuedSample> { a, b -> a.pts.compareTo(b.pts) }

    private val videoFragmentQueue = ConcurrentLinkedQueue<ByteArray>()
    private val audioFragmentQueue = ConcurrentLinkedQueue<ByteArray>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var muxJob: Job? = null
    private var parseJob: Job? = null
    private val buffer = ByteBuffer.allocate(2 * 1024 * 1024)

    @Synchronized
    fun setVideoInitData(data: ByteArray) {
        videoFormat = extractMediaFormat(data)
        val videoTimescale = Fmp4FragmentParser.parseTimescaleFromInitSegment(data)
        videoParser = Fmp4FragmentParser(taskId, videoTimescale)
        if (videoFormat != null) {
            val mime = videoFormat!!.getString(MediaFormat.KEY_MIME) ?: "?"
            val w = videoFormat!!.getInteger(MediaFormat.KEY_WIDTH)
            val h = videoFormat!!.getInteger(MediaFormat.KEY_HEIGHT)
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

    fun addVideoFragment(data: ByteArray) {
        if (isStopped) return
        videoFragmentQueue.offer(data)
    }

    fun addAudioFragment(data: ByteArray) {
        if (isStopped) return
        audioFragmentQueue.offer(data)
    }

    @Synchronized
    fun flush() {
        Log.d(TAG, "[$taskId] Flushing: videoQueue=${videoFragmentQueue.size} audioQueue=${audioFragmentQueue.size} videoSamples=${videoSampleQueue.size} audioSamples=${audioSampleQueue.size}")
    }

    suspend fun stop() {
        isStopped = true
        Log.d(TAG, "[$taskId] Stopping, waiting for jobs to complete...")
        muxJob?.join()
        parseJob?.join()
        drainSampleQueues()
        release()
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
        // parseJob: 立即开始解析 fragment → sample
        parseJob = scope.launch {
            while (!isStopped) {
                parseFragments()
                delay(10)
            }
        }

        // muxJob: 延迟启动，等 native MediaMuxer (MPEG4Writer) 完全初始化
        // 部分设备上 MediaMuxer.start() 返回后 native writer 还未就绪，
        // 此时 writeSampleData 会报 "pushBuffer called before start"
        muxJob = scope.launch {
            delay(300) // 等 native writer 初始化
            while (!isStopped) {
                drainSampleQueues()
                delay(10)
            }
            drainSampleQueues()
        }
    }

    @Synchronized
    private fun parseFragments() {
        var parsed = 0

        while (videoFragmentQueue.isNotEmpty() && videoSampleQueue.size < MAX_SAMPLE_QUEUE_SIZE) {
            val data = videoFragmentQueue.poll() ?: break
            val samples = videoParser?.parseFragment(data) ?: emptyList()
            for (sample in samples) {
                videoSampleQueue.offer(QueuedSample(sample.pts, sample.data, sample.isKeyFrame))
            }
            parsed += samples.size
        }

        while (audioFragmentQueue.isNotEmpty() && audioTrackIndex >= 0 && audioSampleQueue.size < MAX_SAMPLE_QUEUE_SIZE) {
            val data = audioFragmentQueue.poll() ?: break
            val samples = audioParser?.parseFragment(data) ?: emptyList()
            for (sample in samples) {
                audioSampleQueue.offer(QueuedSample(sample.pts, sample.data, sample.isKeyFrame))
            }
            parsed += samples.size
        }

        if (parsed > 0) {
            Log.d(TAG, "[$taskId] Parsed $parsed samples, videoQueue=${videoSampleQueue.size} audioQueue=${audioSampleQueue.size}")
        }
    }

    @Synchronized
    private fun drainSampleQueues() {
        if (!isStarted || mediaMuxer == null) return

        var written = 0
        var dropped = 0

        while (videoSampleQueue.isNotEmpty() || audioSampleQueue.isNotEmpty()) {
            val videoSample = videoSampleQueue.peek()
            val audioSample = audioSampleQueue.peek()

            val writeVideo = videoSample != null && (audioSample == null || videoSample.pts <= audioSample.pts)

            if (writeVideo) {
                val sample = videoSampleQueue.poll() ?: continue
                if (writeSampleToMuxer(videoTrackIndex, sample)) {
                    written++
                } else {
                    dropped++
                }
            } else if (audioSample != null) {
                val sample = audioSampleQueue.poll() ?: continue
                if (writeSampleToMuxer(audioTrackIndex, sample)) {
                    written++
                } else {
                    dropped++
                }
            } else {
                break
            }
        }

        if (written > 0 || dropped > 0) {
            Log.d(TAG, "[$taskId] Wrote $written samples, dropped $dropped, videoQueue=${videoSampleQueue.size} audioQueue=${audioSampleQueue.size}")
        }
    }

    private var muxerReady = false

    private fun writeSampleToMuxer(trackIndex: Int, sample: QueuedSample): Boolean {
        try {
            if (sample.data.size > buffer.capacity()) {
                Log.w(TAG, "[$taskId] Sample too large: ${sample.data.size} > ${buffer.capacity()}")
                return false
            }

            buffer.clear()
            buffer.put(sample.data)
            buffer.flip()

            val info = MediaCodec.BufferInfo().apply {
                offset = 0
                size = sample.data.size
                presentationTimeUs = sample.pts
                flags = if (sample.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            }

            mediaMuxer?.writeSampleData(trackIndex, buffer, info)
            if (!muxerReady) {
                muxerReady = true
                Log.d(TAG, "[$taskId] First writeSampleData succeeded, muxer is ready")
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
