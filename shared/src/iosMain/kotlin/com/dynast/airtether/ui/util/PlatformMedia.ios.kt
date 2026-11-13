package com.dynast.airtether.ui.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import org.jetbrains.skia.Image

actual suspend fun loadImageBitmapFromUri(uri: String): ImageBitmap? {
    return runCatching {
        val path = Path(uri.removePrefix("file://"))
        if (!SystemFileSystem.exists(path)) return@runCatching null
        val bytes = SystemFileSystem.source(path).buffered().use { it.readByteArray() }
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

actual fun resolveLocalIpAddress(): String = "127.0.0.1"
