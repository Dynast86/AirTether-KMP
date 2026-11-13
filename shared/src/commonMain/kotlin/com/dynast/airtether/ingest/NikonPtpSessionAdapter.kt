package com.dynast.airtether.ingest

import com.dynast.airtether.core.nikonptp.protocol.PtpConstants
import com.dynast.airtether.core.nikonptp.protocol.PtpEventPacket
import com.dynast.airtether.core.nikonptp.session.NikonSessionManager
import com.dynast.airtether.core.nikonptp.session.NikonSessionState
import com.dynast.airtether.domain.port.LiveViewFramePacket
import com.dynast.airtether.domain.port.PhotoStorage
import com.dynast.airtether.domain.port.PtpSessionManager
import com.dynast.airtether.model.TetherMediaItem
import com.dynast.airtether.model.TransferStatus
import com.dynast.airtether.viewmodel.IngestionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Bridges `:core:nikon-ptp`'s [NikonSessionManager] to the shared [PtpSessionManager] port
 * and pipes newly captured camera objects (`EVT_OBJECT_ADDED` / `EVT_NIKON_OBJECT_ADDED_IN_SDRAM`)
 * into [PhotoStorage] + [IngestionEvent]s.
 */
class NikonPtpSessionAdapter(
    private val sessionManager: NikonSessionManager,
    private val photoStorage: PhotoStorage,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : PtpSessionManager {

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isLiveViewActive = MutableStateFlow(false)
    override val isLiveViewActive: StateFlow<Boolean> = _isLiveViewActive.asStateFlow()

    private val _ingestionEvents = MutableSharedFlow<IngestionEvent>(extraBufferCapacity = 64)
    val ingestionEvents: SharedFlow<IngestionEvent> = _ingestionEvents.asSharedFlow()

    private var eventJob: Job? = null

    init {
        scope.launch {
            sessionManager.sessionState.collect { state ->
                when (state) {
                    is NikonSessionState.Connected -> {
                        _isConnected.value = true
                        _isLiveViewActive.value = state.isLiveViewActive
                    }
                    NikonSessionState.Disconnected, is NikonSessionState.Error -> {
                        _isConnected.value = false
                        _isLiveViewActive.value = false
                    }
                    NikonSessionState.Connecting -> Unit
                }
            }
        }
        eventJob = scope.launch {
            sessionManager.cameraEvents.collect { evt ->
                handleCameraEvent(evt)
            }
        }
    }

    override suspend fun openSession(): Result<Unit> = runCatching {
        val opened = sessionManager.openSession()
        if (!opened) error("Failed to open Nikon PTP session")
    }

    override suspend fun closeSession() {
        sessionManager.closeSession()
    }

    override fun observeLiveViewFrames(): Flow<LiveViewFramePacket> {
        return sessionManager.liveViewFrames.map { frame ->
            LiveViewFramePacket(
                jpegBytes = frame.jpegBytes,
                timestampMs = frame.timestampMs,
            )
        }
    }

    override suspend fun startLiveView(): Result<Unit> = runCatching {
        val started = sessionManager.startLiveView()
        if (!started) error("Failed to start Nikon LiveView")
    }

    override suspend fun stopLiveView() {
        sessionManager.stopLiveView()
    }

    override suspend fun triggerShutter(): Result<Unit> = runCatching {
        val result = sessionManager.executeWithBusyRetry(PtpConstants.OP_INITIATE_CAPTURE)
        if (!result.response.isOk) {
            error("Shutter trigger failed: 0x${result.response.responseCode.toString(16)}")
        }
    }

    suspend fun ingestObjectHandle(objectHandle: UInt) {
        val itemId = "ptp-$objectHandle"
        var pendingHandle: String? = null
        try {
            val fileName = fetchObjectFileName(objectHandle)
            val mimeType = if (fileName.substringAfterLast('.', "").equals("jpg", ignoreCase = true) ||
                fileName.substringAfterLast('.', "").equals("jpeg", ignoreCase = true)
            ) "image/jpeg" else "application/octet-stream"

            _ingestionEvents.emit(
                IngestionEvent.TransferStarted(
                    TetherMediaItem(
                        id = itemId,
                        fileName = fileName,
                        fileSize = 0L,
                        transferStatus = TransferStatus.Transferring(0f),
                        localUri = null,
                        exifMetadata = null,
                    )
                )
            )

            val objectResult = sessionManager.executeWithBusyRetry(
                opCode = PtpConstants.OP_GET_OBJECT,
                params = listOf(objectHandle),
            )
            if (!objectResult.response.isOk) {
                error("GetObject failed: 0x${objectResult.response.responseCode.toString(16)}")
            }

            val bytes = objectResult.data
            pendingHandle = photoStorage.createPendingFile(fileName, mimeType)
            photoStorage.writeChunk(pendingHandle, bytes, 0L)
            val savedPath = photoStorage.commitPendingFile(pendingHandle)
            pendingHandle = null

            _ingestionEvents.emit(
                IngestionEvent.TransferCompleted(
                    itemId = itemId,
                    localUri = savedPath,
                    exifMetadata = null,
                    fileSizeBytes = bytes.size.toLong(),
                )
            )
        } catch (t: Throwable) {
            pendingHandle?.let { runCatching { photoStorage.abortPendingFile(it) } }
            _ingestionEvents.emit(
                IngestionEvent.TransferFailed(
                    itemId = itemId,
                    reason = t.message ?: "USB PTP object download failed",
                )
            )
        }
    }

    private suspend fun handleCameraEvent(event: PtpEventPacket) {
        when (event.eventCode) {
            PtpConstants.EVT_OBJECT_ADDED,
            PtpConstants.EVT_NIKON_OBJECT_ADDED_IN_SDRAM -> {
                val handle = event.params.firstOrNull() ?: return
                ingestObjectHandle(handle)
            }
        }
    }

    private suspend fun fetchObjectFileName(objectHandle: UInt): String {
        val infoResult = sessionManager.executeWithBusyRetry(
            opCode = PtpConstants.OP_GET_OBJECT_INFO,
            params = listOf(objectHandle),
        )
        if (!infoResult.response.isOk || infoResult.data.size <= 53) {
            return "PTP_${objectHandle}.NEF"
        }
        return parsePtpString(infoResult.data, offset = 52)
            .takeIf { it.isNotBlank() } ?: "PTP_${objectHandle}.NEF"
    }

    private fun parsePtpString(data: ByteArray, offset: Int): String {
        if (offset >= data.size) return ""
        val charCount = data[offset].toInt() and 0xFF
        if (charCount <= 0) return ""
        val sb = StringBuilder(charCount)
        var pos = offset + 1
        for (i in 0 until charCount) {
            if (pos + 1 >= data.size) break
            val lo = data[pos].toInt() and 0xFF
            val hi = data[pos + 1].toInt() and 0xFF
            val code = lo or (hi shl 8)
            pos += 2
            if (code == 0) break
            sb.append(code.toChar())
        }
        return sb.toString().trim()
    }
}
