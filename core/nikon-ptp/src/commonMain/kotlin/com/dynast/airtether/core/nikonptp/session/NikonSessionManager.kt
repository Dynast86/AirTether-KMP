package com.dynast.airtether.core.nikonptp.session

import com.dynast.airtether.core.nikonptp.pacing.FramePacer
import com.dynast.airtether.core.nikonptp.pacing.FramePacingMode
import com.dynast.airtether.core.nikonptp.pacing.FramePacingSnapshot
import com.dynast.airtether.core.nikonptp.pacing.TargetFrameRate
import com.dynast.airtether.core.nikonptp.parser.LiveViewFrame
import com.dynast.airtether.core.nikonptp.parser.LiveViewHeaderParser
import com.dynast.airtether.core.nikonptp.protocol.PtpCommandPacket
import com.dynast.airtether.core.nikonptp.protocol.PtpConstants
import com.dynast.airtether.core.nikonptp.protocol.PtpEventPacket
import com.dynast.airtether.core.nikonptp.protocol.PtpPacketSerializer
import com.dynast.airtether.core.nikonptp.protocol.PtpTransactionResult
import com.dynast.airtether.core.nikonptp.transport.PtpTransport
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * High-level state of a Nikon PTP / PTP-IP camera session.
 */
sealed interface NikonSessionState {
    data object Disconnected : NikonSessionState
    data object Connecting : NikonSessionState
    data class Connected(
        val sessionId: UInt,
        val transportName: String = "",
        val isLiveViewActive: Boolean = false
    ) : NikonSessionState
    data class Error(val message: String) : NikonSessionState
}

/**
 * Serializes all PTP transactions for Nikon cameras (Z 6II, Zf, etc.) using a Coroutine [Mutex].
 *
 * - Supports dynamic **Hot-Swapping** ([swapTransport]) between `MockPtpTransport` and physical
 *   `UsbPtpTransport` / `TcpIpPtpTransport`.
 * - Implements the full Nikon LiveView startup sequence:
 *   `GetDeviceInfo` (`0x1001`) ➔ `OpenSession` (`0x1002`) ➔ `StartLiveView` (`0x9201`) ➔ `GetLiveViewImage` (`0x9203`) loop.
 * - Includes automatic `50ms` backoff retry when the camera responds with `DeviceBusy` (`0x2019`).
 */
