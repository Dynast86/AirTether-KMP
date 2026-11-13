package com.dynast.airtether.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dynast.airtether.domain.port.IngestService
import com.dynast.airtether.domain.port.PhotoStorage
import com.dynast.airtether.domain.port.RawPreviewExtractor
import com.dynast.airtether.model.ExifMetadata
import com.dynast.airtether.model.ServerStatus
import com.dynast.airtether.model.TetherMediaItem
import com.dynast.airtether.model.TetheringUiState
import com.dynast.airtether.model.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * User intents in the MVI / Unidirectional Data Flow architecture.
 */
sealed interface TetheringIntent {
    data class SelectItem(val itemId: String, val isUserGesture: Boolean = true) : TetheringIntent
    data object ToggleLiveFollow : TetheringIntent
    data object PauseLiveFollowOnGesture : TetheringIntent
    data object ToggleExifOverlay : TetheringIntent
    data class UpdateRating(val itemId: String, val rating: Int) : TetheringIntent
    data object SimulateIncomingPhoto : TetheringIntent
    data class RetryTransfer(val itemId: String) : TetheringIntent
    data object ToggleServerStatus : TetheringIntent
}

/**
 * Internal background service events piped via SharedFlow into the ViewModel.
 */
sealed interface IngestionEvent {
    data class TransferStarted(val item: TetherMediaItem) : IngestionEvent
    data class TransferProgress(val itemId: String, val progress: Float) : IngestionEvent
    data class TransferCompleted(
        val itemId: String,
        val localUri: String,
        val exifMetadata: ExifMetadata? = null,
        val fileSizeBytes: Long? = null
    ) : IngestionEvent
    data class TransferFailed(val itemId: String, val reason: String) : IngestionEvent
}

