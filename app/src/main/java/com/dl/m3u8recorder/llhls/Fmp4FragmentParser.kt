package com.dl.m3u8recorder.llhls

import android.media.MediaCodec
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
    companion object {
        private const val TAG = "Fmp4FragmentParser"

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
                                            if (mdiaPos + 20 <= initData.size) {
                                                return readUint32Static(initData, mdiaPos + 16)
                                            }
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
            while (pos + 8 <= fragmentData.size) {
                val size = readUint32(fragmentData, pos)
                val type = readFourcc(fragmentData, pos + 4)

                if (type == "moof") {
                    val moofStartPos = pos  // 记录 moof 起始位置，用于修正 dataOffset
                    val moofEnd = pos + size.toInt()
                    var moofPos = pos + 8

                    var tfdt = 0L
                    var trun: TrunData? = null

                    while (moofPos + 8 <= moofEnd) {
                        val childSize = readUint32(fragmentData, moofPos)
                        val childType = readFourcc(fragmentData, moofPos + 4)

                        if (childType == "traf") {
                            val trafEnd = moofPos + childSize.toInt()
                            var trafPos = moofPos + 8

                            while (trafPos + 8 <= trafEnd) {
                                val trafChildSize = readUint32(fragmentData, trafPos)
                                val trafChildType = readFourcc(fragmentData, trafPos + 4)

                                if (trafChildType == "tfdt") {
                                    tfdt = parseTfdt(fragmentData, trafPos, trafChildSize.toInt())
                                } else if (trafChildType == "trun") {
                                    trun = parseTrun(fragmentData, trafPos, trafChildSize.toInt())
                                }

                                trafPos += trafChildSize.toInt()
                            }
                        }

                        moofPos += childSize.toInt()
                    }

                    if (trun != null && tfdt >= 0) {
                        pos = moofEnd
                        if (pos + 8 <= fragmentData.size) {
                            val nextSize = readUint32(fragmentData, pos)
                            val nextType = readFourcc(fragmentData, pos + 4)

                            if (nextType == "mdat") {
                                val mdatData = ByteArray(nextSize.toInt() - 8)
                                System.arraycopy(fragmentData, pos + 8, mdatData, 0, mdatData.size)

                                // 修正 dataOffset: trun.dataOffset 是相对于 moof 起始位置的偏移，
                                // 但 mdatData 是从 mdat 内容起始 (moofEnd + 8) 开始的。
                                // 需要减去 (moofEnd - moofStartPos + 8) = moof_size + 8
                                val moofSize = moofEnd - moofStartPos
                                val correctedDataOffset = maxOf(0, trun.dataOffset - moofSize - 8)

                                samples.addAll(extractSamplesFromMdat(mdatData, tfdt, trun, correctedDataOffset))
                            }
                        }
                    }

                    break
                }

                pos += size.toInt()
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
        val dataOffset: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as TrunData
            if (sampleCount != other.sampleCount) return false
            if (!sampleSizes.contentEquals(other.sampleSizes)) return false
            if (!sampleDurations.contentEquals(other.sampleDurations)) return false
            if (!sampleFlags.contentEquals(other.sampleFlags)) return false
            if (dataOffset != other.dataOffset) return false
            return true
        }

        override fun hashCode(): Int {
            var result = sampleCount
            result = 31 * result + sampleSizes.contentHashCode()
            result = 31 * result + sampleDurations.contentHashCode()
            result = 31 * result + sampleFlags.contentHashCode()
            result = 31 * result + dataOffset
            return result
        }
    }

    private fun parseTrun(data: ByteArray, pos: Int, size: Int): TrunData? {
        if (pos + 16 > data.size) return null

        val version = data[pos + 8].toInt() and 0xFF
        val flags = readUint24(data, pos + 9)

        var offset = pos + 12

        val sampleCount = readUint32(data, offset).toInt()
        offset += 4

        val hasDataOffset = (flags and 0x000001) != 0
        val hasFirstSampleFlags = (flags and 0x000004) != 0
        val hasSampleDuration = (flags and 0x000100) != 0
        val hasSampleSize = (flags and 0x000200) != 0
        val hasSampleFlags = (flags and 0x000400) != 0

        var dataOffset = 0
        if (hasDataOffset) {
            if (version == 0) {
                if (offset + 4 > data.size) return null
                dataOffset = readUint32(data, offset).toInt()
                offset += 4
            } else {
                if (offset + 8 > data.size) return null
                dataOffset = readUint64(data, offset).toInt()
                offset += 8
            }
        }

        var firstSampleFlags = 0
        if (hasFirstSampleFlags) {
            if (offset + 4 > data.size) return null
            firstSampleFlags = readUint32(data, offset).toInt()
            offset += 4
        }

        val sampleDurations = IntArray(sampleCount)
        if (hasSampleDuration) {
            for (i in 0 until sampleCount) {
                if (offset + 4 > data.size) return null
                sampleDurations[i] = readUint32(data, offset).toInt()
                offset += 4
            }
        } else {
            for (i in 0 until sampleCount) {
                sampleDurations[i] = 1
            }
        }

        val sampleSizes = IntArray(sampleCount)
        if (hasSampleSize) {
            for (i in 0 until sampleCount) {
                if (offset + 4 > data.size) return null
                sampleSizes[i] = readUint32(data, offset).toInt()
                offset += 4
            }
        } else {
            for (i in 0 until sampleCount) {
                sampleSizes[i] = 0
            }
        }

        val sampleFlags = IntArray(sampleCount)
        if (hasSampleFlags) {
            for (i in 0 until sampleCount) {
                if (offset + 4 > data.size) return null
                sampleFlags[i] = readUint32(data, offset).toInt()
                offset += 4
            }
        } else {
            for (i in 0 until sampleCount) {
                sampleFlags[i] = if (i == 0) firstSampleFlags else 0
            }
        }

        return TrunData(sampleCount, sampleSizes, sampleDurations, sampleFlags, dataOffset)
    }

    private fun extractSamplesFromMdat(mdatData: ByteArray, basePts: Long, trun: TrunData, dataOffset: Int): List<ParsedSample> {
        val samples = mutableListOf<ParsedSample>()
        var currentPts = basePts
        var mdatPos = dataOffset

        for (i in 0 until trun.sampleCount) {
            val size = trun.sampleSizes[i]
            if (size <= 0 || mdatPos + size > mdatData.size) {
                currentPts += trun.sampleDurations[i]
                continue
            }

            val sampleData = ByteArray(size)
            System.arraycopy(mdatData, mdatPos, sampleData, 0, size)

            val isKeyFrame = (trun.sampleFlags[i] and 0x00000100) != 0

            val ptsUs = if (timescale > 0) currentPts * 1000000 / timescale else currentPts
            samples.add(ParsedSample(ptsUs, sampleData, isKeyFrame))

            currentPts += trun.sampleDurations[i]
            mdatPos += size
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
