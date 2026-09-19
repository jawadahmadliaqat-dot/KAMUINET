package com.kamuinet.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kamuinet.client.supabase.AuthManager
import com.kamuinet.client.signaling.HostDiscovery
import com.kamuinet.client.data.UsageDao
import com.kamuinet.client.data.LocalUsageLog
import com.kamuinet.client.data.VpnRepository
import com.kamuinet.client.data.HostRepository
import com.kamuinet.client.supabase.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isLoggedIn: Boolean = false,
    val isSharing: Boolean = false,
    val isSignUpMode: Boolean = false,
    val statusText: String = "Not connected",
    val sharingTitle: String = "Ready to share",
    val sharingDetail: String = "Sign in with the same account on both devices",
    val logs: String = "",
    val peerInfo: String = "0 / 2 users connected",
    val endpointInfo: String = "—",
    val linkState: String = "idle",
    val linkType: String = "Direct P2P",
    val hostLocalIp: String = "",
    val hostPublicIp: String = "",
    val hostNetworkType: String = ""
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val usageDao: UsageDao,
    private val subscriptionRepo: SubscriptionRepository,
    private val vpnRepository: VpnRepository,
    private val hostRepository: HostRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        observeSessionStatus()
        observeVpnStatus()
        observeHostStatus()
    }

    private fun observeHostStatus() {
        hostRepository.hostStatus.onEach { status ->
            if (status.isActive) {
                _uiState.value = _uiState.value.copy(
                    isSharing = true,
                    linkState = "hosting",
                    sharingTitle = "Internet Sharing Active",
                    sharingDetail = "Public IP: ${status.publicIp} (${status.networkType})",
                    endpointInfo = "Local Proxy: ${status.localIp}:1080",
                    peerInfo = "${status.peers} / 2 users connected",
                    hostLocalIp = status.localIp,
                    hostPublicIp = status.publicIp,
                    hostNetworkType = status.networkType
                )
            } else {
                _uiState.value = _uiState.value.copy(isSharing = false)
            }
        }.launchIn(viewModelScope)
    }

    private fun observeVpnStatus() {
        vpnRepository.vpnStatus.onEach { status ->
            _uiState.value = _uiState.value.copy(
                statusText = status.message,
                linkType = status.linkType,
                sharingTitle = if (status.isActive) "Secure Sharing Active" else "Ready to share",
                linkState = if (status.isActive) "active" else "idle"
            )
        }.launchIn(viewModelScope)
    }

    private fun observeSessionStatus() {
        AuthManager.sessionStatus.onEach { status ->
            val loggedIn = status is SessionStatus.Authenticated
            _uiState.value = _uiState.value.copy(
                isLoggedIn = loggedIn,
                sharingDetail = if (loggedIn) "Share on the device with internet, then join on the other." else "Sign in with the same account on both devices"
            )
        }.launchIn(viewModelScope)
    }

    fun checkAuthStatus() {
        val loggedIn = AuthManager.isLoggedIn()
        _uiState.value = _uiState.value.copy(
            isLoggedIn = loggedIn,
            sharingDetail = if (loggedIn) "Share on the device with internet, then join on the other." else "Sign in with the same account on both devices"
        )
    }

    fun toggleSignUpMode() {
        _uiState.value = _uiState.value.copy(isSignUpMode = !_uiState.value.isSignUpMode)
    }

    fun addLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _uiState.value = _uiState.value.copy(logs = _uiState.value.logs + "[$time] $message\n")
    }

    fun clearLogs() {
        _uiState.value = _uiState.value.copy(logs = "")
    }

    fun setSharing(sharing: Boolean) {
        _uiState.value = _uiState.value.copy(isSharing = sharing)
    }

    fun updateLinkState(state: String, title: String, detail: String, status: String) {
        _uiState.value = _uiState.value.copy(
            linkState = state,
            sharingTitle = title,
            sharingDetail = detail,
            statusText = status
        )
    }

    fun updateEndpoint(info: String) {
        _uiState.value = _uiState.value.copy(endpointInfo = info)
    }

    fun updateLinkType(type: String) {
        _uiState.value = _uiState.value.copy(linkType = type)
    }

    fun saveUsage(type: String, bytesSent: Long, bytesReceived: Long, targetUid: String?) {
        viewModelScope.launch {
            usageDao.insertLog(LocalUsageLog(
                type = type,
                bytesSent = bytesSent,
                bytesReceived = bytesReceived,
                targetUid = targetUid
            ))
        }
    }

    fun redeemCode(code: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            addLog("Redeeming promo code: $code")
            val result = subscriptionRepo.redeemPromoCode(code)
            if (result.isSuccess) {
                addLog("Promo code redeemed successfully! PRO Unlocked.")
                checkAuthStatus() // Refresh entitlement
                onResult(true, "VIP Pass Redeemed! PRO features unlocked.")
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Redemption failed"
                addLog("Redemption failed: $errorMsg")
                onResult(false, "Failed: $errorMsg")
            }
        }
    }
}
