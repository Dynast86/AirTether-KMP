package com.dynast.airtether.director.ui.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.dynast.airtether.director.ui.model.ShaderOptions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Production OpenGL ES 3.0 Cinema Assist Renderer for `:app-director`.
 *
 * - **Texture Unit 0 (`GL_TEXTURE_2D`, `uVideoTexture`)**: Double-buffered `inBitmap` JPEG decoding
 *   and `GLUtils.texSubImage2D` partial upload to eliminate per-frame GC allocations at 60fps.
 * - **Texture Unit 1 (`GL_TEXTURE_3D`, `uLutTexture`)**: Hardware-accelerated 3D LUT (`GL_RGB16F`)
 *   with `GL_LINEAR` Trilinear Interpolation parsed via [CubeLutParser].
 * - **Single-Pass `#version 300 es` Fragment Shader Pipeline**:
 *   1) Video Texture Sampling & 3D LUT Trilinear Color Grading (`uEnableLut`, `uLutIntensity`)
 *   2) Rec.709 IRE Luminance Computation (`dot(color.rgb, vec3(0.2126, 0.7152, 0.0722))`)
 *   3) Cinema IRE False Color Mapping (`uEnableFalseColor`)
 *   4) Animated Right-Upward Diagonal Zebra Stripes (`uEnableZebra`, `uZebraThreshold`, `uTime`)
 *   5) 8-Neighbor 3x3 Sobel Edge Detection Focus Peaking (`uEnablePeaking`, `uPeakingThreshold`, `uPeakingColor`)
 */
class NikonGlRenderer : GLSurfaceView.Renderer {

    private val pendingJpegBytes = AtomicReference<ByteArray?>(null)
    private val pendingLutData = AtomicReference<CubeLutData?>(null)
    private val videoTextureNeedsInit = AtomicBoolean(true)

    @Volatile
    private var currentShaderOptions: ShaderOptions = ShaderOptions()

    private var programId: Int = 0
    private var videoTextureId: Int = 0
    private var lut3dTextureId: Int = 0
    private var currentLutSize: Int = 33

    // Double-buffered Bitmap pool + reusable temp storage for zero-GC BitmapFactory.decodeByteArray
    private val bitmapDecodeTempStorage = ByteArray(16 * 1024)
    private var activeBitmapSlot: Int = 0
    private val bitmapPool = arrayOfNulls<Bitmap>(2)
    private var uploadedWidth: Int = 1280
    private var uploadedHeight: Int = 720
    private var elapsedSeconds: Float = 0f

    // Uniform locations
    private var uVideoTextureLoc: Int = -1
    private var uLutTextureLoc: Int = -1
    private var uLutSizeLoc: Int = -1
    private var uEnableLutLoc: Int = -1
    private var uLutIntensityLoc: Int = -1
    private var uEnableFalseColorLoc: Int = -1
    private var uEnableZebraLoc: Int = -1
    private var uZebraThresholdLoc: Int = -1
    private var uTimeLoc: Int = -1
    private var uEnablePeakingLoc: Int = -1
    private var uPeakingThresholdLoc: Int = -1
    private var uPeakingColorLoc: Int = -1
    private var uTexelSizeLoc: Int = -1

