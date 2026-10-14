package com.kamuinet.client.router

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kamuinet.client.MainActivity
import com.kamuinet.client.data.HostRepository
import com.kamuinet.client.signaling.HostDiscovery
import com.kamuinet.client.util.IpUtils
import com.kamuinet.client.webrtc.TurnCredentialsManager
import com.kamuinet.client.webrtc.WebRtcManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import org.webrtc.DataChannel
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@AndroidEntryPoint
class KamuiHostService : Service() {

    @Inject lateinit var hostRepository: HostRepository

    private val TAG = "KamuiHostService"
    private val NOTIFICATION_ID = 101
    private val CHANNEL_ID = "kamui_host"
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    
    private var hostRtcManager: WebRtcManager? = null
    private var hostRelayManager: HostRelayManager? = null
    
    private var currentUserId: String? = null
    private var targetUid: String? = null
    private var targetDeviceId: String? = null

    private var localIp: String = "0.0.0.0"
    private var publicIp: String = "Detecting..."
    private var networkType: String = "Unknown"

    private data class IceCandidateWrapper(val sdp: String, val mid: String, val index: Int)
    private val pendingIceCandidates = ConcurrentHashMap<String, MutableList<IceCandidateWrapper>>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand triggered (Intent: ${intent != null})")
        
        val action = intent?.action
        if (action == ACTION_STOP) {
            Log.d(TAG, "Stop action received. Shutting down service.")
            stopSelf()
            return START_NOT_STICKY
        }

        localIp = intent?.getStringExtra(EXTRA_IP) ?: "0.0.0.0"
        val port = intent?.getIntExtra(EXTRA_PORT, 1080) ?: 1080
        val mbLimit = intent?.getIntExtra(EXTRA_LIMIT, 0) ?: 0
        currentUserId = intent?.getStringExtra(EXTRA_USER_ID)
        
        networkType = IpUtils.getNetworkType(this)
        
        acquireLocks()
        Log.d(TAG, "Starting host on $networkType ($localIp)")
        startForeground(localIp)
        
        Log.d(TAG, "Initializing Host Logic (startHosting)")
        startHosting(localIp, port, mbLimit)
        
        // Asynchronously fetch Public IP for UI
        scope.launch {
            publicIp = IpUtils.getPublicIp()
            updateHostStatus(0)
            startForeground(localIp) // Refresh notification with public IP if possible
        }
        
