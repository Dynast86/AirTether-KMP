package com.dynast.airtether.core.nikonptp.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.dynast.airtether.core.nikonptp.protocol.PtpCommandPacket
import com.dynast.airtether.core.nikonptp.protocol.PtpConstants
import com.dynast.airtether.core.nikonptp.protocol.PtpContainerType
import com.dynast.airtether.core.nikonptp.protocol.PtpDataPacket
import com.dynast.airtether.core.nikonptp.protocol.PtpEventPacket
import com.dynast.airtether.core.nikonptp.protocol.PtpPacketSerializer
import com.dynast.airtether.core.nikonptp.protocol.PtpTransactionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Production Android USB Host PTP transport (`androidMain`) wrapping [UsbManager] and [UsbDeviceConnection].
 *
 * - **Interface & Endpoint Discovery**:
 *   Claims the Still Image Capture interface (`Class = 6, Subclass = 1, Protocol = 1` or Nikon Vendor `0x04B0`)
 *   and binds 3 distinct endpoints:
 *   1) [endpointOut]: `USB_DIR_OUT` + `USB_ENDPOINT_XFER_BULK` (Command & outbound Data containers)
 *   2) [endpointIn]: `USB_DIR_IN` + `USB_ENDPOINT_XFER_BULK` (Inbound Data & Response containers)
 *   3) [endpointInterrupt]: `USB_DIR_IN` + `USB_ENDPOINT_XFER_INT` (Asynchronous PTP Event containers)
 * - **Reusable High-Throughput Buffer Pool**:
 *   Uses a reusable 64KB USB chunk buffer (`usbChunkBuffer`) and a 256KB assembly buffer (`payloadAssemblyBuffer`)
 *   to reassemble large `GetLiveViewImage` (`0x9203`) frames at 60fps without heap thrashing.
 */
