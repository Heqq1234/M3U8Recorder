package com.dl.m3u8recorder.utils

import android.content.Context
import java.io.File

object FFmpegMerger {
    fun mergeTsFiles(context: Context, tsFiles: List<File>, outputMp4: File, onComplete: (Boolean) -> Unit) {
        try {
            val concatFile = File(context.cacheDir, "concat.txt")
            concatFile.writeText(tsFiles.joinToString("") { "file '${it.absolutePath}'" })

            val ffmpegPath = "/data/data/${context.packageName}/files/ffmpeg"
            val cmd = arrayOf(
                ffmpegPath,
                "-f", "concat", "-safe", "0",
                "-i", concatFile.absolutePath,
                "-c", "copy", outputMp4.absolutePath
            )

            val process = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()

            process.waitFor()
            onComplete(outputMp4.exists())
        } catch (e: Exception) {
            e.printStackTrace()
            onComplete(false)
        }
    }
}