class TetheringViewModel(
    autoSimulateInitialTransfer: Boolean = true,
    coroutineScope: CoroutineScope? = null,
    private val ingestService: IngestService? = null,
    private val rawPreviewExtractor: RawPreviewExtractor? = null,
    private val photoStorage: PhotoStorage? = null,
) : ViewModel() {

    private val scope: CoroutineScope = coroutineScope ?: runCatching {
        viewModelScope
    }.getOrElse {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    private val _uiState = MutableStateFlow(createInitialMockState())
    val uiState: StateFlow<TetheringUiState> = _uiState.asStateFlow()

    // SharedFlow simulating background FTP (Port 2121) / USB PTP ingestion events
    private val ingestionEvents = MutableSharedFlow<IngestionEvent>(extraBufferCapacity = 32)

    private var sequenceCounter = 1048

    init {
        observeIngestionPipeline()
        observeExternalIngestService()
        if (autoSimulateInitialTransfer) {
            simulateInitialActiveTransfer()
        }
    }

    fun onIntent(intent: TetheringIntent) {
        when (intent) {
            is TetheringIntent.SelectItem -> handleSelectItem(intent.itemId, intent.isUserGesture)
            TetheringIntent.ToggleLiveFollow -> handleToggleLiveFollow()
            TetheringIntent.PauseLiveFollowOnGesture -> handlePauseLiveFollowOnGesture()
            TetheringIntent.ToggleExifOverlay -> handleToggleExifOverlay()
            is TetheringIntent.UpdateRating -> handleUpdateRating(intent.itemId, intent.rating)
            TetheringIntent.SimulateIncomingPhoto -> simulateIncomingPhoto()
            is TetheringIntent.RetryTransfer -> retryTransfer(intent.itemId)
            TetheringIntent.ToggleServerStatus -> handleToggleServerStatus()
        }
    }

    private fun observeExternalIngestService() {
        val service = ingestService ?: return
        scope.launch {
            service.serverStatus.collect { status ->
                _uiState.update { it.copy(serverStatus = status) }
            }
        }
        scope.launch {
            service.events.collect { event ->
                handleIngestionEvent(event)
            }
        }
    }

    private fun observeIngestionPipeline() {
        scope.launch {
            ingestionEvents.collect { event ->
                handleIngestionEvent(event)
            }
        }
    }

    private fun handleIngestionEvent(event: IngestionEvent) {
        when (event) {
            is IngestionEvent.TransferStarted -> {
                _uiState.update { state ->
                    val updatedItems = listOf(event.item) + state.items
                    val nextSelectedId = if (state.isLiveFollowEnabled) {
                        event.item.id
                    } else {
                        state.selectedItemId ?: event.item.id
                    }
                    state.copy(
                        items = updatedItems,
                        selectedItemId = nextSelectedId
                    )
                }
            }

                    is IngestionEvent.TransferProgress -> {
                        _uiState.update { state ->
                            state.copy(
                                items = state.items.map { item ->
                                    if (item.id == event.itemId) {
                                        item.copy(transferStatus = TransferStatus.Transferring(event.progress))
                                    } else {
                                        item
                                    }
                                }
                            )
                        }
                    }

                    is IngestionEvent.TransferCompleted -> {
                        var completedItem: TetherMediaItem? = null
                        _uiState.update { state ->
                            val updatedItems = state.items.map { item ->
                                if (item.id == event.itemId) {
                                    item.copy(
                                        transferStatus = TransferStatus.Completed,
                                        localUri = event.localUri,
                                        fileSize = event.fileSizeBytes ?: item.fileSize,
                                        exifMetadata = event.exifMetadata ?: item.exifMetadata
                                    ).also { completedItem = it }
                                } else {
                                    item
                                }
                            }
                            val nextSelectedId = if (state.isLiveFollowEnabled) {
                                event.itemId
                            } else {
                                state.selectedItemId
                            }
                            state.copy(
                                items = updatedItems,
                                selectedItemId = nextSelectedId
                            )
                        }
                        completedItem?.let { extractPreviewAsync(it, event.localUri) }
                    }

                    is IngestionEvent.TransferFailed -> {
                        _uiState.update { state ->
                            state.copy(
                                items = state.items.map { item ->
                                    if (item.id == event.itemId) {
                                        item.copy(transferStatus = TransferStatus.Failed(event.reason))
                                    } else {
                                        item
                                    }
                                }
                            )
                        }
                    }
        }
    }

    private fun extractPreviewAsync(item: TetherMediaItem, localUri: String) {
        val extractor = rawPreviewExtractor ?: return
        scope.launch {
            runCatching {
                val extracted = extractor.extract(filePath = localUri, isRaw = item.isRaw)
                val cachedPreviewUri = extracted.previewJpegBytes?.let { bytes ->
                    photoStorage?.savePreviewJpeg(item.id, bytes)
                }
                _uiState.update { state ->
                    state.copy(
                        items = state.items.map { existing ->
                            if (existing.id == item.id) {
                                existing.copy(
                                    previewUri = cachedPreviewUri ?: existing.previewUri,
                                    exifMetadata = extracted.exif ?: existing.exifMetadata,
                                )
                            } else {
                                existing
                            }
                        }
                    )
                }
            }
        }
    }

    private fun handleSelectItem(itemId: String, isUserGesture: Boolean) {
        _uiState.update { state ->
            val latestItemId = state.items.firstOrNull()?.id
            // Pause Live Follow when user manually selects an older item in the filmstrip
            val shouldKeepLiveFollow = if (isUserGesture && itemId != latestItemId) {
                false
            } else {
                state.isLiveFollowEnabled
            }
            state.copy(
                selectedItemId = itemId,
                isLiveFollowEnabled = shouldKeepLiveFollow
            )
        }
    }

    private fun handleToggleLiveFollow() {
        _uiState.update { state ->
            val enabling = !state.isLiveFollowEnabled
            val latestItemId = state.items.firstOrNull()?.id
            state.copy(
                isLiveFollowEnabled = enabling,
                selectedItemId = if (enabling && latestItemId != null) latestItemId else state.selectedItemId
            )
        }
    }

    private fun handlePauseLiveFollowOnGesture() {
        _uiState.update { state ->
            if (state.isLiveFollowEnabled) {
                state.copy(isLiveFollowEnabled = false)
            } else {
                state
            }
        }
    }

    private fun handleToggleExifOverlay() {
        _uiState.update { state ->
            state.copy(isExifOverlayVisible = !state.isExifOverlayVisible)
        }
    }

    private fun handleUpdateRating(itemId: String, rating: Int) {
        val clampedRating = rating.coerceIn(0, 5)
        _uiState.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item.id == itemId) {
                        val nextRating = if (item.rating == clampedRating) 0 else clampedRating
                        item.copy(rating = nextRating)
                    } else {
                        item
                    }
                }
            )
        }
    }

    private fun handleToggleServerStatus() {
        val service = ingestService
        if (service != null) {
            scope.launch {
                when (_uiState.value.serverStatus) {
                    is ServerStatus.Running, ServerStatus.Starting -> service.stopFtpServer()
                    else -> service.startFtpServer(2121)
                }
            }
            return
        }
        _uiState.update { state ->
            val nextStatus = when (state.serverStatus) {
                is ServerStatus.Running -> ServerStatus.Stopped
                else -> ServerStatus.Running(ipAddress = "192.168.43.1", port = 2121)
            }
            state.copy(serverStatus = nextStatus)
        }
    }

    /**
     * Simulates an incoming wireless FTP photo transfer and sub-50ms embedded JPEG preview extraction.
     */
    fun simulateIncomingPhoto() {
        val currentNumber = ++sequenceCounter
        val isRawShot = currentNumber % 3 != 0
        val extension = if (isRawShot) {
            when (currentNumber % 3) {
                1 -> "ARW"
                else -> "CR3"
            }
        } else {
            "JPG"
        }
        val fileName = "_DSC$currentNumber.$extension"
        val itemId = "item_$currentNumber"
        val fileSize = if (isRawShot) 54_525_952L else 14_680_064L
        val mockExif = SAMPLE_EXIF_PRESETS[currentNumber % SAMPLE_EXIF_PRESETS.size]
        val mockAccent = ACCENT_PALETTE[currentNumber % ACCENT_PALETTE.size]
        val seconds = (currentNumber % 60).toString().padStart(2, '0')

        val incomingItem = TetherMediaItem(
            id = itemId,
            fileName = fileName,
            fileSize = fileSize,
            transferStatus = TransferStatus.Transferring(progress = 0.08f),
            localUri = null,
            exifMetadata = mockExif,
            rating = 0,
            capturedAt = "10:44:$seconds",
            previewAccentHex = mockAccent
        )

        scope.launch {
            ingestionEvents.emit(IngestionEvent.TransferStarted(incomingItem))
            val steps = listOf(0.24f, 0.48f, 0.72f, 0.91f, 1.0f)
            for (step in steps) {
                delay(180L)
                if (step < 1.0f) {
                    ingestionEvents.emit(IngestionEvent.TransferProgress(itemId, step))
                } else {
                    ingestionEvents.emit(
                        IngestionEvent.TransferCompleted(
                            itemId = itemId,
                            localUri = "file:///storage/emulated/0/Pictures/AirTether/$fileName",
                            exifMetadata = mockExif
                        )
                    )
                }
            }
        }
    }

    private fun retryTransfer(itemId: String) {
        val targetItem = _uiState.value.items.find { it.id == itemId } ?: return
        val fallbackExif = targetItem.exifMetadata ?: SAMPLE_EXIF_PRESETS.first()
        scope.launch {
            val steps = listOf(0.2f, 0.55f, 0.85f, 1.0f)
            for (step in steps) {
                ingestionEvents.emit(IngestionEvent.TransferProgress(itemId, step))
                delay(160L)
            }
            ingestionEvents.emit(
                IngestionEvent.TransferCompleted(
                    itemId = itemId,
                    localUri = "file:///storage/emulated/0/Pictures/AirTether/${targetItem.fileName}",
                    exifMetadata = fallbackExif
                )
            )
        }
    }

    private fun simulateInitialActiveTransfer() {
        val transferringItem = _uiState.value.items.firstOrNull {
            it.transferStatus is TransferStatus.Transferring
        } ?: return

        scope.launch {
            val steps = listOf(0.52f, 0.78f, 0.95f, 1.0f)
            for (step in steps) {
                delay(350L)
                if (step < 1.0f) {
                    ingestionEvents.emit(IngestionEvent.TransferProgress(transferringItem.id, step))
                } else {
                    ingestionEvents.emit(
                        IngestionEvent.TransferCompleted(
                            itemId = transferringItem.id,
                            localUri = "file:///storage/emulated/0/Pictures/AirTether/${transferringItem.fileName}",
                            exifMetadata = transferringItem.exifMetadata ?: SAMPLE_EXIF_PRESETS.first()
                        )
                    )
                }
            }
        }
    }

    companion object {
        private val ACCENT_PALETTE = listOf(
            0xFF38BDF8,
            0xFFA855F7,
            0xFF10B981,
            0xFFF59E0B,
            0xFFF43F5E,
            0xFF6366F1
        )

        private val SAMPLE_EXIF_PRESETS = listOf(
            ExifMetadata(
                shutterSpeed = "1/1600s",
                aperture = "f/1.4",
                iso = 100,
                focalLength = "50mm",
                cameraModel = "Sony ILCE-7RM5"
            ),
            ExifMetadata(
                shutterSpeed = "1/800s",
                aperture = "f/2.8",
                iso = 250,
                focalLength = "85mm",
                cameraModel = "Sony ILCE-1"
            ),
            ExifMetadata(
                shutterSpeed = "1/2500s",
                aperture = "f/1.2",
                iso = 160,
                focalLength = "35mm",
                cameraModel = "Canon EOS R5 Mark II"
            ),
            ExifMetadata(
                shutterSpeed = "1/500s",
                aperture = "f/4.0",
                iso = 64,
                focalLength = "70mm",
                cameraModel = "Nikon Z8"
            )
        )

        private fun createInitialMockState(): TetheringUiState {
            val mockItems = listOf(
                TetherMediaItem(
                    id = "item_1048",
                    fileName = "_DSC1048.ARW",
                    fileSize = 62_914_560L,
                    transferStatus = TransferStatus.Transferring(progress = 0.34f),
                    localUri = null,
                    exifMetadata = ExifMetadata(
                        shutterSpeed = "1/1600s",
                        aperture = "f/1.4",
                        iso = 100,
                        focalLength = "50mm",
                        cameraModel = "Sony ILCE-7RM5"
                    ),
                    rating = 0,
                    capturedAt = "10:42:41",
                    previewAccentHex = 0xFF38BDF8
                ),
                TetherMediaItem(
                    id = "item_1047",
                    fileName = "_DSC1047.ARW",
                    fileSize = 61_341_696L,
                    transferStatus = TransferStatus.Completed,
                    localUri = "file:///storage/emulated/0/Pictures/AirTether/_DSC1047.ARW",
                    exifMetadata = ExifMetadata(
                        shutterSpeed = "1/1250s",
                        aperture = "f/1.4",
                        iso = 100,
                        focalLength = "50mm",
                        cameraModel = "Sony ILCE-7RM5"
                    ),
                    rating = 5,
                    capturedAt = "10:42:19",
                    previewAccentHex = 0xFFA855F7
                ),
                TetherMediaItem(
                    id = "item_1046",
                    fileName = "_DSC1046.JPG",
                    fileSize = 15_728_640L,
                    transferStatus = TransferStatus.Completed,
                    localUri = "file:///storage/emulated/0/Pictures/AirTether/_DSC1046.JPG",
                    exifMetadata = ExifMetadata(
                        shutterSpeed = "1/800s",
                        aperture = "f/2.0",
                        iso = 200,
                        focalLength = "85mm",
                        cameraModel = "Sony ILCE-7RM5"
                    ),
                    rating = 4,
                    capturedAt = "10:41:52",
                    previewAccentHex = 0xFF10B981
                ),
                TetherMediaItem(
                    id = "item_1045",
                    fileName = "_DSC1045.CR3",
                    fileSize = 48_234_496L,
                    transferStatus = TransferStatus.Completed,
                    localUri = "file:///storage/emulated/0/Pictures/AirTether/_DSC1045.CR3",
                    exifMetadata = ExifMetadata(
                        shutterSpeed = "1/2000s",
                        aperture = "f/1.2",
                        iso = 160,
                        focalLength = "85mm",
                        cameraModel = "Canon EOS R5 Mark II"
                    ),
                    rating = 3,
                    capturedAt = "10:41:10",
                    previewAccentHex = 0xFFF59E0B
                ),
                TetherMediaItem(
                    id = "item_1044",
                    fileName = "_DSC1044.NEF",
                    fileSize = 51_380_224L,
                    transferStatus = TransferStatus.Failed(reason = "FTP packet timeout"),
                    localUri = null,
                    exifMetadata = ExifMetadata(
                        shutterSpeed = "1/640s",
                        aperture = "f/2.8",
                        iso = 64,
                        focalLength = "70mm",
                        cameraModel = "Nikon Z8"
                    ),
                    rating = 0,
                    capturedAt = "10:40:33",
                    previewAccentHex = 0xFFF43F5E
                )
            )

            return TetheringUiState(
                serverStatus = ServerStatus.Running(ipAddress = "192.168.43.1", port = 2121),
                isLiveFollowEnabled = true,
                items = mockItems,
                selectedItemId = "item_1047",
                isExifOverlayVisible = true,
                sessionName = "Studio_Portrait_S01"
            )
        }
    }
}
