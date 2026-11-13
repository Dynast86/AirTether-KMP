package com.dynast.airtether.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dynast.airtether.model.ServerStatus
import com.dynast.airtether.ui.theme.StudioBorder
import com.dynast.airtether.ui.theme.StudioDanger
import com.dynast.airtether.ui.theme.StudioPrimary
import com.dynast.airtether.ui.theme.StudioSecondary
import com.dynast.airtether.ui.theme.StudioSurface
import com.dynast.airtether.ui.theme.StudioSurfaceVariant
import com.dynast.airtether.ui.theme.StudioTextPrimary
import com.dynast.airtether.ui.theme.StudioTextSecondary
import com.dynast.airtether.ui.theme.StudioWarning

@Composable
fun TetheringTopBar(
    sessionName: String,
    serverStatus: ServerStatus,
    isLiveFollowEnabled: Boolean,
    isExifOverlayVisible: Boolean,
    totalCount: Int,
    onToggleLiveFollow: () -> Unit,
    onToggleExifOverlay: () -> Unit,
    onSimulateIncomingPhoto: () -> Unit,
    onToggleServerStatus: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = StudioSurface,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .border(width = 1.dp, color = StudioBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Brand + Session Name + FTP IP/Port Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(StudioPrimary)
                    )
                    Text(
                        text = "AirTether",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = StudioTextPrimary
                    )
                }

                Box(
                    modifier = Modifier
                        .height(20.dp)
                        .width(1.dp)
                        .background(StudioBorder)
                )

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(StudioSurfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "SESSION",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = StudioTextSecondary
                    )
                    Text(
                        text = sessionName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = StudioTextPrimary
                    )
                    Text(
                        text = "($totalCount)",
                        fontSize = 11.sp,
                        color = StudioPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                FtpServerStatusBadge(
                    serverStatus = serverStatus,
                    onClick = onToggleServerStatus
                )
            }

            // Right: Simulate Shot Button, Live Follow Toggle Chip, EXIF HUD Toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onSimulateIncomingPhoto,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = StudioPrimary.copy(alpha = 0.16f),
                        contentColor = StudioPrimary
                    ),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(
                        text = "+ Simulate FTP Shot",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                LiveFollowToggleChip(
                    isLiveFollowEnabled = isLiveFollowEnabled,
                    onClick = onToggleLiveFollow
                )

                ExifToggleChip(
                    isVisible = isExifOverlayVisible,
                    onClick = onToggleExifOverlay
                )
            }
        }
    }
}

@Composable
private fun FtpServerStatusBadge(
    serverStatus: ServerStatus,
    onClick: () -> Unit
) {
    val (dotColor, statusLabel, borderColor) = when (serverStatus) {
        is ServerStatus.Running -> Triple(
            StudioSecondary,
            "FTP LISTEN • ${serverStatus.ipAddress}:${serverStatus.port}",
            StudioSecondary.copy(alpha = 0.45f)
        )
        ServerStatus.Starting -> Triple(
            StudioWarning,
            "FTP STARTING...",
            StudioWarning.copy(alpha = 0.45f)
        )
        ServerStatus.Stopped -> Triple(
            StudioTextSecondary,
            "FTP STOPPED (TAP TO START)",
            StudioBorder
        )
        is ServerStatus.Error -> Triple(
            StudioDanger,
            "FTP ERROR • ${serverStatus.message}",
            StudioDanger.copy(alpha = 0.5f)
        )
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(StudioSurfaceVariant)
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = statusLabel,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = StudioTextPrimary
        )
    }
}

@Composable
private fun LiveFollowToggleChip(
    isLiveFollowEnabled: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (isLiveFollowEnabled) {
        StudioSecondary.copy(alpha = 0.18f)
    } else {
        StudioWarning.copy(alpha = 0.15f)
    }
    val borderColor = if (isLiveFollowEnabled) {
        StudioSecondary.copy(alpha = 0.6f)
    } else {
        StudioWarning.copy(alpha = 0.5f)
    }
    val contentColor = if (isLiveFollowEnabled) StudioSecondary else StudioWarning
    val label = if (isLiveFollowEnabled) "LIVE FOLLOW: ON" else "LIVE FOLLOW: PAUSED"

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(contentColor)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
    }
}

@Composable
private fun ExifToggleChip(
    isVisible: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (isVisible) {
        StudioPrimary.copy(alpha = 0.18f)
    } else {
        StudioSurfaceVariant
    }
    val borderColor = if (isVisible) StudioPrimary.copy(alpha = 0.6f) else StudioBorder
    val textColor = if (isVisible) StudioPrimary else StudioTextSecondary

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isVisible) "EXIF HUD: ON" else "EXIF HUD: OFF",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
    }
}
