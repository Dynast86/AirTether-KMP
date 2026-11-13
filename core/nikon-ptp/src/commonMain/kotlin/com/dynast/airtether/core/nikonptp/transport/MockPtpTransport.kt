package com.dynast.airtether.core.nikonptp.transport

import com.dynast.airtether.core.nikonptp.pacing.TargetFrameRate
import com.dynast.airtether.core.nikonptp.parser.LiveViewHeaderParser
import com.dynast.airtether.core.nikonptp.parser.NikonAfStatus
import com.dynast.airtether.core.nikonptp.protocol.PtpCommandPacket
import com.dynast.airtether.core.nikonptp.protocol.PtpConstants
import com.dynast.airtether.core.nikonptp.protocol.PtpEventPacket
import com.dynast.airtether.core.nikonptp.protocol.PtpResponsePacket
import com.dynast.airtether.core.nikonptp.protocol.PtpTransactionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Virtual Nikon Z 6II / Zf camera engine implementing [PtpTransport] for 60fps UI & pipeline verification
 * without requiring physical USB-C or Wi-Fi camera hardware.
 *
 * - Generates spec-compliant 384-byte **Big-Endian** Nikon LiveView headers followed by a JPEG stream
 *   starting with SOI (`0xFF 0xD8`) and ending with EOI (`0xFF 0xD9`).
 * - Reuses an internal payload buffer to minimize GC allocations during 60fps polling.
 * - Responds to `0x9205` (`SetTouchAfArea`) by driving the simulated AF box to the requested Nikon
 *   sensor coordinate, briefly reporting `SEARCHING (0)` before locking `MATCHED_GREEN (1)`.
 */
