package com.dynast.airtether.domain.port

import com.dynast.airtether.model.ExifMetadata

data class ExtractedPreview(
    val previewJpegBytes: ByteArray?,
    val exif: ExifMetadata?,
)

/**
 * Extracts embedded full-size Preview JPEG bytes and EXIF metadata directly
 * from TIFF/ISOBMFF byte offsets in <50ms without full RAW Bayer demosaicing.
 */
interface RawPreviewExtractor {
    suspend fun extract(
        filePath: String,
        isRaw: Boolean,
    ): ExtractedPreview
}
