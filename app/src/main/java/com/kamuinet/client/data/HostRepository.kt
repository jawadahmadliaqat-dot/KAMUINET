package com.kamuinet.client.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HostRepository @Inject constructor() {

    private val _hostStatus = MutableStateFlow(HostStatus())
    val hostStatus: StateFlow<HostStatus> = _hostStatus.asStateFlow()

    fun updateStatus(isActive: Boolean, localIp: String, publicIp: String, networkType: String, peers: Int) {
        _hostStatus.value = HostStatus(isActive, localIp, publicIp, networkType, peers)
    }

    fun updatePeers(count: Int) {
        _hostStatus.value = _hostStatus.value.copy(peers = count)
    }
}

data class HostStatus(
    val isActive: Boolean = false,
    val localIp: String = "0.0.0.0",
    val publicIp: String = "Detecting...",
    val networkType: String = "Unknown",
    val peers: Int = 0
)
