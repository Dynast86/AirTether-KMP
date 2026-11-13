package com.dynast.airtether.raw

internal enum class ByteOrder {
    LITTLE_ENDIAN,
    BIG_ENDIAN,
}

/**
 * Zero-allocation random-access reader over a [ByteArray] slice with configurable endianness.
 */
internal class BinaryByteReader(
    private val data: ByteArray,
    private val baseOffset: Int = 0,
    val length: Int = data.size - baseOffset,
    var byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
) {
    fun inBounds(offset: Int, count: Int = 1): Boolean {
        if (offset < 0 || count < 0) return false
        val end = offset.toLong() + count.toLong()
        return end <= length.toLong() && (baseOffset.toLong() + end) <= data.size.toLong()
    }

    fun u8(offset: Int): Int {
        if (!inBounds(offset, 1)) return 0
        return data[baseOffset + offset].toInt() and 0xFF
    }

    fun u16(offset: Int): Int {
        if (!inBounds(offset, 2)) return 0
        val b0 = data[baseOffset + offset].toInt() and 0xFF
        val b1 = data[baseOffset + offset + 1].toInt() and 0xFF
        return if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
            b0 or (b1 shl 8)
        } else {
            (b0 shl 8) or b1
        }
    }

    fun u32(offset: Int): Long {
        if (!inBounds(offset, 4)) return 0L
        val b0 = (data[baseOffset + offset].toInt() and 0xFF).toLong()
        val b1 = (data[baseOffset + offset + 1].toInt() and 0xFF).toLong()
        val b2 = (data[baseOffset + offset + 2].toInt() and 0xFF).toLong()
        val b3 = (data[baseOffset + offset + 3].toInt() and 0xFF).toLong()
        return if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        } else {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
    }

    fun u64(offset: Int): Long {
        if (!inBounds(offset, 8)) return 0L
        val hi = u32(offset)
        val lo = u32(offset + 4)
        return if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
            (lo shl 32) or hi
        } else {
            (hi shl 32) or lo
        }
    }

    fun ascii(offset: Int, maxCount: Int): String {
        if (maxCount <= 0 || !inBounds(offset, 1)) return ""
        val available = minOf(maxCount, length - offset)
        val sb = StringBuilder(available)
        for (i in 0 until available) {
            val ch = data[baseOffset + offset + i].toInt() and 0xFF
            if (ch == 0) break
            sb.append(ch.toChar())
        }
        return sb.toString().trim()
    }

    fun slice(offset: Int, count: Int): ByteArray? {
        if (count <= 0 || !inBounds(offset, count)) return null
        val start = baseOffset + offset
        return data.copyOfRange(start, start + count)
    }

    fun startsWithJpegSoi(offset: Int): Boolean {
        return inBounds(offset, 2) && u8(offset) == 0xFF && u8(offset + 1) == 0xD8
    }

    fun subReader(offset: Int, subLength: Int, order: ByteOrder = byteOrder): BinaryByteReader? {
        if (!inBounds(offset, subLength)) return null
        return BinaryByteReader(
            data = data,
            baseOffset = baseOffset + offset,
            length = subLength,
            byteOrder = order,
        )
    }
}
