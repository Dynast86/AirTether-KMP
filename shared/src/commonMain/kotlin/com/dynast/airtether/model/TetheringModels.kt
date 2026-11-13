package com.dynast.airtether.model

import kotlin.math.roundToInt

/**
 * Represents the real-time ingestion status of a tethered media item via FTP (Port 2121) or USB PTP.
 */
sealed interface TransferStatus {
    data class Transferring(val progress: Float) : TransferStatus {
        val progressPercent: Int
            get() = (progress.coerceIn(0f, 1f) * 100).roundToInt()
    }

    data object Completed : TransferStatus

    data class Failed(val reason: String = "FTP stream interrupted") : TransferStatus
}

/**
 * Essential EXIF metadata parsed directly from embedded TIFF/JPEG headers.
 */
data class ExifMetadata(
    val shutterSpeed: String,
    val aperture: String,
    val iso: Int,
    val focalLength: String,
    val cameraModel: String
)

/**
 * Represents a single photo asset ingested during a tethering session.
 */
data class TetherMediaItem(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val transferStatus: TransferStatus,
    val localUri: String?,
    val exifMetadata: ExifMetadata?,
    val rating: Int = 0,
    val capturedAt: String = "10:42:00",
    val previewAccentHex: Long = 0xFF38BDF8,
    val previewUri: String? = null
) {
    val displayUri: String?
        get() = previewUri ?: localUri
    val extension: String
        get() = fileName.substringAfterLast('.', "").uppercase()

    val isRaw: Boolean
        get() = extension in RAW_EXTENSIONS

    val formatBadge: String
        get() = if (isRaw) "RAW" else "JPG"

    val formattedFileSize: String
        get() {
            val mb = fileSize / (1024.0 * 1024.0)
            val roundedTenths = (mb * 10.0).roundToInt() / 10.0
            return "$roundedTenths MB"
        }

    companion object {
        private val RAW_EXTENSIONS = setOf("ARW", "CR3", "CR2", "NEF", "RAF", "DNG", "ORF", "RW2")
    }
}

/**
 * Embedded FTP / USB PTP ingestion server status.
 */
sealed interface ServerStatus {
    data class Running(
        val ipAddress: String = "192.168.43.1",
        val port: Int = 2121
    ) : ServerStatus {
        val endpointLabel: String
            get() = "ftp://$ipAddress:$port"
    }

    data object Starting : ServerStatus

    data object Stopped : ServerStatus

    data class Error(val message: String) : ServerStatus
}

/**
 * Single source of truth UI state for the Tethering screen (MVI / UDF).
 */
data class TetheringUiState(
    val serverStatus: ServerStatus = ServerStatus.Running(ipAddress = "192.168.43.1", port = 2121),
    val isLiveFollowEnabled: Boolean = true,
    val items: List<TetherMediaItem> = emptyList(),
    val selectedItemId: String? = null,
    val isExifOverlayVisible: Boolean = true,
    val sessionName: String = "Studio_Portrait_S01",
    val minRatingFilter: Int = 0
) {
    val visibleItems: List<TetherMediaItem>
        get() = if (minRatingFilter <= 0) items else items.filter { it.rating >= minRatingFilter }

    val selectedItem: TetherMediaItem?
        get() = visibleItems.find { it.id == selectedItemId } ?: visibleItems.firstOrNull() ?: items.firstOrNull()

    val completedCount: Int
        get() = items.count { it.transferStatus is TransferStatus.Completed }

    val transferringCount: Int
        get() = items.count { it.transferStatus is TransferStatus.Transferring }
}
