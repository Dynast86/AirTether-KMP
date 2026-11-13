package com.dynast.airtether

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dynast.airtether.domain.port.AndroidStorageContextHolder
import com.dynast.airtether.ingest.AirTetherRuntime
import com.dynast.airtether.ingest.AndroidFtpServiceBridge
import com.dynast.airtether.service.AirTetherFtpService
import com.dynast.airtether.usb.AndroidUsbPtpMonitor
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var usbPtpMonitor: AndroidUsbPtpMonitor? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val appCtx = applicationContext
        AndroidStorageContextHolder.appContext = appCtx
        AndroidFtpServiceBridge.onStartService = { port ->
            AirTetherFtpService.start(appCtx, port)
        }
        AndroidFtpServiceBridge.onStopService = {
            AirTetherFtpService.stop(appCtx)
        }

        usbPtpMonitor = AndroidUsbPtpMonitor(
            context = appCtx,
            onUsbTransportReady = { usbTransport, _ ->
                lifecycleScope.launch {
                    AirTetherRuntime.attachUsbPtpTransport(
                        transport = usbTransport,
                        autoStartLiveView = false,
                    )
                }
            },
            onUsbDeviceDetached = {
                lifecycleScope.launch {
                    AirTetherRuntime.detachUsbPtpTransport()
                }
            },
        ).also { it.startMonitoring() }

        requestNotificationPermissionIfNeeded()

        setContent {
            App()
        }
    }

    override fun onDestroy() {
        usbPtpMonitor?.stopMonitoring()
        usbPtpMonitor = null
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}