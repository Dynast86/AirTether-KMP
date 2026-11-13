package com.dynast.airtether.domain.port

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class LiveViewFramePacket(
    val jpegBytes: ByteArray,
    val width: Int = 0,
    val height: Int = 0,
    val timestampMs: Long = 0L,
)

/**
 * Coordinates single-owner access to the connected USB PTP device
 * (e.g. `:core:nikon-ptp`'s NikonSessionManager) so file ingestion and
 * live view never contend on the same USB interface.
 */
interface PtpSessionManager {
    val isConnected: StateFlow<Boolean>
    val isLiveViewActive: StateFlow<Boolean>

    suspend fun openSession(): Result<Unit>
    suspend fun closeSession()

    fun observeLiveViewFrames(): Flow<LiveViewFramePacket>
    suspend fun startLiveView(): Result<Unit>
    suspend fun stopLiveView()

    suspend fun triggerShutter(): Result<Unit>
}
