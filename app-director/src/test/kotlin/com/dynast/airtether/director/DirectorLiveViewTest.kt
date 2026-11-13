package com.dynast.airtether.director

import com.dynast.airtether.core.nikonptp.pacing.FramePacingMode
import com.dynast.airtether.core.nikonptp.pacing.TargetFrameRate
import com.dynast.airtether.core.nikonptp.parser.LiveViewHeaderParser
import com.dynast.airtether.core.nikonptp.session.NikonSessionManager
import com.dynast.airtether.core.nikonptp.transport.MockPtpTransport
import com.dynast.airtether.director.ui.DirectorIntent
import com.dynast.airtether.director.ui.DirectorLiveViewViewModel
import com.dynast.airtether.director.ui.gl.CubeLutParser
import com.dynast.airtether.director.ui.gl.NikonGlRenderer
import com.dynast.airtether.director.ui.model.AfFocusState
import com.dynast.airtether.director.ui.model.LiveViewTelemetry
import com.dynast.airtether.director.ui.model.PeakingColor
import com.dynast.airtether.director.ui.model.ViewportAspectRatio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectorLiveViewTest {

    @Test
    fun cubeLutParserParsesTitleSizeAndRgbFloatTable() {
        val sampleCube = """
            # Sample 2x2x2 3D LUT
            TITLE "Nikon_NLog_to_Rec709_Test"
            LUT_3D_SIZE 2
            DOMAIN_MIN 0.0 0.0 0.0
            DOMAIN_MAX 1.0 1.0 1.0
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            0.95 0.98 1.0
        """.trimIndent()

        val parsed = CubeLutParser.parse(sampleCube)
        assertEquals("Nikon_NLog_to_Rec709_Test", parsed.title)
        assertEquals(2, parsed.size)
        assertEquals(8, parsed.totalVertices)
        assertEquals(24, parsed.rgbBuffer.capacity())

        // Verify last RGB triple (0.95, 0.98, 1.0)
        assertEquals(0.95f, parsed.rgbBuffer.get(21), 0.001f)
        assertEquals(0.98f, parsed.rgbBuffer.get(22), 0.001f)
        assertEquals(1.0f, parsed.rgbBuffer.get(23), 0.001f)
    }

    @Test
    fun defaultNLogAndIdentity3dLutGeneratorsCreate33CubedTables() {
        val identityLut = CubeLutParser.createIdentityLut(size = 17)
        assertEquals(17, identityLut.size)
        assertEquals(17 * 17 * 17 * 3, identityLut.rgbBuffer.capacity())

        val nLogLut = CubeLutParser.createDefaultNLogToRec709Lut(size = 33)
        assertEquals(33, nLogLut.size)
        assertEquals(33 * 33 * 33 * 3, nLogLut.rgbBuffer.capacity())
    }

    @Test
    fun fragmentShaderContainsAllRequiredCinemaAssistUniformsAndStages() {
        val shader = NikonGlRenderer.FRAGMENT_SHADER_ES30
        assertTrue(shader.contains("#version 300 es"))
        assertTrue(shader.contains("uniform sampler2D uVideoTexture;"))
        assertTrue(shader.contains("uniform sampler3D uLutTexture;"))
        assertTrue(shader.contains("uniform float uLutIntensity;"))
        assertTrue(shader.contains("uniform int uEnableFalseColor;"))
        assertTrue(shader.contains("uniform int uEnableZebra;"))
        assertTrue(shader.contains("uniform float uZebraThreshold;"))
        assertTrue(shader.contains("uniform float uTime;"))
        assertTrue(shader.contains("uniform int uEnablePeaking;"))
        assertTrue(shader.contains("uniform float uPeakingThreshold;"))
        assertTrue(shader.contains("uniform vec3 uPeakingColor;"))
    }

    @Test
    fun normalizedTapMapsToNikonSensorCoordinatesAccurately() {
        val telemetry = LiveViewTelemetry(
            cameraCoordMaxWidth = 6048,
            cameraCoordMaxHeight = 4032
        )

        val (centerX, centerY) = telemetry.mapNormalizedTapToNikonCoordinates(0.5f, 0.5f)
        assertEquals(3024, centerX)
        assertEquals(2016, centerY)

        val (clampedX, clampedY) = telemetry.mapNormalizedTapToNikonCoordinates(1.4f, -0.2f)
        assertEquals(6048, clampedX)
        assertEquals(0, clampedY)
    }

    @Test
    fun fromNikonHeaderNormalizesAfCoordinatesAndTiltAngles() {
        val rawPayload = ByteArray(LiveViewHeaderParser.HEADER_SIZE + 4)
        LiveViewHeaderParser.writeUInt16Be(rawPayload, LiveViewHeaderParser.OFFSET_JPEG_WIDTH, 1024)
        LiveViewHeaderParser.writeUInt16Be(rawPayload, LiveViewHeaderParser.OFFSET_JPEG_HEIGHT, 680)
        LiveViewHeaderParser.writeUInt16Be(rawPayload, LiveViewHeaderParser.OFFSET_CAMERA_MAX_WIDTH, 6000)
        LiveViewHeaderParser.writeUInt16Be(rawPayload, LiveViewHeaderParser.OFFSET_CAMERA_MAX_HEIGHT, 4000)
        LiveViewHeaderParser.writeUInt16Be(rawPayload, LiveViewHeaderParser.OFFSET_AF_LEFT, 2700)
        LiveViewHeaderParser.writeUInt16Be(rawPayload, LiveViewHeaderParser.OFFSET_AF_TOP, 1800)
        LiveViewHeaderParser.writeUInt16Be(rawPayload, LiveViewHeaderParser.OFFSET_AF_WIDTH, 600)
        LiveViewHeaderParser.writeUInt16Be(rawPayload, LiveViewHeaderParser.OFFSET_AF_HEIGHT, 400)
        LiveViewHeaderParser.writeInt32Be(rawPayload, LiveViewHeaderParser.OFFSET_AF_STATUS, 1)
        LiveViewHeaderParser.writeInt32Be(rawPayload, LiveViewHeaderParser.OFFSET_ROLL_FIXED, -150)
        LiveViewHeaderParser.writeInt32Be(rawPayload, LiveViewHeaderParser.OFFSET_PITCH_FIXED, 200)
        rawPayload[LiveViewHeaderParser.HEADER_SIZE] = 0xFF.toByte()
        rawPayload[LiveViewHeaderParser.HEADER_SIZE + 1] = 0xD8.toByte()

        val parsedFrame = LiveViewHeaderParser.parse(rawPayload)
        val telemetry = LiveViewTelemetry.fromNikonHeader(parsedFrame.header, frameSequence = 7L)

        assertEquals(0.5f, telemetry.afNormalizedCenterX, 0.001f)
        assertEquals(0.5f, telemetry.afNormalizedCenterY, 0.001f)
        assertEquals(0.1f, telemetry.afNormalizedWidth, 0.001f)
        assertEquals(0.1f, telemetry.afNormalizedHeight, 0.001f)
        assertEquals(AfFocusState.LOCKED_IN_FOCUS, telemetry.afState)
        assertEquals(-1.5f, telemetry.rollDegrees, 0.001f)
        assertEquals(2.0f, telemetry.pitchDegrees, 0.001f)
        assertEquals(7L, telemetry.frameSequence)
    }

    @Test
    fun viewModelIntegratesWithMockPtpTransportAndHandlesIntents() = runTest {
        val mockTransport = MockPtpTransport()
        val sessionManager = NikonSessionManager(
            transport = mockTransport,
            scope = backgroundScope
        )
        val viewModel = DirectorLiveViewViewModel(
            sessionManager = sessionManager,
            enableTelemetrySimulation = false,
            customScope = CoroutineScope(Dispatchers.Unconfined)
        )

        // Touch AF updates high-frequency telemetryState without altering low-frequency uiState reference
        val stateBeforeTouch = viewModel.uiState.value
        viewModel.onIntent(DirectorIntent.TouchAf(nikonX = 1512, nikonY = 1008))
        assertEquals(0.25f, viewModel.telemetryState.afNormalizedCenterX, 0.001f)
        assertEquals(0.25f, viewModel.telemetryState.afNormalizedCenterY, 0.001f)
        assertEquals(AfFocusState.TRACKING_IDLE, viewModel.telemetryState.afState)
        assertEquals(stateBeforeTouch, viewModel.uiState.value)

        // Fetch a frame from sessionManager and verify ViewModel receives the parsed telemetry
        sessionManager.fetchSingleLiveViewFrame()
        assertTrue(viewModel.telemetryState.frameSequence >= 1L)

        // Toggle Shader & Guide options
        viewModel.onIntent(DirectorIntent.ToggleFocusPeaking)
        viewModel.onIntent(DirectorIntent.CyclePeakingColor)
        viewModel.onIntent(DirectorIntent.SetLutIntensity(0.75f))
        viewModel.onIntent(DirectorIntent.ToggleFalseColor)
        viewModel.onIntent(DirectorIntent.ToggleZebra)
        viewModel.onIntent(DirectorIntent.Toggle9x16Crop)
        viewModel.onIntent(DirectorIntent.ToggleBrightnessBoost)
        viewModel.onIntent(DirectorIntent.ToggleAspectRatio)
        viewModel.onIntent(DirectorIntent.ToggleCleanFeed)

        val updated = viewModel.uiState.value
        assertTrue(updated.shaderOptions.isFocusPeakingEnabled)
        assertEquals(PeakingColor.GREEN, updated.shaderOptions.peakingColor)
        assertEquals(0.75f, updated.shaderOptions.lutIntensity, 0.001f)
        assertTrue(updated.shaderOptions.isFalseColorEnabled)
        assertTrue(updated.shaderOptions.isZebraEnabled)
        assertEquals(0.95f, updated.shaderOptions.zebraThreshold, 0.001f)
        assertTrue(updated.guideOptions.isVertical9x16CropVisible)
        assertTrue(updated.isMaxBrightnessBoostEnabled)
        assertEquals(ViewportAspectRatio.PHOTO_3_2, updated.viewportAspectRatio)
        assertFalse(updated.isHudVisible)

        // Verify USB hot-swap updates cameraModel and restores fallback on detach
        val simulatedUsbTransport = MockPtpTransport()
        viewModel.attachHardwareUsbTransport(simulatedUsbTransport, "Nikon Zf (USB-C)")
        assertEquals("Nikon Zf (USB-C)", viewModel.uiState.value.cameraModel)

        viewModel.onHardwareUsbDetached()
        assertEquals("Nikon Z 6II (Mock 24p)", viewModel.uiState.value.cameraModel)

        // Verify Adaptive Frame Pacer HUD mode cycling (Auto Sync -> 24p -> 30p -> Max -> Auto Sync)
        assertEquals(FramePacingMode.AUTO_SYNC, viewModel.uiState.value.pacingMode)
        assertEquals(TargetFrameRate.FPS_23_976, viewModel.uiState.value.targetFrameRate)
        assertEquals("23.98 FPS (Sync)", viewModel.uiState.value.frameRateHudBadge)

        viewModel.onIntent(DirectorIntent.CycleFramePacingMode)
        assertEquals(FramePacingMode.MANUAL_24P, viewModel.uiState.value.pacingMode)
        assertEquals("23.98 FPS (24p)", viewModel.uiState.value.frameRateHudBadge)

        viewModel.onIntent(DirectorIntent.CycleFramePacingMode)
        assertEquals(FramePacingMode.MANUAL_30P, viewModel.uiState.value.pacingMode)
        assertEquals(TargetFrameRate.FPS_29_97, viewModel.uiState.value.targetFrameRate)
        assertEquals("29.97 FPS (30p)", viewModel.uiState.value.frameRateHudBadge)

        viewModel.onIntent(DirectorIntent.CycleFramePacingMode)
        assertEquals(FramePacingMode.MANUAL_MAX, viewModel.uiState.value.pacingMode)
        assertEquals(TargetFrameRate.FPS_59_94, viewModel.uiState.value.targetFrameRate)
        assertEquals("59.94 FPS (Max)", viewModel.uiState.value.frameRateHudBadge)

        viewModel.onIntent(DirectorIntent.CycleFramePacingMode)
        assertEquals(FramePacingMode.AUTO_SYNC, viewModel.uiState.value.pacingMode)
    }
}
