package com.dynast.airtether.raw

import com.dynast.airtether.domain.port.ExtractedPreview
import com.dynast.airtether.domain.port.RawPreviewExtractor
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * High-speed KMP [RawPreviewExtractor] that reads embedded full-size Preview JPEGs
 * and EXIF metadata directly from TIFF/ISOBMFF byte offsets without Bayer demosaicing.
 *
 * Uses header-first partial reads (256 KB) + targeted range seeks for 50MB+ RAW files
 * to avoid loading the entire sensor payload into memory.
 */
class FastRawPreviewExtractor : RawPreviewExtractor {

    override suspend fun extract(filePath: String, isRaw: Boolean): ExtractedPreview {
        val totalSize = fileByteSize(filePath)
        val headerSlice = readFileSlice(filePath, offset = 0L, maxBytes = HEADER_SLICE_BYTES)

        if (headerSlice != null && totalSize > 0L) {
            val headerReader = BinaryByteReader(headerSlice)
            if (!isRaw) {
                return extractFromJpeg(headerReader)
            }
            if (!IsobmffCr3Parser.isIsobmff(headerReader)) {
                val located = TiffExifParser.parseWithLocation(
                    reader = headerReader,
                    extractPreview = true,
                    totalFileLength = totalSize,
                )
                if (located.previewJpegBytes != null) {
                    return ExtractedPreview(located.previewJpegBytes, located.exif)
                }
                val offset = located.previewOffset
                val length = located.previewLength
                if (offset != null && length != null && length > 0) {
                    val rangeBytes = readFileSlice(filePath, offset, length)
                    if (rangeBytes != null && rangeBytes.size >= 2 &&
                        (rangeBytes[0].toInt() and 0xFF) == 0xFF &&
                        (rangeBytes[1].toInt() and 0xFF) == 0xD8
                    ) {
                        return ExtractedPreview(rangeBytes, located.exif)
                    }
                }
            }
        }

        // Fallback for content:// URIs, CR3 ISOBMFF boxes, or headers > 256KB
        val path = Path(filePath.removePrefix("file://"))
        if (!SystemFileSystem.exists(path)) {
            return ExtractedPreview(previewJpegBytes = null, exif = null)
        }
        val bytes = SystemFileSystem.source(path).buffered().use { it.readByteArray() }
        return extractFromBytes(bytes, isRaw = isRaw)
    }

    fun extractFromBytes(bytes: ByteArray, isRaw: Boolean): ExtractedPreview {
        val reader = BinaryByteReader(bytes)
        if (!isRaw) {
            return extractFromJpeg(reader)
        }
        val result = if (IsobmffCr3Parser.isIsobmff(reader)) {
            IsobmffCr3Parser.parse(reader)
        } else {
            TiffExifParser.parse(reader, extractPreview = true)
        }
        return ExtractedPreview(
            previewJpegBytes = result.previewJpegBytes,
            exif = result.exif,
        )
    }

    private fun extractFromJpeg(reader: BinaryByteReader): ExtractedPreview {
        reader.byteOrder = ByteOrder.BIG_ENDIAN
        if (!reader.startsWithJpegSoi(0)) {
            return ExtractedPreview(previewJpegBytes = null, exif = null)
        }

        var offset = 2
        while (reader.inBounds(offset, 4)) {
            if (reader.u8(offset) != 0xFF) break
            val marker = reader.u8(offset + 1)
            if (marker == 0xDA || marker == 0xD9) break // SOS or EOI

            val segmentLength = reader.u16(offset + 2)
            if (segmentLength < 2 || !reader.inBounds(offset + 2, segmentLength)) break

            if (marker == 0xE1 && segmentLength >= 8) {
                val header = reader.ascii(offset + 4, 4)
                if (header == "Exif" && reader.u8(offset + 8) == 0 && reader.u8(offset + 9) == 0) {
                    val tiffStart = offset + 10
                    val tiffLen = segmentLength - 8
                    val tiffReader = reader.subReader(tiffStart, tiffLen)
                    if (tiffReader != null) {
                        val parsed = TiffExifParser.parse(tiffReader, extractPreview = false)
                        return ExtractedPreview(previewJpegBytes = null, exif = parsed.exif)
                    }
                }
            }
            offset += 2 + segmentLength
        }

        return ExtractedPreview(previewJpegBytes = null, exif = null)
    }

    companion object {
        private const val HEADER_SLICE_BYTES = 256 * 1024
    }
}
