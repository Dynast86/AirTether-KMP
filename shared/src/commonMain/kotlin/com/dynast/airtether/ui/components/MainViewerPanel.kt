package com.dynast.airtether.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.dynast.airtether.ui.util.loadImageBitmapFromUri
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dynast.airtether.model.ExifMetadata
import com.dynast.airtether.model.TetherMediaItem
import com.dynast.airtether.model.TransferStatus
import com.dynast.airtether.ui.theme.StudioBackground
import com.dynast.airtether.ui.theme.StudioBorder
import com.dynast.airtether.ui.theme.StudioDanger
import com.dynast.airtether.ui.theme.StudioPrimary
import com.dynast.airtether.ui.theme.StudioSecondary
import com.dynast.airtether.ui.theme.StudioSurface
import com.dynast.airtether.ui.theme.StudioSurfaceVariant
import com.dynast.airtether.ui.theme.StudioTextPrimary
import com.dynast.airtether.ui.theme.StudioTextSecondary
import com.dynast.airtether.ui.theme.StudioWarning
import kotlin.math.roundToInt

@Composable
fun MainViewerPanel(
    selectedItem: TetherMediaItem?,
    isExifOverlayVisible: Boolean,
    isLiveFollowEnabled: Boolean,
    onUserGesture: () -> Unit,
    onUpdateRating: (String, Int) -> Unit,
    onRetryTransfer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Reset zoom/pan when selection changes
    LaunchedEffect(selectedItem?.id) {
        scale = 1f
        offset = Offset.Zero
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(StudioBackground)
            .clipToBounds()
    ) {
        if (selectedItem == null) {
            EmptyViewerPlaceholder(modifier = Modifier.fillMaxSize())
        } else {
            // Interactive Zoomable & Pannable Image Viewport
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(selectedItem.id) {
                        detectTapGestures(
                            onDoubleTap = {
                                onUserGesture()
                                if (scale > 1.05f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    // 1:1 Pixel Zoom simulation (2.5x)
                                    scale = 2.5f
                                }
                            }
                        )
                    }
                    .pointerInput(selectedItem.id) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            if (zoom != 1f || pan != Offset.Zero) {
                                onUserGesture()
                                val nextScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = nextScale
                                offset = if (nextScale > 1f) {
                                    Offset(
                                        x = (offset.x + pan.x).coerceIn(-600f, 600f),
                                        y = (offset.y + pan.y).coerceIn(-400f, 400f)
                                    )
                                } else {
                                    Offset.Zero
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                EmbeddedPreviewFrame(
                    item = selectedItem,
                    scale = scale,
                    offset = offset,
                    onRetry = { onRetryTransfer(selectedItem.id) }
                )
            }

            // Top Viewer Overlay Bar: Pipeline Info + Zoom Indicator + Field Culling Rating
            ViewerTopHudBar(
                item = selectedItem,
                scale = scale,
                isLiveFollowEnabled = isLiveFollowEnabled,
                onResetZoom = {
                    scale = 1f
                    offset = Offset.Zero
                },
                onUpdateRating = { star -> onUpdateRating(selectedItem.id, star) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
            )

            // Bottom Floating EXIF Info Bar
            AnimatedVisibility(
                visible = isExifOverlayVisible,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp, start = 24.dp, end = 24.dp)
            ) {
                FloatingExifBar(item = selectedItem)
            }
        }
    }
}

@Composable
private fun EmbeddedPreviewFrame(
    item: TetherMediaItem,
    scale: Float,
    offset: Offset,
    onRetry: () -> Unit
) {
    val accentColor = Color(item.previewAccentHex)
    val decodedBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = item.displayUri) {
        val uri = item.displayUri
        value = if (uri != null) loadImageBitmapFromUri(uri) else null
    }

    Box(
        modifier = Modifier
            .padding(horizontal = 44.dp, vertical = 68.dp)
            .aspectRatio(3f / 2f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .clip(RoundedCornerShape(10.dp))
            .background(StudioSurface)
            .border(1.dp, StudioBorder, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = decodedBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = item.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Simulated High-Speed Embedded Preview Canvas (3:2 Full-Frame Sensor Aspect Ratio)
            Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.35f),
                        Color(0xFF111827),
                        Color(0xFF070A0F)
                    ),
                    center = Offset(size.width * 0.52f, size.height * 0.44f),
                    radius = size.minDimension * 0.78f
                )
            )

            // Rule-of-thirds subtle studio composition lines
            val thirdW = size.width / 3f
            val thirdH = size.height / 3f
            val gridColor = Color.White.copy(alpha = 0.07f)

            for (i in 1..2) {
                drawLine(
                    color = gridColor,
                    start = Offset(thirdW * i, 0f),
                    end = Offset(thirdW * i, size.height),
                    strokeWidth = 1f
                )
                drawLine(
                    color = gridColor,
                    start = Offset(0f, thirdH * i),
                    end = Offset(size.width, thirdH * i),
                    strokeWidth = 1f
                )
            }

            // Simulated subject geometry & rim lighting
            val center = Offset(size.width * 0.5f, size.height * 0.48f)
            drawCircle(
                color = accentColor.copy(alpha = 0.22f),
                radius = size.minDimension * 0.26f,
                center = center
            )
            drawCircle(
                color = accentColor.copy(alpha = 0.65f),
                radius = size.minDimension * 0.26f,
                center = center,
                style = Stroke(width = 2f)
            )

            // AF Focus Point Box (Eye-AF simulation)
            val afBoxSize = Size(44f, 44f)
            val afTopLeft = Offset(center.x - 22f, center.y - 36f)
            drawRect(
                color = Color(0xFF10B981).copy(alpha = 0.85f),
                topLeft = afTopLeft,
                size = afBoxSize,
                style = Stroke(width = 2f)
            )
            }
        }

        // Center Status / File Overlay inside the frame
        when (val status = item.transferStatus) {
            is TransferStatus.Transferring -> {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(StudioBackground.copy(alpha = 0.86f))
                        .border(1.dp, StudioPrimary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "RECEIVING RAW STREAM • ${status.progressPercent}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = StudioPrimary
                    )
                    LinearProgressIndicator(
                        progress = { status.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .width(220.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = StudioPrimary,
                        trackColor = StudioBorder
                    )
                    Text(
                        text = "Extracting embedded JPEG preview from TIFF byte offset...",
                        fontSize = 11.sp,
                        color = StudioTextSecondary
                    )
                }
            }

            TransferStatus.Completed -> {
                // Corner Watermark showing embedded JPEG pipeline status
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = item.fileName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = StudioTextPrimary.copy(alpha = 0.9f)
                    )
                    Text(
                        text = if (item.isRaw) {
                            "Embedded Full-Size Preview JPEG • Extracted in 18ms (Zero Bayer Demosaic)"
                        } else {
                            "Direct Full-Resolution JPEG • Decoded in 12ms"
                        },
                        fontSize = 10.sp,
                        color = StudioSecondary
                    )
                }
            }

            is TransferStatus.Failed -> {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(StudioBackground.copy(alpha = 0.9f))
                        .border(1.dp, StudioDanger.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "TRANSFER INTERRUPTED",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = StudioDanger
                    )
                    Text(
                        text = status.reason,
                        fontSize = 11.sp,
                        color = StudioTextSecondary
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(StudioDanger.copy(alpha = 0.2f))
                            .border(1.dp, StudioDanger, RoundedCornerShape(6.dp))
                            .clickable(onClick = onRetry)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Retry FTP Ingestion",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = StudioDanger
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerTopHudBar(
    item: TetherMediaItem,
    scale: Float,
    isLiveFollowEnabled: Boolean,
    onResetZoom: () -> Unit,
    onUpdateRating: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Format + Zoom Scale Pill + Gesture Hint
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val zoomPercent = (scale * 100).roundToInt()
            val zoomText = if (scale > 1.05f) "ZOOM ${zoomPercent}% (1:1)" else "FIT (100%)"

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(StudioSurface.copy(alpha = 0.88f))
                    .border(
                        width = 1.dp,
                        color = if (scale > 1.05f) StudioPrimary else StudioBorder,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable(onClick = onResetZoom)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = zoomText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (scale > 1.05f) StudioPrimary else StudioTextPrimary
                )
                if (scale > 1.05f) {
                    Text(
                        text = "• RESET",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = StudioTextSecondary
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(StudioSurface.copy(alpha = 0.78f))
                    .border(1.dp, StudioBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isLiveFollowEnabled) {
                        "Double-tap for 1:1 zoom (auto-pauses Live Follow)"
                    } else {
                        "Live Follow Paused • Inspecting ${item.fileName}"
                    },
                    fontSize = 11.sp,
                    color = StudioTextSecondary
                )
            }
        }

        // Right: Quick Field Culling Star Rating Bar
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(StudioSurface.copy(alpha = 0.88f))
                .border(1.dp, StudioBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "RATE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = StudioTextSecondary,
                modifier = Modifier.padding(end = 4.dp)
            )
            for (star in 1..5) {
                val filled = star <= item.rating
                Text(
                    text = if (filled) "★" else "☆",
                    fontSize = 15.sp,
                    color = if (filled) StudioWarning else StudioTextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onUpdateRating(star) }
                        .padding(horizontal = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun FloatingExifBar(
    item: TetherMediaItem,
    modifier: Modifier = Modifier
) {
    val exif: ExifMetadata? = item.exifMetadata

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(StudioSurface.copy(alpha = 0.94f))
            .border(1.dp, StudioBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Camera Model
        ExifMetricColumn(
            label = "CAMERA",
            value = exif?.cameraModel ?: "Unknown Body"
        )

        ExifVerticalDivider()

        // Focal Length
        ExifMetricColumn(
            label = "FOCAL",
            value = exif?.focalLength ?: "--"
        )

        ExifVerticalDivider()

        // Aperture
        ExifMetricColumn(
            label = "APERTURE",
            value = exif?.aperture ?: "--"
        )

        ExifVerticalDivider()

        // Shutter Speed
        ExifMetricColumn(
            label = "SHUTTER",
            value = exif?.shutterSpeed ?: "--"
        )

        ExifVerticalDivider()

        // ISO
        ExifMetricColumn(
            label = "ISO",
            value = exif?.iso?.let { "ISO $it" } ?: "--"
        )

        ExifVerticalDivider()

        // File Format + Size
        ExifMetricColumn(
            label = "ASSET",
            value = "${item.formatBadge} (${item.formattedFileSize})",
            valueColor = if (item.isRaw) StudioWarning else StudioPrimary
        )
    }
}

@Composable
private fun ExifMetricColumn(
    label: String,
    value: String,
    valueColor: Color = StudioTextPrimary
) {
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            color = StudioTextSecondary
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = valueColor
        )
    }
}

@Composable
private fun ExifVerticalDivider() {
    Box(
        modifier = Modifier
            .height(26.dp)
            .width(1.dp)
            .background(StudioBorder)
    )
}

@Composable
private fun EmptyViewerPlaceholder(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(StudioPrimary)
            )
            Text(
                text = "Waiting for incoming camera transfer on FTP Port 2121...",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = StudioTextPrimary
            )
            Text(
                text = "Release the camera shutter or click '+ Simulate FTP Shot' in the top bar.",
                fontSize = 12.sp,
                color = StudioTextSecondary
            )
        }
    }
}
