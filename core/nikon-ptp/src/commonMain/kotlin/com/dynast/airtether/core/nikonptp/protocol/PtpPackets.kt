package com.dynast.airtether.core.nikonptp.protocol

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readIntLe
import kotlinx.io.readShortLe
import kotlinx.io.writeIntLe
import kotlinx.io.writeShortLe

/**
 * PTP Container Type defined in ISO 15740.
 */
enum class PtpContainerType(val rawType: UShort) {
    UNDEFINED(PtpConstants.CONTAINER_UNDEFINED),
    COMMAND(PtpConstants.CONTAINER_COMMAND),
    DATA(PtpConstants.CONTAINER_DATA),
    RESPONSE(PtpConstants.CONTAINER_RESPONSE),
    EVENT(PtpConstants.CONTAINER_EVENT);

    companion object {
        fun fromRaw(raw: UShort): PtpContainerType {
            return entries.firstOrNull { it.rawType == raw } ?: UNDEFINED
        }
    }
}

/**
 * 12-byte Little-Endian PTP Container Header.
 */
data class PtpContainerHeader(
    val length: Int,
    val type: PtpContainerType,
    val code: UShort,
    val transactionId: UInt
)

/**
 * Outbound PTP Command container (Type = 0x0001).
 * Supports up to 5 32-bit unsigned integer parameters in Little-Endian order.
 */
data class PtpCommandPacket(
    val opCode: UShort,
    val transactionId: UInt,
    val params: List<UInt> = emptyList()
) {
    fun toLittleEndianBytes(): ByteArray = PtpPacketSerializer.encodeCommand(this)
}

/**
 * Outbound or Inbound PTP Data container (Type = 0x0002).
 */
data class PtpDataPacket(
    val opCode: UShort,
    val transactionId: UInt,
    val payload: ByteArray
) {
    fun toLittleEndianBytes(): ByteArray = PtpPacketSerializer.encodeData(this)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PtpDataPacket) return false
        return opCode == other.opCode &&
            transactionId == other.transactionId &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = opCode.hashCode()
        result = 31 * result + transactionId.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Inbound PTP Response container (Type = 0x0003).
 */
data class PtpResponsePacket(
    val responseCode: UShort,
    val transactionId: UInt,
    val params: List<UInt> = emptyList()
) {
    val isSuccess: Boolean
        get() = responseCode == PtpConstants.RES_OK

    val isDeviceBusy: Boolean
        get() = responseCode == PtpConstants.RES_DEVICE_BUSY
}

/**
 * Inbound PTP Event notification (Type = 0x0004 or parsed from Nikon 0x90C7 GetEvent).
 */
data class PtpEventPacket(
    val eventCode: UShort,
    val transactionId: UInt = 0u,
    val parameter: UInt = 0u
)

/**
 * Complete result of a serialized PTP transaction (Command -> Optional Data -> Response).
 */
data class PtpTransactionResult(
    val response: PtpResponsePacket,
    val dataPayload: ByteArray = ByteArray(0)
) {
    val isSuccess: Boolean
        get() = response.isSuccess

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PtpTransactionResult) return false
        return response == other.response && dataPayload.contentEquals(other.dataPayload)
    }

    override fun hashCode(): Int {
        return 31 * response.hashCode() + dataPayload.contentHashCode()
    }
}

/**
 * Little-Endian binary serializer/deserializer for PTP containers using `kotlinx-io`.
 */
object PtpPacketSerializer {

    fun encodeCommand(command: PtpCommandPacket): ByteArray {
        val totalLength = PtpConstants.PTP_CONTAINER_HEADER_SIZE + (command.params.size * 4)
        val buffer = Buffer()
        buffer.writeIntLe(totalLength)
        buffer.writeShortLe(PtpContainerType.COMMAND.rawType.toShort())
        buffer.writeShortLe(command.opCode.toShort())
        buffer.writeIntLe(command.transactionId.toInt())
        for (param in command.params) {
            buffer.writeIntLe(param.toInt())
        }
        return buffer.readByteArray()
    }

    fun encodeData(dataPacket: PtpDataPacket): ByteArray {
        val totalLength = PtpConstants.PTP_CONTAINER_HEADER_SIZE + dataPacket.payload.size
        val buffer = Buffer()
        buffer.writeIntLe(totalLength)
        buffer.writeShortLe(PtpContainerType.DATA.rawType.toShort())
        buffer.writeShortLe(dataPacket.opCode.toShort())
        buffer.writeIntLe(dataPacket.transactionId.toInt())
        buffer.write(dataPacket.payload)
        return buffer.readByteArray()
    }

    fun decodeHeader(bytes: ByteArray): PtpContainerHeader {
        require(bytes.size >= PtpConstants.PTP_CONTAINER_HEADER_SIZE) {
            "PTP container requires at least ${PtpConstants.PTP_CONTAINER_HEADER_SIZE} bytes, got ${bytes.size}"
        }
        val buffer = Buffer()
        buffer.write(bytes, 0, PtpConstants.PTP_CONTAINER_HEADER_SIZE)
        val length = buffer.readIntLe()
        val typeRaw = buffer.readShortLe().toUShort()
        val code = buffer.readShortLe().toUShort()
        val transactionId = buffer.readIntLe().toUInt()
        return PtpContainerHeader(
            length = length,
            type = PtpContainerType.fromRaw(typeRaw),
            code = code,
            transactionId = transactionId
        )
    }

    fun decodeResponse(bytes: ByteArray): PtpResponsePacket {
        val header = decodeHeader(bytes)
        require(header.type == PtpContainerType.RESPONSE) {
            "Expected RESPONSE container (0x0003), but got ${header.type}"
        }
        val paramBytesCount = (bytes.size - PtpConstants.PTP_CONTAINER_HEADER_SIZE).coerceAtLeast(0)
        val paramCount = paramBytesCount / 4
        val params = ArrayList<UInt>(paramCount)
        if (paramCount > 0) {
            val buffer = Buffer()
            buffer.write(
                bytes,
                PtpConstants.PTP_CONTAINER_HEADER_SIZE,
                PtpConstants.PTP_CONTAINER_HEADER_SIZE + (paramCount * 4)
            )
            repeat(paramCount) {
                params.add(buffer.readIntLe().toUInt())
            }
        }
        return PtpResponsePacket(
            responseCode = header.code,
            transactionId = header.transactionId,
            params = params
        )
    }

    /**
     * Parses the payload of Nikon's `0x90C7` (GetEvent) command:
     * - First 2 bytes (Little-Endian UShort): number of event entries.
     * - Each entry (6 bytes): EventCode (UShort, 2B) + Parameter (UInt, 4B).
     */
    fun decodeNikonGetEventPayload(payload: ByteArray, transactionId: UInt = 0u): List<PtpEventPacket> {
        if (payload.size < 2) return emptyList()
        val buffer = Buffer()
        buffer.write(payload)
        val count = buffer.readShortLe().toUShort().toInt()
        val events = ArrayList<PtpEventPacket>(count)
        repeat(count) {
            if (buffer.size >= 6L) {
                val eventCode = buffer.readShortLe().toUShort()
                val param = buffer.readIntLe().toUInt()
                events.add(
                    PtpEventPacket(
                        eventCode = eventCode,
                        transactionId = transactionId,
                        parameter = param
                    )
                )
            }
        }
        return events
    }
}
