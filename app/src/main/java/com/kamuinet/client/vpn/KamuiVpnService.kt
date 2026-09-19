package com.kamuinet.client.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kamuinet.client.data.VpnRepository
import com.kamuinet.client.data.VpnStatus
import com.kamuinet.client.router.TunProxyEngine
import com.kamuinet.client.signaling.HostDiscovery
import com.kamuinet.client.webrtc.TurnCredentialsManager
import com.kamuinet.client.webrtc.WebRtcManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.DataChannel
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.concurrent.thread

@AndroidEntryPoint
class KamuiVpnService : VpnService() {

    @Inject lateinit var vpnRepository: VpnRepository

    private var tun: ParcelFileDescriptor? = null
    private var isRunning = false
    private val TAG = "KamuiVpnService"
    private var rtcManager: WebRtcManager? = null
    private var retryJob: Job? = null
    private var vpnOutput: FileOutputStream? = null
    private var proxyEngine: TunProxyEngine? = null
    private var dataLimitMb: Int = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var connectivityManager: ConnectivityManager? = null
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private var lastNotificationText: String? = null
    private var lastLinkType: String? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.d(TAG, "New network available. ICE status: $lastStatus")
            // ONLY re-offer if already active to handle handover
            // AND ensure we don't spam offers if already in OPEN state
            if (isTunnelActive.get() && lastStatus != "OPEN") {
                rtcManager?.createOffer() 
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager?.registerDefaultNetworkCallback(networkCallback)
    }

    fun protectSocket(socket: Socket): Boolean {
        return protect(socket)
    }

    fun protectDatagramSocket(socket: DatagramSocket): Boolean {
        return protect(socket)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetUid = intent?.getStringExtra(EXTRA_TARGET_UID)
        val targetDeviceId = intent?.getStringExtra(EXTRA_TARGET_DEVICE_ID)
        dataLimitMb = intent?.getIntExtra(EXTRA_LIMIT_MB, 0) ?: 0
        
        isRunning = true
        isTunnelActive.set(false)
        bytesSent.set(0)
        bytesReceived.set(0)
        lastNotificationText = null
        lastLinkType = null
        
        val prefs = getSharedPreferences("kamui_settings", Context.MODE_PRIVATE)
        val forceP2p = prefs.getBoolean("direct_only", false)

        acquireWakeLock()
        createNotificationChannel()
        startForeground(1, buildNotification("Establishing P2P Tunnel..."))

        scope.launch {
            try {
                // 1. Initialize RTC FIRST
                val iceServers = TurnCredentialsManager.fetchIceServers()
                rtcManager = WebRtcManager(this@KamuiVpnService, iceServers, HostDiscovery.myDeviceId, forceP2p).apply {
                    if (targetDeviceId != null) {
                        remoteDeviceId = targetDeviceId
                    }
                    onLinkTypeDetected = { type ->
                        if (type != lastLinkType) {
                            lastLinkType = type
                            updateNotification("P2P Tunnel active ($type)")
                        }
                        vpnRepository.updateLinkType(type)
                    }
                }

                // 2. THEN wire callbacks + signaling listener
                // (Wiring must happen after rtcManager is initialized)
                setupRtcCallbacks(targetUid, targetDeviceId)

                if (targetUid != null) {
                    startHandshakeRetry(targetUid)
                }
            } catch (e: Exception) {
                Log.e(TAG, "RTC Setup fail: ${e.message}")
                cleanupAndStop()
            }
        }

        try {
            updateNotification("Tunnel ready – waiting for P2P...")
        } catch (e: Exception) {
            Log.e(TAG, "Startup fail: ${e.message}", e)
            cleanupAndStop()
        }

        return START_STICKY
    }

