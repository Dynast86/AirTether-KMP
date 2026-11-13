package com.dynast.airtether.director.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dynast.airtether.core.nikonptp.pacing.FramePacingMode
import com.dynast.airtether.core.nikonptp.pacing.TargetFrameRate
import com.dynast.airtether.core.nikonptp.session.NikonSessionManager
import com.dynast.airtether.core.nikonptp.transport.MockPtpTransport
import com.dynast.airtether.core.nikonptp.transport.PtpTransport
import com.dynast.airtether.director.ui.components.LiveViewHudOverlay
import com.dynast.airtether.director.ui.components.TelemetryOverlayCanvas
import com.dynast.airtether.director.ui.gl.NikonGlSurfaceView
import com.dynast.airtether.director.ui.model.AfFocusState
import com.dynast.airtether.director.ui.model.LiveViewTelemetry
import com.dynast.airtether.director.ui.model.LiveViewUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * MVI User Intents for `:app-director` LiveView Monitor.
 */
sealed interface DirectorIntent {
    data class TouchAf(val nikonX: Int, val nikonY: Int) : DirectorIntent
    data object ToggleCleanFeed : DirectorIntent
    data object ToggleRecording : DirectorIntent
    data object ToggleAfMode : DirectorIntent
    data object ToggleAspectRatio : DirectorIntent
    data object CycleFramePacingMode : DirectorIntent
    data class SetFramePacingMode(val mode: FramePacingMode) : DirectorIntent
    data class SetMockCameraFrameRate(val rate: TargetFrameRate) : DirectorIntent
    data object CycleFStop : DirectorIntent
    data object CycleShutter : DirectorIntent
    data object CycleIso : DirectorIntent
    data object CycleWhiteBalance : DirectorIntent
    data object ToggleLut : DirectorIntent
    data class SetLutIntensity(val intensity: Float) : DirectorIntent
    data object ToggleFocusPeaking : DirectorIntent
    data object CyclePeakingColor : DirectorIntent
    data object ToggleFalseColor : DirectorIntent
    data object ToggleZebra : DirectorIntent
    data object ToggleGrid : DirectorIntent
    data object Toggle9x16Crop : DirectorIntent
    data object ToggleBrightnessBoost : DirectorIntent
}

/**
 * MVI ViewModel for `:app-director`.
 *
 * Connects to `:core:nikon-ptp`'s [NikonSessionManager] backed by [MockPtpTransport] (by default when no
 * physical USB transport is provided) to drive a real 60fps (`16ms` interval) `0x9203` `GetLiveViewImage`
 * binary header + JPEG parsing pipeline.
 */
