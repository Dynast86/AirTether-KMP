package com.dynast.airtether.raw

expect fun readFileSlice(filePath: String, offset: Long, maxBytes: Int): ByteArray?

expect fun fileByteSize(filePath: String): Long
