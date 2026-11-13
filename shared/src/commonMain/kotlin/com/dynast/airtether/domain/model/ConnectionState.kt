package com.dynast.airtether.domain.model

sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data class FtpListening(
        val hostAddress: String,
        val port: Int = 2121,
    ) : ConnectionState

    data class UsbConnected(
        val deviceName: String,
        val supportsLiveView: Boolean = false,
    ) : ConnectionState

    data class Error(val message: String) : ConnectionState
}
