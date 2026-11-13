package com.dynast.airtether.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dynast.airtether.model.TetheringUiState
import com.dynast.airtether.ui.components.FilmstripPanel
import com.dynast.airtether.ui.components.MainViewerPanel
import com.dynast.airtether.ui.components.TetheringTopBar
import com.dynast.airtether.ui.theme.StudioBackground
import com.dynast.airtether.ui.theme.StudioBorder
import com.dynast.airtether.viewmodel.TetheringIntent
import com.dynast.airtether.viewmodel.TetheringViewModel

@Composable
fun TetheringScreen(
    viewModel: TetheringViewModel = viewModel { TetheringViewModel() },
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    TetheringScreenContent(
        uiState = uiState,
        onIntent = viewModel::onIntent,
        modifier = modifier
    )
}

@Composable
fun TetheringScreenContent(
    uiState: TetheringUiState,
    onIntent: (TetheringIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StudioBackground)
            .safeDrawingPadding()
    ) {
        // Top Bar: FTP IP/Port status badge, Live Follow toggle chip, Session name, Simulate button
        TetheringTopBar(
            sessionName = uiState.sessionName,
            serverStatus = uiState.serverStatus,
            isLiveFollowEnabled = uiState.isLiveFollowEnabled,
            isExifOverlayVisible = uiState.isExifOverlayVisible,
            totalCount = uiState.items.size,
            hasMockItems = uiState.hasMockItems,
            isPtpConnected = uiState.isPtpConnected,
            isLiveViewActive = uiState.isLiveViewActive,
            onToggleLiveFollow = { onIntent(TetheringIntent.ToggleLiveFollow) },
            onToggleExifOverlay = { onIntent(TetheringIntent.ToggleExifOverlay) },
            onSimulateIncomingPhoto = { onIntent(TetheringIntent.SimulateIncomingPhoto) },
            onToggleServerStatus = { onIntent(TetheringIntent.ToggleServerStatus) },
            onUpdateSessionName = { name -> onIntent(TetheringIntent.UpdateSessionName(name)) },
            onClearMockItems = { onIntent(TetheringIntent.ClearMockItems) },
            onTogglePtpSession = { onIntent(TetheringIntent.TogglePtpSession) },
            onToggleLiveView = { onIntent(TetheringIntent.ToggleLiveView) },
            onTriggerShutter = { onIntent(TetheringIntent.TriggerPtpShutter) }
        )

        // 2-Panel Landscape Split Layout (Left 28% Filmstrip : Right 72% Main Viewer)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            FilmstripPanel(
                items = uiState.visibleItems,
                selectedItemId = uiState.selectedItem?.id,
                completedCount = uiState.completedCount,
                transferringCount = uiState.transferringCount,
                minRatingFilter = uiState.minRatingFilter,
                onSetRatingFilter = { minRating ->
                    onIntent(TetheringIntent.SetRatingFilter(minRating))
                },
                onSelectItem = { itemId ->
                    onIntent(TetheringIntent.SelectItem(itemId = itemId, isUserGesture = true))
                },
                onUpdateRating = { itemId, rating ->
                    onIntent(TetheringIntent.UpdateRating(itemId = itemId, rating = rating))
                },
                onRetryTransfer = { itemId ->
                    onIntent(TetheringIntent.RetryTransfer(itemId = itemId))
                },
                modifier = Modifier
                    .weight(0.28f)
                    .fillMaxHeight()
            )

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(StudioBorder)
            )

            MainViewerPanel(
                selectedItem = uiState.selectedItem,
                isExifOverlayVisible = uiState.isExifOverlayVisible,
                isLiveFollowEnabled = uiState.isLiveFollowEnabled,
                isLiveViewActive = uiState.isLiveViewActive,
                liveViewFrame = uiState.liveViewFrame,
                onUserGesture = {
                    onIntent(TetheringIntent.PauseLiveFollowOnGesture)
                },
                onUpdateRating = { itemId, rating ->
                    onIntent(TetheringIntent.UpdateRating(itemId = itemId, rating = rating))
                },
                onRetryTransfer = { itemId ->
                    onIntent(TetheringIntent.RetryTransfer(itemId = itemId))
                },
                modifier = Modifier
                    .weight(0.72f)
                    .fillMaxHeight()
            )
        }
    }
}
