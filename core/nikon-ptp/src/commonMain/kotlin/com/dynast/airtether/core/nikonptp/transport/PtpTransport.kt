package com.dynast.airtether.core.nikonptp.transport

import com.dynast.airtether.core.nikonptp.protocol.PtpCommandPacket
import com.dynast.airtether.core.nikonptp.protocol.PtpEventPacket
import com.dynast.airtether.core.nikonptp.protocol.PtpTransactionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hardware-agnostic abstraction over physical camera transport layers:
 * - Wireless PTP/IP over TCP (Port 15740 Dual Socket: Command & Event channels)
 * - Wired USB-C PTP over Android USB Host (Bulk IN/OUT + Interrupt IN endpoints)
 */
interface PtpTransport {

    /**
     * Human-readable transport identifier (e.g., "PTP/IP (192.168.1.1:15740)" or "USB-C Bulk PTP").
     */
    val transportName: String

    /**
     * Reactive connection state of the underlying socket or USB interface.
     */
    val isConnected: StateFlow<Boolean>

    /**
     * Asynchronous stream of unsolicited camera events (PTP/IP Event Socket or USB Interrupt endpoint).
     */
    val incomingEvents: Flow<PtpEventPacket>

    /**
     * Opens the underlying transport connection (e.g., TCP handshake + InitCommandRequest, or USB claimInterface).
     */
    suspend fun connect()

    /**
     * Gracefully closes sockets or releases the USB interface.
     */
    suspend fun disconnect()

    /**
     * Executes a single atomic PTP Command -> (Optional Data In/Out) -> Response cycle.
     * Callers must serialize access through `NikonSessionManager`'s transaction `Mutex`.
     */
    suspend fun executeTransaction(
        command: PtpCommandPacket,
        outboundData: ByteArray? = null
    ): PtpTransactionResult
}
