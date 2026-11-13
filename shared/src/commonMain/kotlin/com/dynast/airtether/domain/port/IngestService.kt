package com.dynast.airtether.domain.port

import com.dynast.airtether.domain.model.ConnectionState
import com.dynast.airtether.domain.model.IngestSource
import com.dynast.airtether.domain.model.PhotoFormat
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface IngestEvent {
    data class Started(
        val id: String,
        val fileName: String,
        val format: PhotoFormat,
        val source: IngestSource,
        val totalBytes: Long,
    ) : IngestEvent

    data class Progress(
        val id: String,
        val bytesReceived: Long,
        val totalBytes: Long,
    ) : IngestEvent

    data class Completed(
        val id: String,
        val fileName: String,
        val format: PhotoFormat,
        val source: IngestSource,
        val savedPath: String,
        val fileSizeBytes: Long,
        val receivedAtEpochMs: Long,
    ) : IngestEvent

    data class Failed(
        val id: String,
        val fileName: String,
        val reason: String,
    ) : IngestEvent
}

interface IngestService {
    val connectionState: StateFlow<ConnectionState>
    val events: SharedFlow<IngestEvent>

    suspend fun startFtpServer(port: Int = 2121)
    suspend fun stopFtpServer()
}
