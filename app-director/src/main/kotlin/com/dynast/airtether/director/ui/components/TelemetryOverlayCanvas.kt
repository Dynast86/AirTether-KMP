package com.dynast.airtether.director.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import com.dynast.airtether.director.ui.model.AfFocusState
import com.dynast.airtether.director.ui.model.GuideOptions
import com.dynast.airtether.director.ui.model.LiveViewTelemetry
import kotlin.math.abs

private val AfGreen = Color(0xFF22C55E)
private val AfRed = Color(0xFFEF4444)
private val AfWhite = Color(0xFFF8FAFC)
private val CinemaYellow = Color(0xFFFACC15)
private val GridLineColor = Color(0x55FFFFFF)
private val CropMatteColor = Color(0x77000000)

/**
 * Layer 2: Vector Telemetry Overlay & Touch Gesture Surface.
 *
 * - Uses **State Read Deferral** (`telemetryProvider: () -> LiveViewTelemetry`) so high-frequency (30~60fps)
 *   Nikon 384-byte header updates are read strictly inside [DrawScope], preventing any Compose Recomposition.
 * - Maps user tap gestures (`px`) -> normalized (`0.0f..1.0f`) -> Nikon sensor coordinates (`cameraCoordMaxWidth`,
 *   `cameraCoordMaxHeight`) and invokes [onTouchAf].
 */
@Composable
fun TelemetryOverlayCanvas(
    telemetryProvider: () -> LiveViewTelemetry,
    guideOptions: GuideOptions,
    onTouchAf: (nikonX: Int, nikonY: Int) -> Unit,
    onToggleCleanFeed: () -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tapOffset ->
                        if (size.width > 0 && size.height > 0) {
                            val normX = (tapOffset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            val normY = (tapOffset.y / size.height.toFloat()).coerceIn(0f, 1f)
                            val currentTelemetry = telemetryProvider()
                            val (nikonX, nikonY) = currentTelemetry.mapNormalizedTapToNikonCoordinates(
                                normalizedX = normX,
                                normalizedY = normY
                            )
                            onTouchAf(nikonX, nikonY)
                        }
                    },
                    onDoubleTap = {
                        onToggleCleanFeed()
                    }
                )
            }
    ) {
        // 1. Composition Guides (Rule of Thirds & 9:16 Vertical Reels/Shorts Safety Crop)
        if (guideOptions.isVertical9x16CropVisible) {
            drawVertical9x16CropGuide()
        }
        if (guideOptions.isRuleOfThirdsVisible) {
            drawRuleOfThirdsGrid()
        }

        // 2. Deferred State Read: Read 30~60fps telemetry ONLY inside DrawScope
        val telemetry = telemetryProvider()

        if (guideOptions.isHorizonLevelVisible) {
            drawElectronicHorizonIndicator(
                rollDegrees = telemetry.rollDegrees,
                pitchDegrees = telemetry.pitchDegrees
            )
        }

        drawNikonAfBox(telemetry = telemetry)
    }
}

private fun DrawScope.drawRuleOfThirdsGrid() {
    val thirdW = size.width / 3f
    val thirdH = size.height / 3f

    for (i in 1..2) {
        drawLine(
            color = GridLineColor,
            start = Offset(thirdW * i, 0f),
            end = Offset(thirdW * i, size.height),
            strokeWidth = 1.2f
        )
        drawLine(
            color = GridLineColor,
            start = Offset(0f, thirdH * i),
            end = Offset(size.width, thirdH * i),
            strokeWidth = 1.2f
        )
    }
}

private fun DrawScope.drawVertical9x16CropGuide() {
    val cropWidth = (size.height * (9f / 16f)).coerceAtMost(size.width)
    val leftEdge = (size.width - cropWidth) / 2f
    val rightEdge = leftEdge + cropWidth

    // Dimmed pillarbox safety mattes outside the 9:16 vertical crop zone
    drawRect(
        color = CropMatteColor,
        topLeft = Offset.Zero,
        size = Size(leftEdge, size.height)
    )
    drawRect(
        color = CropMatteColor,
        topLeft = Offset(rightEdge, 0f),
        size = Size(size.width - rightEdge, size.height)
    )

    // Dashed yellow boundary lines for 9:16 social media vertical frame
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f), 0f)
    drawLine(
        color = CinemaYellow.copy(alpha = 0.85f),
        start = Offset(leftEdge, 0f),
        end = Offset(leftEdge, size.height),
        strokeWidth = 2f,
        pathEffect = dashEffect
    )
    drawLine(
        color = CinemaYellow.copy(alpha = 0.85f),
        start = Offset(rightEdge, 0f),
        end = Offset(rightEdge, size.height),
        strokeWidth = 2f,
        pathEffect = dashEffect
    )
}

