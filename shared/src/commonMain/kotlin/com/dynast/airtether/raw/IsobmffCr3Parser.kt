package com.dynast.airtether.raw

import com.dynast.airtether.model.ExifMetadata

/**
 * Parses Canon CR3 ISOBMFF box hierarchies to locate embedded preview JPEG streams
 * (`PRVW` / `THMB` boxes) and EXIF metadata (`CMT1` / `CMT2` TIFF blocks).
 */
internal object IsobmffCr3Parser {

    private val CONTAINER_BOXES = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "meta")

    fun isIsobmff(reader: BinaryByteReader): Boolean {
        if (reader.length < 12) return false
        return reader.ascii(4, 4) == "ftyp"
    }

    fun parse(reader: BinaryByteReader): TiffParseResult {
        reader.byteOrder = ByteOrder.BIG_ENDIAN
        val previews = mutableListOf<ByteArray>()
        val exifParts = mutableListOf<ExifMetadata>()

        walkBoxes(reader, 0, reader.length, previews, exifParts)

        val mergedExif = mergeExif(exifParts)
        val bestPreview = previews.maxByOrNull { it.size }
        return TiffParseResult(exif = mergedExif, previewJpegBytes = bestPreview)
    }

    private fun walkBoxes(
        reader: BinaryByteReader,
        start: Int,
        end: Int,
        previews: MutableList<ByteArray>,
        exifParts: MutableList<ExifMetadata>,
    ) {
        var cursor = start
        while (cursor + 8 <= end) {
            val rawSize = reader.u32(cursor)
            val boxType = reader.ascii(cursor + 4, 4)
            var headerSize = 8
            val boxSize: Long = when (rawSize) {
                0L -> (end - cursor).toLong()
                1L -> {
                    if (cursor + 16 > end) return
                    headerSize = 16
                    reader.u64(cursor + 8)
                }
                else -> rawSize
            }

            if (boxSize < headerSize || cursor + boxSize > end) break
            val payloadOffset = cursor + headerSize
            val payloadLength = (boxSize - headerSize).toInt()

            when (boxType) {
                "CMT1", "CMT2", "CMT3", "CMT4" -> {
                    reader.subReader(payloadOffset, payloadLength)?.let { sub ->
                        TiffExifParser.parse(sub, extractPreview = false).exif?.let(exifParts::add)
                    }
                }
                "PRVW", "THMB" -> {
                    extractJpegFromBox(reader, payloadOffset, payloadLength)?.let(previews::add)
                }
                "uuid" -> {
                    // Canon CR3 stores CMT1/CMT2/PRVW inside a 16-byte UUID box payload
                    if (payloadLength > 16) {
                        walkBoxes(reader, payloadOffset + 16, payloadOffset + payloadLength, previews, exifParts)
                    }
                }
                in CONTAINER_BOXES -> {
                    val childStart = if (boxType == "meta") payloadOffset + 4 else payloadOffset
                    if (childStart < payloadOffset + payloadLength) {
                        walkBoxes(reader, childStart, payloadOffset + payloadLength, previews, exifParts)
                    }
                }
            }

            cursor += boxSize.toInt()
        }
    }

    private fun extractJpegFromBox(
        reader: BinaryByteReader,
        payloadOffset: Int,
        payloadLength: Int,
    ): ByteArray? {
        val searchLimit = minOf(payloadLength, 64)
        for (rel in 0 until searchLimit - 1) {
            val abs = payloadOffset + rel
            if (reader.startsWithJpegSoi(abs)) {
                val remaining = payloadLength - rel
                return reader.slice(abs, remaining)
            }
        }
        return null
    }

    private fun mergeExif(parts: List<ExifMetadata>): ExifMetadata? {
        if (parts.isEmpty()) return null
        var shutter = "--"
        var aperture = "--"
        var iso = 0
        var focal = "--"
        var model = "Unknown Camera"

        for (p in parts) {
            if (shutter == "--" && p.shutterSpeed != "--") shutter = p.shutterSpeed
            if (aperture == "--" && p.aperture != "--") aperture = p.aperture
            if (iso == 0 && p.iso > 0) iso = p.iso
            if (focal == "--" && p.focalLength != "--") focal = p.focalLength
            if (model == "Unknown Camera" && p.cameraModel != "Unknown Camera") model = p.cameraModel
        }

        return ExifMetadata(
            shutterSpeed = shutter,
            aperture = aperture,
            iso = iso,
            focalLength = focal,
            cameraModel = model,
        )
    }
}
