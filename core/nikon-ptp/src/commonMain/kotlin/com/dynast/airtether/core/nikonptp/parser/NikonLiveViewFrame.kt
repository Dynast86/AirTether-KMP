package com.dynast.airtether.core.nikonptp.parser

import com.dynast.airtether.core.nikonptp.pacing.TargetFrameRate
import kotlin.math.roundToInt

/**
 * Dimensions in pixels or camera coordinate units (`UInt16`).
 */
data class CameraCoordSize(
    val width: Int,
    val height: Int
)

/**
 * Raw & normalized (`0.0f..1.0f`) AF area rectangle parsed from the Nikon LiveView header.
 */
data class AfAreaRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val normLeft: Float,
    val normTop: Float,
    val normWidth: Float,
    val normHeight: Float
) {
    val normCenterX: Float
        get() = (normLeft + normWidth * 0.5f).coerceIn(0f, 1f)

    val normCenterY: Float
        get() = (normTop + normHeight * 0.5f).coerceIn(0f, 1f)
}

/**
 * Nikon LiveView autofocus status code:
 * - `0`: Searching / Unfocused (`SEARCHING`)
 * - `1`: Focus Locked (`MATCHED_GREEN`)
 * - `2`: Focus Failed (`FAILED_RED`)
 */
enum class NikonAfStatus(val rawCode: Int) {
    SEARCHING(0),
    MATCHED_GREEN(1),
    FAILED_RED(2);

    companion object {
        fun fromRaw(code: Int): NikonAfStatus = when (code) {
            1 -> MATCHED_GREEN
            2 -> FAILED_RED
            else -> SEARCHING
        }
    }
}

/**
 * Electronic horizon roll and pitch angles in degrees (`Float`, converted from Big-Endian fixed-point).
 */
data class LevelAngle(
    val rollDegrees: Float,
    val pitchDegrees: Float
)

/**
 * Real-time exposure & movie recording metadata embedded in the 384-byte Nikon LiveView header.
 */
data class LiveViewExposureInfo(
    val fNumberHundredths: Int,
    val shutterNumerator: Int,
    val shutterDenominator: Int,
    val iso: Int,
    val movieRecTimeSeconds: Int,
    val isMovieRecording: Boolean = true,
    val movieFrameRate: TargetFrameRate = TargetFrameRate.FPS_23_976
) {
    val formattedFStop: String
        get() {
            val tenth = (fNumberHundredths / 10.0).roundToInt() / 10.0
            return "f/$tenth"
        }

    val formattedShutterSpeed: String
        get() = if (shutterNumerator <= 1 && shutterDenominator > 1) {
            "1/$shutterDenominator"
        } else if (shutterDenominator > 0) {
            "${shutterNumerator}/${shutterDenominator}"
        } else {
            "1/50"
        }

    val formattedIso: String
        get() = "ISO $iso"

    val formattedTimecode: String
        get() {
            val totalSec = movieRecTimeSeconds.coerceAtLeast(0)
            val hours = (totalSec / 3600).toString().padStart(2, '0')
            val minutes = ((totalSec % 3600) / 60).toString().padStart(2, '0')
            val seconds = (totalSec % 60).toString().padStart(2, '0')
            return "$hours:$minutes:$seconds:00"
        }
}

/**
 * Complete parsed Nikon LiveView (`0x9203`) header metadata (Big-Endian 384-byte layout).
 */
data class NikonLiveViewHeader(
    val jpegOffset: Int,
    val jpegSize: Int,
    val jpegDisplaySize: CameraCoordSize,
    val cameraCoordMax: CameraCoordSize,
    val afAreaRect: AfAreaRect,
    val afStatus: NikonAfStatus,
    val levelAngle: LevelAngle,
    val exposureInfo: LiveViewExposureInfo
) {
    // Convenience properties for direct compatibility
    val jpegWidth: Int get() = jpegDisplaySize.width
    val jpegHeight: Int get() = jpegDisplaySize.height
    val wholeWidth: Int get() = cameraCoordMax.width
    val wholeHeight: Int get() = cameraCoordMax.height
    val afAreaX: Int get() = afAreaRect.left
    val afAreaY: Int get() = afAreaRect.top
    val afAreaWidth: Int get() = afAreaRect.width
    val afAreaHeight: Int get() = afAreaRect.height
    val focusDrivingStatus: Int get() = afStatus.rawCode
    val rollAngleTenthsDeg: Int get() = (levelAngle.rollDegrees * 10f).roundToInt()
    val pitchAngleTenthsDeg: Int get() = (levelAngle.pitchDegrees * 10f).roundToInt()
}

/**
 * Holds the parsed Big-Endian 384-byte [NikonLiveViewHeader] and the extracted JPEG image byte slice
 * starting at the JPEG SOI marker (`0xFF 0xD8`).
 */
data class NikonLiveViewFrame(
    val header: NikonLiveViewHeader,
    val jpegBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NikonLiveViewFrame) return false
        return header == other.header && jpegBytes.contentEquals(other.jpegBytes)
    }

    override fun hashCode(): Int {
        return 31 * header.hashCode() + jpegBytes.contentHashCode()
    }
}

/**
 * Type alias preserving source compatibility with callers referencing `LiveViewFrame`.
 */
typealias LiveViewFrame = NikonLiveViewFrame
