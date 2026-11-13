package com.dynast.airtether.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.dynast.airtether.domain.port.PhotoStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

object AndroidStorageContextHolder {
    @Volatile
    var appContext: Context? = null
}

class AndroidPhotoStorage(
    private val context: Context?,
    private val sessionName: String = "AirTether",
) : PhotoStorage {

    private sealed interface PendingTarget {
        data class MediaStoreTarget(val uri: Uri, val resolver: android.content.ContentResolver) : PendingTarget
        data class FileTarget(val tempFile: File, val finalFile: File) : PendingTarget
    }

    private val pending = ConcurrentHashMap<String, PendingTarget>()

    override suspend fun createPendingFile(fileName: String, mimeType: String): String =
        withContext(Dispatchers.IO) {
            val sanitized = fileName.substringAfterLast('/').substringAfterLast('\\')
                .ifBlank { "capture_${System.currentTimeMillis()}.bin" }

            val ctx = context
            if (ctx != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = ctx.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, sanitized)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/AirTether/$sessionName",
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = if (mimeType.startsWith("image/")) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val uri = resolver.insert(collection, values)
                    ?: error("Failed to insert MediaStore pending entry for $sanitized")
                val handle = uri.toString()
                pending[handle] = PendingTarget.MediaStoreTarget(uri = uri, resolver = resolver)
                handle
            } else {
                val baseDir = File(
                    ctx?.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: File(System.getProperty("java.io.tmpdir")),
                    "AirTether/$sessionName",
                ).apply { mkdirs() }
                val temp = File(baseDir, ".$sanitized.${System.nanoTime()}.part")
                temp.createNewFile()
                val finalFile = File(baseDir, sanitized)
                val handle = temp.absolutePath
                pending[handle] = PendingTarget.FileTarget(tempFile = temp, finalFile = finalFile)
                handle
            }
        }

    override suspend fun writeChunk(pendingHandle: String, bytes: ByteArray, offset: Long) {
        val target = pending[pendingHandle] ?: return
        withContext(Dispatchers.IO) {
            when (target) {
                is PendingTarget.MediaStoreTarget -> {
                    target.resolver.openOutputStream(target.uri, "wa")?.use { out ->
                        out.write(bytes)
                    }
                }
                is PendingTarget.FileTarget -> {
                    FileOutputStream(target.tempFile, true).use { out ->
                        out.write(bytes)
                    }
                }
            }
        }
    }

    override suspend fun commitPendingFile(pendingHandle: String): String =
        withContext(Dispatchers.IO) {
            val target = pending.remove(pendingHandle)
                ?: error("Unknown pending handle: $pendingHandle")
            when (target) {
                is PendingTarget.MediaStoreTarget -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val done = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        target.resolver.update(target.uri, done, null, null)
                    }
                    target.uri.toString()
                }
                is PendingTarget.FileTarget -> {
                    target.tempFile.renameTo(target.finalFile)
                    target.finalFile.absolutePath
                }
            }
        }

    override suspend fun abortPendingFile(pendingHandle: String) {
        val target = pending.remove(pendingHandle) ?: return
        withContext(Dispatchers.IO) {
            when (target) {
                is PendingTarget.MediaStoreTarget -> target.resolver.delete(target.uri, null, null)
                is PendingTarget.FileTarget -> target.tempFile.delete()
            }
        }
    }

    override suspend fun savePreviewJpeg(photoId: String, jpegBytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val cacheRoot = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir"))
            val previewDir = File(cacheRoot, "airtether_previews").apply { mkdirs() }
            val previewFile = File(previewDir, "$photoId.jpg")
            previewFile.writeBytes(jpegBytes)
            previewFile.absolutePath
        }
}

actual fun createPlatformPhotoStorage(sessionName: String): PhotoStorage =
    AndroidPhotoStorage(context = AndroidStorageContextHolder.appContext, sessionName = sessionName)
