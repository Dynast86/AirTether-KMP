package com.dynast.airtether.ingest

import com.dynast.airtether.model.ServerStatus
import com.dynast.airtether.model.TransferStatus
import com.dynast.airtether.storage.JvmPhotoStorage
import com.dynast.airtether.viewmodel.IngestionEvent
import com.dynast.airtether.viewmodel.TetheringViewModel
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FtpIngestServiceJvmTest {

    @Test
    fun ftpStorSavesFileAndEmitsCompletedEventToViewModel() = runBlocking {
        val tempDir = Files.createTempDirectory("airtether-ftp-test").toFile()
        val storage = JvmPhotoStorage(tempDir)
        val service = FtpIngestService(
            photoStorage = storage,
            bindHost = "127.0.0.1",
            advertisedHost = "127.0.0.1",
        )
        val viewModel = TetheringViewModel(
            autoSimulateInitialTransfer = false,
            coroutineScope = this,
            ingestService = service,
        )

        try {
            service.startFtpServer(port = 0)
            val port = requireNotNull(service.boundPort)
            assertTrue(service.serverStatus.value is ServerStatus.Running)

            val completedDeferred = async {
                withTimeout(5_000) {
                    service.events.filterIsInstance<IngestionEvent.TransferCompleted>().first()
                }
            }

            val payload = "FAKE_RAW_OR_JPEG_PAYLOAD_1234567890".encodeToByteArray()
            val selector = SelectorManager(Dispatchers.IO)
            val control = aSocket(selector).tcp().connect(InetSocketAddress("127.0.0.1", port))
            val reader = control.openReadChannel()
            val writer = control.openWriteChannel(autoFlush = true)

            assertTrue(reader.readUTF8Line()!!.startsWith("220"))
            writer.writeStringUtf8("USER camera\r\n")
            assertTrue(reader.readUTF8Line()!!.startsWith("331"))
            writer.writeStringUtf8("PASS \r\n")
            assertTrue(reader.readUTF8Line()!!.startsWith("230"))
            writer.writeStringUtf8("TYPE I\r\n")
            assertTrue(reader.readUTF8Line()!!.startsWith("200"))
            writer.writeStringUtf8("PASV\r\n")
            val pasvLine = reader.readUTF8Line()!!
            assertTrue(pasvLine.startsWith("227"))

            val tuple = pasvLine.substringAfter('(').substringBefore(')').split(',').map { it.toInt() }
            val dataPort = tuple[4] * 256 + tuple[5]
            val dataSocket = aSocket(selector).tcp().connect(InetSocketAddress("127.0.0.1", dataPort))

            writer.writeStringUtf8("STOR DSC_0001.NEF\r\n")
            assertTrue(reader.readUTF8Line()!!.startsWith("150"))

            val dataWriter = dataSocket.openWriteChannel(autoFlush = true)
            dataWriter.writeFully(payload)
            dataWriter.flushAndClose()
            dataSocket.close()

            assertTrue(reader.readUTF8Line()!!.startsWith("226"))
            writer.writeStringUtf8("QUIT\r\n")
            assertTrue(reader.readUTF8Line()!!.startsWith("221"))

            control.close()
            selector.close()

            val completed = completedDeferred.await()
            val savedFile = File(completed.localUri)
            assertTrue(savedFile.exists())
            assertEquals("DSC_0001.NEF", savedFile.name)
            assertEquals(payload.decodeToString(), savedFile.readBytes().decodeToString())

            val topItem = viewModel.uiState.value.items.firstOrNull { it.id == completed.itemId }
            assertEquals(TransferStatus.Completed, topItem?.transferStatus)
        } finally {
            service.stopFtpServer()
            tempDir.deleteRecursively()
        }
    }
}
