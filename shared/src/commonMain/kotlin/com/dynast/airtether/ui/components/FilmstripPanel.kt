package com.dynast.airtether.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dynast.airtether.model.TetherMediaItem
import com.dynast.airtether.model.TransferStatus
import com.dynast.airtether.ui.util.loadImageBitmapFromUri
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

@Composable
fun FilmstripPanel(
    items: List<TetherMediaItem>,
    selectedItemId: String?,
    completedCount: Int,
    transferringCount: Int,
    onSelectItem: (String) -> Unit,
    onUpdateRating: (String, Int) -> Unit,
    onRetryTransfer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = StudioSurface,
        modifier = modifier.fillMaxHeight()
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            // Filmstrip Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StudioSurfaceVariant.copy(alpha = 0.65f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "FILMSTRIP QUEUE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = StudioTextSecondary
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (transferringCount > 0) {
                        Text(
                            text = "$transferringCount INGESTING",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = StudioPrimary
                        )
                    }
                    Text(
                        text = "$completedCount READY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = StudioSecondary
                    )
                }
            }

            // LazyColumn Filmstrip List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = items,
                    key = { it.id }
                ) { item ->
                    val isSelected = item.id == selectedItemId
                    val isLatest = items.firstOrNull()?.id == item.id
                    FilmstripItemCard(
                        item = item,
                        isSelected = isSelected,
                        isLatest = isLatest,
                        onClick = { onSelectItem(item.id) },
                        onRatingSelect = { star -> onUpdateRating(item.id, star) },
                        onRetry = { onRetryTransfer(item.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FilmstripItemCard(
    item: TetherMediaItem,
    isSelected: Boolean,
    isLatest: Boolean,
    onClick: () -> Unit,
    onRatingSelect: (Int) -> Unit,
    onRetry: () -> Unit
) {
    val borderColor = when {
        isSelected -> StudioPrimary
        item.transferStatus is TransferStatus.Failed -> StudioDanger.copy(alpha = 0.6f)
        item.transferStatus is TransferStatus.Transferring -> StudioPrimary.copy(alpha = 0.45f)
        else -> StudioBorder
    }
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val cardBackground = if (isSelected) {
        StudioSurfaceVariant
    } else {
        StudioBackground.copy(alpha = 0.65f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cardBackground)
            .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Embedded Preview Thumbnail Box
            ThumbnailPreviewBox(
                item = item,
                isLatest = isLatest
            )

            Spacer(modifier = Modifier.width(10.dp))

            // Right: File Info + Metadata Summary + Status
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.fileName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = StudioTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    FormatBadgePill(
                        isRaw = item.isRaw,
                        extension = item.extension
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${item.formattedFileSize} • ${item.capturedAt}",
                        fontSize = 11.sp,
                        color = StudioTextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }

                item.exifMetadata?.let { exif ->
                    Text(
                        text = "${exif.focalLength}  ${exif.aperture}  ${exif.shutterSpeed}  ISO ${exif.iso}",
                        fontSize = 10.sp,
                        color = StudioTextSecondary.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Transfer Progress or Rating / Status Footer
        when (val status = item.transferStatus) {
            is TransferStatus.Transferring -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ingesting via FTP (Port 2121)...",
                            fontSize = 10.sp,
                            color = StudioPrimary,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${status.progressPercent}%",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = StudioPrimary
                        )
                    }
                    LinearProgressIndicator(
                        progress = { status.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = StudioPrimary,
                        trackColor = StudioBorder
                    )
                }
            }

            TransferStatus.Completed -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Star Rating Culling Row
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (star in 1..5) {
                            val filled = star <= item.rating
                            Text(
                                text = if (filled) "★" else "☆",
                                fontSize = 13.sp,
                                color = if (filled) StudioWarning else StudioTextSecondary.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { onRatingSelect(star) }
                                    .padding(horizontal = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = "PREVIEW READY",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = StudioSecondary
                    )
                }
            }

            is TransferStatus.Failed -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = status.reason,
                        fontSize = 10.sp,
                        color = StudioDanger,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(StudioDanger.copy(alpha = 0.18f))
                            .border(1.dp, StudioDanger.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .clickable(onClick = onRetry)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "RETRY",
                            fontSize = 9.sp,
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
private fun ThumbnailPreviewBox(
    item: TetherMediaItem,
    isLatest: Boolean
) {
    val accent = Color(item.previewAccentHex)
    val thumbnailBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = item.displayUri) {
        val uri = item.displayUri
        value = if (uri != null) loadImageBitmapFromUri(uri) else null
    }
    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 54.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.38f),
                        StudioBackground
                    )
                )
            )
            .border(1.dp, StudioBorder, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = thumbnailBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = item.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = item.extension,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                color = StudioTextPrimary.copy(alpha = 0.85f)
            )
        }

        if (isLatest) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(StudioPrimary)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "NEW",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF04131F)
                )
            }
        }
    }
}

@Composable
private fun FormatBadgePill(
    isRaw: Boolean,
    extension: String
) {
    val badgeColor = if (isRaw) StudioWarning else StudioPrimary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(badgeColor.copy(alpha = 0.16f))
            .border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = if (isRaw) "RAW•$extension" else "JPG",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = badgeColor
        )
    }
}
