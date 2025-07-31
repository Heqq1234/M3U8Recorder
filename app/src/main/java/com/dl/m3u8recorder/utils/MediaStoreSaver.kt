package com.dl.m3u8recorder.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract // 导入 DocumentsContract
import androidx.documentfile.provider.DocumentFile // 导入 DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream // 导入 OutputStream

object MediaStoreSaver {

    /**
     * 将文件保存到设备的公共媒体库（Movies 目录）。
     *
     * @param context Context 对象。
     * @param sourceFile 源文件的 File 对象（通常是应用内部缓存的临时文件）。
     * @param displayName 要保存的文件显示名称（不含扩展名）。
     * @return 如果保存成功，返回 true；否则返回 false。
     */
    fun saveToMediaStore(context: Context, sourceFile: File, displayName: String): Boolean {
        val resolver = context.contentResolver
        val videoCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            // 对于 Android Q (API 29) 及更高版本，使用 RELATIVE_PATH
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
            }
            put(MediaStore.Video.Media.IS_PENDING, 1) // 标记为 pending 直到写入完成
        }

        val videoUri = resolver.insert(videoCollection, values) ?: run {
            // Log.e("MediaStoreSaver", "Failed to insert new MediaStore item.") // 可以添加日志
            return false
        }

        try {
            resolver.openOutputStream(videoUri)?.use { outputStream ->
                FileInputStream(sourceFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0) // 标记为非 pending
            resolver.update(videoUri, values, null, null)
            return true
        } catch (e: Exception) {
            // Log.e("MediaStoreSaver", "Error saving to MediaStore: ${e.message}", e) // 可以添加日志
            resolver.delete(videoUri, null, null) // 发生错误时删除不完整条目
            return false
        } finally {
            // 无论成功失败，确保源文件可以被清理 (如果需要的话，通常在调用处处理)
            // sourceFile.delete() // 谨慎在这里删除，最好在调用 saveToMediaStore 成功后删除
        }
    }

    /**
     * 将文件保存到用户指定的 Storage Access Framework (SAF) URI 目录下。
     * 这通常用于 Android Q (API 29) 及更高版本。
     *
     * @param context Context 对象。
     * @param sourceFile 源文件的 File 对象（通常是应用内部缓存的临时文件）。
     * @param displayName 要保存的文件显示名称（不含扩展名）。
     * @param customDownloadUriString 用户选择的目录的 String 格式 SAF URI。
     * @return 如果保存成功，返回 true；否则返回 false。
     */
    fun saveToMediaStore(context: Context, sourceFile: File, displayName: String, customDownloadUriString: String?): Boolean {
        // 如果 customDownloadUriString 为空，或者 Android 版本低于 Q，退回到默认保存方式
        if (customDownloadUriString.isNullOrEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return saveToMediaStore(context, sourceFile, displayName) // 调用无 customDownloadUri 的重载方法
        }

        val customUri = Uri.parse(customDownloadUriString)
        val resolver = context.contentResolver

        try {
            // 使用 DocumentFile 访问用户选择的目录
            val documentFile = DocumentFile.fromTreeUri(context, customUri)
            if (documentFile == null || !documentFile.isDirectory || !documentFile.canWrite()) {
                // Log.e("MediaStoreSaver", "Invalid or unwritable custom download URI: $customDownloadUriString")
                return false
            }

            // 在用户选择的目录中创建新文件
            // 注意：SAF 路径是 URI，不能直接像 File 那样拼接，需要通过 DocumentFile.createFile()
            val newFileDocument = documentFile.createFile("video/mp4", "$displayName.mp4")
            if (newFileDocument == null) {
                // Log.e("MediaStoreSaver", "Failed to create new file in custom URI: $customDownloadUriString")
                return false
            }

            // 获取新创建文件的 OutputStream
            resolver.openOutputStream(newFileDocument.uri)?.use { outputStream ->
                FileInputStream(sourceFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            return true
        } catch (e: Exception) {
            // Log.e("MediaStoreSaver", "Error saving to custom MediaStore URI: ${e.message}", e)
            // 如果创建文件或写入过程中出错，SAF 会自动清理不完整的文件
            return false
        } finally {
            // 无论成功失败，确保源文件可以被清理 (如果需要的话，通常在调用处处理)
            // sourceFile.delete() // 谨慎在这里删除，最好在调用 saveToMediaStore 成功后删除
        }
    }
}