package com.dynast.airtether

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dynast.airtether.culling.createPlatformCullingRepository
import com.dynast.airtether.domain.port.createPlatformPhotoStorage
import com.dynast.airtether.ingest.FtpIngestService
import com.dynast.airtether.raw.FastRawPreviewExtractor
import com.dynast.airtether.ui.TetheringScreen
import com.dynast.airtether.ui.theme.AirTetherTheme
import com.dynast.airtether.ui.util.resolveLocalIpAddress
import com.dynast.airtether.viewmodel.TetheringViewModel

@Composable
@Preview
fun App() {
    AirTetherTheme {
        val storage = remember { createPlatformPhotoStorage() }
        val cullingRepository = remember { createPlatformCullingRepository() }
        val ingestService = remember(storage) {
            FtpIngestService(
                photoStorage = storage,
                advertisedHost = resolveLocalIpAddress(),
            )
        }
        val rawExtractor = remember { FastRawPreviewExtractor() }
        val vm = viewModel {
            TetheringViewModel(
                autoSimulateInitialTransfer = true,
                ingestService = ingestService,
                rawPreviewExtractor = rawExtractor,
                photoStorage = storage,
                cullingRepository = cullingRepository,
            )
        }
        TetheringScreen(viewModel = vm)
    }
}
