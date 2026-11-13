package com.dynast.airtether.raw

import java.io.File
import java.io.RandomAccessFile

actual fun readFileSlice(filePath: String, offset: Long, maxBytes: Int): ByteArray? {
    if (offset < 0L || maxBytes <= 0) return null
    val cleanPath = filePath.removePrefix("file://")
    if (cleanPath.startsWith("content://")) return null
    val file = File(cleanPath)
    if (!file.exists() || !file.isFile) return null
    return runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val fileLen = raf.length()
            if (offset >= fileLen) return@use null
            val toRead = minOf(maxBytes.toLong(), fileLen - offset).toInt()
            val buf = ByteArray(toRead)
            raf.seek(offset)
            var total = 0
            while (total < toRead) {
                val read = raf.read(buf, total, toRead - total)
                if (read < 0) break
                total += read
            }
            if (total == toRead) buf else buf.copyOf(total)
        }
    }.getOrNull()
}

actual fun fileByteSize(filePath: String): Long {
    val cleanPath = filePath.removePrefix("file://")
    if (cleanPath.startsWith("content://")) return -1L
    val file = File(cleanPath)
    return if (file.exists() && file.isFile) file.length() else -1L
}
