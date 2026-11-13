package com.dynast.airtether.domain.port

import com.dynast.airtether.model.ServerStatus
import com.dynast.airtether.viewmodel.IngestionEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface IngestService {
    val serverStatus: StateFlow<ServerStatus>
    val events: SharedFlow<IngestionEvent>

    suspend fun startFtpServer(port: Int = 2121)
    suspend fun stopFtpServer()
}
