package com.kamuinet.client.router

import android.util.Log
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TunProxyEngine(
    private val vpnOutput: FileOutputStream,
    private val sendToHost: (ByteArray, Boolean) -> Unit,
    private val log: (String) -> Unit = {}
) {
    private val TAG = "TunProxy"
    private val running = AtomicBoolean(true)
    private val tunWriteLock = Any()
    
    private data class Session(
        val flowId: Int,
        val original: PacketRouter.ParsedPacket,
        val initialSeq: Long,
        var clientSeq: Long = 0,
        var clientAck: Long = 0,
        var hostSeq: Long = (1000..99999).random().toLong(), 
        var hostAck: Long = 0,
        var state: String = "INIT",
        var lastActivity: Long = System.currentTimeMillis(),
        val pendingPackets: MutableList<ByteArray> = mutableListOf()
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private val flowIdToKey = ConcurrentHashMap<Int, String>()
    private val nextFlowId = AtomicInteger(1)

    init {
        startJanitor()
    }

    private fun startJanitor() {
        kotlin.concurrent.thread(name = "TunProxyJanitor", isDaemon = true) {
            while (running.get()) {
                try {
                    Thread.sleep(60_000)
                    val now = System.currentTimeMillis()
                    val timeout = 90_000L // Reduced from 5m to 90s to prevent memory bloat
                    
                    sessions.forEach { (key, session) ->
                        if (now - session.lastActivity > timeout) {
                            Log.d(TAG, "Janitor: Cleaning up stale session $key")
                            sendToHost(FramingProtocol.buildClose(session.flowId), true)
                            sessions.remove(key)
                            flowIdToKey.remove(session.flowId)
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    fun onTunPacket(packet: ByteArray) {
        val p = PacketRouter.parse(packet) ?: return
        val key = PacketRouter.flowKey(p).toString()

        when (p.protocol) {
            PacketRouter.PROTO_TCP -> handleTcp(packet, p, key)
            PacketRouter.PROTO_UDP -> handleUdp(packet, p, key)
        }
    }

    private fun handleTcp(packet: ByteArray, p: PacketRouter.ParsedPacket, key: String) {
        var session = sessions[key]

        if (p.isTcpSyn) {
            // Local DoH Block: Prevent Google DoH storm from hitting WebRTC
            if (p.dstPort == 443 && (p.dstIp == "8.8.8.8" || p.dstIp == "8.8.4.4")) {
                Log.d(TAG, "Locally blocking DoH storm to ${p.dstIp}:443 - Sending RST")
                val rstPacket = PacketRouter.buildResponsePacket(
                    p, ByteArray(0), seq = 0, ack = p.tcpSeq + 1, flags = 0x04 // RST
                )
                synchronized(tunWriteLock) {
                    try { vpnOutput.write(rstPacket) } catch (_: Exception) {}
                }
                return
            }

            // Detect and handle stale session vs retransmission
            if (session != null) {
                if (p.tcpSeq == session.initialSeq) {
                    Log.d(TAG, "TCP SYN retransmission detected for $key - Resending handshake response if possible")
                    if (session.state == "ESTABLISHED") {
                        sendTcpSynAck(p, session)
                    }
                    // If state is CONNECTING, we just wait for host to respond
                    return
                } else {
                    Log.d(TAG, "Genuine TCP port reuse for $key - Force closing stale Flow ${session.flowId}")
                    sendToHost(FramingProtocol.buildClose(session.flowId), true)
                    flowIdToKey.remove(session.flowId)
                    sessions.remove(key)
                }
            }

            val flowId = nextFlowId.getAndIncrement()
            // STATE: CONNECTING - Wait for host ACTION_TCP_ACK before sending SYN-ACK
            session = Session(flowId, p, initialSeq = p.tcpSeq, clientSeq = p.tcpSeq, clientAck = p.tcpAck, state = "CONNECTING")
            sessions[key] = session
            flowIdToKey[flowId] = key
            
            log("TCP Flow $flowId: SYN received -> Connecting to host")
            sendToHost(FramingProtocol.buildTcpOpen(flowId, p.dstIp, p.dstPort), true)
            return
        }

        if (session == null) return
        session.lastActivity = System.currentTimeMillis()

        if (p.isTcpFin || p.isTcpRst) {
            sendToHost(FramingProtocol.buildClose(session.flowId), true)
            sessions.remove(key)
            flowIdToKey.remove(session.flowId)
            return
        }

        val payload = PacketRouter.extractPayload(packet)
        if (payload != null && payload.isNotEmpty()) {
            if (session.state == "CONNECTING") {
                // Buffer data if host hasn't confirmed connection yet
                synchronized(session.pendingPackets) {
                    session.pendingPackets.add(payload)
                }
                log("TCP Flow ${session.flowId}: Host still connecting, buffering ${payload.size}b")
            } else {
                // Satisfy OS stack by ACK-ing immediately
                session.hostAck = p.tcpSeq + payload.size
                val ackPacket = PacketRouter.buildResponsePacket(
                    p, ByteArray(0), seq = session.hostSeq, ack = session.hostAck
                )
                synchronized(tunWriteLock) {
                    try {
                        vpnOutput.write(ackPacket)
                    } catch (e: Exception) {
                        Log.w(TAG, "TCP Ack write failed: ${e.message}")
                    }
                }

                // Forward data to host
                sendToHost(FramingProtocol.buildData(FramingProtocol.ACTION_DATA, session.flowId, payload), true)
            }
        }
    }

    private fun sendTcpSynAck(p: PacketRouter.ParsedPacket, session: Session) {
        val synAck = PacketRouter.buildResponsePacket(
            p, ByteArray(0), seq = session.hostSeq, ack = p.tcpSeq + 1, flags = 0x12 // SYN-ACK
        )
        synchronized(tunWriteLock) {
            try {
                vpnOutput.write(synAck)
                Log.d(TAG, "SYN-ACK sent for Flow ${session.flowId}")
            } catch (e: Exception) {
                Log.w(TAG, "SYN-ACK write failed: ${e.message}")
            }
        }
    }

    private fun handleUdp(packet: ByteArray, p: PacketRouter.ParsedPacket, key: String) {
        // Master Hack: Block QUIC (UDP 443) to force YouTube/Chrome to use TCP (more stable for P2P)
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
        session.lastActivity = System.currentTimeMillis()
        
        val payload = PacketRouter.extractPayload(packet) ?: return
        
        // DNS Trap: If it's port 53, force it to 1.1.1.1 on the host side
        val dstIp = if (p.dstPort == 53) "1.1.1.1" else p.dstIp
        
        // MTU Check for WebRTC limit - Increased threshold to match 1400 MTU
        if (payload.size > 1350) {
            Log.w(TAG, "Truly oversized UDP packet (${payload.size}b) might be dropped by WebRTC")
        }
        sendToHost(FramingProtocol.buildUdp(session.flowId, dstIp, p.dstPort, payload), false)
    }

    /**
     * Handle incoming frames from the Host
     */
    fun onHostFrame(data: ByteArray) {
        val frame = FramingProtocol.parse(data) ?: return
        val key = flowIdToKey[frame.flowId] ?: return
        val session = sessions[key] ?: return
        session.lastActivity = System.currentTimeMillis()

        when (frame.type) {
            FramingProtocol.ACTION_DATA -> {
                val payload = frame.payload ?: return
                val packet = PacketRouter.buildResponsePacket(
                    session.original, payload, seq = session.hostSeq, ack = session.hostAck
                )
                var written = false
                var retries = 0
                synchronized(tunWriteLock) {
                    while (!written && retries < 3) {
                        try {
                            vpnOutput.write(packet)
                            session.hostSeq += payload.size
                            written = true
                        } catch (e: java.io.IOException) {
                            if (e.message?.contains("ENOBUFS") == true || e.message?.contains("buffer") == true) {
                                retries++
                                try { Thread.sleep(2) } catch (_: Exception) {}
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
                    session.state = "ESTABLISHED"
                    
                    // 1. Send SYN-ACK now that host is ready
                    sendTcpSynAck(session.original, session)

                    session.hostSeq++
                    session.hostAck = session.original.tcpSeq + 1
                    
                    // 2. Flush any buffered data and notify client
                    synchronized(session.pendingPackets) {
                        if (session.pendingPackets.isNotEmpty()) {
                            session.pendingPackets.forEach { buffered ->
                                session.hostAck += buffered.size
                                sendToHost(FramingProtocol.buildData(FramingProtocol.ACTION_DATA, session.flowId, buffered), true)
                            }
                            // Send an explicit ACK for the buffered data
                            val ackPacket = PacketRouter.buildResponsePacket(
                                session.original, ByteArray(0), seq = session.hostSeq, ack = session.hostAck
                            )
                            synchronized(tunWriteLock) {
                                try {
                                    vpnOutput.write(ackPacket)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Flush ACK write failed: ${e.message}")
                                }
                            }
                            session.pendingPackets.clear()
                        }
                    }
                }
            }
            FramingProtocol.ACTION_CLOSE -> {
                val fin = PacketRouter.buildResponsePacket(
                    session.original, ByteArray(0), seq = session.hostSeq, ack = session.hostAck, flags = 0x11
                )
                synchronized(tunWriteLock) {
                    try {
                        vpnOutput.write(fin)
                    } catch (e: Exception) {
                        Log.w(TAG, "Close FIN write failed: ${e.message}")
                    }
                }
                sessions.remove(key)
                flowIdToKey.remove(frame.flowId)
            }
            FramingProtocol.ACTION_UDP_DATA -> {
                val payload = frame.payload ?: return
                // Robust DNS Reinjection: Use host from frame (e.g. 1.1.1.1) as source IP
                val srcIpOverride = frame.host 
                val packet = PacketRouter.buildResponsePacket(
                    session.original, payload, overrideSrcIp = srcIpOverride
                )
                
                if (session.original.dstPort == 53) {
                    Log.d(TAG, "Reinjected DNS response from ${srcIpOverride ?: "host"} for Flow ${frame.flowId}")
                }
                
                var written = false
                var retries = 0
                synchronized(tunWriteLock) {
                    while (!written && retries < 3) {
                        try {
                            vpnOutput.write(packet)
                            written = true
                        } catch (e: java.io.IOException) {
                            if (e.message?.contains("ENOBUFS") == true || e.message?.contains("buffer") == true) {
                                retries++
                                try { Thread.sleep(2) } catch (_: Exception) {}
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

    fun stop() {
        running.set(false)
        sessions.clear()
        flowIdToKey.clear()
    }
}