class UsbPtpTransport(
    private val usbManager: UsbManager,
    val usbDevice: UsbDevice,
    private val timeoutMs: Int = 2500,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PtpTransport {

    override val transportName: String =
        "USB-C PTP (${usbDevice.productName ?: usbDevice.deviceName})"

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _incomingEvents = MutableSharedFlow<PtpEventPacket>(extraBufferCapacity = 64)
    override val incomingEvents: Flow<PtpEventPacket> = _incomingEvents.asSharedFlow()

    private val interruptScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var interruptPollingJob: Job? = null

    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null

    var endpointOut: UsbEndpoint? = null
        private set
    var endpointIn: UsbEndpoint? = null
        private set
    var endpointInterrupt: UsbEndpoint? = null
        private set

    // Reusable 64KB chunk buffer and 256KB assembly buffer to minimize GC during 60fps LiveView reads
    private val usbChunkBuffer = ByteArray(BULK_CHUNK_SIZE)
    private var containerAssemblyBuffer = ByteArray(INITIAL_ASSEMBLY_BUFFER_SIZE)

    // Leftover bytes when a single USB Bulk IN read coalesces the trailing 12-byte Response container
    private var coalescedBytes: ByteArray? = null

    override suspend fun connect() = withContext(ioDispatcher) {
        if (_isConnected.value) return@withContext

        val targetInterface = findPtpStillImageInterface(usbDevice)
            ?: throw IllegalStateException(
                "No USB Still Image (Class 6/1/1) interface found on ${usbDevice.deviceName}"
            )

        var bulkOut: UsbEndpoint? = null
        var bulkIn: UsbEndpoint? = null
        var interruptIn: UsbEndpoint? = null

        for (i in 0 until targetInterface.endpointCount) {
            val ep = targetInterface.getEndpoint(i)
            when (ep.type) {
                UsbConstants.USB_ENDPOINT_XFER_BULK -> {
                    if (ep.direction == UsbConstants.USB_DIR_OUT) {
                        bulkOut = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_IN) {
                        bulkIn = ep
                    }
                }
                UsbConstants.USB_ENDPOINT_XFER_INT -> {
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        interruptIn = ep
                    }
                }
            }
        }

        val devConnection = checkNotNull(usbManager.openDevice(usbDevice)) {
            "Failed to open UsbDeviceConnection for ${usbDevice.deviceName} (check USB permission)"
        }
        check(devConnection.claimInterface(targetInterface, true)) {
            "Failed to claim USB Still Image interface on ${usbDevice.deviceName}"
        }

        connection = devConnection
        claimedInterface = targetInterface
        endpointOut = checkNotNull(bulkOut) { "Missing USB Bulk OUT endpoint" }
        endpointIn = checkNotNull(bulkIn) { "Missing USB Bulk IN endpoint" }
        endpointInterrupt = interruptIn
        coalescedBytes = null
        _isConnected.value = true

        startInterruptEventReader()
    }

    override suspend fun disconnect() = withContext(ioDispatcher) {
        _isConnected.value = false
        interruptPollingJob?.cancel()
        interruptPollingJob = null

        claimedInterface?.let { iface ->
            runCatching { connection?.releaseInterface(iface) }
        }
        runCatching { connection?.close() }

        claimedInterface = null
        connection = null
        endpointOut = null
        endpointIn = null
        endpointInterrupt = null
        coalescedBytes = null
    }

    override suspend fun executeTransaction(
        command: PtpCommandPacket,
        outboundData: ByteArray?
    ): PtpTransactionResult = withContext(ioDispatcher) {
        val conn = checkNotNull(connection) { "USB connection is not open" }
        val epOut = checkNotNull(endpointOut) { "Bulk OUT endpoint unavailable" }
        val epIn = checkNotNull(endpointIn) { "Bulk IN endpoint unavailable" }

        // 1. Send 12B+Params Little-Endian Command Container (Type = 1)
        val cmdBytes = command.toLittleEndianBytes()
        val cmdSent = conn.bulkTransfer(epOut, cmdBytes, cmdBytes.size, timeoutMs)
        check(cmdSent >= 0) {
            "USB Bulk OUT Command failed (OpCode=0x${command.opCode.toString(16)}, result=$cmdSent)"
        }

        // 2. Send Optional Outbound Data Container (Type = 2)
        if (outboundData != null) {
            val dataBytes = PtpDataPacket(
                opCode = command.opCode,
                transactionId = command.transactionId,
                payload = outboundData
            ).toLittleEndianBytes()
            var offset = 0
            while (offset < dataBytes.size) {
                val len = (dataBytes.size - offset).coerceAtMost(BULK_CHUNK_SIZE)
                val slice = if (offset == 0 && len == dataBytes.size) {
                    dataBytes
                } else {
                    dataBytes.copyOfRange(offset, offset + len)
                }
                val sent = conn.bulkTransfer(epOut, slice, slice.size, timeoutMs)
                check(sent > 0) { "USB Bulk OUT Data stage failed at $offset / ${dataBytes.size}" }
                offset += sent
            }
        }

        // 3. Read Inbound Container from endpointIn: either [Data (Type 2) -> Response (Type 3)] or [Response (Type 3)]
        val firstContainer = readNextBulkContainer(conn, epIn)
        val firstHeader = PtpPacketSerializer.decodeHeader(firstContainer)

        return@withContext if (firstHeader.type == PtpContainerType.DATA) {
            val payloadLength = (firstHeader.length - PtpConstants.PTP_CONTAINER_HEADER_SIZE)
                .coerceAtLeast(0)
                .coerceAtMost(firstContainer.size - PtpConstants.PTP_CONTAINER_HEADER_SIZE)
            val dataPayload = firstContainer.copyOfRange(
                PtpConstants.PTP_CONTAINER_HEADER_SIZE,
                PtpConstants.PTP_CONTAINER_HEADER_SIZE + payloadLength
            )

            // Read the trailing Response Container (Type = 3)
            val responseContainer = readNextBulkContainer(conn, epIn)
            val response = PtpPacketSerializer.decodeResponse(responseContainer)
            PtpTransactionResult(
                response = response,
                dataPayload = dataPayload
            )
        } else {
            val response = PtpPacketSerializer.decodeResponse(firstContainer)
            PtpTransactionResult(
                response = response,
                dataPayload = ByteArray(0)
            )
        }
    }

    /**
     * Reads a single complete PTP container from [epIn], reassembling multi-chunk Bulk transfers
     * using [usbChunkBuffer] and [containerAssemblyBuffer].
     */
    private fun readNextBulkContainer(conn: UsbDeviceConnection, epIn: UsbEndpoint): ByteArray {
        val pending = coalescedBytes
        val firstReadLength: Int
        if (pending != null && pending.size >= PtpConstants.PTP_CONTAINER_HEADER_SIZE) {
            coalescedBytes = null
            ensureAssemblyCapacity(pending.size)
            pending.copyInto(containerAssemblyBuffer, 0, 0, pending.size)
            firstReadLength = pending.size
        } else {
            val n = conn.bulkTransfer(epIn, usbChunkBuffer, usbChunkBuffer.size, timeoutMs)
            check(n >= PtpConstants.PTP_CONTAINER_HEADER_SIZE) {
                "USB Bulk IN read failed or returned fewer than 12 header bytes ($n)"
            }
            ensureAssemblyCapacity(n)
            usbChunkBuffer.copyInto(containerAssemblyBuffer, 0, 0, n)
            firstReadLength = n
        }

        val header = PtpPacketSerializer.decodeHeader(containerAssemblyBuffer)
        val expectedLength = header.length.coerceAtLeast(PtpConstants.PTP_CONTAINER_HEADER_SIZE)
        ensureAssemblyCapacity(expectedLength)

        var totalReceived = firstReadLength
        while (totalReceived < expectedLength) {
            val remaining = expectedLength - totalReceived
            val requestSize = remaining.coerceAtMost(usbChunkBuffer.size)
            val n = conn.bulkTransfer(epIn, usbChunkBuffer, requestSize, timeoutMs)
            check(n > 0) {
                "USB Bulk IN stream interrupted at $totalReceived / $expectedLength bytes"
            }
            ensureAssemblyCapacity(totalReceived + n)
            usbChunkBuffer.copyInto(containerAssemblyBuffer, totalReceived, 0, n)
            totalReceived += n
        }

        // Preserve any extra bytes if the USB controller coalesced the trailing Response container
        if (totalReceived > expectedLength) {
            coalescedBytes = containerAssemblyBuffer.copyOfRange(expectedLength, totalReceived)
        }

        return containerAssemblyBuffer.copyOfRange(0, expectedLength)
    }

    private fun ensureAssemblyCapacity(requiredBytes: Int) {
        if (containerAssemblyBuffer.size >= requiredBytes) return
        var nextSize = containerAssemblyBuffer.size
        while (nextSize < requiredBytes) {
            nextSize *= 2
        }
        val expanded = ByteArray(nextSize)
        containerAssemblyBuffer.copyInto(expanded, 0, 0, containerAssemblyBuffer.size)
        containerAssemblyBuffer = expanded
    }

    private fun startInterruptEventReader() {
        val epInt = endpointInterrupt ?: return
        interruptPollingJob?.cancel()
        interruptPollingJob = interruptScope.launch {
            val intBuffer = ByteArray(64)
            while (isActive && _isConnected.value) {
                val conn = connection ?: break
                val n = conn.bulkTransfer(epInt, intBuffer, intBuffer.size, 500)
                if (n >= PtpConstants.PTP_CONTAINER_HEADER_SIZE) {
                    runCatching {
                        val header = PtpPacketSerializer.decodeHeader(intBuffer)
                        if (header.type == PtpContainerType.EVENT) {
                            val param = if (n >= 16) {
                                ((intBuffer[12].toInt() and 0xFF)) or
                                    ((intBuffer[13].toInt() and 0xFF) shl 8) or
                                    ((intBuffer[14].toInt() and 0xFF) shl 16) or
                                    ((intBuffer[15].toInt() and 0xFF) shl 24)
                            } else {
                                0
                            }
                            _incomingEvents.tryEmit(
                                PtpEventPacket(
                                    eventCode = header.code,
                                    transactionId = header.transactionId,
                                    parameter = param.toUInt()
                                )
                            )
                        }
                    }
                } else {
                    delay(25L)
                }
            }
        }
    }

    companion object {
        const val NIKON_VENDOR_ID: Int = 0x04B0 // 1200 decimal
        const val PTP_SUBCLASS_STILL_IMAGE: Int = 1
        const val PTP_PROTOCOL_PIMA_15740: Int = 1

        private const val BULK_CHUNK_SIZE: Int = 64 * 1024 // 64 KB
        private const val INITIAL_ASSEMBLY_BUFFER_SIZE: Int = 256 * 1024 // 256 KB reusable frame buffer

        /**
         * Returns true if [device] is a Nikon camera (`vendorId == 0x04B0`) or exposes a USB Still Image
         * Capture interface (`Class 6, Subclass 1, Protocol 1`).
         */
        fun isNikonOrPtpCamera(device: UsbDevice): Boolean {
            if (device.vendorId == NIKON_VENDOR_ID) return true
            return findPtpStillImageInterface(device) != null
        }

        /**
         * Locates the USB Still Image Capture interface (`Class = 6`) on [device].
         */
        fun findPtpStillImageInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&
                    intf.interfaceSubclass == PTP_SUBCLASS_STILL_IMAGE &&
                    intf.interfaceProtocol == PTP_PROTOCOL_PIMA_15740
                ) {
                    return intf
                }
            }
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) {
                    return intf
                }
            }
            return if (device.vendorId == NIKON_VENDOR_ID && device.interfaceCount > 0) {
                device.getInterface(0)
            } else {
                null
            }
        }
    }
}
