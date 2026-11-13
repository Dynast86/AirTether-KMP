package com.dynast.airtether.ui.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

actual suspend fun loadImageBitmapFromUri(uri: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val path = uri.removePrefix("file://")
        val file = File(path)
        if (!file.exists() || !file.isFile) return@runCatching null
        val bytes = file.readBytes()
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

actual fun resolveLocalIpAddress(): String {
    return runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull() ?: "127.0.0.1"
}
