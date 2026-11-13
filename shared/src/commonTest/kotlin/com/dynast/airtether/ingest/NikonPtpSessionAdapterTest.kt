package com.dynast.airtether.ingest

import com.dynast.airtether.core.nikonptp.protocol.PtpCommandPacket
import com.dynast.airtether.core.nikonptp.protocol.PtpConstants
import com.dynast.airtether.core.nikonptp.protocol.PtpResponsePacket
import com.dynast.airtether.core.nikonptp.protocol.PtpTransactionResult
import com.dynast.airtether.core.nikonptp.session.NikonSessionManager
import com.dynast.airtether.core.nikonptp.transport.PtpTransport
import com.dynast.airtether.domain.port.PhotoStorage
import com.dynast.airtether.viewmodel.IngestionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NikonPtpSessionAdapterTest {

    private class FakePtpTransport : PtpTransport {
        override val transportName: String = "FakePTP"
        override val isConnected: StateFlow<Boolean> = MutableStateFlow(true)
        private val _events = MutableSharedFlow<com.dynast.airtether.core.nikonptp.protocol.PtpEventPacket>(extraBufferCapacity = 8)
        override val incomingEvents: Flow<com.dynast.airtether.core.nikonptp.protocol.PtpEventPacket> = _events

        override suspend fun connect() {}
        override suspend fun disconnect() {}

        override suspend fun executeTransaction(
            command: PtpCommandPacket,
            outboundData: ByteArray?,
        ): PtpTransactionResult {
            return when (command.opCode) {
                PtpConstants.OP_GET_DEVICE_INFO,
                PtpConstants.OP_OPEN_SESSION,
                PtpConstants.OP_CLOSE_SESSION -> {
                    PtpTransactionResult(PtpResponsePacket(PtpConstants.RES_OK, command.transactionId))
                }
                PtpConstants.OP_GET_OBJECT_INFO -> {
                    val name = "DSC_9001.NEF\u0000"
                    val header = ByteArray(52)
                    val strLen = byteArrayOf(name.length.toByte())
                    val utf16 = ByteArray(name.length * 2).apply {
                        name.forEachIndexed { idx, c ->
                            this[idx * 2] = (c.code and 0xFF).toByte()
                            this[idx * 2 + 1] = ((c.code ushr 8) and 0xFF).toByte()
                        }
                    }
                    PtpTransactionResult(
                        response = PtpResponsePacket(PtpConstants.RES_OK, command.transactionId),
                        dataPayload = header + strLen + utf16,
                    )
                }
                PtpConstants.OP_GET_OBJECT -> {
                    PtpTransactionResult(
                        response = PtpResponsePacket(PtpConstants.RES_OK, command.transactionId),
                        dataPayload = "PTP_RAW_BYTES".encodeToByteArray(),
                    )
                }
                else -> PtpTransactionResult(PtpResponsePacket(PtpConstants.RES_OK, command.transactionId))
            }
        }
    }

    private class InMemoryPhotoStorage : PhotoStorage {
        val saved = mutableMapOf<String, ByteArray>()
        private val pending = mutableMapOf<String, Pair<String, ByteArray>>()

        override suspend fun createPendingFile(fileName: String, mimeType: String): String {
            pending[fileName] = fileName to ByteArray(0)
            return fileName
        }

        override suspend fun writeChunk(pendingHandle: String, bytes: ByteArray, offset: Long) {
            val (name, existing) = pending[pendingHandle] ?: return
            pending[pendingHandle] = name to (existing + bytes)
        }

        override suspend fun commitPendingFile(pendingHandle: String): String {
            val (name, bytes) = pending.remove(pendingHandle)!!
            saved[name] = bytes
            return "/memory/$name"
        }

        override suspend fun abortPendingFile(pendingHandle: String) {
            pending.remove(pendingHandle)
        }

        override suspend fun savePreviewJpeg(photoId: String, jpegBytes: ByteArray): String = "/memory/$photoId.jpg"
    }

    @Test
    fun ingestsObjectHandleFromPtpSessionAndEmitsCompletedEvent() = runBlocking {
        val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakePtpTransport()
        val sessionManager = NikonSessionManager(transport = transport, scope = adapterScope)
        val storage = InMemoryPhotoStorage()
        val adapter = NikonPtpSessionAdapter(
            sessionManager = sessionManager,
            photoStorage = storage,
            scope = adapterScope,
        )

        try {
            assertTrue(adapter.openSession().isSuccess)
            val completedDeferred = async {
                withTimeout(3_000) {
                    adapter.ingestionEvents.filterIsInstance<IngestionEvent.TransferCompleted>().first()
                }
            }

            adapter.ingestObjectHandle(0x00001001u)

            val completed = completedDeferred.await()
            assertEquals("/memory/DSC_9001.NEF", completed.localUri)
            assertEquals("PTP_RAW_BYTES", storage.saved["DSC_9001.NEF"]?.decodeToString())
        } finally {
            adapterScope.cancel()
        }
    }
}
