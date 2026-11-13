package com.dynast.airtether.ingest

import com.dynast.airtether.domain.port.IngestService
import com.dynast.airtether.domain.port.PhotoStorage
import com.dynast.airtether.model.ServerStatus
import com.dynast.airtether.model.TetherMediaItem
import com.dynast.airtether.model.TransferStatus
import com.dynast.airtether.viewmodel.IngestionEvent
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Lightweight KMP FTP listener (default Port 2121) built on Ktor Sockets.
 * Supports standard camera FTP tethering commands (USER, PASS, SYST, FEAT,
 * OPTS, PWD, CWD, TYPE, PASV, EPSV, SIZE, STOR, NOOP, QUIT).
 */
class FtpIngestService(
    private val photoStorage: PhotoStorage,
    private val bindHost: String = "0.0.0.0",
    private val advertisedHost: String = "127.0.0.1",
    private val ioContext: CoroutineContext = Dispatchers.Default,
) : IngestService {

    private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    override val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    private val _events = MutableSharedFlow<IngestionEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<IngestionEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + ioContext)
    private var selectorManager: SelectorManager? = null
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var fileCounter: Long = 0L

    var boundPort: Int? = null
        private set

    override suspend fun startFtpServer(port: Int) {
        if (_serverStatus.value is ServerStatus.Running || _serverStatus.value is ServerStatus.Starting) {
            return
        }
        _serverStatus.value = ServerStatus.Starting
        try {
            val selector = SelectorManager(ioContext)
            val server = aSocket(selector).tcp().bind(InetSocketAddress(bindHost, port))
            val actualPort = (server.localAddress as? InetSocketAddress)?.port ?: port

            selectorManager = selector
            serverSocket = server
            boundPort = actualPort
            _serverStatus.value = ServerStatus.Running(ipAddress = advertisedHost, port = actualPort)

            acceptJob = scope.launch {
                while (isActive) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    launch { handleControlClient(client, selector) }
                }
            }
        } catch (t: Throwable) {
            _serverStatus.value = ServerStatus.Error(t.message ?: "Failed to bind FTP port $port")
            cleanupSockets()
        }
    }

    override suspend fun stopFtpServer() {
        acceptJob?.cancelAndJoin()
        acceptJob = null
        cleanupSockets()
        boundPort = null
        _serverStatus.value = ServerStatus.Stopped
    }

    private fun cleanupSockets() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { selectorManager?.close() }
        selectorManager = null
    }

    private suspend fun handleControlClient(
        controlSocket: Socket,
        selector: SelectorManager,
    ) {
        val readChannel: ByteReadChannel = controlSocket.openReadChannel()
        val writeChannel: ByteWriteChannel = controlSocket.openWriteChannel()
        var passiveServer: ServerSocket? = null
        var currentDir = "/"

        suspend fun reply(line: String) {
            writeChannel.writeStringUtf8("$line\r\n")
            writeChannel.flush()
        }

        try {
            reply("220 AirTether FTP Ready")

            while (true) {
                val rawLine = readChannel.readUTF8Line() ?: break
                val line = rawLine.trim()
                if (line.isEmpty()) continue

                val spaceIdx = line.indexOf(' ')
                val cmd = (if (spaceIdx >= 0) line.substring(0, spaceIdx) else line).uppercase()
                val arg = if (spaceIdx >= 0) line.substring(spaceIdx + 1).trim() else ""

                when (cmd) {
                    "USER" -> reply("331 User name okay, need password")
                    "PASS" -> reply("230 User logged in, proceed")
                    "SYST" -> reply("215 UNIX Type: L8")
                    "FEAT" -> {
                        writeChannel.writeStringUtf8("211-Features:\r\n PASV\r\n EPSV\r\n UTF8\r\n SIZE\r\n211 End\r\n")
                        writeChannel.flush()
                    }
                    "OPTS" -> reply("200 Always in UTF8 mode")
                    "PWD", "XPWD" -> reply("257 \"$currentDir\" is current directory")
                    "CWD", "XCWD" -> {
                        currentDir = if (arg.startsWith("/")) arg else "$currentDir/$arg".replace("//", "/")
                        reply("250 Directory successfully changed")
                    }
                    "TYPE" -> reply("200 Type set to $arg")
                    "NOOP" -> reply("200 OK")
                    "SIZE" -> reply("550 Could not get file size")
                    "PASV" -> {
                        runCatching { passiveServer?.close() }
                        val dataServer = aSocket(selector).tcp().bind(InetSocketAddress(bindHost, 0))
                        passiveServer = dataServer
                        val dataPort = (dataServer.localAddress as? InetSocketAddress)?.port ?: 0
                        val hostIp = resolvePasvIp(controlSocket)
                        val ipParts = hostIp.split('.')
                        val p1 = dataPort / 256
                        val p2 = dataPort % 256
                        reply("227 Entering Passive Mode (${ipParts[0]},${ipParts[1]},${ipParts[2]},${ipParts[3]},$p1,$p2)")
                    }
                    "EPSV" -> {
                        runCatching { passiveServer?.close() }
                        val dataServer = aSocket(selector).tcp().bind(InetSocketAddress(bindHost, 0))
                        passiveServer = dataServer
                        val dataPort = (dataServer.localAddress as? InetSocketAddress)?.port ?: 0
                        reply("229 Entering Extended Passive Mode (|||$dataPort|)")
                    }
                    "STOR" -> {
                        val fileName = arg.substringAfterLast('/').substringAfterLast('\\')
                            .ifBlank { "capture_${++fileCounter}.bin" }
                        val dataServer = passiveServer
                        if (dataServer == null) {
                            reply("425 Use PASV or EPSV first")
                            continue
                        }
                        reply("150 Opening binary mode data connection for $fileName")
                        val dataSocket = runCatching { dataServer.accept() }.getOrNull()
                        runCatching { dataServer.close() }
                        passiveServer = null

                        if (dataSocket == null) {
                            reply("425 Cannot open data connection")
                            continue
                        }

                        val ok = receiveFile(fileName, dataSocket)
                        runCatching { dataSocket.close() }
                        if (ok) {
                            reply("226 Transfer complete")
                        } else {
                            reply("451 Requested action aborted: local error in processing")
                        }
                    }
                    "QUIT" -> {
                        reply("221 Goodbye")
                        break
                    }
                    else -> reply("502 Command not implemented: $cmd")
                }
            }
        } catch (_: Throwable) {
            // Control socket closed or interrupted
        } finally {
            runCatching { passiveServer?.close() }
            runCatching { controlSocket.close() }
        }
    }

    private suspend fun receiveFile(fileName: String, dataSocket: Socket): Boolean {
        val itemId = "ftp-${++fileCounter}-${fileName.hashCode()}"
        val mimeType = guessMimeType(fileName)
        var pendingHandle: String? = null

        return try {
            pendingHandle = photoStorage.createPendingFile(fileName, mimeType)
            val initialItem = TetherMediaItem(
                id = itemId,
                fileName = fileName,
                fileSize = 0L,
                transferStatus = TransferStatus.Transferring(0f),
                localUri = null,
                exifMetadata = null,
            )
            _events.emit(IngestionEvent.TransferStarted(initialItem))

            val channel = dataSocket.openReadChannel()
            val buffer = ByteArray(32 * 1024)
            var totalBytes = 0L

            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue

                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                photoStorage.writeChunk(pendingHandle, chunk, totalBytes)
                totalBytes += read
                _events.emit(IngestionEvent.TransferProgress(itemId, progress = 0.5f))
            }

            val savedPath = photoStorage.commitPendingFile(pendingHandle)
            pendingHandle = null
            _events.emit(
                IngestionEvent.TransferCompleted(
                    itemId = itemId,
                    localUri = savedPath,
                    exifMetadata = null,
                )
            )
            true
        } catch (t: Throwable) {
            pendingHandle?.let { runCatching { photoStorage.abortPendingFile(it) } }
            _events.emit(IngestionEvent.TransferFailed(itemId, t.message ?: "FTP transfer failed"))
            false
        }
    }

    private fun resolvePasvIp(controlSocket: Socket): String {
        val localHost = (controlSocket.localAddress as? InetSocketAddress)?.hostname
        val candidate = when {
            localHost == null -> advertisedHost
            localHost == "0.0.0.0" || localHost == "::" || localHost == "0:0:0:0:0:0:0:0" -> advertisedHost
            localHost.contains('.') -> localHost
            else -> advertisedHost
        }
        return if (candidate.split('.').size == 4) candidate else "127.0.0.1"
    }

    private fun guessMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "dng" -> "image/x-adobe-dng"
            "arw" -> "image/x-sony-arw"
            "cr2" -> "image/x-canon-cr2"
            "cr3" -> "image/x-canon-cr3"
            "nef" -> "image/x-nikon-nef"
            "raf" -> "image/x-fuji-raf"
            else -> "application/octet-stream"
        }
    }
}
