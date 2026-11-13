package com.dynast.airtether.raw

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

actual fun readFileSlice(filePath: String, offset: Long, maxBytes: Int): ByteArray? {
    if (offset < 0L || maxBytes <= 0) return null
    val path = Path(filePath.removePrefix("file://"))
    if (!SystemFileSystem.exists(path)) return null
    return runCatching {
        SystemFileSystem.source(path).buffered().use { src ->
            if (offset > 0L) src.skip(offset)
            val bytes = src.readByteArray()
            if (bytes.size <= maxBytes) bytes else bytes.copyOf(maxBytes)
        }
    }.getOrNull()
}

actual fun fileByteSize(filePath: String): Long {
    val path = Path(filePath.removePrefix("file://"))
    return SystemFileSystem.metadataOrNull(path)?.size ?: -1L
}
