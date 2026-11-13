package com.dynast.airtether.domain.model

sealed interface TransferState {
    data class InProgress(val progress: Float) : TransferState
    data object Completed : TransferState
    data class Failed(val reason: String) : TransferState
}

enum class IngestSource {
    FTP,
    USB_PTP,
}

data class PhotoItem(
    val id: String,
    val fileName: String,
    val format: PhotoFormat,
    val source: IngestSource,
    val originalPath: String? = null,
    val previewPath: String? = null,
    val fileSizeBytes: Long = 0L,
    val receivedAtEpochMs: Long = 0L,
    val transferState: TransferState = TransferState.Completed,
    val exif: ExifData = ExifData(),
    val culling: CullingMetadata = CullingMetadata(),
)
