package com.dynast.airtether.ui.util

import androidx.compose.ui.graphics.ImageBitmap

expect suspend fun loadImageBitmapFromUri(uri: String): ImageBitmap?

expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?

expect fun resolveLocalIpAddress(): String
