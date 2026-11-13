package com.dynast.airtether.core.nikonptp.parser

import com.dynast.airtether.core.nikonptp.pacing.TargetFrameRate
import com.dynast.airtether.core.nikonptp.protocol.PtpConstants

/**
 * High-speed, zero-allocation Big-Endian parser for Nikon's `0x9203` (`GetLiveViewImage`) response payload.
 *
 * - **Endianness**: All fields inside the 384-byte Nikon LiveView header are stored in **Big-Endian** order.
 * - **JPEG Extraction**: Scans around byte offset 384 (`HEADER_SIZE`) for the JPEG Start-Of-Image (SOI)
 *   marker (`0xFF`, `0xD8`), parsing the preceding bytes as [NikonLiveViewHeader] and extracting the
 *   remaining slice starting at `0xFF 0xD8` as `jpegBytes`.
 * - **GC Optimization**: Reads primitives directly from the `ByteArray` via bitwise shifts without
 *   allocating temporary `Buffer` or `ByteBuffer` wrappers in the 60fps loop.
 */
object LiveViewHeaderParser {

    const val HEADER_SIZE: Int = PtpConstants.NIKON_LIVE_VIEW_HEADER_SIZE
    const val JPEG_SOI_MARKER_0: Byte = 0xFF.toByte()
    const val JPEG_SOI_MARKER_1: Byte = 0xD8.toByte()

    // Big-Endian byte offsets within the 384-byte Nikon LiveView Header
    const val OFFSET_JPEG_WIDTH: Int = 0          // UInt16 BE
    const val OFFSET_JPEG_HEIGHT: Int = 2         // UInt16 BE
    const val OFFSET_CAMERA_MAX_WIDTH: Int = 4    // UInt16 BE
    const val OFFSET_CAMERA_MAX_HEIGHT: Int = 6   // UInt16 BE
    const val OFFSET_AF_LEFT: Int = 8             // UInt16 BE
    const val OFFSET_AF_TOP: Int = 10             // UInt16 BE
    const val OFFSET_AF_WIDTH: Int = 12            // UInt16 BE
    const val OFFSET_AF_HEIGHT: Int = 14           // UInt16 BE
    const val OFFSET_AF_STATUS: Int = 16           // Int32 BE (0: Searching, 1: Matched Green, 2: Failed Red)
    const val OFFSET_ROLL_FIXED: Int = 20          // Int32 BE (degrees * 100, e.g. -150 = -1.50 deg)
    const val OFFSET_PITCH_FIXED: Int = 24         // Int32 BE (degrees * 100, e.g. 50 = +0.50 deg)
    const val OFFSET_F_NUMBER: Int = 28            // UInt16 BE (fNumber * 100, e.g. 180 = f/1.8)
    const val OFFSET_SHUTTER_NUM: Int = 30         // UInt16 BE (e.g. 1)
    const val OFFSET_SHUTTER_DEN: Int = 32         // UInt16 BE (e.g. 50 -> 1/50s)
    const val OFFSET_ISO: Int = 36                 // Int32 BE (e.g. 800)
    const val OFFSET_MOVIE_REC_TIME: Int = 40      // Int32 BE (elapsed seconds)
    const val OFFSET_MOVIE_REC_STATE: Int = 44     // UInt8 (1 = REC, 0 = STBY)
    const val OFFSET_MOVIE_FPS_CODE: Int = 45      // UInt8 (0 = 23.976p, 1 = 25p, 2 = 29.97p, 3 = 59.94p)

