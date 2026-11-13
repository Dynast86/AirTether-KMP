package com.dynast.airtether.director.ui.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import com.dynast.airtether.director.ui.model.ShaderOptions

/**
 * Layer 1 OpenGL ES 3.0 viewport (`GLSurfaceView`) configured with `RENDERMODE_WHEN_DIRTY`.
 *
 * Uses [queueEvent] to guarantee that all Fragment Shader uniform updates and 3D LUT texture swaps
 * execute strictly on the OpenGL rendering thread before triggering [requestRender].
 * Also integrates Android API 30+ (`Build.VERSION_CODES.R`) LTPO adaptive display refresh rate
 * synchronization via [Surface.setFrameRate].
 */
class NikonGlSurfaceView(context: Context) : GLSurfaceView(context) {

    val nikonRenderer: NikonGlRenderer = NikonGlRenderer()

    @Volatile
    private var lastAppliedOptions: ShaderOptions? = null

    @Volatile
    var lastAppliedTargetFps: Float = 0f
        private set

    init {
        setEGLContextClientVersion(3)
        setRenderer(nikonRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        if (lastAppliedTargetFps > 0f) {
            applySurfaceFrameRate(lastAppliedTargetFps)
        }
    }

    /**
     * Synchronizes the hardware display / LTPO panel refresh rate with the camera's recording frame rate
     * (e.g. `23.976f` ➔ 48Hz/96Hz/120Hz exact cadence, `29.97f` ➔ 60Hz/120Hz) on Android 11 (API 30)+.
     */
    fun updateTargetFrameRate(targetFps: Float) {
        if (lastAppliedTargetFps == targetFps) return
        lastAppliedTargetFps = targetFps
        applySurfaceFrameRate(targetFps)
    }

    private fun applySurfaceFrameRate(targetFps: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val surface = holder?.surface
            if (surface != null && surface.isValid) {
                runCatching {
                    surface.setFrameRate(
                        targetFps,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                    )
                }
            }
        }
    }

    /**
     * Pushes a raw LiveView JPEG byte array to the GL renderer and schedules a GPU frame draw.
     */
    fun submitJpegFrame(jpegBytes: ByteArray) {
        nikonRenderer.updateJpegFrame(jpegBytes)
        requestRender()
    }

    /**
     * Dispatches Fragment Shader uniform updates (`LUT`, `PEAK`, `FALSE`, `ZEBRA`) onto the GL thread
     * via [queueEvent] and triggers a dirty render pass.
     */
    fun updateShaderOptions(options: ShaderOptions) {
        if (lastAppliedOptions == options) return
        lastAppliedOptions = options
        queueEvent {
            nikonRenderer.updateShaderOptions(options)
            requestRender()
        }
    }

    /**
     * Dispatches a parsed `.cube` 3D LUT upload (`GL_TEXTURE_3D`) onto the GL thread via [queueEvent].
     */
    fun loadCubeLut(lutData: CubeLutData) {
        queueEvent {
            nikonRenderer.setCubeLut(lutData)
            requestRender()
        }
    }
}
