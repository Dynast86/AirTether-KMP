package com.dynast.airtether.director.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.dynast.airtether.core.nikonptp.transport.UsbPtpTransport

/**
 * Monitors USB-C attachment, permission requests, and detachment of Nikon cameras (`0x04B0`).
 *
 * When a Nikon camera is attached and permission is granted, creates a [UsbPtpTransport] and invokes
 * [onUsbTransportReady] so `NikonSessionManager` can hot-swap from `MockPtpTransport` to physical USB.
 */
class NikonUsbConnectionMonitor(
    private val context: Context,
    private val onUsbTransportReady: (transport: UsbPtpTransport, modelName: String) -> Unit,
    private val onUsbDeviceDetached: () -> Unit
) {
    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var isReceiverRegistered: Boolean = false
    private var activeDeviceName: String? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = extractUsbDevice(intent) ?: return
                    if (UsbPtpTransport.isNikonOrPtpCamera(device)) {
                        checkPermissionAndConnect(device)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = extractUsbDevice(intent) ?: return
                    if (device.deviceName == activeDeviceName ||
                        UsbPtpTransport.isNikonOrPtpCamera(device)
                    ) {
                        activeDeviceName = null
                        onUsbDeviceDetached()
                    }
                }

                ACTION_USB_PERMISSION -> {
                    val device = extractUsbDevice(intent) ?: return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && UsbPtpTransport.isNikonOrPtpCamera(device)) {
                        promoteToUsbTransport(device)
                    }
                }
            }
        }
    }

    /**
     * Registers USB attach/detach/permission broadcast listeners and scans for any already-attached Nikon camera.
     */
    fun startMonitoring() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            ContextCompat.registerReceiver(
                context,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isReceiverRegistered = true
        }
        scanAlreadyAttachedCameras()
    }

    /**
     * Unregisters the broadcast receiver when the host Activity is destroyed.
     */
    fun stopMonitoring() {
        if (isReceiverRegistered) {
            runCatching { context.unregisterReceiver(usbReceiver) }
            isReceiverRegistered = false
        }
    }

    /**
     * Scans `usbManager.deviceList` for any currently connected Nikon camera (`vendorId == 0x04B0`).
     */
    fun scanAlreadyAttachedCameras() {
        val nikonDevice = usbManager.deviceList.values.firstOrNull { device ->
            UsbPtpTransport.isNikonOrPtpCamera(device)
        } ?: return

        checkPermissionAndConnect(nikonDevice)
    }

    private fun checkPermissionAndConnect(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            promoteToUsbTransport(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flags
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun promoteToUsbTransport(device: UsbDevice) {
        if (activeDeviceName == device.deviceName) return
        activeDeviceName = device.deviceName
        val modelName = device.productName?.takeIf { it.isNotBlank() } ?: "Nikon USB-C"
        val usbTransport = UsbPtpTransport(
            usbManager = usbManager,
            usbDevice = device
        )
        onUsbTransportReady(usbTransport, modelName)
    }

    private fun extractUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.dynast.airtether.director.ACTION_USB_PERMISSION"
    }
}
