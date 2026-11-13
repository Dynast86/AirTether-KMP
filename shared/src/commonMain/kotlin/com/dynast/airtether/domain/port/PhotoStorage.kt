package com.dynast.airtether.domain.port

/**
 * Platform-specific storage abstraction.
 * - Android: MediaStore with IS_PENDING flag
 * - Desktop (JVM): Local session directory IO
 * - iOS: App sandbox / Photos library export
 */
interface PhotoStorage {
    suspend fun createPendingFile(fileName: String, mimeType: String): String
    suspend fun writeChunk(pendingHandle: String, bytes: ByteArray, offset: Long)
    suspend fun commitPendingFile(pendingHandle: String): String
    suspend fun abortPendingFile(pendingHandle: String)
    suspend fun savePreviewJpeg(photoId: String, jpegBytes: ByteArray): String
}
