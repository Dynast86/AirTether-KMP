package com.dynast.airtether.raw

import com.dynast.airtether.model.ExifMetadata
import kotlin.math.roundToInt

internal data class TiffParseResult(
    val exif: ExifMetadata?,
    val previewJpegBytes: ByteArray?,
)

/**
 * Parses standard TIFF / EXIF IFD trees (NEF, ARW, CR2, DNG, ORF, RW2, and JPEG APP1 EXIF)
 * to extract [ExifMetadata] and the largest embedded Preview JPEG without Bayer demosaicing.
 */
internal object TiffExifParser {

    private const val TAG_IMAGE_MODEL = 0x0110
    private const val TAG_STRIP_OFFSETS = 0x0111
    private const val TAG_STRIP_BYTE_COUNTS = 0x0117
    private const val TAG_SUB_IFDS = 0x014A
    private const val TAG_JPEG_INTERCHANGE_FORMAT = 0x0201
    private const val TAG_JPEG_INTERCHANGE_FORMAT_LENGTH = 0x0202
    private const val TAG_EXPOSURE_TIME = 0x829A
    private const val TAG_F_NUMBER = 0x829D
    private const val TAG_EXIF_IFD_POINTER = 0x8769
    private const val TAG_ISO_SPEED_RATINGS = 0x8827
    private const val TAG_ISO_SPEED = 0x8833
    private const val TAG_FOCAL_LENGTH = 0x920A

    private const val TYPE_BYTE = 1
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5

    private data class JpegCandidate(val offset: Int, val length: Int)

    fun parse(reader: BinaryByteReader, extractPreview: Boolean = true): TiffParseResult {
        if (reader.length < 8) return TiffParseResult(null, null)

        val order = when {
            reader.u8(0) == 0x49 && reader.u8(1) == 0x49 -> ByteOrder.LITTLE_ENDIAN // "II"
            reader.u8(0) == 0x4D && reader.u8(1) == 0x4D -> ByteOrder.BIG_ENDIAN    // "MM"
            else -> return TiffParseResult(null, null)
        }
        reader.byteOrder = order

        val magic = reader.u16(2)
        if (magic != 42) return TiffParseResult(null, null)

        val ifd0Offset = reader.u32(4).toInt()
        val visited = HashSet<Int>()
        val queue = ArrayDeque<Int>()
        if (ifd0Offset > 0) queue.add(ifd0Offset)

        var cameraModel: String? = null
        var shutterSpeed: String? = null
        var aperture: String? = null
        var iso: Int? = null
        var focalLength: String? = null
        val candidates = mutableListOf<JpegCandidate>()

        while (queue.isNotEmpty()) {
            val ifdOffset = queue.removeFirst()
            if (ifdOffset <= 0 || !visited.add(ifdOffset) || !reader.inBounds(ifdOffset, 2)) continue

            val entryCount = reader.u16(ifdOffset)
            val entriesStart = ifdOffset + 2
            if (!reader.inBounds(entriesStart, entryCount * 12)) continue

            var jpegOffset: Int? = null
            var jpegLength: Int? = null
            var stripOffset: Int? = null
            var stripLength: Int? = null

            for (i in 0 until entryCount) {
                val entryOffset = entriesStart + i * 12
                val tag = reader.u16(entryOffset)
                val type = reader.u16(entryOffset + 2)
                val count = reader.u32(entryOffset + 4).toInt()
                val valueOffsetField = entryOffset + 8

                when (tag) {
                    TAG_IMAGE_MODEL -> {
                        if (cameraModel == null) {
                            cameraModel = readAsciiTag(reader, type, count, valueOffsetField)
                        }
                    }
                    TAG_EXIF_IFD_POINTER -> {
                        val sub = readScalarInt(reader, type, valueOffsetField)
                        if (sub > 0) queue.add(sub)
                    }
                    TAG_SUB_IFDS -> {
                        readLongArray(reader, type, count, valueOffsetField).forEach { sub ->
                            if (sub > 0) queue.add(sub)
                        }
                    }
                    TAG_JPEG_INTERCHANGE_FORMAT -> {
                        jpegOffset = readScalarInt(reader, type, valueOffsetField)
                    }
                    TAG_JPEG_INTERCHANGE_FORMAT_LENGTH -> {
                        jpegLength = readScalarInt(reader, type, valueOffsetField)
                    }
                    TAG_STRIP_OFFSETS -> {
                        if (count == 1) stripOffset = readScalarInt(reader, type, valueOffsetField)
                    }
                    TAG_STRIP_BYTE_COUNTS -> {
                        if (count == 1) stripLength = readScalarInt(reader, type, valueOffsetField)
                    }
                    TAG_EXPOSURE_TIME -> {
                        if (shutterSpeed == null) {
                            shutterSpeed = formatExposure(readRational(reader, type, valueOffsetField))
                        }
                    }
                    TAG_F_NUMBER -> {
                        if (aperture == null) {
                            aperture = formatAperture(readRational(reader, type, valueOffsetField))
                        }
                    }
                    TAG_ISO_SPEED_RATINGS, TAG_ISO_SPEED -> {
                        if (iso == null) {
                            val parsedIso = readScalarInt(reader, type, valueOffsetField)
                            if (parsedIso > 0) iso = parsedIso
                        }
                    }
                    TAG_FOCAL_LENGTH -> {
                        if (focalLength == null) {
                            focalLength = formatFocalLength(readRational(reader, type, valueOffsetField))
                        }
                    }
                }
            }

            if (extractPreview) {
                if (jpegOffset != null && jpegLength != null && jpegLength > 0 &&
                    reader.startsWithJpegSoi(jpegOffset) && reader.inBounds(jpegOffset, jpegLength)
                ) {
                    candidates += JpegCandidate(jpegOffset, jpegLength)
                }
                if (stripOffset != null && stripLength != null && stripLength > 0 &&
                    reader.startsWithJpegSoi(stripOffset) && reader.inBounds(stripOffset, stripLength)
                ) {
                    candidates += JpegCandidate(stripOffset, stripLength)
                }
            }

            val nextIfdPos = entriesStart + entryCount * 12
            if (reader.inBounds(nextIfdPos, 4)) {
                val nextIfd = reader.u32(nextIfdPos).toInt()
                if (nextIfd > 0) queue.add(nextIfd)
            }
        }

        val bestPreview = if (extractPreview) {
            candidates.maxByOrNull { it.length }?.let { reader.slice(it.offset, it.length) }
        } else {
            null
        }

        val hasAnyExif = shutterSpeed != null || aperture != null || iso != null ||
            focalLength != null || !cameraModel.isNullOrBlank()

        val metadata = if (hasAnyExif) {
            ExifMetadata(
                shutterSpeed = shutterSpeed ?: "--",
                aperture = aperture ?: "--",
                iso = iso ?: 0,
                focalLength = focalLength ?: "--",
                cameraModel = cameraModel?.takeIf { it.isNotBlank() } ?: "Unknown Camera",
            )
        } else {
            null
        }

        return TiffParseResult(exif = metadata, previewJpegBytes = bestPreview)
    }

