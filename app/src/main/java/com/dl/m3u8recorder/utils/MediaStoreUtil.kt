package com.dl.m3u8recorder.utils

import android.content.Context
import android.os.Environment
import java.io.File

class MediaStoreUtil {
    companion object {
        fun createVideoPathInScopedStorage(context: Context, fileName: String): String? {
            val movieDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val outputFile = File(movieDir, "$fileName.mp4")
            return outputFile.absolutePath
        }
    }
}