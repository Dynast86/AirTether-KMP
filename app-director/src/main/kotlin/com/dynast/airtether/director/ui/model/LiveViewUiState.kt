package com.dynast.airtether.director.ui.model

import androidx.compose.runtime.Immutable
import com.dynast.airtether.core.nikonptp.pacing.FramePacingMode
import com.dynast.airtether.core.nikonptp.pacing.TargetFrameRate
import com.dynast.airtether.core.nikonptp.parser.NikonAfStatus
import com.dynast.airtether.core.nikonptp.parser.NikonLiveViewHeader
import kotlin.math.roundToInt

/**
 * Supported camera sensor/viewport aspect ratios in Director monitor mode.
 */
@Immutable
enum class ViewportAspectRatio(
    val ratio: Float,
    val label: String
) {
    VIDEO_16_9(16f / 9f, "16:9"),
    PHOTO_3_2(3f / 2f, "3:2");

    fun next(): ViewportAspectRatio = when (this) {
        VIDEO_16_9 -> PHOTO_3_2
        PHOTO_3_2 -> VIDEO_16_9
    }
}

/**
 * Autofocus confirmation state parsed from the 384-byte Nikon LiveView header (`afStatus`).
 * - `0`: `TRACKING_IDLE` (Unmatched / Searching - White)
 * - `1`: `LOCKED_IN_FOCUS` (Focus Matched - Green)
 * - `2`: `OUT_OF_FOCUS` (Focus Failed - Red)
 */
@Immutable
enum class AfFocusState {
    LOCKED_IN_FOCUS,
    OUT_OF_FOCUS,
    TRACKING_IDLE
}

/**
 * Selectable highlight color for Sobel Edge Focus Peaking (`uPeakingColor` uniform).
 */
@Immutable
enum class PeakingColor(
    val r: Float,
    val g: Float,
    val b: Float,
    val label: String
) {
    RED(1.0f, 0.16f, 0.16f, "RED"),
    GREEN(0.15f, 0.95f, 0.38f, "GRN"),
    YELLOW(1.0f, 0.88f, 0.12f, "YEL"),
    BLUE(0.20f, 0.62f, 1.0f, "BLU");

    fun next(): PeakingColor = when (this) {
        RED -> GREEN
        GREEN -> YELLOW
        YELLOW -> BLUE
        BLUE -> RED
    }
}

/**
 * High-frequency (60fps) telemetry parsed from the 384-byte Nikon `GetLiveViewImage` (`0x9203`) header.
 *
 * Passed via a lambda provider `() -> LiveViewTelemetry` so it is read strictly inside `Canvas`'s `DrawScope`
 * (State Read Deferral), completely preventing Recomposition of the surrounding HUD composables.
 */
