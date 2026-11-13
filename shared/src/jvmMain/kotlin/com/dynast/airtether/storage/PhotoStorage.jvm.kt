package com.dynast.airtether.domain.port

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

class JvmPhotoStorage(
    private val sessionDir: File,
) : PhotoStorage {

    private data class PendingEntry(
        val tempFile: File,
        val targetName: String,
    )

    private val pending = ConcurrentHashMap<String, PendingEntry>()

    init {
        sessionDir.mkdirs()
    }

    override suspend fun createPendingFile(fileName: String, mimeType: String): String =
        withContext(Dispatchers.IO) {
            val sanitized = fileName.substringAfterLast('/').substringAfterLast('\\')
                .ifBlank { "capture_${System.currentTimeMillis()}.bin" }
            val tempFile = File(sessionDir, ".$sanitized.${System.nanoTime()}.part")
            tempFile.parentFile?.mkdirs()
            tempFile.createNewFile()
            val handle = tempFile.absolutePath
            pending[handle] = PendingEntry(tempFile = tempFile, targetName = sanitized)
            handle
        }

    override suspend fun writeChunk(pendingHandle: String, bytes: ByteArray, offset: Long) {
        val entry = pending[pendingHandle] ?: return
        withContext(Dispatchers.IO) {
            RandomAccessFile(entry.tempFile, "rw").use { raf ->
                raf.seek(offset)
                raf.write(bytes)
            }
        }
    }

    override suspend fun commitPendingFile(pendingHandle: String): String =
        withContext(Dispatchers.IO) {
            val entry = pending.remove(pendingHandle)
                ?: error("Unknown pending handle: $pendingHandle")
            val target = resolveUniqueTarget(entry.targetName)
            if (!entry.tempFile.renameTo(target)) {
                entry.tempFile.copyTo(target, overwrite = true)
                entry.tempFile.delete()
            }
            target.absolutePath
        }

    override suspend fun abortPendingFile(pendingHandle: String) {
        val entry = pending.remove(pendingHandle) ?: return
        withContext(Dispatchers.IO) {
            entry.tempFile.delete()
        }
    }

    override suspend fun savePreviewJpeg(photoId: String, jpegBytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val previewDir = File(sessionDir, ".previews").apply { mkdirs() }
            val previewFile = File(previewDir, "$photoId.jpg")
            previewFile.writeBytes(jpegBytes)
            previewFile.absolutePath
        }

    private fun resolveUniqueTarget(fileName: String): File {
        var candidate = File(sessionDir, fileName)
        if (!candidate.exists()) return candidate

        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var index = 1
        while (candidate.exists()) {
            val nextName = if (ext.isNotEmpty()) "${base}_$index.$ext" else "${base}_$index"
            candidate = File(sessionDir, nextName)
            index++
        }
        return candidate
    }
}

actual fun createPlatformPhotoStorage(sessionName: String): PhotoStorage {
    val home = System.getProperty("user.home").orEmpty().ifBlank { "." }
    val dir = File(home, "Pictures/AirTether/$sessionName")
    return JvmPhotoStorage(dir)
}