    private fun startHandshakeRetry(targetUid: String) {
        Log.d(TAG, "Starting handshake retry for $targetUid")
        retryJob?.cancel()
        retryJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var attempt = 1
            while (isRunning && !isTunnelActive.get()) {
                if (System.currentTimeMillis() - startTime > 45000) {
                    Log.e(TAG, "P2P Handshake Timeout (45s). Stopping service.")
                    updateNotification("Connection timeout. Please try again.")
                    cleanupAndStop()
                    break
                }
                
                Log.d(TAG, "📡 P2P Handshake Attempt #$attempt...")
                rtcManager?.createOffer()
                delay(6000) // Retry every 6 seconds until OPEN
                attempt++
                if (attempt > 4) {
                    updateNotification("Optimizing P2P route... (Attempt $attempt)")
                }
            }
            if (isTunnelActive.get()) {
                Log.d(TAG, "✅ Handshake successful (Tunnel Active)")
            }
        }
    }

    private fun establishVpn() {
        if (tun != null) return
        
        try {
            // CRITICAL: Initialize builder and add disallowed app BEFORE establish()
            val builder = Builder()
                .setSession("KamuiNet Tunnel")
                .addAddress("10.66.0.2", 24)
                .addRoute("0.0.0.0", 0) 
                
                // ORDER: Disallow app BEFORE establish() to prevent routing loops
                .addDisallowedApplication(packageName)
                
                // --- SPLIT TUNNELING: Route ONLY specific apps ---
                // This reduces Host load by 90% by skipping OS background updates.
                val targetPackages = listOf(
                    "com.google.android.youtube",             // YouTube
                    "com.android.chrome",                     // Google Chrome
                    "com.google.android.apps.youtube.music", // YouTube Music
                    "org.mozilla.firefox",                    // Firefox
                    "com.microsoft.emmx"                      // Edge
                )

                var allowedAppCount = 0
                for (pkg in targetPackages) {
                    try {
                        builder.addAllowedApplication(pkg)
                        allowedAppCount++
                        Log.i(TAG, "Split-Tunneling enabled for: $pkg")
                    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                        Log.w(TAG, "App not installed on device, skipping: $pkg")
                    } catch (e: Throwable) {
                        Log.w(TAG, "Could not add allowed app $pkg: ${e.message}")
                    }
                }
                Log.i(TAG, "Split-Tunnel active with $allowedAppCount allowed applications.")

                builder.addDnsServer("1.1.1.1")
                builder.addDnsServer("8.8.8.8")
                
                // MTU 1400: Increased from 1200 to support QUIC (1232b) without local fragmentation
                .setMtu(1400)
                
                .setBlocking(true)

            tun = builder.establish()
            if (tun == null) {
                Log.e(TAG, "Failed to establish TUN: System returned null (likely permission not granted)")
                cleanupAndStop()
                return
            }

            vpnOutput = FileOutputStream(tun!!.fileDescriptor)
            proxyEngine = TunProxyEngine(vpnOutput!!, sendToHost = { data, highPriority -> 
                rtcManager?.sendPacket(data, highPriority) 
            })
            isRunning = true
            
            startTunReader()
            Log.d(TAG, "🔑 VPN Key icon established and TunReader started")
        } catch (e: SecurityException) {
            Log.e(TAG, "VPN SecurityException (UID Mismatch?): ${e.message}")
            // Notify UI to tell user to reinstall if this persists
            cleanupAndStop()
        } catch (e: Exception) {
            Log.e(TAG, "VPN Establishment failed: ${e.message}", e)
            cleanupAndStop()
        }
    }

    private fun cleanupAndStop() {
        isRunning = false
        try { vpnOutput?.close() } catch (_: Exception) {}
        try { tun?.close() } catch (_: Exception) {}
        vpnOutput = null
        tun = null
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KamuiNet::VpnWakeLock").apply {
            acquire() // Held until service destroyed
        }
        
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KamuiNet::VpnWifiLock").apply {
            acquire()
        }
    }

    private suspend fun setupRtcCallbacks(targetUid: String?, targetDeviceId: String?) {
        rtcManager?.onSdpGenerated = { sdp ->
            if (targetUid != null && targetDeviceId != null) {
                HostDiscovery.sendSdp(targetUid, targetDeviceId, sdp.description, if (sdp.type == org.webrtc.SessionDescription.Type.OFFER) "offer" else "answer")
            }
        }

        rtcManager?.onIceCandidateGenerated = { ice ->
            if (targetUid != null && targetDeviceId != null) {
                HostDiscovery.sendIceCandidate(targetUid, targetDeviceId, ice.sdp, ice.sdpMid, ice.sdpMLineIndex)
            }
        }

        Log.d(TAG, "Subscribing to signaling for target: $targetDeviceId...")
        HostDiscovery.listenForSignaling(
            id = "VpnService",
            explicitUid = targetUid,
            onSdp = { sdp, type, fromUid, fromDeviceId ->
                // Same Account Filter: Verify UID AND DeviceID to avoid loops/collisions
                if (fromUid == targetUid && fromDeviceId == targetDeviceId) {
                    if (type == "answer") {
                        rtcManager?.setAnswer(sdp, fromDeviceId)
                    } else if (type == "offer") {
                        rtcManager?.createAnswer(sdp, fromDeviceId)
                    }
                }
            },
            onIce = { sdp, mid, index, fromUid, fromDeviceId ->
                if (fromUid == targetUid && fromDeviceId == targetDeviceId) {
                    rtcManager?.addRemoteIceCandidate(sdp, mid, index)
                }
            }
        )
        Log.d(TAG, "Signaling subscription active. Ready for handshake.")

        rtcManager?.onPacketReceived = { data ->
            val received = bytesReceived.addAndGet(data.size.toLong())
            vpnRepository.updateBytes(bytesSent.get(), received)
            val engine = proxyEngine
            if (engine != null) {
                try {
                    engine.onHostFrame(data)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling incoming RTC packet: ${e.message}", e)
                }
            } else {
                // If tunnel just opened, proxyEngine might be initializing.
                // Log this to see if it's the cause of DNS startup failure.
                if (isTunnelActive.get()) {
                    Log.d(TAG, "Host packet received but proxyEngine not ready yet (${data.size}b)")
                }
            }
        }

        rtcManager?.onChannelStateChanged = { state ->
            lastStatus = state.name
            when (state) {
                DataChannel.State.OPEN -> {
                    updateNotification("P2P Tunnel active")
                    isTunnelActive.set(true)
                    retryJob?.cancel() // Stop retrying handshake once open
                    // CRITICAL ARCHITECTURE FIX: Establish TUN only when tunnel is ready
                    scope.launch {
                        establishVpn()
                    }
                }
                DataChannel.State.CLOSED, DataChannel.State.CLOSING -> {
                    updateNotification("Tunnel closed")
                    isTunnelActive.set(false)
                }
                else -> {
                    isTunnelActive.set(false)
                }
            }
        }
    }

    private fun startTunReader() {
        scope.launch(Dispatchers.IO) {
            val vpnInput = FileInputStream(tun?.fileDescriptor)
            val buffer = ByteArray(32768)
            try {
                while (isRunning && tun != null) {
                    // CONGESTION CONTROL: Non-blocking backpressure if WebRTC buffer is full (>2MB)
                    val buffered = rtcManager?.getBufferedAmount() ?: 0
                    if (buffered > 2 * 1024 * 1024) {
                        delay(10) 
                        continue
                    }

                    val length = vpnInput.read(buffer)
                    if (length > 0) {
                        val packet = buffer.copyOfRange(0, length)
                        withContext(Dispatchers.Default) {
                            proxyEngine?.onTunPacket(packet)
                        }
                        
                        val sent = bytesSent.addAndGet(length.toLong())
                        vpnRepository.updateBytes(sent, bytesReceived.get())
                        if (dataLimitMb > 0 && sent > dataLimitMb * 1024L * 1024L) {
                            Log.w(TAG, "Data limit reached ($dataLimitMb MB). Disconnecting.")
                            withContext(Dispatchers.Main) {
                                stopSelf()
                            }
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "TunReader ended: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "kamui_vpn", "KamuiNet Tunnel Service", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, "kamui_vpn")
            .setContentTitle("KamuiNet VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        if (text == lastNotificationText) return
        lastNotificationText = text
        
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, buildNotification(text))
        vpnRepository.updateState(isTunnelActive.get(), text)
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        retryJob?.cancel()
        connectivityManager?.unregisterNetworkCallback(networkCallback)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        HostDiscovery.stopListening("VpnService")
        proxyEngine?.stop()
        scope.cancel()
        rtcManager?.close()
        try { vpnOutput?.close() } catch (_: Exception) {}
        try { tun?.close() } catch (_: Exception) {}
        vpnOutput = null
        tun = null
        super.onDestroy()
    }

    companion object {
        var instance: KamuiVpnService? = null
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_TARGET_UID = "target_uid"
        const val EXTRA_TARGET_DEVICE_ID = "target_device_id"
        const val EXTRA_LIMIT_MB = "limit_mb"

        var bytesSent = AtomicLong(0)
        var bytesReceived = AtomicLong(0)
        var isTunnelActive = AtomicBoolean(false)
        var lastStatus = "Disconnected"
    }
}
