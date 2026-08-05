package com.dl.m3u8recorder.llhls
import android.util.Log
data class ParsedSample(
    val pts: Long,
    val data: ByteArray,
    val isKeyFrame: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ParsedSample
        if (pts != other.pts) return false
        if (!data.contentEquals(other.data)) return false
        if (isKeyFrame != other.isKeyFrame) return false
        return true
    }
    override fun hashCode(): Int {
        var result = pts.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + isKeyFrame.hashCode()
        return result
    }
}
class Fmp4FragmentParser(private val taskId: String, private val timescale: Long) {
    // 当前分片的 tfdt 基准时间（轨道内时间单位）
    var baseMediaDecodeTime: Long = 0
        private set
    // 该轨的时间刻度
    val trackTimescale: Long
        get() = timescale
    companion object {
        private const val TAG = "Fmp4FragmentParser"
        /**
         * 从 fMP4 Init Segment (moov/trak/mdia/mdhd) 中解析 timescale
         * 修复：正确根据 version 判断偏移量，读取 timescale 而非 modification_time
         */
        fun parseTimescaleFromInitSegment(initData: ByteArray): Long {
            var pos = 0
            while (pos + 8 <= initData.size) {
                val size = readUint32Static(initData, pos)
                val type = readFourccStatic(initData, pos + 4)
                if (type == "moov") {
                    var moovPos = pos + 8
                    val moovEnd = pos + size.toInt()
                    while (moovPos + 8 <= moovEnd) {
                        val childSize = readUint32Static(initData, moovPos)
                        val childType = readFourccStatic(initData, moovPos + 4)
                        if (childType == "trak") {
                            var trakPos = moovPos + 8
                            val trakEnd = moovPos + childSize.toInt()
                            while (trakPos + 8 <= trakEnd) {
                                val trakChildSize = readUint32Static(initData, trakPos)
                                val trakChildType = readFourccStatic(initData, trakPos + 4)
                                if (trakChildType == "mdia") {
                                    var mdiaPos = trakPos + 8
                                    val mdiaEnd = trakPos + trakChildSize.toInt()
                                    while (mdiaPos + 8 <= mdiaEnd) {
                                        val mdiaChildSize = readUint32Static(initData, mdiaPos)
                                        val mdiaChildType = readFourccStatic(initData, mdiaPos + 4)
                                        if (mdiaChildType == "mdhd") {
                                            // === 修复开始 ===
                                            // 1. 检查是否有足够空间读取 version 字节 (offset + 8)
                                            if (mdiaPos + 9 > initData.size) {
                                                return 0L
                                            }
                                            // 2. 读取 version 字节
                                            val version = initData[mdiaPos + 8].toInt() and 0xFF
                                            // 3. 根据 version 确定 timescale 偏移量
                                            // ISO 14496-12:
                                            // Version 0: creation(4) + modification(4) -> timescale at +20
                                            // Version 1: creation(8) + modification(8) -> timescale at +28
                                            val timescaleOffset = when (version) {
                                                0 -> 20
                                                1 -> 28
                                                else -> return 0L // 未知版本
                                            }
                                            // 4. 边界检查并读取
                                            if (mdiaPos + timescaleOffset + 4 <= initData.size) {
                                                return readUint32Static(initData, mdiaPos + timescaleOffset)
                                            } else {
                                                Log.e(TAG, "Init segment too short to read timescale (v=$version)")
                                                return 0L
                                            }
                                            // === 修复结束 ===
                                        }
                                        mdiaPos += mdiaChildSize.toInt()
                                    }
                                }
                                trakPos += trakChildSize.toInt()
                            }
                        }
                        moovPos += childSize.toInt()
                    }
                }
                pos += size.toInt()
            }
            return 0
        }

        /**
         * 从 fMP4 Init Segment 解析视频 NAL 长度前缀字节数 (lengthSizeMinusOne + 1)。
         * avcC: 第5字节(索引4)低2位 = lengthSizeMinusOne
         * hvcC: 第13字节(索引12)低2位 = lengthSizeMinusOne
         * 找不到时返回默认 4。
         */
        fun parseNalLengthSizeFromInitSegment(initData: ByteArray): Int {
            var pos = 0
            while (pos + 8 <= initData.size) {
                val size = readUint32Static(initData, pos)
                if (size <= 0 || pos + size.toInt() > initData.size) break
                val type = readFourccStatic(initData, pos + 4)
                if (type == "moov") {
                    val r = nalLenInMoov(initData, pos + 8, pos + size.toInt())
                    if (r > 0) return r
                }
                pos += size.toInt()
            }
            return 4
        }

        private fun nalLenInMoov(data: ByteArray, start: Int, end: Int): Int {
            var pos = start
            while (pos + 8 <= end && pos + 8 <= data.size) {
                val size = readUint32Static(data, pos)
                if (size <= 0) break
                val type = readFourccStatic(data, pos + 4)
                if (type == "trak") {
                    val r = nalLenInTrak(data, pos + 8, pos + size.toInt())
                    if (r > 0) return r
                }
                pos += size.toInt()
            }
            return 0
        }

        private fun nalLenInTrak(data: ByteArray, start: Int, end: Int): Int {
            var pos = start
            while (pos + 8 <= end && pos + 8 <= data.size) {
                val size = readUint32Static(data, pos)
                if (size <= 0) break
                val type = readFourccStatic(data, pos + 4)
                if (type == "mdia") {
                    val r = nalLenInMdia(data, pos + 8, pos + size.toInt())
                    if (r > 0) return r
                }
                pos += size.toInt()
            }
            return 0
        }

        private fun nalLenInMdia(data: ByteArray, start: Int, end: Int): Int {
            var pos = start
            while (pos + 8 <= end && pos + 8 <= data.size) {
                val size = readUint32Static(data, pos)
                if (size <= 0) break
                val type = readFourccStatic(data, pos + 4)
                if (type == "minf") {
                    val r = nalLenInMinf(data, pos + 8, pos + size.toInt())
                    if (r > 0) return r
                }
                pos += size.toInt()
            }
            return 0
        }

        private fun nalLenInMinf(data: ByteArray, start: Int, end: Int): Int {
            var pos = start
            while (pos + 8 <= end && pos + 8 <= data.size) {
                val size = readUint32Static(data, pos)
                if (size <= 0) break
                val type = readFourccStatic(data, pos + 4)
                if (type == "stbl") {
                    val r = nalLenInStbl(data, pos + 8, pos + size.toInt())
                    if (r > 0) return r
                }
                pos += size.toInt()
            }
            return 0
        }

        private fun nalLenInStbl(data: ByteArray, start: Int, end: Int): Int {
            var pos = start
            while (pos + 8 <= end && pos + 8 <= data.size) {
                val size = readUint32Static(data, pos)
                if (size <= 0) break
                val type = readFourccStatic(data, pos + 4)
                if (type == "stsd") {
                    val r = nalLenInStsd(data, pos + 8, pos + size.toInt())
                    if (r > 0) return r
                }
                pos += size.toInt()
            }
            return 0
        }

        private fun nalLenInStsd(data: ByteArray, start: Int, end: Int): Int {
            if (start + 8 > end || start + 8 > data.size) return 0
            // stsd: version(1) + flags(3) + entry_count(4)
            val entryCount = readUint32Static(data, start + 4)
            if (entryCount <= 0) return 0
            val entryPos = start + 8
            if (entryPos + 8 > end || entryPos + 8 > data.size) return 0
            val entrySize = readUint32Static(data, entryPos)
            val entryEnd = minOf(entryPos + entrySize.toInt(), end, data.size)
            var cpos = entryPos + 8 // skip sample entry header (size + type)
            while (cpos + 8 <= entryEnd) {
                val csize = readUint32Static(data, cpos)
                if (csize <= 0 || cpos + csize.toInt() > entryEnd) break
                val ctype = readFourccStatic(data, cpos + 4)
                if (ctype == "avcC") {
                    val p = cpos + 8
                    if (p < data.size) return (data[p].toInt() and 0x03) + 1
                } else if (ctype == "hvcC") {
                    val p = cpos + 12
                    if (p < data.size) return (data[p].toInt() and 0x03) + 1
                }
                cpos += csize.toInt()
            }
            return 0
        }

        private fun readUint32Static(data: ByteArray, offset: Int): Long {
            return ((data[offset].toLong() and 0xFF) shl 24) or
                    ((data[offset + 1].toLong() and 0xFF) shl 16) or
                    ((data[offset + 2].toLong() and 0xFF) shl 8) or
                    (data[offset + 3].toLong() and 0xFF)
        }
        private fun readFourccStatic(data: ByteArray, offset: Int): String {
            return String(data, offset, 4, Charsets.US_ASCII)
        }
    }
    fun parseFragment(fragmentData: ByteArray): List<ParsedSample> {
        val samples = mutableListOf<ParsedSample>()
        try {
            var pos = 0
            var firstMoof = true
            while (pos + 8 <= fragmentData.size) {
                val size = readUint32(fragmentData, pos)
                if (size <= 0) break
                val type = readFourcc(fragmentData, pos + 4)
                if (type == "moof") {
                    val moofStartPos = pos  // 记录 moof 起始位置，用于修正 dataOffset
                    val moofEnd = pos + size.toInt()
                    var moofPos = pos + 8
                    var tfdt = 0L
                    var trun: TrunData? = null
                    var cttsData: CttsData? = null
                    while (moofPos + 8 <= moofEnd) {
                        val childSize = readUint32(fragmentData, moofPos)
                        if (childSize <= 0) break
                        val childType = readFourcc(fragmentData, moofPos + 4)
                        if (childType == "traf") {
                            val trafEnd = moofPos + childSize.toInt()
                            var trafPos = moofPos + 8
                            var tfhd: TfhdData? = null
                            while (trafPos + 8 <= trafEnd) {
                                val trafChildSize = readUint32(fragmentData, trafPos)
                                if (trafChildSize <= 0) break
                                val trafChildType = readFourcc(fragmentData, trafPos + 4)
                                when (trafChildType) {
                                    "tfhd" -> tfhd = parseTfhd(fragmentData, trafPos, trafChildSize.toInt())
                                    "tfdt" -> tfdt = parseTfdt(fragmentData, trafPos, trafChildSize.toInt())
                                    "trun" -> trun = parseTrun(fragmentData, trafPos, trafChildSize.toInt())
                                    "ctts" -> cttsData = parseCtts(fragmentData, trafPos, trafChildSize.toInt())
                                }
                                trafPos += trafChildSize.toInt()
                            }
                            // tfhd 默认值回填到 trun
                            if (trun != null && tfhd != null) {
                                val trunFlags = trun.flags
                                val hasTrunDuration = (trunFlags and 0x000100) != 0
                                val hasTrunSize = (trunFlags and 0x000200) != 0
                                val hasTrunFlags = (trunFlags and 0x000400) != 0
                                val hasFirstSampleFlags = (trunFlags and 0x000004) != 0
                                for (i in 0 until trun.sampleCount) {
                                    if (!hasTrunDuration && tfhd.defaultSampleDuration > 0) {
                                        trun.sampleDurations[i] = tfhd.defaultSampleDuration.toInt()
                                    }
                                    if (!hasTrunSize && tfhd.defaultSampleSize > 0) {
                                        trun.sampleSizes[i] = tfhd.defaultSampleSize.toInt()
                                    }
                                    if (!hasTrunFlags && tfhd.defaultSampleFlags != 0) {
                                        // first_sample_flags 优先级高于 tfhd 默认值
                                        if (i != 0 || !hasFirstSampleFlags) {
                                            trun.sampleFlags[i] = tfhd.defaultSampleFlags
                                        }
                                    }
                                }
                            }
                        }
                        moofPos += childSize.toInt()
                    }
                    if (trun != null && tfdt >= 0) {
                        // 仅用首个 moof 的 tfdt 作为本分片基准（RealTimeMuxer 跨轨对齐用）
                        if (firstMoof) {
                            baseMediaDecodeTime = tfdt
                            firstMoof = false
                        }
                        // 在 moof 之后查找 mdat（通常紧跟其后的下一个 box）
                        // 一个完整 segment 可能含多个 moof+mdat 对，必须逐个解析，否则会丢帧/黑屏
                        var mdatPos = moofEnd
                        while (mdatPos + 8 <= fragmentData.size) {
                            val mSize = readUint32(fragmentData, mdatPos)
                            if (mSize <= 0) break
                            val mType = readFourcc(fragmentData, mdatPos + 4)
                            if (mType == "mdat") {
                                val mdatDataSize = mSize.toInt() - 8
                                if (mdatDataSize > 0) {
                                    val mdatData = ByteArray(mdatDataSize)
                                    System.arraycopy(fragmentData, mdatPos + 8, mdatData, 0, mdatData.size)
                                    // data_offset 是相对于 moof 开头的偏移（ISO BMFF 规范）
                                    // mdat 内容在 fragmentData 中的绝对位置 = moofStartPos + dataOffset
                                    // 而 mdatData 是从 mdatPos+8 开始的，所以相对偏移 = (moofStartPos + dataOffset) - (mdatPos + 8)
                                    val correctedDataOffset = maxOf(0, moofStartPos + trun.dataOffset - (mdatPos + 8))
                                    samples.addAll(extractSamplesFromMdat(mdatData, tfdt, trun, correctedDataOffset, cttsData))
                                }
                                break
                            }
                            mdatPos += mSize.toInt()
                        }
                    }
                    pos = moofEnd
                } else {
                    pos += size.toInt()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$taskId] Failed to parse fragment (${fragmentData.size} bytes)", e)
        }
        if (samples.isNotEmpty()) {
            Log.d(TAG, "[$taskId] Parsed ${samples.size} samples, pts=${samples.first().pts}~${samples.last().pts}")
        }
        return samples
    }
    private fun parseTfdt(data: ByteArray, pos: Int, size: Int): Long {
        if (pos + 12 > data.size) return -1L
        val version = data[pos + 8].toInt() and 0xFF
        return if (version == 0) {
            if (pos + 16 <= data.size) {
                readUint32(data, pos + 12)
            } else {
                -1L
            }
        } else {
            if (pos + 20 <= data.size) {
                readUint64(data, pos + 12)
            } else {
                -1L
            }
        }
    }
    private data class TrunData(
        val sampleCount: Int,
        val sampleSizes: IntArray,
        val sampleDurations: IntArray,
        val sampleFlags: IntArray,
        val sampleCompositionOffsets: IntArray,
        val dataOffset: Int,
        val flags: Int = 0  // 保存 trun flags，用于 tfhd 默认值回填判断
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as TrunData
            if (sampleCount != other.sampleCount) return false
            if (!sampleSizes.contentEquals(other.sampleSizes)) return false
            if (!sampleDurations.contentEquals(other.sampleDurations)) return false
            if (!sampleFlags.contentEquals(other.sampleFlags)) return false
            if (!sampleCompositionOffsets.contentEquals(other.sampleCompositionOffsets)) return false
            if (dataOffset != other.dataOffset) return false
            if (flags != other.flags) return false
            return true
        }
        override fun hashCode(): Int {
            var result = sampleCount
            result = 31 * result + sampleSizes.contentHashCode()
            result = 31 * result + sampleDurations.contentHashCode()
            result = 31 * result + sampleFlags.contentHashCode()
            result = 31 * result + sampleCompositionOffsets.contentHashCode()
            result = 31 * result + dataOffset
            result = 31 * result + flags
            return result
        }
    }
    /**
     * Track Fragment Header Box 解析结果
     * 存储 trun 未提供时的默认样本时长、大小、标志位
     */
    private data class TfhdData(
        val defaultSampleDuration: Long = 0,
        val defaultSampleSize: Long = 0,
        val defaultSampleFlags: Int = 0
    )
    private fun parseTfhd(data: ByteArray, pos: Int, size: Int): TfhdData? {
        if (pos + 16 > data.size) return null
        val version = data[pos + 8].toInt() and 0xFF
        val flags = readUint24(data, pos + 9)
        var offset = pos + 12
        // 跳过 track_ID
        offset += 4
        var defaultSampleDuration = 0L
        var defaultSampleSize = 0L
        var defaultSampleFlags = 0
        val hasDefaultSampleDuration = (flags and 0x000010) != 0
        val hasDefaultSampleSize = (flags and 0x000020) != 0
        val hasDefaultSampleFlags = (flags and 0x000040) != 0
        if (hasDefaultSampleDuration) {
            if (offset + 4 > data.size) return null
            defaultSampleDuration = readUint32(data, offset)
            offset += 4
        }
        if (hasDefaultSampleSize) {
            if (offset + 4 > data.size) return null
            defaultSampleSize = readUint32(data, offset)
            offset += 4
        }
        if (hasDefaultSampleFlags) {
            if (offset + 4 > data.size) return null
            defaultSampleFlags = readUint32(data, offset).toInt()
            offset += 4
        }
        return TfhdData(defaultSampleDuration, defaultSampleSize, defaultSampleFlags)
    }
    /**
     * CTTS (Composition Time to Sample) 数据
     * 用于将 DTS 转换为 PTS：PTS = DTS + compositionOffset
     */
    private data class CttsData(
        val compositionOffsets: IntArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as CttsData
            return compositionOffsets.contentEquals(other.compositionOffsets)
        }
        override fun hashCode(): Int {
            return compositionOffsets.contentHashCode()
        }
    }
    /**
     * 解析 ctts box
     * 返回每个 sample 的 composition offset 数组（已展开，每个 sample 一个 entry）
     *
     * CTTS entry 格式：
     *   version 0: sample_count(4) + sample_offset(4, unsigned)
     *   version 1: sample_count(4) + sample_offset(4, signed)
     *
     * 注意：entry_count 不等于 sample_count，需要按 sample_count 展开
     */
    private fun parseCtts(data: ByteArray, pos: Int, size: Int): CttsData? {
        if (pos + 12 > data.size) return null
        val version = data[pos + 8].toInt() and 0xFF
        val entryCount = readUint32(data, pos + 12).toInt()
        if (entryCount <= 0) return null
        // 先解析所有 entry，然后展开成 per-sample 数组
        val expandedOffsets = mutableListOf<Int>()
        var offset = pos + 16
        for (i in 0 until entryCount) {
            if (offset + 8 > data.size) break
            val sampleCount = readUint32(data, offset).toInt()
            val sampleOffset = if (version == 0) {
                readUint32(data, offset + 4).toInt()
            } else {
                readInt32(data, offset + 4)
            }
            // 展开：这个 entry 对应 sampleCount 个 sample
            repeat(sampleCount) {
                expandedOffsets.add(sampleOffset)
            }
            offset += 8
        }
        if (expandedOffsets.isEmpty()) return null
        return CttsData(expandedOffsets.toIntArray())
    }
    private fun readInt32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF))
    }
    private fun parseTrun(data: ByteArray, pos: Int, size: Int): TrunData? {
        if (pos + 16 > data.size) return null
        val version = data[pos + 8].toInt() and 0xFF
        val flags = readUint24(data, pos + 9)
        var offset = pos + 12
        val sampleCount = readUint32(data, offset).toInt()
        offset += 4
        // ISO 14496-12 标准 trun flags 位定义
        val hasDataOffset = (flags and 0x000001) != 0
        val hasFirstSampleFlags = (flags and 0x000004) != 0
        val hasSampleDuration = (flags and 0x000100) != 0
        val hasSampleSize = (flags and 0x000200) != 0
        val hasSampleFlags = (flags and 0x000400) != 0
        val hasSampleCompositionTimeOffset = (flags and 0x000800) != 0
        var dataOffset = 0
        if (hasDataOffset) {
            if (offset + 4 > data.size) return null
            dataOffset = readInt32(data, offset)
            offset += 4
        }
        var firstSampleFlags = 0
        if (hasFirstSampleFlags) {
            if (offset + 4 > data.size) return null
            firstSampleFlags = readUint32(data, offset).toInt()
            offset += 4
        }
        val sampleDurations = IntArray(sampleCount) { 0 }
        val sampleSizes = IntArray(sampleCount) { 0 }
        val sampleFlags = IntArray(sampleCount) { 0 }
        val sampleCompositionOffsets = IntArray(sampleCount) { 0 }
        for (i in 0 until sampleCount) {
            if (hasSampleDuration) {
                if (offset + 4 > data.size) return null
                sampleDurations[i] = readUint32(data, offset).toInt()
                offset += 4
            }
            if (hasSampleSize) {
                if (offset + 4 > data.size) return null
                sampleSizes[i] = readUint32(data, offset).toInt()
                offset += 4
            }
            if (hasSampleFlags) {
                if (offset + 4 > data.size) return null
                sampleFlags[i] = readUint32(data, offset).toInt()
                offset += 4
            } else if (i == 0 && hasFirstSampleFlags) {
                // 仅第一个样本使用 first_sample_flags
                sampleFlags[i] = firstSampleFlags
            }
            if (hasSampleCompositionTimeOffset) {
                if (offset + 4 > data.size) return null
                sampleCompositionOffsets[i] = if (version == 0) {
                    readUint32(data, offset).toInt()
                } else {
                    readInt32(data, offset)
                }
                offset += 4
            }
        }
        return TrunData(sampleCount, sampleSizes, sampleDurations, sampleFlags, sampleCompositionOffsets, dataOffset, flags)
    }
    private fun extractSamplesFromMdat(
        mdatData: ByteArray,
        basePts: Long,
        trun: TrunData,
        dataOffset: Int,
        ctts: CttsData? = null
    ): List<ParsedSample> {
        val samples = mutableListOf<ParsedSample>()
        var currentDts = basePts
        var mdatPos = dataOffset
        var totalSampleSize = 0
        for (i in 0 until trun.sampleCount) {
            val size = trun.sampleSizes[i]
            if (size <= 0) {
                currentDts += trun.sampleDurations[i]
                continue
            }
            if (mdatPos + size > mdatData.size) {
                Log.w(TAG, "[$taskId] Sample $i overflow: mdatPos=$mdatPos size=$size mdatSize=${mdatData.size}, skipping remaining samples")
                break
            }
            val sampleData = ByteArray(size)
            System.arraycopy(mdatData, mdatPos, sampleData, 0, size)
            totalSampleSize += size
            // 正确的 sample_flags 位域解析（ISO 14496-12 标准，高字节在前）
            val flags = trun.sampleFlags[i]
            // sample_depends_on: 第25-24位，值为2表示不依赖其他帧（关键帧）
            val sampleDependsOn = (flags shr 24) and 0x03
            // sample_is_non_sync_sample: 第16位，值为1表示非同步样本（非关键帧）
            val isNonSync = (flags and 0x00010000) != 0
            val isKeyFrame = when {
                sampleDependsOn == 2 -> true
                !isNonSync && flags != 0 -> true
                i == 0 && flags == 0 -> true // 首帧无标记时兜底
                else -> false
            }
            if (i == 0) {
                Log.w(TAG, "[$taskId] SampleFlags[0]=0x${trun.sampleFlags[i].toString(16).padStart(8,'0')} dependsOn=$sampleDependsOn isNonSync=$isNonSync -> isKeyFrame=$isKeyFrame")
            }
            // 计算 PTS：DTS + composition offset（优先用 trun 中的，回退到 ctts）
            val compositionOffset = when {
                i < trun.sampleCompositionOffsets.size -> trun.sampleCompositionOffsets[i].toLong()
                ctts != null && i < ctts.compositionOffsets.size -> ctts.compositionOffsets[i].toLong()
                else -> 0L
            }
            val pts = currentDts + compositionOffset
            val ptsUs = if (timescale > 0) pts * 1000000 / timescale else pts
            samples.add(ParsedSample(ptsUs, sampleData, isKeyFrame))
            currentDts += trun.sampleDurations[i]
            mdatPos += size
        }
        // mdat 大小校验：仅溢出时报错丢弃，不足时仅告警（可能存在填充字节）
        val expectedSize = mdatData.size - dataOffset
        if (totalSampleSize > expectedSize) {
            Log.e(TAG, "[$taskId] mdat size overflow: parsed=$totalSampleSize expected=$expectedSize, discarding samples")
            return emptyList()
        }
        if (totalSampleSize < expectedSize * 0.7 && totalSampleSize > 0) {
            Log.w(TAG, "[$taskId] mdat size mismatch: parsed=$totalSampleSize expected=$expectedSize, possible parse error")
        }
        // 校验：完整 segment 的第一个 sample 必须是关键帧（LL-HLS 规范强制要求）
        if (samples.isNotEmpty() && !samples[0].isKeyFrame) {
            Log.w(TAG, "[$taskId] First sample is not key frame, segment may be corrupted or flags parsed wrong")
        }
        return samples
    }
    private fun readUint32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
    }
    private fun readUint24(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 16) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                (data[offset + 2].toInt() and 0xFF)
    }
    private fun readUint64(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 56) or
                ((data[offset + 1].toLong() and 0xFF) shl 48) or
                ((data[offset + 2].toLong() and 0xFF) shl 40) or
                ((data[offset + 3].toLong() and 0xFF) shl 32) or
                ((data[offset + 4].toLong() and 0xFF) shl 24) or
                ((data[offset + 5].toLong() and 0xFF) shl 16) or
                ((data[offset + 6].toLong() and 0xFF) shl 8) or
                (data[offset + 7].toLong() and 0xFF)
    }
    private fun readFourcc(data: ByteArray, offset: Int): String {
        return String(data, offset, 4, Charsets.US_ASCII)
    }
}