package com.dynast.airtether.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dynast.airtether.domain.model.PhotoItem
import com.dynast.airtether.domain.model.TransferState
import com.dynast.airtether.domain.port.CullingRepository
import com.dynast.airtether.domain.port.IngestEvent
import com.dynast.airtether.domain.port.IngestService
import com.dynast.airtether.domain.port.PhotoStorage
import com.dynast.airtether.domain.port.PtpSessionManager
import com.dynast.airtether.domain.port.RawPreviewExtractor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TetheringViewModel(
    private val ingestService: IngestService? = null,
    private val ptpSessionManager: PtpSessionManager? = null,
    private val rawPreviewExtractor: RawPreviewExtractor? = null,
    private val photoStorage: PhotoStorage? = null,
    private val cullingRepository: CullingRepository? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TetheringUiState())
    val uiState: StateFlow<TetheringUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<TetheringEffect>()
    val effects: SharedFlow<TetheringEffect> = _effects.asSharedFlow()

    private var liveViewJob: Job? = null

    init {
        observeIngestEvents()
        observeConnectionState()
    }

    fun onIntent(intent: TetheringIntent) {
        when (intent) {
            is TetheringIntent.SelectPhoto -> {
                _uiState.update {
                    it.copy(
                        selectedPhotoId = intent.photoId,
                        isLiveViewActive = false,
                        isZoomed1to1 = false,
                    )
                }
            }

            TetheringIntent.Toggle1to1Zoom -> {
                _uiState.update { it.copy(isZoomed1to1 = !it.isZoomed1to1) }
            }

            TetheringIntent.ToggleExifOverlay -> {
                _uiState.update { it.copy(showExifOverlay = !it.showExifOverlay) }
            }

            is TetheringIntent.SetRating -> updateCulling(intent.photoId) {
                it.copy(rating = intent.rating.coerceIn(0, 5))
            }

            is TetheringIntent.SetFlag -> updateCulling(intent.photoId) {
                it.copy(flag = intent.flag)
            }

            is TetheringIntent.SetColorLabel -> updateCulling(intent.photoId) {
                it.copy(colorLabel = intent.label)
            }

            is TetheringIntent.ExportXmp -> exportXmp(intent.photoId)

            is TetheringIntent.StartFtpServer -> viewModelScope.launch {
                ingestService?.startFtpServer(intent.port)
            }

            TetheringIntent.StopFtpServer -> viewModelScope.launch {
                ingestService?.stopFtpServer()
            }

            TetheringIntent.ToggleLiveView -> toggleLiveView()

            TetheringIntent.TriggerShutter -> viewModelScope.launch {
                ptpSessionManager?.triggerShutter()?.onFailure { err ->
                    _effects.emit(TetheringEffect.ShowError(err.message ?: "Shutter trigger failed"))
                }
            }
        }
    }

    private fun observeConnectionState() {
        val service = ingestService ?: return
        viewModelScope.launch {
            service.connectionState.collect { conn ->
                _uiState.update { it.copy(connectionState = conn) }
            }
        }
    }

    private fun observeIngestEvents() {
        val service = ingestService ?: return
        viewModelScope.launch {
            service.events.collect { event ->
                when (event) {
                    is IngestEvent.Started -> {
                        val pending = PhotoItem(
                            id = event.id,
                            fileName = event.fileName,
                            format = event.format,
                            source = event.source,
                            fileSizeBytes = event.totalBytes,
                            transferState = TransferState.InProgress(0f),
                        )
                        _uiState.update { state ->
                            state.copy(
                                photos = listOf(pending) + state.photos,
                                selectedPhotoId = event.id,
                            )
                        }
                        _effects.emit(TetheringEffect.ScrollFilmstripTo(event.id))
                    }

                    is IngestEvent.Progress -> {
                        val progress = if (event.totalBytes > 0) {
                            (event.bytesReceived.toFloat() / event.totalBytes).coerceIn(0f, 1f)
                        } else 0f
                        updatePhoto(event.id) {
                            it.copy(transferState = TransferState.InProgress(progress))
                        }
                    }

                    is IngestEvent.Completed -> {
                        updatePhoto(event.id) {
                            it.copy(
                                originalPath = event.savedPath,
                                fileSizeBytes = event.fileSizeBytes,
                                receivedAtEpochMs = event.receivedAtEpochMs,
                                transferState = TransferState.Completed,
                            )
                        }
                        extractPreviewAsync(event)
                    }

                    is IngestEvent.Failed -> {
                        updatePhoto(event.id) {
                            it.copy(transferState = TransferState.Failed(event.reason))
                        }
                        _effects.emit(TetheringEffect.ShowError("Transfer failed: ${event.fileName}"))
                    }
                }
            }
        }
    }

    private fun extractPreviewAsync(event: IngestEvent.Completed) {
        val extractor = rawPreviewExtractor ?: return
        viewModelScope.launch {
            val extracted = extractor.extract(event.savedPath, event.format)
            val previewPath = extracted.previewJpegBytes?.let { bytes ->
                photoStorage?.savePreviewJpeg(event.id, bytes)
            } ?: if (!event.format.isRaw) event.savedPath else null

            updatePhoto(event.id) {
                it.copy(
                    previewPath = previewPath,
                    exif = extracted.exif,
                )
            }
        }
    }

    private fun toggleLiveView() {
        val manager = ptpSessionManager ?: return
        val nextActive = !_uiState.value.isLiveViewActive
        if (nextActive) {
            liveViewJob?.cancel()
            liveViewJob = viewModelScope.launch {
                manager.startLiveView().onSuccess {
                    _uiState.update { it.copy(isLiveViewActive = true) }
                    manager.observeLiveViewFrames().collect { frame ->
                        _uiState.update { it.copy(latestLiveViewFrame = frame) }
                    }
                }.onFailure { err ->
                    _effects.emit(TetheringEffect.ShowError(err.message ?: "LiveView start failed"))
                }
            }
        } else {
            liveViewJob?.cancel()
            liveViewJob = null
            viewModelScope.launch {
                manager.stopLiveView()
                _uiState.update { it.copy(isLiveViewActive = false, latestLiveViewFrame = null) }
            }
        }
    }

    private fun updateCulling(
        photoId: String,
        transform: (com.dynast.airtether.domain.model.CullingMetadata) -> com.dynast.airtether.domain.model.CullingMetadata,
    ) {
        var updatedPhoto: PhotoItem? = null
        updatePhoto(photoId) { photo ->
            photo.copy(culling = transform(photo.culling)).also { updatedPhoto = it }
        }
        val target = updatedPhoto ?: return
        viewModelScope.launch {
            cullingRepository?.updateCulling(photoId, target.culling)
        }
    }

    private fun exportXmp(photoId: String) {
        val target = _uiState.value.photos.firstOrNull { it.id == photoId } ?: return
        viewModelScope.launch {
            cullingRepository?.exportXmpSidecar(target)
                ?.onSuccess { path ->
                    _effects.emit(TetheringEffect.ShowMessage("XMP exported: $path"))
                }
                ?.onFailure { err ->
                    _effects.emit(TetheringEffect.ShowError(err.message ?: "XMP export failed"))
                }
        }
    }

    private inline fun updatePhoto(photoId: String, transform: (PhotoItem) -> PhotoItem) {
        _uiState.update { state ->
            state.copy(
                photos = state.photos.map { if (it.id == photoId) transform(it) else it }
            )
        }
    }
}