class DirectorLiveViewViewModel(
    sessionManager: NikonSessionManager? = null,
    enableTelemetrySimulation: Boolean = true,
    customScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope = customScope ?: runCatching {
        viewModelScope
    }.getOrElse {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    private val mockTransport: MockPtpTransport? = if (sessionManager == null) {
        MockPtpTransport()
    } else {
        null
    }

    private val activeSessionManager: NikonSessionManager = sessionManager ?: NikonSessionManager(
        transport = checkNotNull(mockTransport),
        scope = scope
    )

    private val _uiState = MutableStateFlow(LiveViewUiState())
    val uiState: StateFlow<LiveViewUiState> = _uiState.asStateFlow()

    // High-frequency Compose State read ONLY inside TelemetryOverlayCanvas's DrawScope
    var telemetryState by mutableStateOf(LiveViewTelemetry())
        private set

    // Raw JPEG stream piped directly into NikonGlSurfaceView without touching Compose state
    private val _jpegFrames = MutableSharedFlow<ByteArray>(replay = 1, extraBufferCapacity = 2)
    val jpegFrames: SharedFlow<ByteArray> = _jpegFrames.asSharedFlow()

    private var fStopIdx = 1
    private var shutterIdx = 1
    private var isoIdx = 2
    private var wbIdx = 0

    init {
        observeNikonSession()
        if (enableTelemetrySimulation) {
            startLiveViewSessionLoop()
        }
    }

    fun onIntent(intent: DirectorIntent) {
        when (intent) {
            is DirectorIntent.TouchAf -> handleTouchAf(intent.nikonX, intent.nikonY)
            DirectorIntent.ToggleCleanFeed -> {
                _uiState.update { it.copy(isHudVisible = !it.isHudVisible) }
            }
            DirectorIntent.ToggleRecording -> {
                val nextRec = !_uiState.value.isRecording
                mockTransport?.isMovieRecording = nextRec
                _uiState.update { it.copy(isRecording = nextRec) }
            }
            DirectorIntent.ToggleAfMode -> {
                _uiState.update { state ->
                    val nextAf = if (state.exposure.afMode == "AF-C") "AF-S" else "AF-C"
                    state.copy(exposure = state.exposure.copy(afMode = nextAf))
                }
            }
            DirectorIntent.ToggleAspectRatio -> {
                _uiState.update { state ->
                    state.copy(viewportAspectRatio = state.viewportAspectRatio.next())
                }
            }
            DirectorIntent.CycleFramePacingMode -> {
                val snapshot = activeSessionManager.cyclePacingMode()
                _uiState.update { state ->
                    state.copy(
                        pacingMode = snapshot.pacingMode,
                        targetFrameRate = snapshot.effectiveTargetRate,
                        frameRateHudBadge = snapshot.formattedHudBadge
                    )
                }
            }
            is DirectorIntent.SetFramePacingMode -> {
                val snapshot = activeSessionManager.setPacingMode(intent.mode)
                _uiState.update { state ->
                    state.copy(
                        pacingMode = snapshot.pacingMode,
                        targetFrameRate = snapshot.effectiveTargetRate,
                        frameRateHudBadge = snapshot.formattedHudBadge
                    )
                }
            }
            is DirectorIntent.SetMockCameraFrameRate -> {
                mockTransport?.configuredFrameRate = intent.rate
                scope.launch {
                    activeSessionManager.queryCameraMovieFrameRate()
                }
            }
            DirectorIntent.CycleFStop -> {
                fStopIdx = (fStopIdx + 1) % F_STOP_HUNDREDTHS.size
                val nextHundredths = F_STOP_HUNDREDTHS[fStopIdx]
                mockTransport?.fNumberHundredths = nextHundredths
                _uiState.update { state ->
                    state.copy(exposure = state.exposure.copy(fStop = F_STOP_LABELS[fStopIdx]))
                }
            }
            DirectorIntent.CycleShutter -> {
                shutterIdx = (shutterIdx + 1) % SHUTTER_DENOMINATORS.size
                val nextDen = SHUTTER_DENOMINATORS[shutterIdx]
                mockTransport?.shutterDenominator = nextDen
                _uiState.update { state ->
                    state.copy(exposure = state.exposure.copy(shutterSpeed = "1/$nextDen"))
                }
            }
            DirectorIntent.CycleIso -> {
                isoIdx = (isoIdx + 1) % ISO_VALUES.size
                val nextIso = ISO_VALUES[isoIdx]
                mockTransport?.isoSensitivity = nextIso
                _uiState.update { state ->
                    state.copy(exposure = state.exposure.copy(iso = "ISO $nextIso"))
                }
            }
            DirectorIntent.CycleWhiteBalance -> {
                wbIdx = (wbIdx + 1) % WB_PRESETS.size
                _uiState.update { state ->
                    state.copy(exposure = state.exposure.copy(whiteBalance = WB_PRESETS[wbIdx]))
                }
            }
            DirectorIntent.ToggleLut -> {
                _uiState.update { state ->
                    state.copy(
                        shaderOptions = state.shaderOptions.copy(
                            isLutEnabled = !state.shaderOptions.isLutEnabled
                        )
                    )
                }
            }
            is DirectorIntent.SetLutIntensity -> {
                _uiState.update { state ->
                    state.copy(
                        shaderOptions = state.shaderOptions.copy(
                            lutIntensity = intent.intensity.coerceIn(0f, 1f)
                        )
                    )
                }
            }
            DirectorIntent.ToggleFocusPeaking -> {
                _uiState.update { state ->
                    state.copy(
                        shaderOptions = state.shaderOptions.copy(
                            isFocusPeakingEnabled = !state.shaderOptions.isFocusPeakingEnabled
                        )
                    )
                }
            }
            DirectorIntent.CyclePeakingColor -> {
                _uiState.update { state ->
                    state.copy(
                        shaderOptions = state.shaderOptions.copy(
                            peakingColor = state.shaderOptions.peakingColor.next()
                        )
                    )
                }
            }
            DirectorIntent.ToggleFalseColor -> {
                _uiState.update { state ->
                    state.copy(
                        shaderOptions = state.shaderOptions.copy(
                            isFalseColorEnabled = !state.shaderOptions.isFalseColorEnabled
                        )
                    )
                }
            }
            DirectorIntent.ToggleZebra -> {
                _uiState.update { state ->
                    state.copy(
                        shaderOptions = state.shaderOptions.copy(
                            isZebraEnabled = !state.shaderOptions.isZebraEnabled
                        )
                    )
                }
            }
            DirectorIntent.ToggleGrid -> {
                _uiState.update { state ->
                    state.copy(
                        guideOptions = state.guideOptions.copy(
                            isRuleOfThirdsVisible = !state.guideOptions.isRuleOfThirdsVisible
                        )
                    )
                }
            }
            DirectorIntent.Toggle9x16Crop -> {
                _uiState.update { state ->
                    state.copy(
                        guideOptions = state.guideOptions.copy(
                            isVertical9x16CropVisible = !state.guideOptions.isVertical9x16CropVisible
                        )
                    )
                }
            }
            DirectorIntent.ToggleBrightnessBoost -> {
                _uiState.update { state ->
                    state.copy(isMaxBrightnessBoostEnabled = !state.isMaxBrightnessBoostEnabled)
                }
            }
        }
    }

    private fun handleTouchAf(nikonX: Int, nikonY: Int) {
        val current = telemetryState
        val normX = (nikonX.toFloat() / current.cameraCoordMaxWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
        val normY = (nikonY.toFloat() / current.cameraCoordMaxHeight.coerceAtLeast(1)).coerceIn(0f, 1f)

        telemetryState = current.copy(
            afNormalizedCenterX = normX,
            afNormalizedCenterY = normY,
            afState = AfFocusState.TRACKING_IDLE,
            frameSequence = current.frameSequence + 1
        )

        // Queue `0x9205` SetTouchAfArea command through NikonSessionManager's transaction Mutex
        scope.launch {
            activeSessionManager.setTouchAfArea(nikonX.toUInt(), nikonY.toUInt())
        }
    }

    private fun startLiveViewSessionLoop() {
        scope.launch {
            activeSessionManager.startCameraLiveViewSequence(
                sessionId = 1u,
                frameIntervalMs = 16L
            )
        }
    }

    /**
     * Hot-swaps the active session from `MockPtpTransport` to a physical `UsbPtpTransport`
     * and initiates `GetDeviceInfo` (0x1001) -> `OpenSession` (0x1002) -> `StartLiveView` (0x9201) -> `0x9203` loop.
     */
    fun attachHardwareUsbTransport(
        usbTransport: PtpTransport,
        cameraModelName: String = "Nikon USB-C"
    ) {
        scope.launch {
            val connected = activeSessionManager.swapTransport(
                newTransport = usbTransport,
                sessionId = 1u,
                frameIntervalMs = 16L,
                autoStartLiveView = true
            )
            _uiState.update { state ->
                state.copy(
                    cameraModel = cameraModelName,
                    isUsbConnected = connected
                )
            }
        }
    }

    /**
     * Safely closes the physical USB session (`EndLiveView` 0x9202 -> `CloseSession` 0x1003)
     * and falls back to the virtual 60fps `MockPtpTransport`.
     */
    fun onHardwareUsbDetached() {
        val fallback = mockTransport ?: MockPtpTransport()
        scope.launch {
            activeSessionManager.swapTransport(
                newTransport = fallback,
                sessionId = 1u,
                frameIntervalMs = 16L,
                autoStartLiveView = true
            )
            _uiState.update { state ->
                state.copy(
                    cameraModel = "Nikon Z 6II (Mock 24p)",
                    isUsbConnected = true
                )
            }
        }
    }

    /**
     * Sends `EndLiveView` (`0x9202`) and `CloseSession` (`0x1003`) before releasing the transport.
     */
    fun shutdownSession() {
        scope.launch {
            activeSessionManager.closeSession()
        }
    }

    override fun onCleared() {
        shutdownSession()
        super.onCleared()
    }

    private fun observeNikonSession() {
        // Observe adaptive frame pacing state and update HUD / Surface frame rate only when pacing changes
        scope.launch {
            activeSessionManager.pacingState.collect { snapshot ->
                val current = _uiState.value
                val badge = snapshot.formattedHudBadge
                if (current.pacingMode != snapshot.pacingMode ||
                    current.targetFrameRate != snapshot.effectiveTargetRate ||
                    current.frameRateHudBadge != badge
                ) {
                    _uiState.update { state ->
                        state.copy(
                            pacingMode = snapshot.pacingMode,
                            targetFrameRate = snapshot.effectiveTargetRate,
                            frameRateHudBadge = badge
                        )
                    }
                }
            }
        }

        scope.launch {
            var seq = 0L
            activeSessionManager.liveViewFrames.collect { frame ->
                seq++
                // 1. Update high-frequency telemetry (read only inside TelemetryOverlayCanvas DrawScope)
                telemetryState = LiveViewTelemetry.fromNikonHeader(
                    header = frame.header,
                    frameSequence = seq
                )

                // 2. Update low-frequency HUD state ONLY when exposure or 1-second timecode changes
                val exp = frame.header.exposureInfo
                val currentHud = _uiState.value
                val newTimecode = exp.formattedTimecode
                val newFStop = exp.formattedFStop
                val newShutter = exp.formattedShutterSpeed
                val newIso = exp.formattedIso

                if (currentHud.timecode != newTimecode ||
                    currentHud.isRecording != exp.isMovieRecording ||
                    currentHud.exposure.fStop != newFStop ||
                    currentHud.exposure.shutterSpeed != newShutter ||
                    currentHud.exposure.iso != newIso
                ) {
                    _uiState.update { state ->
                        state.copy(
                            isRecording = exp.isMovieRecording,
                            timecode = newTimecode,
                            exposure = state.exposure.copy(
                                fStop = newFStop,
                                shutterSpeed = newShutter,
                                iso = newIso
                            )
                        )
                    }
                }

                // 3. Emit raw JPEG frame to Layer 1 OpenGL ES 3.0 renderer
                if (frame.jpegBytes.isNotEmpty()) {
                    _jpegFrames.tryEmit(frame.jpegBytes)
                }
            }
        }
    }

    companion object {
        private val F_STOP_HUNDREDTHS = listOf(140, 180, 280, 400, 560, 800)
        private val F_STOP_LABELS = listOf("f/1.4", "f/1.8", "f/2.8", "f/4.0", "f/5.6", "f/8.0")
        private val SHUTTER_DENOMINATORS = listOf(24, 50, 60, 100, 125, 250)
        private val ISO_VALUES = listOf(100, 400, 800, 1600, 3200, 6400)
        private val WB_PRESETS = listOf("5600K", "3200K", "4500K", "6500K", "AUTO")
    }
}

typealias DirectorViewModel = DirectorLiveViewViewModel

/**
 * Main 3-Layer Z-Index LiveView Monitoring Screen for `:app-director`.
 *
 * - **Layer 1 (`zIndex = 1f`)**: OpenGL ES 3.0 `NikonGlSurfaceView` (`RENDERMODE_WHEN_DIRTY`)
 * - **Layer 2 (`zIndex = 2f`)**: `TelemetryOverlayCanvas` (State Read Deferral inside `DrawScope` + Touch AF mapping)
 * - **Layer 3 (`zIndex = 3f`)**: `LiveViewHudOverlay` (Top cinema status bar + Bottom exposure/assist control dock)
 */
@Composable
fun DirectorLiveViewScreen(
    viewModel: DirectorLiveViewViewModel = viewModel { DirectorLiveViewViewModel() },
    onBrightnessBoostChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hostView = LocalView.current

    // Keep screen on continuously while monitoring
    DisposableEffect(hostView) {
        val previousKeepScreenOn = hostView.keepScreenOn
        hostView.keepScreenOn = true
        onDispose {
            hostView.keepScreenOn = previousKeepScreenOn
        }
    }

    // Notify host Activity when outdoor Max Brightness Boost (1.0f) is toggled
    LaunchedEffect(uiState.isMaxBrightnessBoostEnabled) {
        onBrightnessBoostChanged(uiState.isMaxBrightnessBoostEnabled)
    }

    var glSurfaceViewRef by remember { mutableStateOf<NikonGlSurfaceView?>(null) }

    // Pipe raw JPEG frames directly to the GL thread without triggering Compose Recomposition
    LaunchedEffect(glSurfaceViewRef) {
        val surfaceView = glSurfaceViewRef ?: return@LaunchedEffect
        viewModel.jpegFrames.collect { jpegBytes ->
            surfaceView.submitJpegFrame(jpegBytes)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Center Camera Viewport Container preserving true sensor aspect ratio (16:9 Video or 3:2 Photo)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .aspectRatio(uiState.viewportAspectRatio.ratio)
                .fillMaxSize()
        ) {
            // LAYER 1 (Z-Index 1): GPU Video & Fragment Shader Viewport (AndroidView + GLSurfaceView)
            AndroidView(
                factory = { context ->
                    NikonGlSurfaceView(context).also { glView ->
                        glView.updateShaderOptions(uiState.shaderOptions)
                        glView.updateTargetFrameRate(uiState.targetFrameRate.fps)
                        glSurfaceViewRef = glView
                    }
                },
                update = { glView ->
                    glView.updateShaderOptions(uiState.shaderOptions)
                    glView.updateTargetFrameRate(uiState.targetFrameRate.fps)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
            )

            // LAYER 2 (Z-Index 2): Vector Telemetry Overlay & Touch AF Gesture Mapper
            TelemetryOverlayCanvas(
                telemetryProvider = { viewModel.telemetryState },
                guideOptions = uiState.guideOptions,
                onTouchAf = { nikonX, nikonY ->
                    viewModel.onIntent(DirectorIntent.TouchAf(nikonX, nikonY))
                },
                onToggleCleanFeed = {
                    viewModel.onIntent(DirectorIntent.ToggleCleanFeed)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2f)
            )
        }

        // LAYER 3 (Z-Index 3): Top & Bottom Cinema Camera Control HUD Overlay
        LiveViewHudOverlay(
            uiState = uiState,
            onToggleRecording = { viewModel.onIntent(DirectorIntent.ToggleRecording) },
            onToggleAfMode = { viewModel.onIntent(DirectorIntent.ToggleAfMode) },
            onToggleAspectRatio = { viewModel.onIntent(DirectorIntent.ToggleAspectRatio) },
            onCycleFramePacingMode = { viewModel.onIntent(DirectorIntent.CycleFramePacingMode) },
            onCycleFStop = { viewModel.onIntent(DirectorIntent.CycleFStop) },
            onCycleShutter = { viewModel.onIntent(DirectorIntent.CycleShutter) },
            onCycleIso = { viewModel.onIntent(DirectorIntent.CycleIso) },
            onCycleWhiteBalance = { viewModel.onIntent(DirectorIntent.CycleWhiteBalance) },
            onToggleLut = { viewModel.onIntent(DirectorIntent.ToggleLut) },
            onToggleFocusPeaking = { viewModel.onIntent(DirectorIntent.ToggleFocusPeaking) },
            onToggleFalseColor = { viewModel.onIntent(DirectorIntent.ToggleFalseColor) },
            onToggleZebra = { viewModel.onIntent(DirectorIntent.ToggleZebra) },
            onToggleGrid = { viewModel.onIntent(DirectorIntent.ToggleGrid) },
            onToggle9x16Crop = { viewModel.onIntent(DirectorIntent.Toggle9x16Crop) },
            onToggleBrightnessBoost = { viewModel.onIntent(DirectorIntent.ToggleBrightnessBoost) },
            onToggleCleanFeed = { viewModel.onIntent(DirectorIntent.ToggleCleanFeed) },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(3f)
        )
    }
}