    /**
     * Parses a raw `0x9203` (`GetLiveViewImage`) payload into [NikonLiveViewFrame].
     */
    fun parse(rawPayload: ByteArray): NikonLiveViewFrame {
        require(rawPayload.size >= HEADER_SIZE) {
            "Nikon GetLiveViewImage payload must be at least $HEADER_SIZE bytes, got ${rawPayload.size}"
        }

        val jpegOffset = findJpegSoiOffset(rawPayload)
        val jpegSize = (rawPayload.size - jpegOffset).coerceAtLeast(0)

        val jpegWidth = readUInt16Be(rawPayload, OFFSET_JPEG_WIDTH).coerceAtLeast(1)
        val jpegHeight = readUInt16Be(rawPayload, OFFSET_JPEG_HEIGHT).coerceAtLeast(1)
        val maxW = readUInt16Be(rawPayload, OFFSET_CAMERA_MAX_WIDTH).coerceAtLeast(1)
        val maxH = readUInt16Be(rawPayload, OFFSET_CAMERA_MAX_HEIGHT).coerceAtLeast(1)

        val afLeft = readUInt16Be(rawPayload, OFFSET_AF_LEFT)
        val afTop = readUInt16Be(rawPayload, OFFSET_AF_TOP)
        val afWidth = readUInt16Be(rawPayload, OFFSET_AF_WIDTH)
        val afHeight = readUInt16Be(rawPayload, OFFSET_AF_HEIGHT)

        val normLeft = (afLeft.toFloat() / maxW.toFloat()).coerceIn(0f, 1f)
        val normTop = (afTop.toFloat() / maxH.toFloat()).coerceIn(0f, 1f)
        val normWidth = (afWidth.toFloat() / maxW.toFloat()).coerceIn(0.02f, 1f)
        val normHeight = (afHeight.toFloat() / maxH.toFloat()).coerceIn(0.02f, 1f)

        val afStatusCode = readInt32Be(rawPayload, OFFSET_AF_STATUS)
        val rollFixedHundredths = readInt32Be(rawPayload, OFFSET_ROLL_FIXED)
        val pitchFixedHundredths = readInt32Be(rawPayload, OFFSET_PITCH_FIXED)

        val fNumberHundredths = readUInt16Be(rawPayload, OFFSET_F_NUMBER).let { if (it == 0) 180 else it }
        val shutterNum = readUInt16Be(rawPayload, OFFSET_SHUTTER_NUM).let { if (it == 0) 1 else it }
        val shutterDen = readUInt16Be(rawPayload, OFFSET_SHUTTER_DEN).let { if (it == 0) 50 else it }
        val iso = readInt32Be(rawPayload, OFFSET_ISO).let { if (it <= 0) 800 else it }
        val movieRecSeconds = readInt32Be(rawPayload, OFFSET_MOVIE_REC_TIME).coerceAtLeast(0)
        val isMovieRec = (rawPayload[OFFSET_MOVIE_REC_STATE].toInt() and 0xFF) != 0
        val movieFpsCode = rawPayload[OFFSET_MOVIE_FPS_CODE].toInt() and 0xFF

        val header = NikonLiveViewHeader(
            jpegOffset = jpegOffset,
            jpegSize = jpegSize,
            jpegDisplaySize = CameraCoordSize(width = jpegWidth, height = jpegHeight),
            cameraCoordMax = CameraCoordSize(width = maxW, height = maxH),
            afAreaRect = AfAreaRect(
                left = afLeft,
                top = afTop,
                width = afWidth,
                height = afHeight,
                normLeft = normLeft,
                normTop = normTop,
                normWidth = normWidth,
                normHeight = normHeight
            ),
            afStatus = NikonAfStatus.fromRaw(afStatusCode),
            levelAngle = LevelAngle(
                rollDegrees = rollFixedHundredths / 100f,
                pitchDegrees = pitchFixedHundredths / 100f
            ),
            exposureInfo = LiveViewExposureInfo(
                fNumberHundredths = fNumberHundredths,
                shutterNumerator = shutterNum,
                shutterDenominator = shutterDen,
                iso = iso,
                movieRecTimeSeconds = movieRecSeconds,
                isMovieRecording = isMovieRec,
                movieFrameRate = TargetFrameRate.fromNikonCode(movieFpsCode)
            )
        )

        val jpegBytes = if (jpegSize > 0) {
            rawPayload.copyOfRange(jpegOffset, rawPayload.size)
        } else {
            ByteArray(0)
        }

        return NikonLiveViewFrame(
            header = header,
            jpegBytes = jpegBytes
        )
    }

    /**
     * Locates the JPEG Start-Of-Image (`0xFF`, `0xD8`) marker near the 384-byte header boundary.
     * Checks offset 384 in O(1) first, then scans from byte 64 to the end of the buffer.
     */
    fun findJpegSoiOffset(rawPayload: ByteArray): Int {
        if (rawPayload.size > HEADER_SIZE + 1 &&
            rawPayload[HEADER_SIZE] == JPEG_SOI_MARKER_0 &&
            rawPayload[HEADER_SIZE + 1] == JPEG_SOI_MARKER_1
        ) {
            return HEADER_SIZE
        }

        val searchStart = 64.coerceAtMost(rawPayload.size - 1)
        for (i in searchStart until rawPayload.size - 1) {
            if (rawPayload[i] == JPEG_SOI_MARKER_0 && rawPayload[i + 1] == JPEG_SOI_MARKER_1) {
                return i
            }
        }
        return HEADER_SIZE.coerceAtMost(rawPayload.size)
    }

    fun readUInt16Be(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
    }

    fun readInt32Be(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    fun writeUInt16Be(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }

    fun writeInt32Be(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }
}