        return START_STICKY
    }

    private fun updateHostStatus(peers: Int) {
        hostRepository.updateStatus(true, localIp, publicIp, networkType, peers)
    }

    private fun startForeground(ip: String) {
        val stopIntent = Intent(this, KamuiHostService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        val displayIp = if (publicIp != "Detecting..." && publicIp != "unknown") publicIp else ip
        val netLabel = if (networkType != "Unknown") " ($networkType)" else ""

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KamuiNet Host: Active$netLabel")
            .setContentText("Sharing internet from $displayIp")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Sharing", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startHosting(localIp: String, port: Int, mbLimit: Int) {
        val prefs = getSharedPreferences("kamui_settings", Context.MODE_PRIVATE)
        val forceP2p = prefs.getBoolean("direct_only", false)

        scope.launch {
            HostDiscovery.setHostOnline(localIp, port, mbLimit, "local")
        }

        hostRelayManager = HostRelayManager(
            sendToPeer = { bytes, priority -> hostRtcManager?.sendPacket(bytes, priority) },
            getBufferedAmount = { hostRtcManager?.getBufferedAmount() ?: 0 },
            log = { msg -> Log.d(TAG, msg) }
        )

        initWebRtc(forceP2p)
    }

    private fun initWebRtc(forceP2p: Boolean) {
        scope.launch {
            try {
                val iceServers = TurnCredentialsManager.fetchIceServers()
                hostRtcManager?.close()
                hostRtcManager = WebRtcManager(this@KamuiHostService, iceServers, HostDiscovery.myDeviceId, forceP2p).apply {
                    onSdpGenerated = { sdp ->
                        val remoteId = targetDeviceId ?: remoteDeviceId
                        // ROOM ALIGNMENT: Broadcast to OWN room so client (who is listening here) gets it
                        val roomUid = currentUserId ?: HostDiscovery.myDeviceId
                        Log.d(TAG, "[SDP_STEP] Sending ${sdp.type} to room: $roomUid | TargetDevice: $remoteId")
                        HostDiscovery.sendSdp(roomUid, remoteId, sdp.description, if (sdp.type == org.webrtc.SessionDescription.Type.OFFER) "offer" else "answer")
                    }
                    onIceCandidateGenerated = { ice ->
                        val remoteId = targetDeviceId ?: remoteDeviceId
                        val roomUid = currentUserId ?: HostDiscovery.myDeviceId
                        HostDiscovery.sendIceCandidate(roomUid, remoteId, ice.sdp, ice.sdpMid, ice.sdpMLineIndex)
                    }
                    onPacketReceived = { data ->
                        hostRelayManager?.onClientPacket(data)
                    }
                    onBufferedAmountLow = {
                        hostRelayManager?.onBufferedAmountLow()
                    }
                    onChannelStateChanged = { state ->
                        Log.d(TAG, "Channel State Changed: $state")
                        if (state == DataChannel.State.OPEN) {
                            Log.d(TAG, "🚀 P2P Tunnel Established")
                            updateHostStatus(1)
                        } else if (state == DataChannel.State.CLOSED || state == DataChannel.State.CLOSING) {
                            Log.d(TAG, "Tunnel Closed/Closing. Resetting targets.")
                            targetUid = null
                            targetDeviceId = null
                            updateHostStatus(0)
                        }
                    }
                }
                
                setupSignalingListener()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WebRTC: ${e.message}")
            }
        }
    }

    private fun setupSignalingListener() {
        val roomUid = currentUserId ?: HostDiscovery.myDeviceId
        Log.d(TAG, "Setting up signaling listener for Host Mode (Room: p2p-signals-$roomUid)")
        
        scope.launch {
            HostDiscovery.listenForSignaling(
                id = "HostService",
                explicitUid = currentUserId,
                onSdp = { sdp, type, fromUid, fromDeviceId ->
                    if (type == "offer") {
                        Log.d(TAG, "🔥 OFFER RECEIVED! (Room: p2p-signals-$roomUid) from $fromDeviceId")
                        
                        // Guard: If we already have an OPEN data channel with this device, ignore duplicate offer
                        if (hostRtcManager?.getChannelState() == DataChannel.State.OPEN && targetDeviceId == fromDeviceId) {
                            Log.d(TAG, "[HANDSHAKE] Channel already OPEN with $fromDeviceId. Ignoring duplicate offer.")
                            return@listenForSignaling
                        }

                        if (targetDeviceId != null && targetDeviceId != fromDeviceId) {
                            Log.w(TAG, "[HANDSHAKE] Busy with $targetDeviceId. Resetting for new offer from $fromDeviceId")
                            val prefs = getSharedPreferences("kamui_settings", Context.MODE_PRIVATE)
                            val forceP2p = prefs.getBoolean("direct_only", false)
                            initWebRtc(forceP2p) // This closes existing and starts fresh
                        }

                        targetUid = fromUid
                        targetDeviceId = fromDeviceId
                        
                        // Trigger answer immediately
                        hostRtcManager?.createAnswer(sdp, fromDeviceId)

                        // Drain buffered candidates
                        pendingIceCandidates.remove(fromDeviceId)?.let { buffered ->
                            Log.d(TAG, "📩 [HANDSHAKE] Draining ${buffered.size} buffered ICE candidates for $fromDeviceId")
                            buffered.forEach { hostRtcManager?.addRemoteIceCandidate(it.sdp, it.mid, it.index) }
                        }
                    }
                },
                onIce = { sdp, mid, index, fromUid, fromDeviceId ->
                    if (fromUid == targetUid && fromDeviceId == targetDeviceId) {
                        Log.d(TAG, "📩 [HANDSHAKE] Received ICE Candidate from $fromDeviceId")
                        hostRtcManager?.addRemoteIceCandidate(sdp, mid, index)
                    } else {
                        // Buffer early candidates from any device
                        Log.d(TAG, "📩 [HANDSHAKE] Buffering early ICE from $fromDeviceId")
                        val list = pendingIceCandidates.getOrPut(fromDeviceId) { 
                            java.util.Collections.synchronizedList(mutableListOf()) 
                        }
                        list.add(IceCandidateWrapper(sdp, mid, index))
                    }
                }
            )
            Log.d(TAG, "Host signaling listener ready.")
        }
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld == true) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KamuiNet:HostWakeLock").apply {
            acquire(24 * 60 * 60 * 1000L) // 24 hours
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KamuiNet:HostWifiLock").apply {
            acquire()
        }
        Log.d(TAG, "High-performance locks acquired")
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
        Log.d(TAG, "Locks released")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "KamuiNet Host Service", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Stopping Host Service")
        scope.launch {
            HostDiscovery.setHostOffline()
        }
        HostDiscovery.stopListening("HostService")
        
        hostRelayManager?.shutdown()
        hostRtcManager?.close()
        
        releaseLocks()
        
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.kamuinet.client.STOP_HOST"
        const val EXTRA_IP = "local_ip"
        const val EXTRA_PORT = "port"
        const val EXTRA_LIMIT = "mb_limit"
        const val EXTRA_USER_ID = "user_id"
    }
}