private fun DrawScope.drawElectronicHorizonIndicator(
    rollDegrees: Float,
    pitchDegrees: Float
) {
    val center = Offset(size.width * 0.5f, size.height * 0.5f)
    val isLevel = abs(rollDegrees) <= 1.0f && abs(pitchDegrees) <= 1.0f
    val horizonColor = if (isLevel) AfGreen else CinemaYellow.copy(alpha = 0.85f)
    val wingRadius = size.minDimension * 0.11f

    // Static reference crosshair ticks
    drawLine(
        color = Color.White.copy(alpha = 0.35f),
        start = Offset(center.x - wingRadius * 1.35f, center.y),
        end = Offset(center.x - wingRadius * 1.05f, center.y),
        strokeWidth = 2f
    )
    drawLine(
        color = Color.White.copy(alpha = 0.35f),
        start = Offset(center.x + wingRadius * 1.05f, center.y),
        end = Offset(center.x + wingRadius * 1.35f, center.y),
        strokeWidth = 2f
    )

    // Pitch vertical offset & Roll rotation bar
    val pitchPixelOffset = (pitchDegrees.coerceIn(-30f, 30f) / 30f) * (size.height * 0.08f)
    val pitchedCenter = Offset(center.x, center.y + pitchPixelOffset)

    rotate(degrees = -rollDegrees, pivot = center) {
        drawLine(
            color = horizonColor,
            start = Offset(pitchedCenter.x - wingRadius, pitchedCenter.y),
            end = Offset(pitchedCenter.x - wingRadius * 0.25f, pitchedCenter.y),
            strokeWidth = 3f
        )
        drawLine(
            color = horizonColor,
            start = Offset(pitchedCenter.x + wingRadius * 0.25f, pitchedCenter.y),
            end = Offset(pitchedCenter.x + wingRadius, pitchedCenter.y),
            strokeWidth = 3f
        )
        drawCircle(
            color = horizonColor,
            radius = 3.5f,
            center = pitchedCenter
        )
    }
}

private fun DrawScope.drawNikonAfBox(telemetry: LiveViewTelemetry) {
    val boxW = (telemetry.afNormalizedWidth * size.width).coerceAtLeast(36f)
    val boxH = (telemetry.afNormalizedHeight * size.height).coerceAtLeast(36f)
    val centerX = telemetry.afNormalizedCenterX * size.width
    val centerY = telemetry.afNormalizedCenterY * size.height

    val left = (centerX - boxW * 0.5f).coerceIn(0f, (size.width - boxW).coerceAtLeast(0f))
    val top = (centerY - boxH * 0.5f).coerceIn(0f, (size.height - boxH).coerceAtLeast(0f))
    val right = left + boxW
    val bottom = top + boxH

    val afColor = when (telemetry.afState) {
        AfFocusState.LOCKED_IN_FOCUS -> AfGreen
        AfFocusState.OUT_OF_FOCUS -> AfRed
        AfFocusState.TRACKING_IDLE -> AfWhite
    }

    // Outer AF Rectangle
    drawRect(
        color = afColor.copy(alpha = 0.85f),
        topLeft = Offset(left, top),
        size = Size(boxW, boxH),
        style = Stroke(width = 2.5f)
    )

    // Corner Brackets for high visibility against bright outdoor scenes
    val cornerLen = (boxW.coerceAtMost(boxH) * 0.28f).coerceAtLeast(8f)
    val bracketStroke = 4.5f

    // Top-Left corner
    drawLine(afColor, Offset(left, top), Offset(left + cornerLen, top), bracketStroke)
    drawLine(afColor, Offset(left, top), Offset(left, top + cornerLen), bracketStroke)

    // Top-Right corner
    drawLine(afColor, Offset(right, top), Offset(right - cornerLen, top), bracketStroke)
    drawLine(afColor, Offset(right, top), Offset(right, top + cornerLen), bracketStroke)

    // Bottom-Left corner
    drawLine(afColor, Offset(left, bottom), Offset(left + cornerLen, bottom), bracketStroke)
    drawLine(afColor, Offset(left, bottom), Offset(left, bottom - cornerLen), bracketStroke)

    // Bottom-Right corner
    drawLine(afColor, Offset(right, bottom), Offset(right - cornerLen, bottom), bracketStroke)
    drawLine(afColor, Offset(right, bottom), Offset(right, bottom - cornerLen), bracketStroke)

    // Center focus point dot
    drawCircle(
        color = afColor,
        radius = 2.5f,
        center = Offset(left + boxW * 0.5f, top + boxH * 0.5f)
    )
}