class NikonSessionManager(
    transport: PtpTransport,
    private val scope: CoroutineScope,
    private val deviceBusyBackoffMs: Long = 50L,
    private val maxDeviceBusyRetries: Int = 12,
    val framePacer: FramePacer = FramePacer()
) {
    @Volatile
    var currentTransport: PtpTransport = transport
        private set

    private val transactionMutex = Mutex()
    private var transactionSequence: UInt = 0u
    private var liveViewPollingJob: Job? = null

    private val _sessionState = MutableStateFlow<NikonSessionState>(NikonSessionState.Disconnected)
    val sessionState: StateFlow<NikonSessionState> = _sessionState.asStateFlow()

    private val _pacingState = MutableStateFlow(FramePacingSnapshot())
    val pacingState: StateFlow<FramePacingSnapshot> = _pacingState.asStateFlow()

    private val _liveViewFrames = MutableSharedFlow<LiveViewFrame>(replay = 1, extraBufferCapacity = 4)
    val liveViewFrames: SharedFlow<LiveViewFrame> = _liveViewFrames.asSharedFlow()

    private val _cameraEvents = MutableSharedFlow<PtpEventPacket>(extraBufferCapacity = 64)
    val cameraEvents: SharedFlow<PtpEventPacket> = _cameraEvents.asSharedFlow()

    /**
     * Executes any PTP command atomically inside [transactionMutex] with an auto-incremented TransactionID.
     */
    suspend fun executeSerialized(
        opCode: UShort,
        params: List<UInt> = emptyList(),
        outboundData: ByteArray? = null
    ): PtpTransactionResult = transactionMutex.withLock {
        val txId = ++transactionSequence
        val command = PtpCommandPacket(
            opCode = opCode,
            transactionId = txId,
            params = params
        )
        currentTransport.executeTransaction(command = command, outboundData = outboundData)
    }

    /**
     * Executes a PTP command with automatic 50ms backoff retries when the camera returns `DeviceBusy` (`0x2019`).
     */
    suspend fun executeWithBusyRetry(
        opCode: UShort,
        params: List<UInt> = emptyList(),
        outboundData: ByteArray? = null,
        maxRetries: Int = maxDeviceBusyRetries,
        backoffMs: Long = deviceBusyBackoffMs
    ): PtpTransactionResult {
        var attempt = 0
        while (true) {
            val result = executeSerialized(
                opCode = opCode,
                params = params,
                outboundData = outboundData
            )
            if (!result.response.isDeviceBusy || attempt >= maxRetries) {
                return result
            }
            attempt++
            delay(backoffMs)
        }
    }

    /**
     * Connects [currentTransport] and opens a PTP session (`0x1001` GetDeviceInfo -> `0x1002` OpenSession).
     */
    suspend fun openSession(sessionId: UInt = 1u): Boolean {
        _sessionState.value = NikonSessionState.Connecting
        return runCatching {
            currentTransport.connect()

            // Query GetDeviceInfo (0x1001) to wake/verify the Nikon PTPResponder
            executeWithBusyRetry(opCode = PtpConstants.OP_GET_DEVICE_INFO)

            // OpenSession (0x1002) with DeviceBusy backoff
            val result = executeWithBusyRetry(
                opCode = PtpConstants.OP_OPEN_SESSION,
                params = listOf(sessionId)
            )
            val isOpen = result.isSuccess ||
                result.response.responseCode == PtpConstants.RES_SESSION_ALREADY_OPEN
            if (isOpen) {
                // Auto-detect camera's configured recording frame rate (0xD1A9 MovieFrameRate)
                queryCameraMovieFrameRate()

                _sessionState.value = NikonSessionState.Connected(
                    sessionId = sessionId,
                    transportName = currentTransport.transportName,
                    isLiveViewActive = false
                )
            } else {
                _sessionState.value = NikonSessionState.Error(
                    "OpenSession failed with code 0x${result.response.responseCode.toString(16)}"
                )
            }
            isOpen
        }.getOrElse { error ->
            _sessionState.value = NikonSessionState.Error(error.message ?: "Transport connection failed")
            false
        }
    }

    /**
     * Executes the complete Nikon LiveView startup sequence:
     * `GetDeviceInfo` (`0x1001`) ➔ `OpenSession` (`0x1002`) ➔ `StartLiveView` (`0x9201`) ➔ `GetLiveViewImage` (`0x9203`) loop.
     */
    suspend fun startCameraLiveViewSequence(
        sessionId: UInt = 1u,
        frameIntervalMs: Long = 16L
    ): Boolean {
        if (!openSession(sessionId)) return false
        return startLiveView(frameIntervalMs)
    }

    /**
     * Hot-swaps the underlying [PtpTransport] (e.g., from `MockPtpTransport` to physical `UsbPtpTransport`
     * on USB attach, or back to `MockPtpTransport` on USB detach) and restarts the LiveView session.
     */
    suspend fun swapTransport(
        newTransport: PtpTransport,
        sessionId: UInt = 1u,
        frameIntervalMs: Long = 16L,
        autoStartLiveView: Boolean = true
    ): Boolean {
        closeSession()
        transactionMutex.withLock {
            currentTransport = newTransport
            transactionSequence = 0u
        }
        return if (autoStartLiveView) {
            startCameraLiveViewSequence(
                sessionId = sessionId,
                frameIntervalMs = frameIntervalMs
            )
        } else {
            openSession(sessionId = sessionId)
        }
    }

    /**
     * Stops LiveView polling (`0x9202` EndLiveView), sends `0x1003` CloseSession, and disconnects the transport.
     */
    suspend fun closeSession() {
        val wasLiveViewActive = (_sessionState.value as? NikonSessionState.Connected)?.isLiveViewActive == true
        stopLiveViewPolling()
        if (wasLiveViewActive) {
            runCatching {
                executeSerialized(opCode = PtpConstants.OP_NIKON_END_LIVE_VIEW)
            }
        }
        runCatching {
            executeSerialized(opCode = PtpConstants.OP_CLOSE_SESSION)
        }
        runCatching {
            currentTransport.disconnect()
        }
        _sessionState.value = NikonSessionState.Disconnected
    }

    /**
     * Starts Nikon LiveView (`0x9201` StartLiveView) with `DeviceBusy` (`0x2019`) 50ms backoff retry
     * and begins the serialized polling loop for `0x9203` frames.
     */
    suspend fun startLiveView(frameIntervalMs: Long = 16L): Boolean {
        val startResult = executeWithBusyRetry(opCode = PtpConstants.OP_NIKON_START_LIVE_VIEW)
        if (!startResult.isSuccess) return false

        val current = _sessionState.value
        if (current is NikonSessionState.Connected) {
            _sessionState.value = current.copy(isLiveViewActive = true)
        }

        startLiveViewPolling(frameIntervalMs)
        return true
    }

    /**
     * Ends Nikon LiveView (`0x9202` EndLiveView) and cancels the polling coroutine.
     */
    suspend fun stopLiveView(): Boolean {
        stopLiveViewPolling()
        val endResult = executeWithBusyRetry(opCode = PtpConstants.OP_NIKON_END_LIVE_VIEW)
        val current = _sessionState.value
        if (current is NikonSessionState.Connected) {
            _sessionState.value = current.copy(isLiveViewActive = false)
        }
        return endResult.isSuccess
    }

    /**
     * Queries the camera's configured video frame rate via Nikon `GetDevicePropValue` (`0x1015`)
     * for `PROP_NIKON_MOVIE_FRAME_RATE` (`0xD1A9`), updating [pacingState].
     */
    suspend fun queryCameraMovieFrameRate(): TargetFrameRate {
        val detectedRate = runCatching {
            val result = executeWithBusyRetry(
                opCode = PtpConstants.OP_GET_DEVICE_PROP_VALUE,
                params = listOf(PtpConstants.PROP_NIKON_MOVIE_FRAME_RATE.toUInt()),
                maxRetries = 3,
                backoffMs = deviceBusyBackoffMs
            )
            if (result.isSuccess && result.dataPayload.isNotEmpty()) {
                val code = result.dataPayload[0].toInt() and 0xFF
                TargetFrameRate.fromNikonCode(code)
            } else {
                _pacingState.value.cameraConfiguredRate
            }
        }.getOrDefault(_pacingState.value.cameraConfiguredRate)

        updateCameraConfiguredFrameRate(detectedRate)
        return detectedRate
    }

    /**
     * Sets the active [FramePacingMode] (`AUTO_SYNC`, `MANUAL_24P`, `MANUAL_30P`, `MANUAL_MAX`)
     * and recalculates [FramePacingSnapshot.effectiveTargetRate].
     */
    fun setPacingMode(mode: FramePacingMode): FramePacingSnapshot {
        _pacingState.update { current ->
            val effective = mode.resolveTargetFrameRate(current.cameraConfiguredRate)
            current.copy(
                pacingMode = mode,
                effectiveTargetRate = effective,
                measuredFps = effective.fps,
                lastRemainingDelayNanos = effective.intervalNanos
            )
        }
        return _pacingState.value
    }

    /**
     * Cycles [FramePacingMode] (`Auto Sync` ➔ `24p` ➔ `30p` ➔ `Max` ➔ `Auto Sync`).
     */
    fun cyclePacingMode(): FramePacingSnapshot {
        return setPacingMode(_pacingState.value.pacingMode.next())
    }

    private fun updateCameraConfiguredFrameRate(rate: TargetFrameRate) {
        _pacingState.update { current ->
            if (current.cameraConfiguredRate == rate) return@update current
            val effective = current.pacingMode.resolveTargetFrameRate(rate)
            current.copy(
                cameraConfiguredRate = rate,
                effectiveTargetRate = effective,
                measuredFps = effective.fps,
                lastRemainingDelayNanos = effective.intervalNanos
            )
        }
    }

    /**
     * Requests a single LiveView frame (`0x9203` GetLiveViewImage).
     * If the camera responds with `DeviceBusy` (`0x2019`), backs off for [deviceBusyBackoffMs] (50ms) and retries.
     */
    suspend fun fetchSingleLiveViewFrame(): Result<LiveViewFrame> = runCatching {
        val result = executeWithBusyRetry(
            opCode = PtpConstants.OP_NIKON_GET_LIVE_VIEW_IMAGE,
            maxRetries = 5,
            backoffMs = deviceBusyBackoffMs
        )
        check(result.isSuccess) {
            "GetLiveViewImage failed with code 0x${result.response.responseCode.toString(16)}"
        }
        val frame = LiveViewHeaderParser.parse(result.dataPayload)
        updateCameraConfiguredFrameRate(frame.header.exposureInfo.movieFrameRate)
        _liveViewFrames.tryEmit(frame)
        frame
    }

    /**
     * Sends Nikon Touch AF coordinates (`0x9205` SetTouchAfArea).
     * Automatically queues via [transactionMutex] between LiveView polling frames.
     */
    suspend fun setTouchAfArea(x: UInt, y: UInt): PtpTransactionResult {
        return executeWithBusyRetry(
            opCode = PtpConstants.OP_NIKON_SET_TOUCH_AF_AREA,
            params = listOf(x, y)
        )
    }

    /**
     * Updates a camera property (`0x1016` SetDevicePropValue, e.g., Aperture `0x5007`, Shutter `0x500D`, ISO `0x500F`).
     */
    suspend fun setDevicePropertyValue(
        propCode: UShort,
        valuePayload: ByteArray
    ): PtpTransactionResult {
        return executeWithBusyRetry(
            opCode = PtpConstants.OP_SET_DEVICE_PROP_VALUE,
            params = listOf(propCode.toUInt()),
            outboundData = valuePayload
        )
    }

    /**
     * Fetches a byte range of an object (`0x101B` GetPartialObject).
     */
    suspend fun getPartialObject(
        objectHandle: UInt,
        offset: UInt,
        maxBytes: UInt
    ): PtpTransactionResult {
        return executeWithBusyRetry(
            opCode = PtpConstants.OP_GET_PARTIAL_OBJECT,
            params = listOf(objectHandle, offset, maxBytes)
        )
    }

    /**
     * Polls Nikon camera event queue (`0x90C7` GetEvent) and emits parsed events to [cameraEvents].
     */
    suspend fun pollNikonEvents(): List<PtpEventPacket> {
        val result = executeSerialized(opCode = PtpConstants.OP_NIKON_GET_EVENT)
        if (!result.isSuccess || result.dataPayload.isEmpty()) return emptyList()
        val events = PtpPacketSerializer.decodeNikonGetEventPayload(
            payload = result.dataPayload,
            transactionId = result.response.transactionId
        )
        events.forEach { _cameraEvents.tryEmit(it) }
        return events
    }

    private fun startLiveViewPolling(@Suppress("UNUSED_PARAMETER") frameIntervalMs: Long) {
        liveViewPollingJob?.cancel()
        liveViewPollingJob = scope.launch {
            while (isActive) {
                val targetRate = _pacingState.value.effectiveTargetRate
                var frameResult: Result<LiveViewFrame> = Result.failure(IllegalStateException("Not executed"))
                framePacer.executePacedFrame(targetRate = targetRate) {
                    frameResult = fetchSingleLiveViewFrame()
                }
                _pacingState.update { current ->
                    current.copy(
                        measuredFps = framePacer.measuredFps,
                        lastTransactionElapsedNanos = framePacer.lastElapsedNanos,
                        lastRemainingDelayNanos = framePacer.lastRemainingDelayNanos
                    )
                }
                if (frameResult.isFailure) {
                    // Backoff 50ms when camera is temporarily busy or switching modes
                    delay(deviceBusyBackoffMs)
                }
            }
        }
    }

    private fun stopLiveViewPolling() {
        liveViewPollingJob?.cancel()
        liveViewPollingJob = null
    }
}