@Immutable
data class LiveViewTelemetry(
    val afNormalizedCenterX: Float = 0.5f,
    val afNormalizedCenterY: Float = 0.5f,
    val afNormalizedWidth: Float = 0.08f,
    val afNormalizedHeight: Float = 0.12f,
    val cameraCoordMaxWidth: Int = 6048,
    val cameraCoordMaxHeight: Int = 4032,
    val afState: AfFocusState = AfFocusState.LOCKED_IN_FOCUS,
    val rollDegrees: Float = 0.0f,
    val pitchDegrees: Float = 0.0f,
    val frameSequence: Long = 0L
) {
    /**
     * Converts normalized viewport tap coordinates (`0.0f..1.0f`) into Nikon camera sensor coordinates (`0x9205`).
     */
    fun mapNormalizedTapToNikonCoordinates(
        normalizedX: Float,
        normalizedY: Float
    ): Pair<Int, Int> {
        val clampedX = normalizedX.coerceIn(0f, 1f)
        val clampedY = normalizedY.coerceIn(0f, 1f)
        val nikonX = (clampedX * cameraCoordMaxWidth).roundToInt().coerceIn(0, cameraCoordMaxWidth)
        val nikonY = (clampedY * cameraCoordMaxHeight).roundToInt().coerceIn(0, cameraCoordMaxHeight)
        return nikonX to nikonY
    }

    companion object {
        fun fromNikonHeader(
            header: NikonLiveViewHeader,
            frameSequence: Long = 0L
        ): LiveViewTelemetry {
            val focusState = when (header.afStatus) {
                NikonAfStatus.MATCHED_GREEN -> AfFocusState.LOCKED_IN_FOCUS
                NikonAfStatus.FAILED_RED -> AfFocusState.OUT_OF_FOCUS
                NikonAfStatus.SEARCHING -> AfFocusState.TRACKING_IDLE
            }

            return LiveViewTelemetry(
                afNormalizedCenterX = header.afAreaRect.normCenterX,
                afNormalizedCenterY = header.afAreaRect.normCenterY,
                afNormalizedWidth = header.afAreaRect.normWidth,
                afNormalizedHeight = header.afAreaRect.normHeight,
                cameraCoordMaxWidth = header.cameraCoordMax.width,
                cameraCoordMaxHeight = header.cameraCoordMax.height,
                afState = focusState,
                rollDegrees = header.levelAngle.rollDegrees,
                pitchDegrees = header.levelAngle.pitchDegrees,
                frameSequence = frameSequence
            )
        }
    }
}

/**
 * Real-time GPU Fragment Shader uniform options for cinema monitoring assist tools.
 */
@Immutable
data class ShaderOptions(
    val isLutEnabled: Boolean = true,
    val activeLutName: String = "N-Log -> Rec.709 (33³)",
    val lutIntensity: Float = 1.0f,
    val isFocusPeakingEnabled: Boolean = false,
    val peakingThreshold: Float = 0.25f,
    val peakingColor: PeakingColor = PeakingColor.RED,
    val isFalseColorEnabled: Boolean = false,
    val isZebraEnabled: Boolean = false,
    val zebraThreshold: Float = 0.95f
)

/**
 * Vector framing and composition overlay options drawn in Layer 2 `TelemetryOverlayCanvas`.
 */
@Immutable
data class GuideOptions(
    val isRuleOfThirdsVisible: Boolean = true,
    val isVertical9x16CropVisible: Boolean = false,
    val isHorizonLevelVisible: Boolean = true
)

/**
 * Camera exposure and autofocus status displayed in Layer 3 HUD.
 */
@Immutable
data class CameraExposureStatus(
    val fStop: String = "f/1.8",
    val shutterSpeed: String = "1/50",
    val iso: String = "ISO 800",
    val whiteBalance: String = "5600K",
    val afMode: String = "AF-C"
)

/**
 * Low-frequency, immutable UI state for Layer 3 HUD and viewport configuration.
 * High-frequency telemetry and JPEG bytes are intentionally excluded from this class to avoid Recomposition.
 */
@Immutable
data class LiveViewUiState(
    val isRecording: Boolean = true,
    val timecode: String = "00:14:28:00",
    val batteryPercent: Int = 88,
    val cameraModel: String = "Nikon Z 6II (Mock 24p)",
    val isUsbConnected: Boolean = true,
    val isHudVisible: Boolean = true,
    val isMaxBrightnessBoostEnabled: Boolean = false,
    val viewportAspectRatio: ViewportAspectRatio = ViewportAspectRatio.VIDEO_16_9,
    val pacingMode: FramePacingMode = FramePacingMode.AUTO_SYNC,
    val targetFrameRate: TargetFrameRate = TargetFrameRate.FPS_23_976,
    val frameRateHudBadge: String = "23.98 FPS (Sync)",
    val exposure: CameraExposureStatus = CameraExposureStatus(),
    val shaderOptions: ShaderOptions = ShaderOptions(),
    val guideOptions: GuideOptions = GuideOptions()
)
