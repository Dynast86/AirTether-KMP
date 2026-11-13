package com.dynast.airtether.core.nikonptp.transport

import com.dynast.airtether.core.nikonptp.protocol.PtpCommandPacket
import com.dynast.airtether.core.nikonptp.protocol.PtpConstants
import com.dynast.airtether.core.nikonptp.protocol.PtpContainerType
import com.dynast.airtether.core.nikonptp.protocol.PtpDataPacket
import com.dynast.airtether.core.nikonptp.protocol.PtpEventPacket
import com.dynast.airtether.core.nikonptp.protocol.PtpPacketSerializer
import com.dynast.airtether.core.nikonptp.protocol.PtpTransactionResult
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Wireless PTP/IP transport implementation using Ktor `aSocket` (`commonMain`).
 * Establishes Command & Event TCP sockets on port 15740 for Nikon Wi-Fi tethering (`:app-airtether`).
 */
class TcpIpPtpTransport(
    private val host: String = "192.168.1.1",
    private val port: Int = PtpConstants.PTP_IP_DEFAULT_PORT,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) : PtpTransport {

    override val transportName: String = "PTP/IP ($host:$port)"

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _incomingEvents = MutableSharedFlow<PtpEventPacket>(extraBufferCapacity = 64)
    override val incomingEvents: Flow<PtpEventPacket> = _incomingEvents.asSharedFlow()

    private var selectorManager: SelectorManager? = null
    private var commandSocket: Socket? = null
    private var eventSocket: Socket? = null
    private var commandReadChannel: ByteReadChannel? = null
    private var commandWriteChannel: ByteWriteChannel? = null

    override suspend fun connect() = withContext(ioDispatcher) {
        if (_isConnected.value) return@withContext
        val selector = SelectorManager(ioDispatcher)
        selectorManager = selector

        // Primary Command/Data TCP Socket (Port 15740)
        val cmdSock = aSocket(selector).tcp().connect(host, port)
        commandSocket = cmdSock
        commandReadChannel = cmdSock.openReadChannel()
        commandWriteChannel = cmdSock.openWriteChannel(autoFlush = true)

        // Secondary Event TCP Socket (Port 15740)
        val evtSock = aSocket(selector).tcp().connect(host, port)
        eventSocket = evtSock

        _isConnected.value = true
    }

    override suspend fun disconnect() = withContext(ioDispatcher) {
        _isConnected.value = false
        runCatching { eventSocket?.close() }
        runCatching { commandSocket?.close() }
        runCatching { selectorManager?.close() }
        eventSocket = null
        commandSocket = null
        commandReadChannel = null
        commandWriteChannel = null
        selectorManager = null
    }

    override suspend fun executeTransaction(
        command: PtpCommandPacket,
        outboundData: ByteArray?
    ): PtpTransactionResult = withContext(ioDispatcher) {
        val writeChannel = checkNotNull(commandWriteChannel) {
            "TcpIpPtpTransport is not connected to $host:$port"
        }
        val readChannel = checkNotNull(commandReadChannel) {
            "TcpIpPtpTransport read channel is not initialized"
        }

        // 1. Send Command Packet
        val commandBytes = command.toLittleEndianBytes()
        writeChannel.writeFully(commandBytes, 0, commandBytes.size)
        writeChannel.flush()

        // 2. Send Optional Outbound Data Packet
        if (outboundData != null) {
            val dataBytes = PtpDataPacket(
                opCode = command.opCode,
                transactionId = command.transactionId,
                payload = outboundData
            ).toLittleEndianBytes()
            writeChannel.writeFully(dataBytes, 0, dataBytes.size)
            writeChannel.flush()
        }

        // 3. Read Inbound Container(s): either [DATA -> RESPONSE] or [RESPONSE]
        val firstContainer = readNextContainer(readChannel)
        val firstHeader = PtpPacketSerializer.decodeHeader(firstContainer)

        return@withContext if (firstHeader.type == PtpContainerType.DATA) {
            val payload = firstContainer.copyOfRange(
                PtpConstants.PTP_CONTAINER_HEADER_SIZE,
                firstContainer.size
            )
            val responseContainer = readNextContainer(readChannel)
            val responsePacket = PtpPacketSerializer.decodeResponse(responseContainer)
            PtpTransactionResult(
                response = responsePacket,
                dataPayload = payload
            )
        } else {
            val responsePacket = PtpPacketSerializer.decodeResponse(firstContainer)
            PtpTransactionResult(
                response = responsePacket,
                dataPayload = ByteArray(0)
            )
        }
    }

    private suspend fun readNextContainer(channel: ByteReadChannel): ByteArray {
        val headerBytes = ByteArray(PtpConstants.PTP_CONTAINER_HEADER_SIZE)
        channel.readFully(headerBytes, 0, headerBytes.size)
        val header = PtpPacketSerializer.decodeHeader(headerBytes)
        val remainingLength = (header.length - PtpConstants.PTP_CONTAINER_HEADER_SIZE).coerceAtLeast(0)
        if (remainingLength == 0) {
            return headerBytes
        }
        val fullContainer = ByteArray(header.length)
        headerBytes.copyInto(fullContainer, 0, 0, headerBytes.size)
        channel.readFully(fullContainer, headerBytes.size, header.length)
        return fullContainer
    }
}
