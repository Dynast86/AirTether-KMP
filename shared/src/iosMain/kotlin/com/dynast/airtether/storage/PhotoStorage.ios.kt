package com.dynast.airtether.domain.port

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

object IosPhotoLibraryBridge {
    var onExportToPhotos: ((filePath: String) -> Unit)? = null
}

private fun resolveIosDocumentsRoot(): String {
    val docs = NSSearchPathForDirectoriesInDomains(
        directory = NSDocumentDirectory,
        domainMask = NSUserDomainMask,
        expandTilde = true,
    ).firstOrNull() as? String
    return (docs ?: NSTemporaryDirectory()).trimEnd('/')
}

class IosPhotoStorage(
    private val sessionName: String = "AirTether",
) : PhotoStorage {

    private val baseDir: Path by lazy {
        val root = resolveIosDocumentsRoot()
        Path("$root/AirTether/$sessionName").also {
            SystemFileSystem.createDirectories(it)
        }
    }

    private val pending = mutableMapOf<String, Pair<Path, Path>>()

    override suspend fun createPendingFile(fileName: String, mimeType: String): String {
        val sanitized = fileName.substringAfterLast('/').ifBlank { "capture.bin" }
        val tempPath = Path("$baseDir/.$sanitized.part")
        val finalPath = Path("$baseDir/$sanitized")
        val handle = tempPath.toString()
        pending[handle] = tempPath to finalPath
        return handle
    }

    override suspend fun writeChunk(pendingHandle: String, bytes: ByteArray, offset: Long) {
        val (tempPath, _) = pending[pendingHandle] ?: return
        SystemFileSystem.sink(tempPath, append = offset > 0L).use { sink ->
            sink.write(bytes)
        }
    }

    override suspend fun commitPendingFile(pendingHandle: String): String {
        val (tempPath, finalPath) = pending.remove(pendingHandle)
            ?: error("Unknown pending handle: $pendingHandle")
        SystemFileSystem.atomicMove(tempPath, finalPath)
        val savedPath = finalPath.toString()
        IosPhotoLibraryBridge.onExportToPhotos?.invoke(savedPath)
        return savedPath
    }

    override suspend fun abortPendingFile(pendingHandle: String) {
        val (tempPath, _) = pending.remove(pendingHandle) ?: return
        SystemFileSystem.delete(tempPath, mustExist = false)
    }

    override suspend fun savePreviewJpeg(photoId: String, jpegBytes: ByteArray): String {
        val previewDir = Path("$baseDir/.previews").also { SystemFileSystem.createDirectories(it) }
        val previewPath = Path("$previewDir/$photoId.jpg")
        SystemFileSystem.sink(previewPath).use { sink ->
            sink.write(jpegBytes)
        }
        return previewPath.toString()
    }
}

actual fun createPlatformPhotoStorage(sessionName: String): PhotoStorage =
    IosPhotoStorage(sessionName = sessionName)
