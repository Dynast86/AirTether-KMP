package com.dynast.airtether.ingest

actual fun createPlatformFtpHostController(ftpService: FtpIngestService): FtpHostController =
    object : FtpHostController {
        override suspend fun start(port: Int) {
            ftpService.startFtpServer(port)
        }

        override suspend fun stop() {
            ftpService.stopFtpServer()
        }
    }
