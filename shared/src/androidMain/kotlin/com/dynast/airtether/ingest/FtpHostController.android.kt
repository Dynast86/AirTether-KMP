package com.dynast.airtether.ingest

object AndroidFtpServiceBridge {
    @Volatile
    var onStartService: ((port: Int) -> Unit)? = null

    @Volatile
    var onStopService: (() -> Unit)? = null
}

actual fun createPlatformFtpHostController(ftpService: FtpIngestService): FtpHostController =
    object : FtpHostController {
        override suspend fun start(port: Int) {
            val startHandler = AndroidFtpServiceBridge.onStartService
            if (startHandler != null) {
                startHandler(port)
            } else {
                ftpService.startFtpServer(port)
            }
        }

        override suspend fun stop() {
            val stopHandler = AndroidFtpServiceBridge.onStopService
            if (stopHandler != null) {
                stopHandler()
            } else {
                ftpService.stopFtpServer()
            }
        }
    }
