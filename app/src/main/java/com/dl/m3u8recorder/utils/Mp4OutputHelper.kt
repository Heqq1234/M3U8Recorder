package com.dl.m3u8recorder.utils

import android.content.Context
import java.io.File

object Mp4OutputHelper {
    fun getOutputFile(context: Context, fileName: String): File {
        val dir = context.getExternalFilesDir("Movies") ?: context.cacheDir
        if (!dir.exists()) dir.mkdirs()
        val safeFileName = if (fileName.endsWith(".mp4")) fileName else "$fileName.mp4"
        return File(dir, safeFileName)
    }
}