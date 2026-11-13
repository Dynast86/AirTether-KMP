package com.dynast.airtether.ui.util

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.dynast.airtether.domain.port.AndroidStorageContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

actual suspend fun loadImageBitmapFromUri(uri: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bitmap = if (uri.startsWith("content://")) {
            val resolver = AndroidStorageContextHolder.appContext?.contentResolver ?: return@runCatching null
            resolver.openInputStream(Uri.parse(uri))?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } else {
            val path = uri.removePrefix("file://")
            val file = File(path)
            if (!file.exists() || !file.isFile) return@runCatching null
            BitmapFactory.decodeFile(file.absolutePath)
        }
        bitmap?.asImageBitmap()
    }.getOrNull()
}

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
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
