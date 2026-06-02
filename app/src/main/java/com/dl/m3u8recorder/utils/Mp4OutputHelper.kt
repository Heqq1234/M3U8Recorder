package com.dl.m3u8recorder.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

object Mp4OutputHelper {

    private const val PREFS_NAME = "DownloadPrefs"
    private const val KEY_CUSTOM_DOWNLOAD_DIR_URI = "customDownloadDirUri"

    /**
     * 获取应用专属的下载目录 (默认目录)。
     * 这是应用内部存储，应用卸载后文件会一并删除。
     */
    fun getAppSpecificDownloadsDir(context: Context): File {
        val dir = ContextCompat.getExternalFilesDirs(context, Environment.DIRECTORY_MOVIES)?.firstOrNull()
        return dir ?: context.filesDir // Fallback to internal files dirclaude

    }

    /**
     * 根据自定义 URI 获取输出文件路径。
     * @param context Context
     * @param uri 自定义目录的 Uri (通过 DocumentProvider 获取的)
     * @param fileName 用户指定的文件名
     * @param extension 文件扩展名 (例如 ".mp4", ".ts")
     * @return File 对象
     */
    fun getOutputFileFromUri(context: Context, uri: Uri, fileName: String, extension: String): File {
        // 为了兼容 FFmpegKit，我们暂时仍然让 FFmpeg 写入到应用私有目录，
        // 然后在任务完成时，由 DownloadManager 负责将其移动到用户指定的目录（MediaStoreSaver）。
        val directory = getAppSpecificDownloadsDir(context) // FFmpeg 实际写入的临时目录
        return File(directory, fileName + extension)
    }

    /**
     * 获取输出文件路径，用于 FFmpeg 写入。
     *
     * @param context Context
     * @param fileName 用户指定的文件名
     * @param extension 文件扩展名 (例如 ".mp4", ".ts")
     * @return File 对象，指向应用私有目录中的文件。
     */
    fun getOutputFile(context: Context, fileName: String, extension: String): File {
        return File(getAppSpecificDownloadsDir(context), fileName + extension)
    }


    /**
     * 保存自定义下载目录的 URI 到 SharedPreferences。
     */
    fun setCustomDownloadDirectory(context: Context, uriString: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_DOWNLOAD_DIR_URI, uriString)
            .apply()
    }

    /**
     * 从 SharedPreferences 获取自定义下载目录的 URI。
     */
    fun getCustomDownloadDirectory(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_DOWNLOAD_DIR_URI, null)
    }

    /**
     * 从 DocumentProvider URI 获取可读的路径（如果可能）。
     * 这主要用于在UI上显示给用户，不直接用于文件操作。
     */
    fun getUriPath(context: Context, uri: Uri): String? {
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":")
            if (split.size == 2) {
                val type = split[0]
                val path = split[1]

                return when (type) {
                    "primary" -> "${Environment.getExternalStorageDirectory().absolutePath}/$path"
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.e("Mp4OutputHelper", "Failed to get path from URI: $uri", e)
        }
        return null // If unable to parse
    }
}