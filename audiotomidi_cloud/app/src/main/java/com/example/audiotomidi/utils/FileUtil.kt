package com.example.audiotomidi.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

object FileUtil {

    fun getAudioDirectory(context: Context): File {
        return context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?:
        context.filesDir
    }

    @Throws(Exception::class)
    fun copyFileFromUri(context: Context, uri: Uri): File {
        val contentResolver: ContentResolver = context.contentResolver
        val timestamp = System.currentTimeMillis()
        val fileName = "selected_audio_$timestamp.wav"

        val audioDir = getAudioDirectory(context)
        val outputFile = File(audioDir, fileName)

        contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        return outputFile
    }

    fun inputStreamToFile(inputStream: InputStream, outputFile: File) {
        FileOutputStream(outputFile).use { outputStream ->
            inputStream.copyTo(outputStream)
        }
    }

    fun deleteFile(file: File) {
        if (file.exists()) {
            file.delete()
        }
    }

    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        return when {
            uri.scheme == "file" -> {
                File(uri.path ?: "").name
            }
            uri.scheme == "content" -> {
                var fileName: String? = null
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        // 尝试从OpenableColumns获取显示名称
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            fileName = cursor.getString(nameIndex)
                        } else {
                            // 尝试从MediaColumns获取标题
                            val titleIndex = cursor.getColumnIndex("title")
                            if (titleIndex != -1) {
                                fileName = cursor.getString(titleIndex)
                            }
                        }
                    }
                }
                fileName
            }
            else -> {
                uri.lastPathSegment
            }
        }
    }
}
