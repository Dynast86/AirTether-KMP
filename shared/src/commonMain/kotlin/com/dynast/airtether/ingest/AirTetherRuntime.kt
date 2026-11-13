package com.dynast.airtether.ingest

import com.dynast.airtether.core.nikonptp.session.NikonSessionManager
import com.dynast.airtether.core.nikonptp.transport.MockPtpTransport
import com.dynast.airtether.core.nikonptp.transport.PtpTransport
import com.dynast.airtether.culling.createPlatformCullingRepository
import com.dynast.airtether.domain.port.CullingRepository
import com.dynast.airtether.domain.port.PhotoStorage
import com.dynast.airtether.domain.port.createPlatformPhotoStorage
import com.dynast.airtether.raw.FastRawPreviewExtractor
import com.dynast.airtether.ui.util.resolveLocalIpAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
 * Shared singleton runtime so Android ForegroundService, USB PTP monitor, and Compose UI
 * operate on the exact same [FtpIngestService], [NikonPtpSessionAdapter], and repositories.
 */
object AirTetherRuntime {
    private val ptpScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    val nikonSessionManager: NikonSessionManager by lazy {
        NikonSessionManager(
            transport = MockPtpTransport(),
            scope = ptpScope,
        )
    }

    val ptpSessionAdapter: NikonPtpSessionAdapter by lazy {
        NikonPtpSessionAdapter(
            sessionManager = nikonSessionManager,
            photoStorage = photoStorage,
            scope = ptpScope,
        )
    }

    suspend fun attachUsbPtpTransport(
        transport: PtpTransport,
        autoStartLiveView: Boolean = false,
    ): Boolean {
        return nikonSessionManager.swapTransport(
            newTransport = transport,
            autoStartLiveView = autoStartLiveView,
        )
    }

    suspend fun detachUsbPtpTransport() {
        nikonSessionManager.closeSession()
        nikonSessionManager.swapTransport(
            newTransport = MockPtpTransport(),
            autoStartLiveView = false,
        )
        nikonSessionManager.closeSession()
    }
}
