package com.dynast.airtether.ingest

import com.dynast.airtether.culling.createPlatformCullingRepository
import com.dynast.airtether.domain.port.CullingRepository
import com.dynast.airtether.domain.port.PhotoStorage
import com.dynast.airtether.domain.port.createPlatformPhotoStorage
import com.dynast.airtether.raw.FastRawPreviewExtractor
import com.dynast.airtether.ui.util.resolveLocalIpAddress

/**
 * Platform-aware controller for starting/stopping the FTP server.
 * On Android, delegates to a ForegroundService so FTP Port 2121 survives backgrounding.
 */
interface FtpHostController {
    suspend fun start(port: Int = 2121)
    suspend fun stop()
}

expect fun createPlatformFtpHostController(ftpService: FtpIngestService): FtpHostController

/**
 * Shared singleton runtime so Android ForegroundService and Compose UI
 * operate on the exact same [FtpIngestService] and repositories.
 */
object AirTetherRuntime {
    val photoStorage: PhotoStorage by lazy { createPlatformPhotoStorage() }
    val cullingRepository: CullingRepository by lazy { createPlatformCullingRepository() }
    val rawPreviewExtractor: FastRawPreviewExtractor by lazy { FastRawPreviewExtractor() }
    val ftpIngestService: FtpIngestService by lazy {
        FtpIngestService(
            photoStorage = photoStorage,
            advertisedHost = resolveLocalIpAddress(),
        )
    }
    val ftpHostController: FtpHostController by lazy {
        createPlatformFtpHostController(ftpIngestService)
    }
}
