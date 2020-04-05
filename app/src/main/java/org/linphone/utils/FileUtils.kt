/*
 * Copyright (c) 2010-2020 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.tools.Log

class FileUtils {
    companion object {
        fun getMimeFromFile(path: String?): String? {
            val filePath = path ?: ""
            return if (isExtensionImage(filePath)) {
                "image/" + getExtensionFromFileName(filePath)
            } else "file/" + getExtensionFromFileName(filePath)
        }

        fun getNameFromFilePath(filePath: String): String {
            var name = filePath
            val i = filePath.lastIndexOf('/')
            if (i > 0) {
                name = filePath.substring(i + 1)
            }
            return name
        }

        fun getExtensionFromFileName(fileName: String): String {
            var extension = ""
            val i = fileName.lastIndexOf('.')
            if (i > 0) {
                extension = fileName.substring(i + 1)
            }
            return extension
        }

        fun isExtensionImage(path: String): Boolean {
            val extension = getExtensionFromFileName(path).toLowerCase(Locale.getDefault())
            return extension.matches(Regex("(png|jpg|jpeg|bmp|gif)"))
        }

        fun getFileStorageDir(isPicture: Boolean = false): File {
            var path: File? = null
            if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                Log.w("[File Utils] External storage is mounted")
                var directory = Environment.DIRECTORY_DOWNLOADS
                if (isPicture) {
                    Log.w("[File Utils] Using pictures directory instead of downloads")
                    directory = Environment.DIRECTORY_PICTURES
                }
                path = coreContext.context.getExternalFilesDir(directory)
            }

            val returnPath: File = path ?: coreContext.context.filesDir
            if (path == null) Log.w("[File Utils] Couldn't get external storage path, using internal")

            return returnPath
        }

        fun getFileStoragePath(fileName: String): File {
            val path = getFileStorageDir(isExtensionImage(fileName))
            var file = File(path, fileName)

            var prefix = 1
            while (file.exists()) {
                file = File(path, prefix.toString() + "_" + fileName)
                Log.w("[File Utils] File with that name already exists, renamed to ${file.name}")
                prefix += 1
            }
            return file
        }

        fun deleteFile(filePath: String) {
            val file = File(filePath)
            if (file.exists()) {
                try {
                    if (file.delete()) {
                        Log.i("[File Utils] Deleted $filePath")
                    } else {
                        Log.e("[File Utils] Can't delete $filePath")
                    }
                } catch (e: Exception) {
                    Log.e("[File Utils] Can't delete $filePath, exception: $e")
                }
            } else {
                Log.e("[File Utils] File $filePath doesn't exists")
            }
        }

        fun getFilePath(context: Context, uri: Uri): String? {
            var result: String? = null
            val name: String = getNameFromUri(uri, context)

            try {
                val localFile: File = createFile(name)
                val remoteFile =
                    context.contentResolver.openInputStream(uri)
                Log.i(
                    "[File Utils] Trying to copy file from " +
                            uri.toString() +
                            " to local file " +
                            localFile.absolutePath
                )
                if (copyToFile(remoteFile, localFile)) {
                    Log.i("[File Utils] Copy successful")
                    result = localFile.absolutePath
                } else {
                    Log.e("[File Utils] Copy failed")
                }
                remoteFile?.close()
            } catch (e: IOException) {
                Log.e("[File Utils] getFilePath exception: ", e)
            }

            return result
        }

        private fun getNameFromUri(uri: Uri, context: Context): String {
            var name = ""
            if (uri.scheme == "content") {
                val returnCursor =
                    context.contentResolver.query(uri, null, null, null, null)
                if (returnCursor != null) {
                    returnCursor.moveToFirst()
                    val nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = returnCursor.getString(nameIndex)
                    } else {
                        Log.e("[File Utils] Couldn't get DISPLAY_NAME column index for URI $uri")
                    }
                    returnCursor.close()
                }
            } else if (uri.scheme == "file") {
                name = uri.lastPathSegment ?: ""
            }
            return name
        }

        fun copyFileTo(filePath: String, outputStream: OutputStream?): Boolean {
            if (outputStream == null) {
                Log.e("[File Utils] Can't copy file $filePath to given null output stream")
                return false
            }

            val file = File(filePath)
            if (!file.exists()) {
                Log.e("[File Utils] Can't copy file $filePath, it doesn't exists")
                return false
            }

            try {
                val inputStream = FileInputStream(file)
                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } >= 0) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                return true
            } catch (e: IOException) {
                Log.e("[File Utils] copyFileTo exception: $e")
            }
            return false
        }

        private fun copyToFile(inputStream: InputStream?, destFile: File?): Boolean {
            if (inputStream == null || destFile == null) return false
            try {
                FileOutputStream(destFile).use { out ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } >= 0) {
                        out.write(buffer, 0, bytesRead)
                    }
                }
                return true
            } catch (e: IOException) {
                Log.e("[File Utils] copyToFile exception: $e")
            }
            return false
        }

        private fun createFile(fileName: String): File {
            var fileName = fileName

            if (fileName.isEmpty()) fileName = getStartDate()
            if (!fileName.contains(".")) {
                fileName = "$fileName.unknown"
            }

            return getFileStoragePath(fileName)
        }

        private fun getStartDate(): String {
            return try {
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
            } catch (e: RuntimeException) {
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            }
        }
    }
}