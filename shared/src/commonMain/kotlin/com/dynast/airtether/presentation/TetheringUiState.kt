package com.dynast.airtether.presentation

import com.dynast.airtether.domain.model.ConnectionState
import com.dynast.airtether.domain.model.LiveViewFrame
import com.dynast.airtether.domain.model.PhotoItem

data class TetheringUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val photos: List<PhotoItem> = emptyList(),
    val selectedPhotoId: String? = null,
    val isLiveViewActive: Boolean = false,
    val latestLiveViewFrame: LiveViewFrame? = null,
    val isZoomed1to1: Boolean = false,
    val showExifOverlay: Boolean = true,
) {
    val selectedPhoto: PhotoItem?
        get() = photos.firstOrNull { it.id == selectedPhotoId } ?: photos.firstOrNull()
}
