package com.dynast.airtether.presentation

import com.dynast.airtether.domain.model.ColorLabel
import com.dynast.airtether.domain.model.CullingFlag

sealed interface TetheringIntent {
    data class SelectPhoto(val photoId: String) : TetheringIntent
    data object Toggle1to1Zoom : TetheringIntent
    data object ToggleExifOverlay : TetheringIntent

    data class SetRating(val photoId: String, val rating: Int) : TetheringIntent
    data class SetFlag(val photoId: String, val flag: CullingFlag) : TetheringIntent
    data class SetColorLabel(val photoId: String, val label: ColorLabel) : TetheringIntent
    data class ExportXmp(val photoId: String) : TetheringIntent

    data class StartFtpServer(val port: Int = 2121) : TetheringIntent
    data object StopFtpServer : TetheringIntent

    data object ToggleLiveView : TetheringIntent
    data object TriggerShutter : TetheringIntent
}
