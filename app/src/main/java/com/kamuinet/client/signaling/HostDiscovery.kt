package com.kamuinet.client.signaling

import android.util.Log
import com.kamuinet.client.KamuiApplication
import com.kamuinet.client.data.ActiveHost
import com.kamuinet.client.data.HostInfo
import com.kamuinet.client.data.SignalingRow
import com.kamuinet.client.supabase.KamuiSupabase
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object HostDiscovery {

    private const val TAG = "HostDiscovery"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val incomingChannels = ConcurrentHashMap<String, RealtimeChannel>()
    private val listenJobs = ConcurrentHashMap<String, Job>()
    private val outgoingChannels = ConcurrentHashMap<String, RealtimeChannel>()
    private val channelMutex = Mutex()

    // Joker Stealth: Obfuscated room prefix
    private val ROOM_PREFIX = com.kamuinet.client.util.CryptoUtils.reveal("CBAfDBoMCgwQCBA=") // "p2p-signals-"

    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(KamuiApplication.instance)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            KamuiApplication.instance,
            "kamui_identity_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val myDeviceId: String by lazy {
        var id = encryptedPrefs.getString("device_uuid", null)
        if (id == null) {
            // Migrate from old plain prefs if exists, then delete it
            val oldPrefs = KamuiApplication.instance.getSharedPreferences("kamui_identity", android.content.Context.MODE_PRIVATE)
            val oldId = oldPrefs.getString("device_uuid", null)
            
            id = if (oldId != null && !oldId.contains(android.os.Build.MODEL)) {
                oldId // Keep existing UUID but move to encrypted
            } else {
                java.util.UUID.randomUUID().toString().uppercase()
            }
            
            encryptedPrefs.edit().putString("device_uuid", id).apply()
            oldPrefs.edit().remove("device_uuid").apply()
            Log.d(TAG, "Initialized secure persistent Kamui UUID: $id")
        }
        id
    }

    private val sdpListeners = ConcurrentHashMap<String, (String, String, String, String) -> Unit>()
    private val iceListeners = ConcurrentHashMap<String, (String, String, Int, String, String) -> Unit>()
    private val heartbeatListeners = ConcurrentHashMap<String, (ip: String, port: Int, fromDeviceId: String) -> Unit>()

    private suspend fun getChannelFor(uid: String): RealtimeChannel = channelMutex.withLock {
        val channelId = "$ROOM_PREFIX$uid"
        
        // 1. Check if this is one of the incoming channels we already have
        incomingChannels[uid]?.let { 
            val status = it.status.value
            if (status == RealtimeChannel.Status.SUBSCRIBED || status == RealtimeChannel.Status.SUBSCRIBING) {
                return it
            }
        }

        // 2. Check outgoing cache
        val existing = outgoingChannels[uid]
        if (existing != null) {
            val status = existing.status.value
            if (status == RealtimeChannel.Status.SUBSCRIBED || status == RealtimeChannel.Status.SUBSCRIBING) {
                return existing
            }
        }
        
        Log.d(TAG, "[CHANNEL] Creating/Subscribing to new channel: $channelId")
        val channel = KamuiSupabase.client.realtime.channel(channelId)
        try {
            channel.subscribe(blockUntilSubscribed = true)
            outgoingChannels[uid] = channel
            return channel
        } catch (e: Exception) {
            Log.e(TAG, "[CHANNEL] Subscription failed for $channelId: ${e.message}")
            throw e
        }
    }

    suspend fun discover(targetDeviceId: String? = null): HostInfo? {
        val myUid = currentUid() ?: return null
        Log.d(TAG, "[DISCOVERY] Starting BROADCAST-based search in room: $myUid. TargetDevice: $targetDeviceId (My Device: $myDeviceId)")
        
        val deferredHost = CompletableDeferred<HostInfo?>()
        val discoveryId = "Discovery-${java.util.UUID.randomUUID()}"
        
        listenForSignaling(
            id = discoveryId,
            onSdp = { _, _, _, _ -> },
            onIce = { _, _, _, _, _ -> },
            onHeartbeat = { ip, port, deviceId ->
                // Ensure we don't connect to ourselves
                if (deviceId != myDeviceId) {
                    if (targetDeviceId == null || deviceId == targetDeviceId) {
                        Log.d(TAG, "[DISCOVERY] Found MATCHING host heartbeat: $ip:$port from $deviceId")
                        deferredHost.complete(HostInfo(myUid, deviceId, "local", ip, port))
                    }
                }
            }
        )

        val result = try {
            withTimeout(25000) {
                deferredHost.await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[DISCOVERY] Timeout: No host heartbeat found via broadcast for TargetDevice: $targetDeviceId")
            null
        } finally {
            stopListening(discoveryId)
        }

        return result
    }

    suspend fun setHostOnline(
        localIp: String,
        socksPort: Int = 1080,
        mbLimit: Int = 0,
        mode: String = "local"
    ) {
        val uid = currentUid() ?: return
        try {
            val row = ActiveHost(
                uid = uid,
                deviceId = myDeviceId,
                localIp = localIp,
                socksPort = socksPort,
                mode = mode,
                status = "online",
                mbLimit = mbLimit
            )
            KamuiSupabase.db.from("active_hosts").upsert(row)
            Log.d(TAG, "Host status updated in DB: $uid | Device: $myDeviceId")
            
            startHeartbeatBroadcast(localIp, socksPort)
        } catch (e: Exception) {
            Log.e(TAG, "setHostOnline failed: ${e.message}", e)
        }
    }

    private var heartbeatJob: Job? = null
    private fun startHeartbeatBroadcast(ip: String, port: Int) {
        val myUid = currentUid() ?: return
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            val channel = getChannelFor(myUid)
            while (isActive) {
                try {
                    val row = SignalingRow(
                        targetUid = myUid,
                        fromUid = myUid,
                        targetDeviceId = null, // Heartbeat is for everyone in the room
                        fromDeviceId = myDeviceId,
                        kind = "heartbeat",
                        payload = mapOf(
                            "ip" to ip,
                            "port" to port.toString(),
                            "from_device_id" to myDeviceId
                        )
                    )
                    channel.broadcast("signal", row)
                    Log.d(TAG, "[HEARTBEAT_BROADCAST] Sent from $myDeviceId")
                } catch (e: Exception) {
                    Log.w(TAG, "[HEARTBEAT_BROADCAST] Failed: ${e.message}")
                }
                delay(3000)
            }
        }
    }

    suspend fun setHostOffline() {
        val uid = currentUid() ?: return
        heartbeatJob?.cancel()
        try {
            KamuiSupabase.db.from("active_hosts").update(
                mapOf("status" to "offline")
            ) {
                filter { eq("uid", uid) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "setHostOffline failed: ${e.message}", e)
        }
    }

    fun sendSdp(targetUid: String, targetDeviceId: String, sdp: String, type: String) {
        val myUid = currentUid() ?: return
        scope.launch {
            withContext(NonCancellable) {
                try {
                    val channel = getChannelFor(targetUid)
                    val row = SignalingRow(
                        targetUid = targetUid,
                        fromUid = myUid,
                        targetDeviceId = targetDeviceId,
                        fromDeviceId = myDeviceId,
                        kind = "sdp",
                        payload = mapOf(
                            "sdp" to sdp,
                            "type" to type,
                            "from_device_id" to myDeviceId
                        )
                    )
                    channel.broadcast("signal", row)
                    Log.d(TAG, "[BROADCAST_SENT] SDP type=$type to target=$targetUid | Device: $targetDeviceId")
                } catch (e: Exception) {
                    Log.e(TAG, "sendSdp broadcast failed: ${e.message}")
                }
            }
        }
    }

    fun sendIceCandidate(targetUid: String, targetDeviceId: String, candidateSdp: String, sdpMid: String, sdpMLineIndex: Int) {
        val myUid = currentUid() ?: return
        scope.launch {
            withContext(NonCancellable) {
                try {
                    val channel = getChannelFor(targetUid)
                    val row = SignalingRow(
                        targetUid = targetUid,
                        fromUid = myUid,
                        targetDeviceId = targetDeviceId,
                        fromDeviceId = myDeviceId,
                        kind = "ice",
                        payload = mapOf(
                            "sdp" to candidateSdp,
                            "sdpMid" to sdpMid,
                            "sdpMLineIndex" to sdpMLineIndex.toString(),
                            "from_device_id" to myDeviceId
                        )
                    )
                    channel.broadcast("signal", row)
                    Log.d(TAG, "[BROADCAST_SENT] ICE to target=$targetUid | Device: $targetDeviceId")
                } catch (e: Exception) {
                    Log.e(TAG, "sendIce broadcast failed: ${e.message}")
                }
            }
        }
    }

    suspend fun listenForSignaling(
        id: String,
        explicitUid: String? = null,
        onSdp: (sdp: String, type: String, fromUid: String, fromDeviceId: String) -> Unit,
        onIce: (sdp: String, mid: String, index: Int, fromUid: String, fromDeviceId: String) -> Unit,
        onHeartbeat: ((ip: String, port: Int, fromDeviceId: String) -> Unit)? = null
    ): RealtimeChannel? = channelMutex.withLock {
        val myUid = explicitUid ?: currentUid()
        if (myUid.isNullOrBlank()) {
            Log.w(TAG, "listenForSignaling: Ignored because userId is null/blank. Waiting for auth.")
            return null
        }

        sdpListeners[id] = onSdp
        iceListeners[id] = onIce
        onHeartbeat?.let { heartbeatListeners[id] = it }

        val channelId = "$ROOM_PREFIX$myUid"
        
        // If already joined to this room, just return the channel
        val existing = incomingChannels[myUid]
        if (existing != null) {
            val status = existing.status.value
            if (status == RealtimeChannel.Status.SUBSCRIBED || status == RealtimeChannel.Status.SUBSCRIBING) {
                return existing
            }
        }

        try {
            Log.d(TAG, "Subscribing to Realtime channel: $channelId for listener: $id")
            val channel = KamuiSupabase.client.realtime.channel(channelId)
            incomingChannels[myUid] = channel

            val job = channel.broadcastFlow<SignalingRow>("signal")
                .onEach { row ->
                    handleRow(row)
                }.launchIn(scope)
            
            listenJobs[myUid] = job

            channel.subscribe(blockUntilSubscribed = true)
            Log.d(TAG, "Realtime Broadcast subscribed for room=$channelId")
            return channel
        } catch (e: Exception) {
            Log.e(TAG, "listenForSignaling (Broadcast) failed for $channelId: ${e.message}", e)
            incomingChannels.remove(myUid)
            return null
        }
    }

    fun stopListening(id: String) {
        sdpListeners.remove(id)
        iceListeners.remove(id)
        heartbeatListeners.remove(id)
        
        // ONLY stop signaling if there are no more active listeners across the app
        // NOTE: We check if it's currently transitioning (mutex locked) in the caller if needed,
        // but since this is called from main/IO, and the next listener is usually added 
        // immediately after discover() returns, we should be careful.
        if (sdpListeners.isEmpty() && iceListeners.isEmpty() && heartbeatListeners.isEmpty()) {
            Log.d(TAG, "No more listeners, stopping global signaling jobs")
            stopAll()
        }
    }

    fun stopAll() {
        sdpListeners.clear()
        iceListeners.clear()
        heartbeatListeners.clear()
        
        listenJobs.values.forEach { it.cancel() }
        listenJobs.clear()
        
        heartbeatJob?.cancel()
        heartbeatJob = null
        
        // Capture channels before clearing maps to ensure synchronous reset
        val channelsToUnsub = incomingChannels.values.toList() + outgoingChannels.values.toList()
        incomingChannels.clear()
        outgoingChannels.clear()

        scope.launch(NonCancellable) {
            try {
                channelsToUnsub.forEach { it.unsubscribe() }
            } catch (_: Exception) {}
        }
    }

    private fun handleRow(row: SignalingRow) {
        val senderDeviceId = row.fromDeviceId
        val targetDeviceId = row.targetDeviceId
        
        // Log all incoming signals for debugging Same Account scenarios
        Log.d(TAG, "[BROADCAST_RECV] kind=${row.kind} from=${row.fromUid} device=$senderDeviceId -> targetDevice=$targetDeviceId (Mine: $myDeviceId)")
        
        if (senderDeviceId == myDeviceId) {
            return
        }

        // If target_device_id is set and it's not me, ignore it (Signaling routing)
        if (targetDeviceId != null && targetDeviceId != myDeviceId && row.kind != "heartbeat") {
            Log.d(TAG, "[BROADCAST_RECV] Ignoring signal: TargetDeviceId ($targetDeviceId) does not match Mine ($myDeviceId)")
            return
        }

        when (row.kind) {
            "sdp" -> {
                val sdp = row.payload["sdp"] ?: return
                val type = row.payload["type"] ?: return
                if (sdpListeners.isEmpty()) Log.w(TAG, "[BROADCAST_RECV] Received SDP but no listeners active!")
                sdpListeners.values.forEach { it(sdp, type, row.fromUid, senderDeviceId ?: "unknown") }
            }
            "ice" -> {
                val sdp = row.payload["sdp"] ?: return
                val mid = row.payload["sdpMid"] ?: return
                val index = row.payload["sdpMLineIndex"]?.toIntOrNull() ?: return
                if (iceListeners.isEmpty()) Log.w(TAG, "[BROADCAST_RECV] Received ICE but no listeners active!")
                iceListeners.values.forEach { it(sdp, mid, index, row.fromUid, senderDeviceId ?: "unknown") }
            }
            "heartbeat" -> {
                val ip = row.payload["ip"] ?: return
                val port = row.payload["port"]?.toIntOrNull() ?: return
                heartbeatListeners.values.forEach { it(ip, port, senderDeviceId ?: "unknown") }
            }
        }
    }

    private fun currentUid(): String? =
        KamuiSupabase.auth.currentUserOrNull()?.id
}
