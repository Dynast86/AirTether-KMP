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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    hasMockItems: Boolean,
    isPtpConnected: Boolean,
    isLiveViewActive: Boolean,
    onToggleLiveFollow: () -> Unit,
    onToggleExifOverlay: () -> Unit,
    onSimulateIncomingPhoto: () -> Unit,
    onToggleServerStatus: () -> Unit,
    onUpdateSessionName: (String) -> Unit,
    onClearMockItems: () -> Unit,
    onTogglePtpSession: () -> Unit,
    onToggleLiveView: () -> Unit,
    onTriggerShutter: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isEditingSessionName by remember { mutableStateOf(false) }
    var draftSessionName by remember(sessionName) { mutableStateOf(sessionName) }

    if (isEditingSessionName) {
        AlertDialog(
            onDismissRequest = { isEditingSessionName = false },
            containerColor = StudioSurface,
            titleContentColor = StudioTextPrimary,
            textContentColor = StudioTextSecondary,
            title = {
                Text(
                    text = "Rename Session",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                OutlinedTextField(
                    value = draftSessionName,
                    onValueChange = { draftSessionName = it },
                    singleLine = true,
                    label = { Text("Session Name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = StudioTextPrimary,
                        unfocusedTextColor = StudioTextPrimary,
                        focusedBorderColor = StudioPrimary,
                        unfocusedBorderColor = StudioBorder,
                        focusedLabelColor = StudioPrimary,
                        unfocusedLabelColor = StudioTextSecondary,
                        cursorColor = StudioPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draftSessionName.trim().isNotEmpty(),
                    onClick = {
                        onUpdateSessionName(draftSessionName)
                        isEditingSessionName = false
                    }
                ) {
                    Text("Save", color = StudioPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { isEditingSessionName = false }) {
                    Text("Cancel", color = StudioTextSecondary)
                }
            }
        )
    }

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
                        .border(1.dp, StudioBorder, RoundedCornerShape(6.dp))
                        .clickable {
                            draftSessionName = sessionName
                            isEditingSessionName = true
                        }
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
                    Text(
                        text = "✎",
                        fontSize = 11.sp,
                        color = StudioTextSecondary
                    )
                }

                FtpServerStatusBadge(
                    serverStatus = serverStatus,
                    onClick = onToggleServerStatus
                )

                PtpStatusChip(
                    isConnected = isPtpConnected,
                    onClick = onTogglePtpSession
                )
            }

            // Right: Clear Sample, LiveView, Shutter, Simulate Shot, Live Follow, EXIF HUD
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasMockItems) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(StudioSurfaceVariant)
                            .border(1.dp, StudioBorder, RoundedCornerShape(8.dp))
                            .clickable(onClick = onClearMockItems)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Clear Sample",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = StudioTextSecondary
                        )
                    }
                }

                LiveViewToggleChip(
                    isActive = isLiveViewActive,
                    onClick = onToggleLiveView
                )

                if (isPtpConnected) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(StudioWarning.copy(alpha = 0.16f))
                            .border(1.dp, StudioWarning.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .clickable(onClick = onTriggerShutter)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "SHUTTER",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = StudioWarning
                        )
                    }
                }

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
private fun PtpStatusChip(
    isConnected: Boolean,
    onClick: () -> Unit
) {
    val dotColor = if (isConnected) StudioSecondary else StudioTextSecondary
    val borderColor = if (isConnected) StudioSecondary.copy(alpha = 0.5f) else StudioBorder
    val label = if (isConnected) "PTP: ON" else "PTP: OFF"

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(StudioSurfaceVariant)
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = StudioTextPrimary
        )
    }
}

@Composable
private fun LiveViewToggleChip(
    isActive: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (isActive) StudioDanger.copy(alpha = 0.18f) else StudioSurfaceVariant
    val borderColor = if (isActive) StudioDanger.copy(alpha = 0.65f) else StudioBorder
    val contentColor = if (isActive) StudioDanger else StudioTextSecondary

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
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
            text = if (isActive) "LIVE VIEW: ON" else "LIVE VIEW",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
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
