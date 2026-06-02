package com.dl.m3u8recorder.merger

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class FFmpegStreamMerger(
    private val outputFile: File
) {
    private val TAG = "FFmpegStreamMerger"

    private val tsFileList = CopyOnWriteArrayList<File>()
    private var isFinished = false

    fun startMerge() {
        tsFileList.clear()
        isFinished = false
        Log.d(TAG, "准备合并到: ${outputFile.absolutePath}")
    }

    fun feed(tsData: ByteArray) {
        if (isFinished) return

        val tempTsFile = File.createTempFile("segment_", ".ts")
        tempTsFile.writeBytes(tsData)
        tsFileList.add(tempTsFile)

        Log.d(TAG, "接收分片: ${tempTsFile.name}")
    }

    fun finish() {
        isFinished = true

        if (tsFileList.isEmpty()) {
            Log.e(TAG, "没有可合并的分片")
            return
        }

        try {
            val concatFile = File.createTempFile("concat_list", ".txt")
            concatFile.bufferedWriter().use { writer ->
                tsFileList.forEach {
                    writer.write("file '${it.absolutePath}'\n")
                }
            }

            // Phase 1: 添加时间戳修正参数解决音画不同步问题
            val command = listOf(
                "-fflags", "+genpts+igndts",
                "-f", "concat",
                "-safe", "0",
                "-i", concatFile.absolutePath,
                "-avoid_negative_ts", "make_zero",
                "-max_interleave_delta", "0",
                "-c", "copy",
                "-movflags", "+faststart",
                outputFile.absolutePath
            ).joinToString(" ")
            Log.d(TAG, "执行合并命令: $command")

            val session = FFmpegKit.execute(command)

            if (session.returnCode.isSuccess) {
                Log.d(TAG, "合并完成: ${outputFile.absolutePath}")
            } else {
                Log.e(TAG, "合并失败: ${session.failStackTrace}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "合并异常", e)
        } finally {
            // 清理所有临时分片
            tsFileList.forEach { it.delete() }
            tsFileList.clear()
        }
    }
}