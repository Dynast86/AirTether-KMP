package com.dynast.airtether.director

import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dynast.airtether.director.ui.DirectorLiveViewScreen
import com.dynast.airtether.director.ui.DirectorLiveViewViewModel
import com.dynast.airtether.director.usb.NikonUsbConnectionMonitor

/**
 * Landscape-locked (`sensorLandscape`) field monitor Activity for `:app-director`.
 *
 * - Configures Immersive Sticky fullscreen mode, screen-on lock, and outdoor max brightness boost (`1.0f`).
 * - Registers [NikonUsbConnectionMonitor] to automatically detect Nikon USB-C camera connection/disconnection
 *   and hot-swap `NikonSessionManager` between `MockPtpTransport` and physical `UsbPtpTransport`.
 */
class DirectorMonitorActivity : ComponentActivity() {

    private val viewModel: DirectorLiveViewViewModel by viewModels()
    private var usbConnectionMonitor: NikonUsbConnectionMonitor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveStickyFullscreen(window)

        usbConnectionMonitor = NikonUsbConnectionMonitor(
            context = this,
            onUsbTransportReady = { usbTransport, modelName ->
                viewModel.attachHardwareUsbTransport(
                    usbTransport = usbTransport,
                    cameraModelName = modelName
                )
            },
            onUsbDeviceDetached = {
                viewModel.onHardwareUsbDetached()
            }
        ).also { monitor ->
            monitor.startMonitoring()
        }

        setContent {
            DirectorLiveViewScreen(
                viewModel = viewModel,
                onBrightnessBoostChanged = { boostEnabled ->
                    setWindowMaxBrightnessBoost(window, boostEnabled)
                }
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveStickyFullscreen(window)
        }
    }

    override fun onDestroy() {
        usbConnectionMonitor?.stopMonitoring()
        usbConnectionMonitor = null
        viewModel.shutdownSession()
        super.onDestroy()
    }

    companion object {
        /**
         * Hides status and navigation bars using `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` (Immersive Sticky).
         */
        fun applyImmersiveStickyFullscreen(window: Window) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        /**
         * Boosts screen brightness to 100% (`1.0f`) for outdoor daylight monitoring or restores system default.
         */
        fun setWindowMaxBrightnessBoost(window: Window, enabled: Boolean) {
            val params = window.attributes
            params.screenBrightness = if (enabled) {
                1.0f
            } else {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = params
        }
    }
}
