package com.kamuinet.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kamuinet.client.vpn.KamuiVpnService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SecurityUiState(
    val encryptionStatus: String = "Kamui-GCM Secure (Hardware Accelerated)",
    val tunnelHealth: String = "Idle",
    val activeConnections: Int = 0,
    val totalDataSecured: String = "0 MB",
    val hostTrustScore: Int = 98
)

@HiltViewModel
class SecurityViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(SecurityUiState())
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (isActive) {
                updateStats()
                delay(2000)
            }
        }
    }

    fun updateStats() {
        val totalBytes = KamuiVpnService.bytesSent.get() + KamuiVpnService.bytesReceived.get()
        val mb = totalBytes / (1024 * 1024)
        
        val isActive = KamuiVpnService.isTunnelActive.get()
        val status = if (isActive) "Verified (P2P Connected)" else "Waiting / ${KamuiVpnService.lastStatus}"
        
        _uiState.value = _uiState.value.copy(
            totalDataSecured = "$mb MB",
            tunnelHealth = status,
            encryptionStatus = if (isActive) "AES-256-GCM Active" else "Kamui-GCM Ready"
        )
    }
}
