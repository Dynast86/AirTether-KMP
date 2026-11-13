package com.dynast.airtether.domain.port

import com.dynast.airtether.domain.model.LiveViewFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Coordinates single-owner access to the connected USB PTP device so that
 * file ingestion (`feature/ingest-ftp-usb`) and live view (`feature/ptp-liveview`)
 * never contend on the same USB interface or transaction pipeline.
 */
interface PtpSessionManager {
    val isConnected: StateFlow<Boolean>
    val isLiveViewActive: StateFlow<Boolean>

    suspend fun openSession(): Result<Unit>
    suspend fun closeSession()

    fun observeLiveViewFrames(): Flow<LiveViewFrame>
    suspend fun startLiveView(): Result<Unit>
    suspend fun stopLiveView()

    suspend fun triggerShutter(): Result<Unit>
}
