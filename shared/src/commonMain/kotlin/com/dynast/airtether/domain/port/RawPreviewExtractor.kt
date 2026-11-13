package com.dynast.airtether.domain.port

import com.dynast.airtether.domain.model.ExifData
import com.dynast.airtether.domain.model.PhotoFormat

data class ExtractedPreview(
    val previewJpegBytes: ByteArray?,
    val exif: ExifData,
)

/**
 * Extracts embedded full-size Preview JPEG bytes and EXIF metadata directly
 * from TIFF/ISOBMFF byte offsets in <50ms without full RAW Bayer demosaicing.
 */
interface RawPreviewExtractor {
    suspend fun extract(
        filePath: String,
        format: PhotoFormat,
    ): ExtractedPreview
}
