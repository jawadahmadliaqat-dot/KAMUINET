package com.kamuinet.client.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnRepository @Inject constructor() {

    private val _vpnStatus = MutableStateFlow(VpnStatus())
    val vpnStatus: StateFlow<VpnStatus> = _vpnStatus.asStateFlow()

    fun updateVpnStatus(status: VpnStatus) {
        _vpnStatus.value = status
    }

    fun updateBytes(sent: Long, received: Long, saved: Long = 0) {
        _vpnStatus.value = _vpnStatus.value.copy(
            bytesSent = sent,
            bytesReceived = received,
            bytesSaved = _vpnStatus.value.bytesSaved + saved
        )
    }

    fun updateLinkType(type: String) {
        _vpnStatus.value = _vpnStatus.value.copy(linkType = type)
    }

    fun updateState(isActive: Boolean, message: String) {
        _vpnStatus.value = _vpnStatus.value.copy(
            isActive = isActive,
            message = message
        )
    }
}

data class VpnStatus(
    val isActive: Boolean = false,
    val message: String = "Disconnected",
    val linkType: String = "Unknown",
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val bytesSaved: Long = 0
)

