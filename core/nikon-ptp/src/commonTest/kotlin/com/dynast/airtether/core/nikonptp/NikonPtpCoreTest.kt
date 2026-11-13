package com.dynast.airtether.core.nikonptp

import com.dynast.airtether.core.nikonptp.pacing.FramePacer
import com.dynast.airtether.core.nikonptp.pacing.FramePacingMode
import com.dynast.airtether.core.nikonptp.pacing.TargetFrameRate
import com.dynast.airtether.core.nikonptp.parser.LiveViewHeaderParser
import com.dynast.airtether.core.nikonptp.parser.NikonAfStatus
import com.dynast.airtether.core.nikonptp.protocol.PtpCommandPacket
import com.dynast.airtether.core.nikonptp.protocol.PtpConstants
import com.dynast.airtether.core.nikonptp.protocol.PtpContainerType
import com.dynast.airtether.core.nikonptp.protocol.PtpEventPacket
import com.dynast.airtether.core.nikonptp.protocol.PtpPacketSerializer
import com.dynast.airtether.core.nikonptp.protocol.PtpResponsePacket
import com.dynast.airtether.core.nikonptp.protocol.PtpTransactionResult
import com.dynast.airtether.core.nikonptp.session.NikonSessionManager
import com.dynast.airtether.core.nikonptp.transport.MockPtpTransport
import com.dynast.airtether.core.nikonptp.transport.PtpTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NikonPtpCoreTest {

    @Test
    fun commandPacketEncodesAndDecodesLittleEndianHeaderCorrectly() {
        val command = PtpCommandPacket(
            opCode = PtpConstants.OP_GET_PARTIAL_OBJECT,
            transactionId = 42u,
            params = listOf(0x29100001u, 0x00001000u, 0x00040000u)
        )

        val bytes = command.toLittleEndianBytes()
        assertEquals(24, bytes.size) // 12B header + 3 * 4B params

        val header = PtpPacketSerializer.decodeHeader(bytes)
        assertEquals(24, header.length)
        assertEquals(PtpContainerType.COMMAND, header.type)
        assertEquals(PtpConstants.OP_GET_PARTIAL_OBJECT, header.code)
        assertEquals(42u, header.transactionId)
    }

    @Test
    fun liveViewHeaderParserDecodesBigEndianFieldsAndScansForJpegSoi() {
        val fakeJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0x01, 0x02, 0x03,
            0xFF.toByte(), 0xD9.toByte()
        )
        val customSoiOffset = 390
        val rawPayload = ByteArray(customSoiOffset + fakeJpeg.size)

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
        LiveViewHeaderParser.writeInt32Be(rawPayload, LiveViewHeaderParser.OFFSET_PITCH_FIXED, 75)
        LiveViewHeaderParser.writeUInt16Be(rawPayload, LiveViewHeaderParser.OFFSET_F_NUMBER, 280)
        LiveViewHeaderParser.writeUInt16Be(rawPayload, LiveViewHeaderParser.OFFSET_SHUTTER_NUM, 1)
        LiveViewHeaderParser.writeUInt16Be(rawPayload, LiveViewHeaderParser.OFFSET_SHUTTER_DEN, 100)
        LiveViewHeaderParser.writeInt32Be(rawPayload, LiveViewHeaderParser.OFFSET_ISO, 1600)
        LiveViewHeaderParser.writeInt32Be(rawPayload, LiveViewHeaderParser.OFFSET_MOVIE_REC_TIME, 125)
        rawPayload[LiveViewHeaderParser.OFFSET_MOVIE_REC_STATE] = 1

        fakeJpeg.copyInto(rawPayload, destinationOffset = customSoiOffset)

        val parsedFrame = LiveViewHeaderParser.parse(rawPayload)
        val header = parsedFrame.header

        assertEquals(customSoiOffset, header.jpegOffset)
        assertEquals(fakeJpeg.size, header.jpegSize)
        assertEquals(1024, header.jpegDisplaySize.width)
        assertEquals(680, header.jpegDisplaySize.height)
        assertEquals(6000, header.cameraCoordMax.width)
        assertEquals(4000, header.cameraCoordMax.height)
        assertEquals(0.5f, header.afAreaRect.normCenterX, 0.001f)
        assertEquals(0.5f, header.afAreaRect.normCenterY, 0.001f)
        assertEquals(NikonAfStatus.MATCHED_GREEN, header.afStatus)
        assertEquals(-1.5f, header.levelAngle.rollDegrees, 0.001f)
        assertEquals(0.75f, header.levelAngle.pitchDegrees, 0.001f)
        assertEquals("f/2.8", header.exposureInfo.formattedFStop)
        assertEquals("1/100", header.exposureInfo.formattedShutterSpeed)
        assertEquals("ISO 1600", header.exposureInfo.formattedIso)
        assertEquals("00:02:05:00", header.exposureInfo.formattedTimecode)
        assertContentEquals(fakeJpeg, parsedFrame.jpegBytes)
    }

    @Test
    fun mockPtpTransportDrivesTouchAfAndProducesValidLiveViewFrames() = runTest {
        val mockTransport = MockPtpTransport()
        val sessionManager = NikonSessionManager(
            transport = mockTransport,
            scope = backgroundScope
        )

        assertTrue(sessionManager.openSession(1u))

        val initialFrame = sessionManager.fetchSingleLiveViewFrame().getOrThrow()
        assertEquals(NikonAfStatus.MATCHED_GREEN, initialFrame.header.afStatus)
        assertEquals(0xFF.toByte(), initialFrame.jpegBytes[0])
        assertEquals(0xD8.toByte(), initialFrame.jpegBytes[1])

        sessionManager.setTouchAfArea(x = 1500u, y = 1000u)

        val searchingFrame = sessionManager.fetchSingleLiveViewFrame().getOrThrow()
        assertEquals(NikonAfStatus.SEARCHING, searchingFrame.header.afStatus)

        repeat(8) {
            sessionManager.fetchSingleLiveViewFrame().getOrThrow()
        }
        val lockedFrame = sessionManager.fetchSingleLiveViewFrame().getOrThrow()
        assertEquals(NikonAfStatus.MATCHED_GREEN, lockedFrame.header.afStatus)
        assertEquals(1500f / 6048f, lockedFrame.header.afAreaRect.normCenterX, 0.02f)
        assertEquals(1000f / 4032f, lockedFrame.header.afAreaRect.normCenterY, 0.02f)
    }

    @Test
    fun nikonSessionManagerRetriesOnDeviceBusyAndSupportsTransportHotSwap() = runTest {
        val initialMock = MockPtpTransport()
        val busySimulatedUsb = FakeMutexVerifyingTransport(simulateInitialBusyCount = 3)
        val sessionManager = NikonSessionManager(
            transport = initialMock,
            scope = backgroundScope
        )

        assertTrue(sessionManager.openSession(1u))
        assertEquals(initialMock, sessionManager.currentTransport)

        // Hot-swap from MockPtpTransport to busySimulatedUsb (which returns 0x2019 DeviceBusy 3 times before 0x2001 OK)
        val swapped = sessionManager.swapTransport(
            newTransport = busySimulatedUsb,
            sessionId = 1u,
            autoStartLiveView = false
        )
        assertTrue(swapped)
        assertEquals(busySimulatedUsb, sessionManager.currentTransport)
        // 3 DeviceBusy retries + 1 GetDeviceInfo OK + 1 OpenSession OK + 1 GetDevicePropValue (MovieFrameRate) OK = 6 transactions
        assertEquals(6, busySimulatedUsb.recordedTransactionIds.size)
    }

    @Test
    fun nikonSessionManagerSerializesConcurrentTransactionsWithMutex() = runTest {
        val fakeTransport = FakeMutexVerifyingTransport()
        val sessionManager = NikonSessionManager(
            transport = fakeTransport,
            scope = backgroundScope
        )

        // openSession executes GetDeviceInfo (txId=1) + OpenSession (txId=2) + GetDevicePropValue (txId=3)
        assertTrue(sessionManager.openSession(sessionId = 1u))

        val jobs = listOf(
            async { sessionManager.fetchSingleLiveViewFrame() },
            async { sessionManager.setTouchAfArea(x = 3000u, y = 2000u) },
            async { sessionManager.fetchSingleLiveViewFrame() },
            async { sessionManager.getPartialObject(objectHandle = 100u, offset = 0u, maxBytes = 4096u) }
        )
        jobs.awaitAll()

        assertEquals(0, fakeTransport.overlapCount)
        assertEquals(listOf(1u, 2u, 3u, 4u, 5u, 6u, 7u), fakeTransport.recordedTransactionIds)
    }

    @Test
    fun adaptiveFramePacerCalculatesExactDelayAndAutoSyncsCameraFrameRate() = runTest {
        // 1. Verify exact cinema nanosecond intervals
        assertEquals(41_708_375L, TargetFrameRate.FPS_23_976.intervalNanos)
        assertEquals(40_000_000L, TargetFrameRate.FPS_25.intervalNanos)
        assertEquals(33_366_700L, TargetFrameRate.FPS_29_97.intervalNanos)
        assertEquals(16_683_350L, TargetFrameRate.FPS_59_94.intervalNanos)
        assertEquals(
            TargetFrameRate.FPS_29_97.intervalNanos,
            TargetFrameRate.FPS_59_94.effectiveIntervalNanos(capHighFpsForBandwidth = true)
        )

        // 2. Verify FramePacer remaining delay calculation (targetIntervalNanos - elapsedNanos)
        val pacer = FramePacer()
        val remaining24p = pacer.computeRemainingDelayNanos(
            targetIntervalNanos = TargetFrameRate.FPS_23_976.intervalNanos,
            elapsedNanos = 11_708_375L
        )
        assertEquals(30_000_000L, remaining24p)
        assertEquals(
            0L,
            pacer.computeRemainingDelayNanos(
                targetIntervalNanos = TargetFrameRate.FPS_23_976.intervalNanos,
                elapsedNanos = 50_000_000L
            )
        )

        // 3. Verify NikonSessionManager auto-syncs with MockPtpTransport (default 23.976fps) and runtime changes
        val mockTransport = MockPtpTransport()
        val sessionManager = NikonSessionManager(
            transport = mockTransport,
            scope = backgroundScope
        )

        assertTrue(sessionManager.openSession(1u))
        assertEquals(TargetFrameRate.FPS_23_976, sessionManager.pacingState.value.cameraConfiguredRate)
        assertEquals(TargetFrameRate.FPS_23_976, sessionManager.pacingState.value.effectiveTargetRate)
        assertEquals("23.98 FPS (Sync)", sessionManager.pacingState.value.formattedHudBadge)

        // Change camera recording frame rate at runtime to 29.97fps and fetch a frame
        mockTransport.configuredFrameRate = TargetFrameRate.FPS_29_97
        sessionManager.fetchSingleLiveViewFrame().getOrThrow()
        assertEquals(TargetFrameRate.FPS_29_97, sessionManager.pacingState.value.cameraConfiguredRate)
        assertEquals(TargetFrameRate.FPS_29_97, sessionManager.pacingState.value.effectiveTargetRate)
        assertEquals("29.97 FPS (Sync)", sessionManager.pacingState.value.formattedHudBadge)

        // Cycle manual pacing modes (Sync -> 24p -> 30p -> Max -> Sync)
        assertEquals(FramePacingMode.MANUAL_24P, sessionManager.cyclePacingMode().pacingMode)
        assertEquals("23.98 FPS (24p)", sessionManager.pacingState.value.formattedHudBadge)

        assertEquals(FramePacingMode.MANUAL_30P, sessionManager.cyclePacingMode().pacingMode)
        assertEquals("29.97 FPS (30p)", sessionManager.pacingState.value.formattedHudBadge)

        assertEquals(FramePacingMode.MANUAL_MAX, sessionManager.cyclePacingMode().pacingMode)
        assertEquals("59.94 FPS (Max)", sessionManager.pacingState.value.formattedHudBadge)

        assertEquals(FramePacingMode.AUTO_SYNC, sessionManager.cyclePacingMode().pacingMode)
        assertEquals("29.97 FPS (Sync)", sessionManager.pacingState.value.formattedHudBadge)
    }

    private class FakeMutexVerifyingTransport(
        private var simulateInitialBusyCount: Int = 0
    ) : PtpTransport {
        override val transportName: String = "FakeTestTransport"
        override val isConnected: StateFlow<Boolean> = MutableStateFlow(true)
        override val incomingEvents: Flow<PtpEventPacket> = emptyFlow()

        private var inFlightCount = 0
        var overlapCount = 0
        val recordedTransactionIds = mutableListOf<UInt>()

        override suspend fun connect() = Unit
        override suspend fun disconnect() = Unit

        override suspend fun executeTransaction(
            command: PtpCommandPacket,
            outboundData: ByteArray?
        ): PtpTransactionResult {
            inFlightCount++
            if (inFlightCount > 1) {
                overlapCount++
            }
            recordedTransactionIds.add(command.transactionId)

            delay(10L)

            if (simulateInitialBusyCount > 0) {
                simulateInitialBusyCount--
                inFlightCount--
                return PtpTransactionResult(
                    response = PtpResponsePacket(
                        responseCode = PtpConstants.RES_DEVICE_BUSY,
                        transactionId = command.transactionId
                    )
                )
            }

            val payload = if (command.opCode == PtpConstants.OP_NIKON_GET_LIVE_VIEW_IMAGE) {
                ByteArray(LiveViewHeaderParser.HEADER_SIZE + 4).also {
                    it[LiveViewHeaderParser.HEADER_SIZE] = 0xFF.toByte()
                    it[LiveViewHeaderParser.HEADER_SIZE + 1] = 0xD8.toByte()
                }
            } else {
                ByteArray(0)
            }

            inFlightCount--
            return PtpTransactionResult(
                response = PtpResponsePacket(
                    responseCode = PtpConstants.RES_OK,
                    transactionId = command.transactionId
                ),
                dataPayload = payload
            )
        }
    }
}
