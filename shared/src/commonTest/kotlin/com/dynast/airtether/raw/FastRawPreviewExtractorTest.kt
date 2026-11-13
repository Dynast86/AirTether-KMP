package com.dynast.airtether.raw

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FastRawPreviewExtractorTest {

    private val extractor = FastRawPreviewExtractor()

    @Test
    fun extractsLargestEmbeddedPreviewAndExifFromLittleEndianTiffRaw() {
        val smallThumbJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0xFF.toByte(), 0xD9.toByte())
        val fullPreviewJpeg = ByteArray(2048) { idx ->
            when (idx) {
                0 -> 0xFF.toByte()
                1 -> 0xD8.toByte()
                2046 -> 0xFF.toByte()
                2047 -> 0xD9.toByte()
                else -> (idx and 0x7F).toByte()
            }
        }

        val tiffBytes = buildSyntheticTiff(
            littleEndian = true,
            cameraModel = "Nikon Z8",
            thumbJpeg = smallThumbJpeg,
            fullJpeg = fullPreviewJpeg,
        )

        val result = extractor.extractFromBytes(tiffBytes, isRaw = true)
        assertNotNull(result.previewJpegBytes)
        assertContentEquals(fullPreviewJpeg, result.previewJpegBytes)

        val exif = assertNotNull(result.exif)
        assertEquals("Nikon Z8", exif.cameraModel)
        assertEquals("1/640s", exif.shutterSpeed)
        assertEquals("f/2.8", exif.aperture)
        assertEquals(64, exif.iso)
        assertEquals("70mm", exif.focalLength)
    }

    @Test
    fun extractsEmbeddedPreviewAndExifFromBigEndianTiffRaw() {
        val smallThumb = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x10, 0xFF.toByte(), 0xD9.toByte())
        val fullPreview = ByteArray(512) { idx ->
            when (idx) {
                0 -> 0xFF.toByte()
                1 -> 0xD8.toByte()
                510 -> 0xFF.toByte()
                511 -> 0xD9.toByte()
                else -> 0x42
            }
        }

        val tiffBytes = buildSyntheticTiff(
            littleEndian = false,
            cameraModel = "SONY ILCE-7RM5",
            thumbJpeg = smallThumb,
            fullJpeg = fullPreview,
        )

        val result = extractor.extractFromBytes(tiffBytes, isRaw = true)
        assertContentEquals(fullPreview, result.previewJpegBytes)
        val exif = assertNotNull(result.exif)
        assertEquals("SONY ILCE-7RM5", exif.cameraModel)
        assertEquals("1/640s", exif.shutterSpeed)
        assertEquals("f/2.8", exif.aperture)
        assertEquals(64, exif.iso)
        assertEquals("70mm", exif.focalLength)
    }

    @Test
    fun extractsPreviewAndExifFromCanonCr3Isobmff() {
        val fullPreview = ByteArray(1024) { idx ->
            when (idx) {
                0 -> 0xFF.toByte()
                1 -> 0xD8.toByte()
                1022 -> 0xFF.toByte()
                1023 -> 0xD9.toByte()
                else -> 0x55
            }
        }
        val cmtTiff = buildSyntheticTiff(
            littleEndian = true,
            cameraModel = "Canon EOS R5",
            thumbJpeg = null,
            fullJpeg = null,
        )

        val ftyp = box("ftyp", "crx ".encodeToByteArray() + ByteArray(8))
        val cmt1 = box("CMT1", cmtTiff)
        val prvwPayload = ByteArray(8) + fullPreview
        val prvw = box("PRVW", prvwPayload)
        val uuid = box("uuid", ByteArray(16) + cmt1 + prvw)
        val moov = box("moov", uuid)
        val cr3 = ftyp + moov

        val result = extractor.extractFromBytes(cr3, isRaw = true)
        assertContentEquals(fullPreview, result.previewJpegBytes)
        val exif = assertNotNull(result.exif)
        assertEquals("Canon EOS R5", exif.cameraModel)
        assertEquals("1/640s", exif.shutterSpeed)
        assertEquals("f/2.8", exif.aperture)
        assertEquals(64, exif.iso)
        assertEquals("70mm", exif.focalLength)
    }

    @Test
    fun extractsExifFromJpegApp1WithoutDuplicatingPreviewBytes() {
        val tiff = buildSyntheticTiff(
            littleEndian = true,
            cameraModel = "FUJIFILM X-T5",
            thumbJpeg = null,
            fullJpeg = null,
        )
        val app1Payload = "Exif\u0000\u0000".encodeToByteArray() + tiff
        val app1Len = app1Payload.size + 2
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE1.toByte(),
            ((app1Len ushr 8) and 0xFF).toByte(),
            (app1Len and 0xFF).toByte(),
        ) + app1Payload + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        val result = extractor.extractFromBytes(jpeg, isRaw = false)
        assertNull(result.previewJpegBytes)
        val exif = assertNotNull(result.exif)
        assertEquals("FUJIFILM X-T5", exif.cameraModel)
        assertEquals("1/640s", exif.shutterSpeed)
        assertEquals("f/2.8", exif.aperture)
        assertEquals(64, exif.iso)
        assertEquals("70mm", exif.focalLength)
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        val header = byteArrayOf(
            ((size ushr 24) and 0xFF).toByte(),
            ((size ushr 16) and 0xFF).toByte(),
            ((size ushr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte(),
        ) + type.encodeToByteArray()
        return header + payload
    }

    private fun buildSyntheticTiff(
        littleEndian: Boolean,
        cameraModel: String,
        thumbJpeg: ByteArray?,
        fullJpeg: ByteArray?,
    ): ByteArray {
        val buf = mutableListOf<Byte>()
        fun writeU16(v: Int) {
            if (littleEndian) {
                buf += (v and 0xFF).toByte()
                buf += ((v ushr 8) and 0xFF).toByte()
            } else {
                buf += ((v ushr 8) and 0xFF).toByte()
                buf += (v and 0xFF).toByte()
            }
        }
        fun writeU32(v: Long) {
            if (littleEndian) {
                buf += (v and 0xFF).toByte()
                buf += ((v ushr 8) and 0xFF).toByte()
                buf += ((v ushr 16) and 0xFF).toByte()
                buf += ((v ushr 24) and 0xFF).toByte()
            } else {
                buf += ((v ushr 24) and 0xFF).toByte()
                buf += ((v ushr 16) and 0xFF).toByte()
                buf += ((v ushr 8) and 0xFF).toByte()
                buf += (v and 0xFF).toByte()
            }
        }
        fun writeEntry(tag: Int, type: Int, count: Int, valueOrOffset: Long) {
            writeU16(tag)
            writeU16(type)
            writeU32(count.toLong())
            if (type == 3 && count == 1) {
                if (littleEndian) {
                    writeU16(valueOrOffset.toInt())
                    writeU16(0)
                } else {
                    writeU16(valueOrOffset.toInt())
                    writeU16(0)
                }
            } else {
                writeU32(valueOrOffset)
            }
        }

        // TIFF Header (8 bytes)
        if (littleEndian) {
            buf += 0x49; buf += 0x49
        } else {
            buf += 0x4D; buf += 0x4D
        }
        writeU16(42)
        writeU32(8) // IFD0 starts at offset 8

        val modelBytes = (cameraModel + "\u0000").encodeToByteArray()

        // Layout calculation:
        // IFD0 at 8: 2 entries (Model, ExifIFD) + nextIfd(IFD1 if thumb) -> size = 2 + 2*12 + 4 = 30 -> ends at 38
        val ifd0Count = if (fullJpeg != null) 3 else 2
        val ifd0End = 8 + 2 + ifd0Count * 12 + 4
        val modelOffset = ifd0End
        val exifIfdOffset = modelOffset + modelBytes.size
        // ExifIFD: 4 entries (ExposureTime, FNumber, ISO, FocalLength) -> size = 2 + 4*12 + 4 = 54
        val exifDataOffset = exifIfdOffset + 2 + 4 * 12 + 4
        // 3 rationals * 8 bytes = 24 bytes
        val expOffset = exifDataOffset
        val fnumOffset = expOffset + 8
        val focalOffset = fnumOffset + 8
        val afterRationals = focalOffset + 8

        val ifd1Offset = if (thumbJpeg != null) afterRationals else 0
        val ifd1End = if (thumbJpeg != null) ifd1Offset + 2 + 2 * 12 + 4 else afterRationals
        val thumbOffset = ifd1End
        val subIfdOffset = thumbOffset + (thumbJpeg?.size ?: 0)
        val subIfdEnd = if (fullJpeg != null) subIfdOffset + 2 + 2 * 12 + 4 else subIfdOffset
        val fullOffset = subIfdEnd

        // Write IFD0
        writeU16(ifd0Count)
        writeEntry(0x0110, 2, modelBytes.size, modelOffset.toLong())
        if (fullJpeg != null) {
            writeEntry(0x014A, 4, 1, subIfdOffset.toLong())
        }
        writeEntry(0x8769, 4, 1, exifIfdOffset.toLong())
        writeU32(ifd1Offset.toLong())

        // Model ASCII bytes
        modelBytes.forEach { buf += it }

        // ExifIFD
        writeU16(4)
        writeEntry(0x829A, 5, 1, expOffset.toLong())
        writeEntry(0x829D, 5, 1, fnumOffset.toLong())
        writeEntry(0x8827, 3, 1, 64L)
        writeEntry(0x920A, 5, 1, focalOffset.toLong())
        writeU32(0)

        // Rationals: 1/640, 28/10, 70/1
        writeU32(1); writeU32(640)
        writeU32(28); writeU32(10)
        writeU32(70); writeU32(1)

        // IFD1 (thumbnail)
        if (thumbJpeg != null) {
            writeU16(2)
            writeEntry(0x0201, 4, 1, thumbOffset.toLong())
            writeEntry(0x0202, 4, 1, thumbJpeg.size.toLong())
            writeU32(0)
            thumbJpeg.forEach { buf += it }
        }

        // SubIFD (full preview)
        if (fullJpeg != null) {
            writeU16(2)
            writeEntry(0x0201, 4, 1, fullOffset.toLong())
            writeEntry(0x0202, 4, 1, fullJpeg.size.toLong())
            writeU32(0)
            fullJpeg.forEach { buf += it }
        }

        return buf.toByteArray()
    }
}
