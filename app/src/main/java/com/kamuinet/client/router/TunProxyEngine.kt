package com.kamuinet.client.router

import android.util.Log
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TunProxyEngine(
    private val vpnOutput: FileOutputStream,
    private val sendToHost: (ByteArray, Boolean) -> Unit,
    private val log: (String) -> Unit = {},
    private val onSavingsReported: (Long) -> Unit = {}
) {

    private val TAG = "TunProxy"
    private val running = AtomicBoolean(true)
    private val tunWriteMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private data class Session(
        val flowId: Int,
        val original: PacketRouter.ParsedPacket,
        val initialSeq: Long,
        val clientSeq: AtomicLong = AtomicLong(0),
        val clientAck: AtomicLong = AtomicLong(0),
        val hostSeq: AtomicLong = AtomicLong((1000..99999).random().toLong()), 
        val hostAck: AtomicLong = AtomicLong(0),
        @Volatile var state: String = "INIT",
        val firstDataSent: AtomicBoolean = AtomicBoolean(false),
        val clientFin: AtomicBoolean = AtomicBoolean(false),
        val hostFin: AtomicBoolean = AtomicBoolean(false),
        val lastActivity: AtomicLong = AtomicLong(System.currentTimeMillis()),
        val pendingPackets: MutableList<ByteArray> = mutableListOf()
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private val flowIdToKey = ConcurrentHashMap<Int, String>()
    private val nextFlowId = AtomicInteger(1)

    // "Data Ghost" Config
    @Volatile var smartRoutingEnabled = true
    private val socialWhitelist = setOf(
        "googlevideo.com", "youtube.com", "ytimg.com", // YouTube
        "tiktokv.com", "tiktok.com", "byteoversea.com", // TikTok
        "cdninstagram.com", "fbcdn.net", "facebook.com", "instagram.com", // Meta
        "whatsapp.net", "whatsapp.com" // WhatsApp
    )

    init {
        startJanitor()
    }


    private fun startJanitor() {
        scope.launch {
            while (running.get()) {
                try {
                    delay(60_000)
                    val now = System.currentTimeMillis()
                    val timeout = 90_000L 
                    
                    sessions.forEach { (key, session) ->
                        if (now - session.lastActivity.get() > timeout) {
                            Log.d(TAG, "Janitor: Cleaning up stale session $key")
                            sendToHost(FramingProtocol.buildClose(session.flowId), true)
                            sessions.remove(key)
                            flowIdToKey.remove(session.flowId)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
        }
    }

    suspend fun onTunPacket(packet: ByteArray) {
        val p = PacketRouter.parse(packet) ?: return
        val key = PacketRouter.flowKey(p).toString()

        when (p.protocol) {
            PacketRouter.PROTO_TCP -> handleTcp(packet, p, key)
            PacketRouter.PROTO_UDP -> handleUdp(packet, p, key)
        }
    }

    private suspend fun handleTcp(packet: ByteArray, p: PacketRouter.ParsedPacket, key: String) {
        var session = sessions[key]

        if (p.isTcpSyn) {
            if (p.dstPort == 443 && (p.dstIp == "8.8.8.8" || p.dstIp == "8.8.4.4")) {
                Log.d(TAG, "Locally blocking DoH storm to ${p.dstIp}:443 - Sending RST")
                val rstPacket = PacketRouter.buildResponsePacket(
                    p, ByteArray(0), seq = 0, ack = p.tcpSeq + 1, flags = 0x04 // RST
                )
                tunWriteMutex.withLock {
                    try { vpnOutput.write(rstPacket) } catch (_: Exception) {}
                }
                return
            }

            if (session != null) {
                if (p.tcpSeq == session.initialSeq) {
                    Log.d(TAG, "TCP SYN retransmission detected for $key - Resending SYN-ACK")
                    sendTcpSynAck(p, session)
                    return
                } else {
                    Log.d(TAG, "Genuine TCP port reuse for $key - Force closing stale Flow ${session.flowId}")
                    sendToHost(FramingProtocol.buildClose(session.flowId), true)
                    flowIdToKey.remove(session.flowId)
                    sessions.remove(key)
                }
            }

            val flowId = nextFlowId.getAndIncrement()
            session = Session(flowId, p, initialSeq = p.tcpSeq, state = "CONNECTING").apply {
                clientSeq.set(p.tcpSeq)
                clientAck.set(p.tcpAck)
            }
            sessions[key] = session
            flowIdToKey[flowId] = key
            
            sendTcpSynAck(p, session)
            session.hostAck.set(p.tcpSeq + 1)

            log("TCP Flow $flowId: SYN received -> Connecting to host")
            sendToHost(FramingProtocol.buildTcpOpen(flowId, p.dstIp, p.dstPort), true)
            return
        }

        if (session == null) return
        session.lastActivity.set(System.currentTimeMillis())

        if (p.isTcpRst) {
            sendToHost(FramingProtocol.buildClose(session.flowId), true)
            cleanupSession(key, session.flowId)
            return
        }

        if (p.isTcpFin) {
            if (session.clientFin.compareAndSet(false, true)) {
                Log.d(TAG, "TCP Flow ${session.flowId}: FIN received from TUN")
                sendToHost(FramingProtocol.buildData(FramingProtocol.ACTION_FIN, session.flowId, ByteArray(0)), true)
                checkTeardown(key, session)
            }
            return
        }

        val payload = PacketRouter.extractPayload(packet)
        if (payload != null && payload.isNotEmpty()) {
            // SNI Sniffing & Filtering
            var isAllowed = true
            var isHighPriority = false
            
            if (session.state == "CONNECTING" || session.state == "INIT") {
                val hostname = SniSniffer.getSnihostname(payload)
                if (hostname != null) {
                    val matched = socialWhitelist.any { hostname.contains(it) }
                    if (smartRoutingEnabled && !matched) {
                        Log.d(TAG, "Data Ghost: Blocking non-social traffic to $hostname")
                        isAllowed = false
                    }
                    if (matched && (hostname.contains("googlevideo") || hostname.contains("tiktokv"))) {
                        isHighPriority = true
                    }
                }
            }

            if (!isAllowed) {
                sendToHost(FramingProtocol.buildClose(session.flowId), true)
                cleanupSession(key, session.flowId)
                return
            }

            if (session.state == "CONNECTING") {
                synchronized(session.pendingPackets) {
                    session.pendingPackets.add(payload)
                }
                log("TCP Flow ${session.flowId}: Host still connecting, buffering ${payload.size}b")
            } else {
                session.hostAck.set(p.tcpSeq + payload.size)
                val ackPacket = PacketRouter.buildResponsePacket(
                    p, ByteArray(0), seq = session.hostSeq.get(), ack = session.hostAck.get()
                )
                tunWriteMutex.withLock {
                    try {
                        vpnOutput.write(ackPacket)
                    } catch (e: Exception) {
                        Log.w(TAG, "TCP Ack write failed: ${e.message}")
                    }
                }

                // JOKER STEALTH: TLS ClientHello Fragmentation
                // If this is the first data packet and looks like TLS, split it.
                if (session.firstDataSent.compareAndSet(false, true) && isTlsClientHello(payload)) {
                    Log.d(TAG, "Ghost: TLS ClientHello detected in Flow ${session.flowId}. Applying fragmentation.")
                    val part1 = payload.copyOfRange(0, 5)
                    val part2 = payload.copyOfRange(5, payload.size)
                    
                    // Send as two separate frames to confuse DPI
                    sendToHost(FramingProtocol.buildData(FramingProtocol.ACTION_FRAG_START, session.flowId, part1), true)
                    sendToHost(FramingProtocol.buildData(FramingProtocol.ACTION_DATA, session.flowId, part2), true)
                } else {
                    sendToHost(FramingProtocol.buildData(FramingProtocol.ACTION_DATA, session.flowId, payload), isHighPriority)
                }
            }
        }

    }

    private fun isTlsClientHello(payload: ByteArray): Boolean {
        if (payload.size < 11) return false
        // TLS Record Layer: Content Type (22=0x16), Version (3.x), Length
        // Handshake Layer: Handshake Type (1=ClientHello)
        return payload[0] == 0x16.toByte() && payload[1] == 0x03.toByte() && payload[5] == 0x01.toByte()
    }

    private suspend fun sendTcpSynAck(p: PacketRouter.ParsedPacket, session: Session) {
        val synAck = PacketRouter.buildResponsePacket(
            p, ByteArray(0), seq = session.hostSeq.get(), ack = p.tcpSeq + 1, flags = 0x12 // SYN-ACK
        )
        tunWriteMutex.withLock {
            try {
                vpnOutput.write(synAck)
                Log.d(TAG, "SYN-ACK sent for Flow ${session.flowId}")
            } catch (e: Exception) {
                Log.w(TAG, "SYN-ACK write failed: ${e.message}")
            }
        }
    }

    private fun handleUdp(packet: ByteArray, p: PacketRouter.ParsedPacket, key: String) {
        if (p.dstPort == 443) {
            Log.d(TAG, "Blocking QUIC (UDP 443) for Flow $key - Forcing TCP fallback")
            return
        }

        var session = sessions[key]
        if (session == null) {
            val flowId = nextFlowId.getAndIncrement()
            session = Session(flowId, p, initialSeq = 0L)
            sessions[key] = session
            flowIdToKey[flowId] = key
        }
        session.lastActivity.set(System.currentTimeMillis())
        
        val payload = PacketRouter.extractPayload(packet) ?: return
        val dstIp = if (p.dstPort == 53) "1.1.1.1" else p.dstIp
        
        // Data Ghost: Only allow DNS or specific social ports in UDP
        if (smartRoutingEnabled && p.dstPort != 53 && p.dstPort != 5222 && p.dstPort != 3478) {
            // 3478 is STUN, used for WhatsApp calls
            return
        }

        if (payload.size > 1270) {
            Log.w(TAG, "Truly oversized UDP packet (${payload.size}b) might be dropped by WebRTC")
        }
        sendToHost(FramingProtocol.buildUdp(session.flowId, dstIp, p.dstPort, payload), p.dstPort == 53) // DNS is high priority

    }

    suspend fun onHostFrame(data: ByteArray) {
        val frame = FramingProtocol.parse(data) ?: return
        val key = flowIdToKey[frame.flowId] ?: return
        val session = sessions[key] ?: return
        session.lastActivity.set(System.currentTimeMillis())

        when (frame.type) {
            FramingProtocol.ACTION_DATA -> {
                val payload = frame.payload ?: return
                
                // Savings: Original Size - Compressed Frame Size (data.size is slightly larger due to header)
                val savings = (frame.originalPayloadSize - (data.size - 7)).toLong().coerceAtLeast(0)
                if (savings > 0) onSavingsReported(savings)

                var written = false
                var retries = 0
                var backoff = 2L
                tunWriteMutex.withLock {
                    val packet = PacketRouter.buildResponsePacket(
                        session.original, payload, seq = session.hostSeq.get(), ack = session.hostAck.get()
                    )
                    while (!written && retries < 5) {
                        try {
                            vpnOutput.write(packet)
                            session.hostSeq.addAndGet(payload.size.toLong())
                            written = true
                        } catch (e: java.io.IOException) {
                            if (e.message?.contains("ENOBUFS") == true || e.message?.contains("buffer") == true) {
                                retries++
                                delay(backoff)
                                backoff *= 2 // Exponential backoff
                            } else {
                                Log.w(TAG, "Host data write failed: ${e.message}")
                                break
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Host data write failed: ${e.message}")
                            break
                        }
                    }
                }
            }
            FramingProtocol.ACTION_TCP_ACK -> {
                if (session.state == "CONNECTING") {
                    log("Host confirmed TCP flow ${frame.flowId} -> Establishing TUN session")
                    
                    synchronized(session.pendingPackets) {
                        if (session.pendingPackets.isNotEmpty()) {
                            session.pendingPackets.forEach { buffered ->
                                session.hostAck.addAndGet(buffered.size.toLong())
                                sendToHost(FramingProtocol.buildData(FramingProtocol.ACTION_DATA, session.flowId, buffered), true)
                            }
                            
                            val ackPacket = PacketRouter.buildResponsePacket(
                                session.original, ByteArray(0), seq = session.hostSeq.get() + 1, ack = session.hostAck.get()
                            )
                            scope.launch {
                                tunWriteMutex.withLock {
                                    try {
                                        vpnOutput.write(ackPacket)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Flush ACK write failed: ${e.message}")
                                    }
                                }
                            }
                            session.pendingPackets.clear()
                        }
                    }
                    
                    session.hostSeq.incrementAndGet()
                    session.state = "ESTABLISHED"
                }
            }
            FramingProtocol.ACTION_FIN -> {
                if (session.hostFin.compareAndSet(false, true)) {
                    log("TCP Flow ${frame.flowId}: Host sent FIN")
                    tunWriteMutex.withLock {
                        val fin = PacketRouter.buildResponsePacket(
                            session.original, ByteArray(0), seq = session.hostSeq.get(), ack = session.hostAck.get(), flags = 0x11 // FIN-ACK
                        )
                        try {
                            vpnOutput.write(fin)
                            session.hostSeq.incrementAndGet()
                        } catch (e: Exception) {
                            Log.w(TAG, "Host FIN write failed: ${e.message}")
                        }
                    }
                    checkTeardown(key, session)
                }
            }
            FramingProtocol.ACTION_CLOSE -> {
                tunWriteMutex.withLock {
                    val rst = PacketRouter.buildResponsePacket(
                        session.original, ByteArray(0), seq = session.hostSeq.get(), ack = session.hostAck.get(), flags = 0x04 // RST
                    )
                    try {
                        vpnOutput.write(rst)
                    } catch (e: Exception) {
                        Log.w(TAG, "Close RST write failed: ${e.message}")
                    }
                }
                cleanupSession(key, frame.flowId)
            }
            FramingProtocol.ACTION_UDP_DATA -> {
                val payload = frame.payload ?: return
                
                // UDP Savings
                val savings = (frame.originalPayloadSize - (data.size - 7)).toLong().coerceAtLeast(0)
                if (savings > 0) onSavingsReported(savings)

                val srcIpOverride = frame.host 
                val packet = PacketRouter.buildResponsePacket(
                    session.original, payload, overrideSrcIp = srcIpOverride
                )
                
                if (session.original.dstPort == 53) {
                    Log.d(TAG, "Reinjected DNS response from ${srcIpOverride ?: "host"} for Flow ${frame.flowId}")
                }
                
                var written = false
                var retries = 0
                var backoff = 1L
                tunWriteMutex.withLock {
                    while (!written && retries < 3) {
                        try {
                            vpnOutput.write(packet)
                            written = true
                        } catch (e: java.io.IOException) {
                            if (e.message?.contains("ENOBUFS") == true || e.message?.contains("buffer") == true) {
                                retries++
                                delay(backoff)
                                backoff *= 2
                            } else {
                                Log.w(TAG, "UDP write failed: ${e.message}")
                                break
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "UDP write failed: ${e.message}")
                            break
                        }
                    }
                }
            }
        }
    }

    private fun checkTeardown(key: String, session: Session) {
        if (session.clientFin.get() && session.hostFin.get()) {
            log("TCP Flow ${session.flowId}: Both sides closed, cleaning up session")
            cleanupSession(key, session.flowId)
        }
    }

    private fun cleanupSession(key: String, flowId: Int) {
        sessions.remove(key)
        flowIdToKey.remove(flowId)
    }

    fun stop() {
        running.set(false)
        scope.cancel()
        sessions.clear()
        flowIdToKey.clear()
    }
}