class MockPtpTransport(
    private val cameraMaxWidth: Int = 6048,
    private val cameraMaxHeight: Int = 4032,
    private val jpegDisplayWidth: Int = 1024,
    private val jpegDisplayHeight: Int = 680
) : PtpTransport {

    override val transportName: String = "MockNikonZ6II (60fps Virtual Engine)"

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _incomingEvents = MutableSharedFlow<PtpEventPacket>(extraBufferCapacity = 32)
    override val incomingEvents: Flow<PtpEventPacket> = _incomingEvents.asSharedFlow()

    private var isLiveViewRunning: Boolean = false
    private var frameTick: Long = 0L

    // Simulated AF box state in Nikon sensor coordinates (0..6048, 0..4032)
    private val afBoxWidth: Int = 480
    private val afBoxHeight: Int = 480
    private var targetAfCenterX: Int = cameraMaxWidth / 2
    private var targetAfCenterY: Int = cameraMaxHeight / 2
    private var currentAfCenterX: Int = cameraMaxWidth / 2
    private var currentAfCenterY: Int = cameraMaxHeight / 2
    private var focusSeekingFramesRemaining: Int = 0

    // Simulated camera exposure & recording state
    var fNumberHundredths: Int = 180 // f/1.8
    var shutterNumerator: Int = 1
    var shutterDenominator: Int = 50 // 1/50s
    var isoSensitivity: Int = 800
    var baseMovieRecSeconds: Int = 868 // 00:14:28
    var isMovieRecording: Boolean = true
    var configuredFrameRate: TargetFrameRate = TargetFrameRate.FPS_23_976

    // Pre-allocated 384-byte header + minimal valid JPEG frame buffer to avoid per-frame allocations
    private val reusableLiveViewPayload: ByteArray = ByteArray(
        LiveViewHeaderParser.HEADER_SIZE + MINIMAL_VALID_JPEG_BYTES.size
    ).apply {
        MINIMAL_VALID_JPEG_BYTES.copyInto(
            destination = this,
            destinationOffset = LiveViewHeaderParser.HEADER_SIZE
        )
    }

    override suspend fun connect() {
        _isConnected.value = true
    }

    override suspend fun disconnect() {
        isLiveViewRunning = false
        _isConnected.value = false
    }

    override suspend fun executeTransaction(
        command: PtpCommandPacket,
        outboundData: ByteArray?
    ): PtpTransactionResult {
        return when (command.opCode) {
            PtpConstants.OP_OPEN_SESSION -> okResult(command.transactionId)

            PtpConstants.OP_CLOSE_SESSION, PtpConstants.OP_NIKON_END_LIVE_VIEW -> {
                isLiveViewRunning = false
                okResult(command.transactionId)
            }

            PtpConstants.OP_NIKON_START_LIVE_VIEW -> {
                isLiveViewRunning = true
                okResult(command.transactionId)
            }

            PtpConstants.OP_NIKON_SET_TOUCH_AF_AREA -> {
                val reqX = command.params.getOrNull(0)?.toInt() ?: (cameraMaxWidth / 2)
                val reqY = command.params.getOrNull(1)?.toInt() ?: (cameraMaxHeight / 2)
                targetAfCenterX = reqX.coerceIn(afBoxWidth / 2, cameraMaxWidth - afBoxWidth / 2)
                targetAfCenterY = reqY.coerceIn(afBoxHeight / 2, cameraMaxHeight - afBoxHeight / 2)
                // Simulate 8 frames of AF motor drive before focus lock
                focusSeekingFramesRemaining = 8
                okResult(command.transactionId)
            }

            PtpConstants.OP_GET_DEVICE_PROP_VALUE -> {
                val propCode = command.params.getOrNull(0)?.toUShort()
                val payload = if (
                    propCode == PtpConstants.PROP_NIKON_MOVIE_FRAME_RATE ||
                    propCode == PtpConstants.PROP_NIKON_MOVIE_REC_QUALITY
                ) {
                    byteArrayOf(configuredFrameRate.nikonHeaderCode.toByte())
                } else {
                    ByteArray(0)
                }
                PtpTransactionResult(
                    response = PtpResponsePacket(
                        responseCode = PtpConstants.RES_OK,
                        transactionId = command.transactionId
                    ),
                    dataPayload = payload
                )
            }

            PtpConstants.OP_NIKON_GET_LIVE_VIEW_IMAGE -> {
                val framePayload = buildNextLiveViewPayload()
                PtpTransactionResult(
                    response = PtpResponsePacket(
                        responseCode = PtpConstants.RES_OK,
                        transactionId = command.transactionId
                    ),
                    dataPayload = framePayload
                )
            }

            else -> okResult(command.transactionId)
        }
    }

    private fun buildNextLiveViewPayload(): ByteArray {
        frameTick++

        // Smoothly interpolate current AF box position toward target Touch AF coordinates
        currentAfCenterX += ((targetAfCenterX - currentAfCenterX) * 0.45f).roundToInt()
        currentAfCenterY += ((targetAfCenterY - currentAfCenterY) * 0.45f).roundToInt()

        val afLeft = (currentAfCenterX - afBoxWidth / 2).coerceIn(0, cameraMaxWidth - afBoxWidth)
        val afTop = (currentAfCenterY - afBoxHeight / 2).coerceIn(0, cameraMaxHeight - afBoxHeight)

        val afStatus = if (focusSeekingFramesRemaining > 0) {
            focusSeekingFramesRemaining--
            NikonAfStatus.SEARCHING.rawCode
        } else {
            NikonAfStatus.MATCHED_GREEN.rawCode
        }

        // Smooth 60fps electronic horizon oscillation (in hundredths of a degree)
        val rollHundredths = (sin(frameTick * 0.035) * 165.0).roundToInt()   // -1.65° .. +1.65°
        val pitchHundredths = (sin(frameTick * 0.022) * 90.0).roundToInt()   // -0.90° .. +0.90°
        val elapsedMovieSec = baseMovieRecSeconds + (frameTick / 60L).toInt()

        val buf = reusableLiveViewPayload

        // Write Big-Endian 384-byte Nikon LiveView Header fields
        LiveViewHeaderParser.writeUInt16Be(buf, LiveViewHeaderParser.OFFSET_JPEG_WIDTH, jpegDisplayWidth)
        LiveViewHeaderParser.writeUInt16Be(buf, LiveViewHeaderParser.OFFSET_JPEG_HEIGHT, jpegDisplayHeight)
        LiveViewHeaderParser.writeUInt16Be(buf, LiveViewHeaderParser.OFFSET_CAMERA_MAX_WIDTH, cameraMaxWidth)
        LiveViewHeaderParser.writeUInt16Be(buf, LiveViewHeaderParser.OFFSET_CAMERA_MAX_HEIGHT, cameraMaxHeight)
        LiveViewHeaderParser.writeUInt16Be(buf, LiveViewHeaderParser.OFFSET_AF_LEFT, afLeft)
        LiveViewHeaderParser.writeUInt16Be(buf, LiveViewHeaderParser.OFFSET_AF_TOP, afTop)
        LiveViewHeaderParser.writeUInt16Be(buf, LiveViewHeaderParser.OFFSET_AF_WIDTH, afBoxWidth)
        LiveViewHeaderParser.writeUInt16Be(buf, LiveViewHeaderParser.OFFSET_AF_HEIGHT, afBoxHeight)
        LiveViewHeaderParser.writeInt32Be(buf, LiveViewHeaderParser.OFFSET_AF_STATUS, afStatus)
        LiveViewHeaderParser.writeInt32Be(buf, LiveViewHeaderParser.OFFSET_ROLL_FIXED, rollHundredths)
        LiveViewHeaderParser.writeInt32Be(buf, LiveViewHeaderParser.OFFSET_PITCH_FIXED, pitchHundredths)
        LiveViewHeaderParser.writeUInt16Be(buf, LiveViewHeaderParser.OFFSET_F_NUMBER, fNumberHundredths)
        LiveViewHeaderParser.writeUInt16Be(buf, LiveViewHeaderParser.OFFSET_SHUTTER_NUM, shutterNumerator)
        LiveViewHeaderParser.writeUInt16Be(buf, LiveViewHeaderParser.OFFSET_SHUTTER_DEN, shutterDenominator)
        LiveViewHeaderParser.writeInt32Be(buf, LiveViewHeaderParser.OFFSET_ISO, isoSensitivity)
        LiveViewHeaderParser.writeInt32Be(buf, LiveViewHeaderParser.OFFSET_MOVIE_REC_TIME, elapsedMovieSec)
        buf[LiveViewHeaderParser.OFFSET_MOVIE_REC_STATE] = if (isMovieRecording) 1 else 0
        buf[LiveViewHeaderParser.OFFSET_MOVIE_FPS_CODE] = configuredFrameRate.nikonHeaderCode.toByte()

        return buf
    }

    private fun okResult(txId: UInt): PtpTransactionResult {
        return PtpTransactionResult(
            response = PtpResponsePacket(
                responseCode = PtpConstants.RES_OK,
                transactionId = txId
            )
        )
    }

    companion object {
        /**
         * Spec-compliant JPEG stream starting with SOI (`0xFF`, `0xD8`) and ending with EOI (`0xFF`, `0xD9`).
         */
        val MINIMAL_VALID_JPEG_BYTES: ByteArray = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), // SOI
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            0x01, 0x01, 0x00, 0x48, 0x00, 0x48, 0x00, 0x00,
            0xFF.toByte(), 0xD9.toByte()  // EOI
        )
    }
}