    private fun readScalarInt(reader: BinaryByteReader, type: Int, valueFieldOffset: Int): Int {
        return when (type) {
            TYPE_BYTE -> reader.u8(valueFieldOffset)
            TYPE_SHORT -> reader.u16(valueFieldOffset)
            TYPE_LONG -> reader.u32(valueFieldOffset).toInt()
            else -> reader.u32(valueFieldOffset).toInt()
        }
    }

    private fun readLongArray(reader: BinaryByteReader, type: Int, count: Int, valueFieldOffset: Int): List<Int> {
        if (count <= 0) return emptyList()
        if (count == 1) return listOf(readScalarInt(reader, type, valueFieldOffset))
        val ptr = reader.u32(valueFieldOffset).toInt()
        if (!reader.inBounds(ptr, count * 4)) return emptyList()
        return List(count) { idx -> reader.u32(ptr + idx * 4).toInt() }
    }

    private fun readAsciiTag(reader: BinaryByteReader, type: Int, count: Int, valueFieldOffset: Int): String? {
        if (type != TYPE_ASCII || count <= 0) return null
        val text = if (count <= 4) {
            reader.ascii(valueFieldOffset, count)
        } else {
            val ptr = reader.u32(valueFieldOffset).toInt()
            reader.ascii(ptr, count)
        }
        return text.ifBlank { null }
    }

    private fun readRational(reader: BinaryByteReader, type: Int, valueFieldOffset: Int): Pair<Long, Long>? {
        if (type != TYPE_RATIONAL) return null
        val ptr = reader.u32(valueFieldOffset).toInt()
        if (!reader.inBounds(ptr, 8)) return null
        val num = reader.u32(ptr)
        val den = reader.u32(ptr + 4)
        if (den == 0L) return null
        return num to den
    }

    private fun formatExposure(rational: Pair<Long, Long>?): String? {
        val (num, den) = rational ?: return null
        if (num == 0L) return null
        return if (num < den) {
            val recip = (den.toDouble() / num.toDouble()).roundToInt().coerceAtLeast(1)
            "1/${recip}s"
        } else {
            val sec = ((num.toDouble() / den.toDouble()) * 10.0).roundToInt() / 10.0
            "${sec}s"
        }
    }

    private fun formatAperture(rational: Pair<Long, Long>?): String? {
        val (num, den) = rational ?: return null
        val f = ((num.toDouble() / den.toDouble()) * 10.0).roundToInt() / 10.0
        return if (f > 0.0) "f/$f" else null
    }

    private fun formatFocalLength(rational: Pair<Long, Long>?): String? {
        val (num, den) = rational ?: return null
        val mm = (num.toDouble() / den.toDouble()).roundToInt()
        return if (mm > 0) "${mm}mm" else null
    }
}
