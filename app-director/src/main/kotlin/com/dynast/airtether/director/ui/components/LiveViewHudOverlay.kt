package com.dynast.airtether.director.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dynast.airtether.director.ui.model.LiveViewUiState

private val HudSurface = Color(0xCC0A0C10)
private val HudChipBackground = Color(0xE6141820)
private val HudBorder = Color(0xFF2B3240)
private val CinemaYellow = Color(0xFFFACC15)
private val CinemaRed = Color(0xFFEF4444)
private val CinemaGreen = Color(0xFF22C55E)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)

/**
 * Layer 3: Cinema Monitor Top Status HUD & Bottom Exposure/Assist Control Dock.
 *
 * Strictly consumes immutable [LiveViewUiState] (low-frequency state) so 30~60fps LiveView frames
 * and telemetry updates never trigger Recomposition of this HUD layer.
 */
@Composable
fun LiveViewHudOverlay(
    uiState: LiveViewUiState,
    onToggleRecording: () -> Unit,
    onToggleAfMode: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onCycleFramePacingMode: () -> Unit = {},
    onCycleFStop: () -> Unit,
    onCycleShutter: () -> Unit,
    onCycleIso: () -> Unit,
    onCycleWhiteBalance: () -> Unit,
    onToggleLut: () -> Unit,
    onToggleFocusPeaking: () -> Unit,
    onToggleFalseColor: () -> Unit,
    onToggleZebra: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggle9x16Crop: () -> Unit,
    onToggleBrightnessBoost: () -> Unit,
    onToggleCleanFeed: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Top HUD Bar
        AnimatedVisibility(
            visible = uiState.isHudVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            DirectorTopStatusBar(
                uiState = uiState,
                onToggleRecording = onToggleRecording,
                onToggleAfMode = onToggleAfMode,
                onToggleAspectRatio = onToggleAspectRatio,
                onCycleFramePacingMode = onCycleFramePacingMode,
                onToggleCleanFeed = onToggleCleanFeed
            )
        }

        // Bottom Exposure & Monitoring Assist Control Dock
        AnimatedVisibility(
            visible = uiState.isHudVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            DirectorBottomControlDock(
                uiState = uiState,
                onCycleFStop = onCycleFStop,
                onCycleShutter = onCycleShutter,
                onCycleIso = onCycleIso,
                onCycleWhiteBalance = onCycleWhiteBalance,
                onToggleLut = onToggleLut,
                onToggleFocusPeaking = onToggleFocusPeaking,
                onToggleFalseColor = onToggleFalseColor,
                onToggleZebra = onToggleZebra,
                onToggleGrid = onToggleGrid,
                onToggle9x16Crop = onToggle9x16Crop,
                onToggleBrightnessBoost = onToggleBrightnessBoost
            )
        }

        // Minimal Clean Feed Restore Pill when HUD is hidden
        AnimatedVisibility(
            visible = !uiState.isHudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(HudSurface)
                    .border(1.dp, HudBorder, RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleCleanFeed)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "CLEAN FEED • SHOW HUD",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = CinemaYellow
                )
            }
        }
    }
}

@Composable
private fun DirectorTopStatusBar(
    uiState: LiveViewUiState,
    onToggleRecording: () -> Unit,
    onToggleAfMode: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onCycleFramePacingMode: () -> Unit,
    onToggleCleanFeed: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HudSurface)
            .border(width = 1.dp, color = HudBorder)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: REC Indicator + Timecode + Camera Model + Adaptive Frame Rate Badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // REC Badge & Timecode
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (uiState.isRecording) CinemaRed.copy(alpha = 0.22f) else HudChipBackground)
                    .border(
                        width = 1.dp,
                        color = if (uiState.isRecording) CinemaRed else HudBorder,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clickable(onClick = onToggleRecording)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (uiState.isRecording) CinemaRed else TextMuted)
                )
                Text(
                    text = if (uiState.isRecording) "REC" else "STBY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    color = if (uiState.isRecording) CinemaRed else TextMuted
                )
                Text(
                    text = uiState.timecode,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = TextPrimary
                )
            }

            // Camera Body & USB-C PTP Status
            TopHudPill(
                label = "CAM",
                value = uiState.cameraModel,
                valueColor = if (uiState.isUsbConnected) CinemaGreen else CinemaRed
            )

            // Adaptive Frame Rate & Display Refresh Sync Switcher (Auto Sync / 24p / 30p / Max)
            TopHudPill(
                label = "FPS",
                value = uiState.frameRateHudBadge,
                valueColor = CinemaYellow,
                onClick = onCycleFramePacingMode
            )

            // Aspect Ratio Switcher (16:9 / 3:2)
            TopHudPill(
                label = "ASPECT",
                value = uiState.viewportAspectRatio.label,
                valueColor = CinemaYellow,
                onClick = onToggleAspectRatio
            )
        }

        // Right: AF Mode + Active LUT Name + Battery + Clean Feed Toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TopHudPill(
                label = "FOCUS",
                value = uiState.exposure.afMode,
                valueColor = CinemaYellow,
                onClick = onToggleAfMode
            )

            TopHudPill(
                label = "LUT",
                value = if (uiState.shaderOptions.isLutEnabled) {
                    uiState.shaderOptions.activeLutName
                } else {
                    "BYPASS (N-Log)"
                },
                valueColor = if (uiState.shaderOptions.isLutEnabled) CinemaYellow else TextMuted
            )

            TopHudPill(
                label = "BAT",
                value = "${uiState.batteryPercent}%",
                valueColor = if (uiState.batteryPercent > 20) CinemaGreen else CinemaRed
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(HudChipBackground)
                    .border(1.dp, HudBorder, RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleCleanFeed)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "CLEAN FEED",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = TextPrimary
                )
            }
        }
    }
}

