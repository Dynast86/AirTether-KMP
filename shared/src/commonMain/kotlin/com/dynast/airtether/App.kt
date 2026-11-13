package com.dynast.airtether

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dynast.airtether.ingest.AirTetherRuntime
import com.dynast.airtether.ui.TetheringScreen
import com.dynast.airtether.ui.theme.AirTetherTheme
import com.dynast.airtether.viewmodel.TetheringViewModel

@Composable
@Preview
fun App() {
    AirTetherTheme {
        val vm = viewModel {
            TetheringViewModel(
                autoSimulateInitialTransfer = true,
                ingestService = AirTetherRuntime.ftpIngestService,
                rawPreviewExtractor = AirTetherRuntime.rawPreviewExtractor,
                photoStorage = AirTetherRuntime.photoStorage,
                cullingRepository = AirTetherRuntime.cullingRepository,
                ftpHostController = AirTetherRuntime.ftpHostController,
                ptpSessionManager = AirTetherRuntime.ptpSessionAdapter,
                ptpIngestionEvents = AirTetherRuntime.ptpSessionAdapter.ingestionEvents,
            )
        }
        TetheringScreen(viewModel = vm)
    }
}
