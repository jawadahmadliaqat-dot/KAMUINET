package com.kamuinet.client.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceServer
import org.webrtc.PeerConnection.RTCConfiguration
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class WebRtcManager(
    private val context: Context, 
    private val iceServers: List<IceServer> = WebRtcConfig.iceServers,
    private val myDeviceId: String = "",
    private val forceP2p: Boolean = false
) {

    private val TAG = "WebRtcManager"
    private var peerConnection: PeerConnection? = null
    private val dataChannels = mutableListOf<DataChannel>()
    @Volatile var turboMode: Boolean = false
    
    private val sendScope = CoroutineScope(Dispatchers.IO.limitedParallelism(4) + SupervisorJob())
    private val packetQueue = kotlinx.coroutines.channels.Channel<ByteArray>(200)
    private val nextChannelIndex = java.util.concurrent.atomic.AtomicInteger(0)

    
    // --- LINK MONITORING ---
    var onLinkTypeDetected: ((String) -> Unit)? = null
    private var statsJob: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    // --- RACE CONDITION FIX: ICE Candidate Queuing ---
    private val iceQueue = ConcurrentLinkedQueue<IceCandidate>()
    private val isRemoteDescriptionSet = AtomicBoolean(false)

    var onIceCandidateGenerated: ((IceCandidate) -> Unit)? = null
    var onSdpGenerated: ((SessionDescription) -> Unit)? = null
    var onPacketReceived: ((ByteArray) -> Unit)? = null
    var onChannelStateChanged: ((DataChannel.State) -> Unit)? = null
    var onBufferedAmountLow: (() -> Unit)? = null
    
    var remoteDeviceId: String = "unknown"

    companion object {
        @Volatile
        private var factory: PeerConnectionFactory? = null

        fun getFactory(context: Context): PeerConnectionFactory {
            return factory ?: synchronized(this) {
                factory ?: run {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(context)
                            .createInitializationOptions()
                    )
                    val f = PeerConnectionFactory.builder().createPeerConnectionFactory()
                    factory = f
                    f
                }
            }
        }
    }

    init {
        // Start Multiple Parallel Packet Queue Consumers (Stripe Workers)
        repeat(4) {
            sendScope.launch {
                for (packet in packetQueue) {
                    sendData(packet)
                }
            }
        }

        val filteredServers = if (forceP2p) {
            iceServers.filter { srv -> srv.urls.any { it.startsWith("stun:") } }
        } else {
            iceServers
        }

        val rtcConfig = RTCConfiguration(filteredServers).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            
            // JOKER STEALTH: Optimized for restricted carriers
            activeResetSrtpParams = true
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        
        peerConnection = getFactory(context).createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "Generated Local ICE Candidate: ${candidate.sdpMid} | ${candidate.serverUrl ?: "Trickle"}")
                // Trickle ICE: Send immediately without waiting for completion
                onIceCandidateGenerated?.invoke(candidate)
            }

            override fun onDataChannel(dc: DataChannel) {
                Log.d(TAG, "Incoming DataChannel: ${dc.label()}")
                setupDataChannel(dc)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE State Change: $state")
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                    startStatsMonitoring()
                }
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "ICE Gathering State: $state")
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: org.webrtc.RtpReceiver?, p1: Array<out org.webrtc.MediaStream>?) {}
        })
    }

    fun createOffer() {
        val state = peerConnection?.signalingState()
        
        if (state == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            val localSdp = peerConnection?.localDescription
            if (localSdp != null) {
                Log.d(TAG, "[SDP_STEP] Re-sending existing Offer (State: $state)")
                onSdpGenerated?.invoke(localSdp)
            }
            return
        }

        if (state != PeerConnection.SignalingState.STABLE) {
            Log.w(TAG, "[SDP_STEP] Skipping createOffer - State is $state (Not STABLE)")
            return
        }

        val dcInit = DataChannel.Init().apply {
            ordered = true
            maxRetransmits = -1 // Unlimited retransmits for TCP reliability over UDP
        }
        
        // DataChannel Pool Setup: 4 channels standard, 8 channels in Turbo Mode
        val channelCount = if (turboMode) 8 else 4
        synchronized(dataChannels) {
            val activeChannels = dataChannels.count { it.state() == DataChannel.State.OPEN || it.state() == DataChannel.State.CONNECTING }
            if (activeChannels < channelCount) {
                for (i in 1..(channelCount - activeChannels)) {
                    val label = "KamuiTunnel-$i-${System.currentTimeMillis()}"
                    val dc = peerConnection?.createDataChannel(label, dcInit)
                    if (dc != null) setupDataChannel(dc)
                }
            }
        }


        Log.d(TAG, "[SDP_STEP] Creating Offer (State: ${peerConnection?.signalingState()})")
        peerConnection?.createOffer(object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "[SDP_STEP] Offer Created. Setting Local Description.")
                peerConnection?.setLocalDescription(object : org.webrtc.SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() { 
                        Log.d(TAG, "[SDP_STEP] Local Description Set (Offer)")
                        // Social Carrier Masking: Inject WhatsApp domain in SDP attributes
                        val maskedSdp = injectSocialMask(sdp.description)
                        onSdpGenerated?.invoke(SessionDescription(sdp.type, maskedSdp)) 
                    }

                    override fun onCreateFailure(error: String?) {
                        Log.e(TAG, "[SDP_STEP] Set Local Description Failure: $error")
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "[SDP_STEP] Set Local Description Failure: $error")
                    }
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "[SDP_STEP] Create Offer Failure: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "[SDP_STEP] Create Offer Failure: $error")
            }
        }, MediaConstraints())
    }

    fun createAnswer(remoteSdp: String, remoteId: String = "") {
        if (remoteId.isNotEmpty()) {
            this.remoteDeviceId = remoteId
        }
        // --- RACE CONDITION FIX: Perfect Negotiation (Polite Peer) ---
        val state = peerConnection?.signalingState()
        Log.d(TAG, "[SDP_STEP] Processing Remote Offer. State: $state, MyID: $myDeviceId, RemoteID: $remoteDeviceId")
        
        if (state == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            // Collision! Lower device ID yields (is polite)
            val isPolite = myDeviceId < remoteDeviceId
            if (!isPolite) {
                Log.w(TAG, "[SDP_STEP] Glare Detected. I am IM-POLITE. Ignoring remote offer.")
                return
            }
            Log.w(TAG, "[SDP_STEP] Glare Detected. I am POLITE. Rolling back to accept remote offer.")
            // WebRTC implicitly rolls back local offer when setRemoteDescription(OFFER) is called in HAVE_LOCAL_OFFER state
        }

        val sdp = SessionDescription(SessionDescription.Type.OFFER, remoteSdp)
        peerConnection?.setRemoteDescription(object : org.webrtc.SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "[SDP_STEP] Remote Description Set (Offer). Creating Answer.")
                isRemoteDescriptionSet.set(true)
                drainIceQueue()
                
                peerConnection?.createAnswer(object : org.webrtc.SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        Log.d(TAG, "[SDP_STEP] Answer Created. Setting Local Description.")
                        peerConnection?.setLocalDescription(object : org.webrtc.SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() { 
                                Log.d(TAG, "[SDP_STEP] Local Description Set (Answer)")
                                val maskedSdp = injectSocialMask(answer.description)
                                onSdpGenerated?.invoke(SessionDescription(answer.type, maskedSdp)) 
                            }

                            override fun onCreateFailure(error: String?) {
                                Log.e(TAG, "[SDP_STEP] Set Local Description Failure (Answer): $error")
                            }
                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "[SDP_STEP] Set Local Description Failure (Answer): $error")
                            }
                        }, answer)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) {
                        Log.e(TAG, "[SDP_STEP] Create Answer Failure: $error")
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "[SDP_STEP] Create Answer Failure: $error")
                    }
                }, MediaConstraints())
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "[SDP_STEP] Set Remote Description Failure (Offer): $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "[SDP_STEP] Set Remote Description Failure (Offer): $error")
            }
        }, sdp)
    }

    fun setAnswer(remoteSdp: String, remoteId: String = "") {
        if (remoteId.isNotEmpty()) {
            this.remoteDeviceId = remoteId
        }
        val state = peerConnection?.signalingState()
        Log.d(TAG, "[SDP_STEP] Processing Remote Answer. Current State: $state | RemoteID: $remoteDeviceId")
        
        if (state == PeerConnection.SignalingState.STABLE) {
            Log.w(TAG, "[SDP_STEP] Connection already STABLE. Ignoring duplicate answer.")
            return
        }

        val sdp = SessionDescription(SessionDescription.Type.ANSWER, remoteSdp)
        peerConnection?.setRemoteDescription(object : org.webrtc.SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "[SDP_STEP] Remote Description Set (Answer).")
                isRemoteDescriptionSet.set(true)
                drainIceQueue()
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "[SDP_STEP] Set Remote Description Failure (Answer): $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "[SDP_STEP] Set Remote Description Failure (Answer): $error")
            }
        }, sdp)
    }

    fun addRemoteIceCandidate(candidateSdp: String, sdpMid: String, sdpMLineIndex: Int) {
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
        if (isRemoteDescriptionSet.get()) {
            Log.d(TAG, "Adding remote ICE candidate immediately: $sdpMid")
            peerConnection?.addIceCandidate(candidate)
        } else {
            // Buffer candidate until remote description is set to prevent WebRTC error
            iceQueue.add(candidate)
            Log.d(TAG, "Queued remote ICE candidate (RemoteDescription not yet set) for $sdpMid")
        }
    }

    private fun drainIceQueue() {
        while (iceQueue.isNotEmpty()) {
            val candidate = iceQueue.poll()
            if (candidate != null) {
                peerConnection?.addIceCandidate(candidate)
                Log.d(TAG, "Drained ICE candidate from queue")
            }
        }
    }

    private fun setupDataChannel(dc: DataChannel) {
        synchronized(dataChannels) {
            dataChannels.add(dc)
        }
        
        dc.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                onPacketReceived?.invoke(bytes)
            }

            override fun onStateChange() {
                Log.d(TAG, "DataChannel ${dc.label()} State: ${dc.state()}")
                
                synchronized(dataChannels) {
                    val allOpen = dataChannels.any { it.state() == DataChannel.State.OPEN }
                    if (allOpen) onChannelStateChanged?.invoke(DataChannel.State.OPEN)
                    else if (dataChannels.all { it.state() == DataChannel.State.CLOSED }) {
                        onChannelStateChanged?.invoke(DataChannel.State.CLOSED)
                    }
                }
            }

            override fun onBufferedAmountChange(p0: Long) {
                // Flow Control: Trigger resume if buffer drops below 128KB
                if (p0 < 128 * 1024) {
                    onBufferedAmountLow?.invoke()
                }
            }
        })
    }

    fun getBufferedAmount(): Long {
        return synchronized(dataChannels) {
            dataChannels.sumOf { it.bufferedAmount() }
        }
    }

    fun getChannelState(): DataChannel.State {
        return synchronized(dataChannels) {
            if (dataChannels.any { it.state() == DataChannel.State.OPEN }) DataChannel.State.OPEN
            else if (dataChannels.any { it.state() == DataChannel.State.CONNECTING }) DataChannel.State.CONNECTING
            else DataChannel.State.CLOSED
        }
    }

    fun sendData(data: ByteArray): Boolean {
        val dc = synchronized(dataChannels) {
            val openChannels = dataChannels.filter { it.state() == DataChannel.State.OPEN }
            if (openChannels.isEmpty()) return false
            
            // Round-robin packet striping across open channels in the pool
            val index = nextChannelIndex.getAndIncrement() % openChannels.size
            openChannels[if (index < 0) index + openChannels.size else index]
        }

        // Backpressure protection (prevent native buffer overflow)
        val maxBuffer = if (turboMode) 6_000_000 else 3_000_000
        if (getBufferedAmount() > maxBuffer) { 
            Log.w(TAG, "WebRTC aggregate buffer overflow (${getBufferedAmount()} bytes). Dropping packet.")
            return false
        }

        return try {
            // Truly concurrent submission without global lock contention
            val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), true)
            dc.send(buffer)
            true
        } catch (e: Throwable) {
            Log.e("WebRtcManager", "Native send failed: ${e.message}")
            false
        }
    }


    fun sendPacket(data: ByteArray, highPriority: Boolean = true) {
        val result = packetQueue.trySend(data)
        if (!result.isSuccess) {
            // Drop non-priority packets if queue is full to avoid lag
            if (highPriority) {
                sendScope.launch {
                    packetQueue.send(data)
                }
            } else {
                Log.w(TAG, "WebRTC Packet Queue full. Dropping low-priority packet.")
            }
        }
    }

    private fun startStatsMonitoring() {
        if (statsJob != null) return
        
        statsJob = scope.launch {
            while (peerConnection != null) {
                peerConnection?.getStats { report ->
                    var connectionType = "Unknown"
                    val statsMap = report.statsMap
                    
                    // Look for active candidate pair
                    val transportStats = statsMap.values.find { it.type == "transport" }
                    val selectedPairId = transportStats?.members?.get("selectedCandidatePairId") as? String
                    
                    if (selectedPairId != null) {
                        val pairStats = statsMap[selectedPairId]
                        val remoteCandidateId = pairStats?.members?.get("remoteCandidateId") as? String
                        if (remoteCandidateId != null) {
                            val remoteStats = statsMap[remoteCandidateId]
                            val type = remoteStats?.members?.get("candidateType") as? String
                            connectionType = when (type) {
                                "host" -> "Local (Direct)"
                                "srflx" -> "P2P (Direct)"
                                "prflx" -> "P2P (Direct)"
                                "relay" -> "Relay (Server)"
                                else -> type ?: "Direct"
                            }
                        }
                    }
                    
                    if (connectionType != "Unknown") {
                        onLinkTypeDetected?.invoke(connectionType)
                    }
                }
                delay(5000)
            }
        }
    }

    fun close() {
        statsJob?.cancel()
        scope.cancel()
        synchronized(dataChannels) {
            dataChannels.forEach { it.dispose() }
            dataChannels.clear()
        }
        peerConnection?.dispose()
        // Explicitly stop native network monitor to prevent IntentReceiver leaks
        org.webrtc.NetworkMonitor.getInstance().stopMonitoring()
    }


    /**
     * Injects "Social Carrier" attributes into SDP to mask as WhatsApp traffic.
     */
    private fun injectSocialMask(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val newLines = mutableListOf<String>()
        
        for (line in lines) {
            newLines.add(line)
            // After m=application (DataChannel), inject WhatsApp domain attribute
            if (line.startsWith("m=application")) {
                newLines.add("a=host:m.whatsapp.net")
                newLines.add("a=extmap:5222 urn:ietf:params:rtp-hdrext:social-mask")
            }
        }
        
        return newLines.joinToString("\r\n")
    }
}