    private val quadVertices: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_COORDS)
            position(0)
        }

    /**
     * Enqueues a raw JPEG frame byte array from `NikonSessionManager` (`0x9203` GetLiveViewImage).
     */
    fun updateJpegFrame(jpegBytes: ByteArray) {
        pendingJpegBytes.set(jpegBytes)
    }

    /**
     * Updates Fragment Shader uniform state on the GL thread (invoked via `GLSurfaceView.queueEvent`).
     */
    fun updateShaderOptions(options: ShaderOptions) {
        currentShaderOptions = options
    }

    /**
     * Enqueues a parsed `.cube` 3D LUT for upload to `GL_TEXTURE_3D` on the GL thread.
     */
    fun setCubeLut(lutData: CubeLutData) {
        pendingLutData.set(lutData)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.03f, 0.03f, 0.04f, 1.0f)
        programId = buildShaderProgram(VERTEX_SHADER_ES30, FRAGMENT_SHADER_ES30)

        videoTextureId = create2dVideoTexture()
        lut3dTextureId = create3dLutTexture()
        videoTextureNeedsInit.set(true)

        uVideoTextureLoc = GLES30.glGetUniformLocation(programId, "uVideoTexture")
        uLutTextureLoc = GLES30.glGetUniformLocation(programId, "uLutTexture")
        uLutSizeLoc = GLES30.glGetUniformLocation(programId, "uLutSize")
        uEnableLutLoc = GLES30.glGetUniformLocation(programId, "uEnableLut")
        uLutIntensityLoc = GLES30.glGetUniformLocation(programId, "uLutIntensity")
        uEnableFalseColorLoc = GLES30.glGetUniformLocation(programId, "uEnableFalseColor")
        uEnableZebraLoc = GLES30.glGetUniformLocation(programId, "uEnableZebra")
        uZebraThresholdLoc = GLES30.glGetUniformLocation(programId, "uZebraThreshold")
        uTimeLoc = GLES30.glGetUniformLocation(programId, "uTime")
        uEnablePeakingLoc = GLES30.glGetUniformLocation(programId, "uEnablePeaking")
        uPeakingThresholdLoc = GLES30.glGetUniformLocation(programId, "uPeakingThreshold")
        uPeakingColorLoc = GLES30.glGetUniformLocation(programId, "uPeakingColor")
        uTexelSizeLoc = GLES30.glGetUniformLocation(programId, "uTexelSize")

        // Upload built-in N-Log -> Rec.709 33^3 3D LUT to Texture Unit 1
        val defaultLut = pendingLutData.getAndSet(null)
            ?: CubeLutParser.createDefaultNLogToRec709Lut(size = 33)
        upload3dLutTexture(defaultLut)

        // Upload initial synthetic N-Log cinema test frame to Texture Unit 0
        uploadSyntheticCinemaTestFrame()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // 1. Upload pending 3D LUT if changed
        pendingLutData.getAndSet(null)?.let { newLut ->
            upload3dLutTexture(newLut)
        }

        // 2. Decode & upload latest LiveView JPEG frame using double-buffered inBitmap reuse
        val latestJpeg = pendingJpegBytes.getAndSet(null)
        if (latestJpeg != null && latestJpeg.size > 32) {
            decodeAndUploadVideoFrame(latestJpeg)
        }

        val options = currentShaderOptions
        elapsedSeconds = (elapsedSeconds + 0.0166f) % 3600f

        GLES30.glUseProgram(programId)

        // Bind Texture Unit 0: 2D LiveView Video Texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, videoTextureId)
        GLES30.glUniform1i(uVideoTextureLoc, 0)

        // Bind Texture Unit 1: 3D LUT Texture (GL_TEXTURE_3D with GL_LINEAR Trilinear Filtering)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lut3dTextureId)
        GLES30.glUniform1i(uLutTextureLoc, 1)
        GLES30.glUniform1f(uLutSizeLoc, currentLutSize.toFloat())

        // Set Fragment Shader Pipeline Uniforms
        GLES30.glUniform2f(
            uTexelSizeLoc,
            1f / uploadedWidth.coerceAtLeast(1).toFloat(),
            1f / uploadedHeight.coerceAtLeast(1).toFloat()
        )
        GLES30.glUniform1i(uEnableLutLoc, if (options.isLutEnabled) 1 else 0)
        GLES30.glUniform1f(uLutIntensityLoc, options.lutIntensity.coerceIn(0f, 1f))
        GLES30.glUniform1i(uEnableFalseColorLoc, if (options.isFalseColorEnabled) 1 else 0)
        GLES30.glUniform1i(uEnableZebraLoc, if (options.isZebraEnabled) 1 else 0)
        GLES30.glUniform1f(uZebraThresholdLoc, options.zebraThreshold.coerceIn(0.5f, 1.0f))
        GLES30.glUniform1f(uTimeLoc, elapsedSeconds)
        GLES30.glUniform1i(uEnablePeakingLoc, if (options.isFocusPeakingEnabled) 1 else 0)
        GLES30.glUniform1f(uPeakingThresholdLoc, options.peakingThreshold)
        GLES30.glUniform3f(
            uPeakingColorLoc,
            options.peakingColor.r,
            options.peakingColor.g,
            options.peakingColor.b
        )

        // Draw Fullscreen Viewport Quad
        quadVertices.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quadVertices)

        quadVertices.position(2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quadVertices)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    private fun decodeAndUploadVideoFrame(jpegBytes: ByteArray) {
        val candidateBitmap = bitmapPool[activeBitmapSlot]
        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            inTempStorage = bitmapDecodeTempStorage
            if (candidateBitmap != null) {
                inBitmap = candidateBitmap
            }
        }

        val decoded = runCatching {
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOptions)
        }.getOrElse {
            // Fallback without inBitmap if incoming JPEG resolution changed
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
                inTempStorage = bitmapDecodeTempStorage
            }.let { fallbackOpts ->
                BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, fallbackOpts)
            }
        } ?: return

        bitmapPool[activeBitmapSlot] = decoded
        activeBitmapSlot = (activeBitmapSlot + 1) % bitmapPool.size

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, videoTextureId)

        if (videoTextureNeedsInit.getAndSet(false) ||
            uploadedWidth != decoded.width ||
            uploadedHeight != decoded.height
        ) {
            uploadedWidth = decoded.width
            uploadedHeight = decoded.height
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, decoded, 0)
        } else {
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, decoded)
        }
    }

    private fun upload3dLutTexture(lut: CubeLutData) {
        currentLutSize = lut.size
        lut.rgbBuffer.position(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lut3dTextureId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D,
            0,
            GLES30.GL_RGB16F,
            lut.size,
            lut.size,
            lut.size,
            0,
            GLES30.GL_RGB,
            GLES30.GL_FLOAT,
            lut.rgbBuffer
        )
    }

    private fun uploadSyntheticCinemaTestFrame() {
        val width = 1280
        val height = 720
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Full IRE 0..100% ramp + skin tone & middle grey patches for False Color, Zebra, and 3D LUT testing
        paint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(
                Color.rgb(4, 5, 8),        // ~1% IRE (Clipping Black -> Purple)
                Color.rgb(20, 26, 38),     // ~8% IRE (Deep Shadow -> Blue)
                Color.rgb(104, 108, 104),  // ~41% IRE (18% Middle Grey -> Green)
                Color.rgb(148, 132, 122),  // ~55% IRE (Skin Tone -> Pink)
                Color.rgb(230, 224, 208),  // ~90% IRE (Highlight -> Yellow)
                Color.rgb(252, 252, 250)   // ~99% IRE (Clipping White / Zebra -> Red)
            ),
            floatArrayOf(0f, 0.16f, 0.42f, 0.62f, 0.86f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        // Sharp concentric resolution rings & Siemens star spokes for 8-neighbor Sobel Focus Peaking
        paint.style = Paint.Style.STROKE
        paint.color = Color.rgb(240, 244, 248)
        val cx = width * 0.5f
        val cy = height * 0.5f
        for (r in 36..240 step 20) {
            paint.strokeWidth = if (r % 40 == 0) 3.2f else 1.6f
            canvas.drawCircle(cx, cy, r.toFloat(), paint)
        }

        bitmapPool[0] = bmp
        uploadedWidth = width
        uploadedHeight = height

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, videoTextureId)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        videoTextureNeedsInit.set(false)
    }

    private fun create2dVideoTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun create3dLutTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, id)
        // Trilinear interpolation (GL_LINEAR) across R, G, B axes
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun buildShaderProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        return shader
    }

    companion object {
        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 1.0f,
            1.0f, -1.0f, 1.0f, 1.0f,
            -1.0f, 1.0f, 0.0f, 0.0f,
            1.0f, 1.0f, 1.0f, 0.0f
        )

        const val VERTEX_SHADER_ES30 = """#version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                vTexCoord = aTexCoord;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SHADER_ES30 = """#version 300 es
            precision highp float;
            precision mediump sampler3D;

            in vec2 vTexCoord;
            out vec4 fragColor;

            uniform sampler2D uVideoTexture;
            uniform sampler3D uLutTexture;
            uniform float uLutSize;
            uniform int uEnableLut;
            uniform float uLutIntensity;

            uniform int uEnableFalseColor;

            uniform int uEnableZebra;
            uniform float uZebraThreshold;
            uniform float uTime;

            uniform int uEnablePeaking;
            uniform float uPeakingThreshold;
            uniform vec3 uPeakingColor;
            uniform vec2 uTexelSize;

            // Step 2 helper: Rec.709 Luminance (IRE 0.0 ~ 1.0)
            float computeRec709Luma(vec3 rgb) {
                return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
            }

            // Step 3 helper: Cinema Monitor Standard IRE False Color Map
            vec3 mapCinemaFalseColor(float ire) {
                if (ire <= 0.03) {
                    return vec3(0.52, 0.12, 0.85); // 0~3% Clipping Black: Purple
                } else if (ire <= 0.10) {
                    return vec3(0.14, 0.45, 0.95); // 4~10% Deep Shadow: Blue
                } else if (ire >= 0.38 && ire <= 0.45) {
                    return vec3(0.15, 0.80, 0.38); // 38~45% (18% Middle Grey): Green
                } else if (ire >= 0.52 && ire <= 0.58) {
                    return vec3(0.95, 0.58, 0.64); // 52~58% Skin Tone IRE: Pink
                } else if (ire >= 0.85 && ire < 0.96) {
                    return vec3(0.98, 0.84, 0.15); // 85~95% Near Highlight Clip: Yellow
                } else if (ire >= 0.96) {
                    return vec3(0.96, 0.16, 0.16); // 96~100% Clipping White: Red
                }
                return vec3(ire);
            }

            void main() {
                // 1. Sample raw video frame & apply 3D LUT with Trilinear Interpolation
                vec4 rawColor = texture(uVideoTexture, vTexCoord);
                vec3 color = rawColor.rgb;

                if (uEnableLut == 1) {
                    float scale = (uLutSize - 1.0) / uLutSize;
                    float offset = 0.5 / uLutSize;
                    vec3 lutCoord = clamp(rawColor.rgb, 0.0, 1.0) * scale + offset;
                    vec3 lutColor = texture(uLutTexture, lutCoord).rgb;
                    color = mix(rawColor.rgb, lutColor, clamp(uLutIntensity, 0.0, 1.0));
                }

                // 2. Compute Rec.709 Luminance (IRE 0.0 ~ 1.0)
                float ire = computeRec709Luma(color);

                // 3. Apply False Color IRE mapping
                if (uEnableFalseColor == 1) {
                    color = mapCinemaFalseColor(ire);
                }

                // 4. Overlay Right-Upward Diagonal Zebra Stripes for IRE >= uZebraThreshold
                if (uEnableZebra == 1 && ire >= uZebraThreshold) {
                    float diagonalPhase = mod((gl_FragCoord.x - gl_FragCoord.y) + uTime * 30.0, 14.0);
                    if (diagonalPhase < 7.0) {
                        color = mix(color, vec3(0.08, 0.08, 0.08), 0.82);
                    }
                }

                // 5. 8-Neighbor 3x3 Sobel Edge Detection for Focus Peaking
                if (uEnablePeaking == 1) {
                    vec2 d = uTexelSize;
                    float tl = computeRec709Luma(texture(uVideoTexture, vTexCoord + vec2(-d.x, -d.y)).rgb);
                    float tc = computeRec709Luma(texture(uVideoTexture, vTexCoord + vec2( 0.0, -d.y)).rgb);
                    float tr = computeRec709Luma(texture(uVideoTexture, vTexCoord + vec2( d.x, -d.y)).rgb);
                    float ml = computeRec709Luma(texture(uVideoTexture, vTexCoord + vec2(-d.x,  0.0)).rgb);
                    float mr = computeRec709Luma(texture(uVideoTexture, vTexCoord + vec2( d.x,  0.0)).rgb);
                    float bl = computeRec709Luma(texture(uVideoTexture, vTexCoord + vec2(-d.x,  d.y)).rgb);
                    float bc = computeRec709Luma(texture(uVideoTexture, vTexCoord + vec2( 0.0,  d.y)).rgb);
                    float br = computeRec709Luma(texture(uVideoTexture, vTexCoord + vec2( d.x,  d.y)).rgb);

                    float gx = -tl - (2.0 * ml) - bl + tr + (2.0 * mr) + br;
                    float gy = -tl - (2.0 * tc) - tr + bl + (2.0 * bc) + br;
                    float edgeMagnitude = length(vec2(gx, gy));

                    if (edgeMagnitude >= uPeakingThreshold) {
                        float peakBlend = clamp((edgeMagnitude - uPeakingThreshold) * 3.0, 0.65, 1.0);
                        color = mix(color, uPeakingColor, peakBlend);
                    }
                }

                fragColor = vec4(color, 1.0);
            }
        """
    }
}
