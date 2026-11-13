package com.dynast.airtether.director.ui.gl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.pow

/**
 * Holds parsed 3D LUT data ready for upload to an OpenGL ES 3.0 `GL_TEXTURE_3D` target.
 *
 * @property title LUT title parsed from `TITLE "..."` or generated preset name.
 * @property size Cube dimension `N` (`LUT_3D_SIZE`), typically 17, 33, or 65 (`N * N * N` RGB entries).
 * @property rgbBuffer Direct native-order [FloatBuffer] containing `size * size * size * 3` floats in RGB order.
 */
data class CubeLutData(
    val title: String,
    val size: Int,
    val rgbBuffer: FloatBuffer
) {
    val totalVertices: Int
        get() = size * size * size
}

/**
 * Parser and procedural generator for Adobe/Iridas `.cube` 3D LUT files.
 */
object CubeLutParser {

    private const val DEFAULT_LUT_SIZE = 33

    /**
     * Parses a standard `.cube` text payload into [CubeLutData].
     * Supports `TITLE`, `LUT_3D_SIZE`, `DOMAIN_MIN`, `DOMAIN_MAX`, `#` comments, and `R G B` float triples.
     */
    fun parse(cubeContent: String): CubeLutData {
        var title = "Custom 3D LUT"
        var lutSize = 0
        var floatArray: FloatArray? = null
        var floatWriteIndex = 0

        val lines = cubeContent.lineSequence()
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            when {
                line.startsWith("TITLE", ignoreCase = true) -> {
                    val firstQuote = line.indexOf('"')
                    val lastQuote = line.lastIndexOf('"')
                    title = if (firstQuote >= 0 && lastQuote > firstQuote) {
                        line.substring(firstQuote + 1, lastQuote)
                    } else {
                        line.substringAfter("TITLE").trim()
                    }
                }

                line.startsWith("LUT_3D_SIZE", ignoreCase = true) -> {
                    val parts = line.split(WHITESPACE_REGEX)
                    val parsedSize = parts.getOrNull(1)?.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid LUT_3D_SIZE declaration: $line")
                    require(parsedSize in 2..129) {
                        "Unsupported LUT_3D_SIZE: $parsedSize"
                    }
                    lutSize = parsedSize
                    floatArray = FloatArray(parsedSize * parsedSize * parsedSize * 3)
                }

                line.startsWith("DOMAIN_MIN", ignoreCase = true) ||
                    line.startsWith("DOMAIN_MAX", ignoreCase = true) ||
                    line.startsWith("LUT_1D_SIZE", ignoreCase = true) -> {
                    // Domain metadata ignored for normalized 0.0..1.0 3D LUT sampling
                }

                else -> {
                    val firstChar = line[0]
                    if (firstChar.isDigit() || firstChar == '-' || firstChar == '+' || firstChar == '.') {
                        val target = floatArray ?: run {
                            lutSize = DEFAULT_LUT_SIZE
                            FloatArray(DEFAULT_LUT_SIZE * DEFAULT_LUT_SIZE * DEFAULT_LUT_SIZE * 3).also {
                                floatArray = it
                            }
                        }
                        val tokens = line.split(WHITESPACE_REGEX)
                        if (tokens.size >= 3 && floatWriteIndex + 2 < target.size) {
                            target[floatWriteIndex++] = tokens[0].toFloat()
                            target[floatWriteIndex++] = tokens[1].toFloat()
                            target[floatWriteIndex++] = tokens[2].toFloat()
                        }
                    }
                }
            }
        }

        val finalFloats = requireNotNull(floatArray) {
            "No LUT_3D_SIZE or RGB table entries found in .cube input"
        }
        require(floatWriteIndex == finalFloats.size) {
            "Expected ${finalFloats.size / 3} RGB entries for LUT_3D_SIZE $lutSize, but parsed ${floatWriteIndex / 3}"
        }

        val directBuffer = allocateDirectFloatBuffer(finalFloats)
        return CubeLutData(
            title = title,
            size = lutSize,
            rgbBuffer = directBuffer
        )
    }

    /**
     * Generates a 3D Identity LUT (`output RGB == input RGB`) of dimension [size]^3.
     */
    fun createIdentityLut(size: Int = DEFAULT_LUT_SIZE): CubeLutData {
        require(size in 2..65) { "LUT size must be between 2 and 65" }
        val floats = FloatArray(size * size * size * 3)
        val maxIdx = (size - 1).toFloat()
        var idx = 0

        // Standard .cube order: Red changes fastest, then Green, then Blue
        for (b in 0 until size) {
            val bf = b / maxIdx
            for (g in 0 until size) {
                val gf = g / maxIdx
                for (r in 0 until size) {
                    val rf = r / maxIdx
                    floats[idx++] = rf
                    floats[idx++] = gf
                    floats[idx++] = bf
                }
            }
        }

        return CubeLutData(
            title = "Identity (Linear Bypass)",
            size = size,
            rgbBuffer = allocateDirectFloatBuffer(floats)
        )
    }

    /**
     * Generates a built-in Nikon N-Log to Rec.709 cinematic 3D LUT (`33x33x33` by default)
     * so 3D LUT trilinear interpolation (`GL_TEXTURE_3D`) can be tested immediately without external files.
     */
    fun createDefaultNLogToRec709Lut(size: Int = DEFAULT_LUT_SIZE): CubeLutData {
        require(size in 2..65) { "LUT size must be between 2 and 65" }
        val floats = FloatArray(size * size * size * 3)
        val maxIdx = (size - 1).toFloat()
        var idx = 0

        for (b in 0 until size) {
            val inB = b / maxIdx
            for (g in 0 until size) {
                val inG = g / maxIdx
                for (r in 0 until size) {
                    val inR = r / maxIdx

                    // Filmic S-curve & Rec.709 gamma/color matrix approximation
                    val curveR = filmicSCurve(inR)
                    val curveG = filmicSCurve(inG)
                    val curveB = filmicSCurve(inB)

                    val luma = 0.2126f * curveR + 0.7152f * curveG + 0.0722f * curveB
                    val satBoost = 1.18f
                    val outR = (luma + (curveR - luma) * satBoost).coerceIn(0f, 1f)
                    val outG = (luma + (curveG - luma) * satBoost).coerceIn(0f, 1f)
                    val outB = (luma + (curveB - luma) * satBoost).coerceIn(0f, 1f)

                    floats[idx++] = outR
                    floats[idx++] = outG
                    floats[idx++] = outB
                }
            }
        }

        return CubeLutData(
            title = "N-Log -> Rec.709 (33³)",
            size = size,
            rgbBuffer = allocateDirectFloatBuffer(floats)
        )
    }

    private fun filmicSCurve(x: Float): Float {
        val clamped = ((x - 0.04f) / 0.92f).coerceIn(0f, 1f)
        val smooth = clamped * clamped * (3f - 2f * clamped)
        return smooth.toDouble().pow(0.92).toFloat().coerceIn(0f, 1f)
    }

    private fun allocateDirectFloatBuffer(floats: FloatArray): FloatBuffer {
        return ByteBuffer
            .allocateDirect(floats.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(floats)
                position(0)
            }
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
}
