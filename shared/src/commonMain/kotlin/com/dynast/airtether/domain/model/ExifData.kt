package com.dynast.airtether.domain.model

data class ExifData(
    val cameraModel: String? = null,
    val lensModel: String? = null,
    val focalLengthMm: Double? = null,
    val aperture: Double? = null,
    val shutterSpeed: String? = null,
    val iso: Int? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val capturedAtIso: String? = null,
)