@Composable
private fun DirectorBottomControlDock(
    uiState: LiveViewUiState,
    onCycleFStop: () -> Unit,
    onCycleShutter: () -> Unit,
    onCycleIso: () -> Unit,
    onCycleWhiteBalance: () -> Unit,
    onToggleLut: () -> Unit,
    onToggleFocusPeaking: () -> Unit,
    onToggleFalseColor: () -> Unit,
    onToggleZebra: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggle9x16Crop: () -> Unit,
    onToggleBrightnessBoost: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HudSurface)
            .border(width = 1.dp, color = HudBorder)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: Camera Exposure Control Chips (F-Stop, Shutter, ISO, WB)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExposureControlChip(
                title = "IRIS",
                value = uiState.exposure.fStop,
                onClick = onCycleFStop
            )
            ExposureControlChip(
                title = "SHUTTER",
                value = uiState.exposure.shutterSpeed,
                onClick = onCycleShutter
            )
            ExposureControlChip(
                title = "ISO",
                value = uiState.exposure.iso,
                onClick = onCycleIso
            )
            ExposureControlChip(
                title = "WB",
                value = uiState.exposure.whiteBalance,
                onClick = onCycleWhiteBalance
            )
        }

        // Right: Cinema Monitoring Assist Tool Toggle Buttons
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AssistToggleButton(
                label = "LUT",
                isActive = uiState.shaderOptions.isLutEnabled,
                onClick = onToggleLut
            )
            AssistToggleButton(
                label = if (uiState.shaderOptions.isFocusPeakingEnabled) {
                    "PEAK:${uiState.shaderOptions.peakingColor.label}"
                } else {
                    "PEAK"
                },
                isActive = uiState.shaderOptions.isFocusPeakingEnabled,
                activeColor = CinemaRed,
                onClick = onToggleFocusPeaking
            )
            AssistToggleButton(
                label = "FALSE",
                isActive = uiState.shaderOptions.isFalseColorEnabled,
                onClick = onToggleFalseColor
            )
            AssistToggleButton(
                label = "ZEBRA",
                isActive = uiState.shaderOptions.isZebraEnabled,
                onClick = onToggleZebra
            )
            AssistToggleButton(
                label = "GRID",
                isActive = uiState.guideOptions.isRuleOfThirdsVisible,
                onClick = onToggleGrid
            )
            AssistToggleButton(
                label = "9:16",
                isActive = uiState.guideOptions.isVertical9x16CropVisible,
                onClick = onToggle9x16Crop
            )
            AssistToggleButton(
                label = "BRIGHT",
                isActive = uiState.isMaxBrightnessBoostEnabled,
                onClick = onToggleBrightnessBoost
            )
        }
    }
}

@Composable
private fun TopHudPill(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
    onClick: (() -> Unit)? = null
) {
    val baseModifier = Modifier
        .clip(RoundedCornerShape(6.dp))
        .background(HudChipBackground)
        .border(1.dp, HudBorder, RoundedCornerShape(6.dp))
        .let { mod -> if (onClick != null) mod.clickable(onClick = onClick) else mod }
        .padding(horizontal = 9.dp, vertical = 5.dp)

    Row(
        modifier = baseModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = valueColor
        )
    }
}

@Composable
private fun ExposureControlChip(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(HudChipBackground)
            .border(1.dp, HudBorder, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = title,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            color = TextPrimary
        )
    }
}

@Composable
private fun AssistToggleButton(
    label: String,
    isActive: Boolean,
    activeColor: Color = CinemaYellow,
    onClick: () -> Unit
) {
    val bgColor = if (isActive) activeColor.copy(alpha = 0.2f) else HudChipBackground
    val borderColor = if (isActive) activeColor else HudBorder
    val textColor = if (isActive) activeColor else TextMuted

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            color = textColor
        )
    }
}
