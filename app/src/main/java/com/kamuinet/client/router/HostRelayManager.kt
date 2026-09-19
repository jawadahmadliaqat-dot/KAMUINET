package com.kamuinet.client.router

import android.util.Log
import com.kamuinet.client.vpn.KamuiVpnService
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HostRelayManager(
    private val sendToPeer: (ByteArray, Boolean) -> Unit,
    private val getBufferedAmount: () -> Long = { 0 },
    private val log: (String) -> Unit = {},
    private val protectSocket: ((Socket) -> Boolean)? = null,
    private val protectUdp: ((DatagramSocket) -> Boolean)? = null
) {
    private val TAG = "HostRelay"
    private val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(true)
    private val MAX_SESSIONS = 200 // Increased to support browser port randomization

    private data class TcpSession(
        val socket: Socket,
        val flowId: Int,
        val closed: AtomicBoolean = AtomicBoolean(false),
        val lastActivity: AtomicLong = AtomicLong(System.currentTimeMillis()),
        val writeChannel: kotlinx.coroutines.channels.Channel<ByteArray> = kotlinx.coroutines.channels.Channel(100)
    )

    private data class UdpSession(
        val socket: DatagramSocket,
        val flowId: Int,
        val targetHost: String,
        val targetPort: Int,
        val lastUsed: AtomicLong = AtomicLong(System.currentTimeMillis())
    )

    private val tcpSessions = ConcurrentHashMap<Int, TcpSession>()
    private val udpSessions = ConcurrentHashMap<Int, UdpSession>()
    private val pendingData = ConcurrentHashMap<Int, MutableList<ByteArray>>()

    init {
        startJanitor()
        startIdleReaper()
    }

    /**
     * Handle framed commands from the peer (Client)
     */
    fun onClientPacket(data: ByteArray) {
        if (!running.get()) return
        val frame = FramingProtocol.parse(data) ?: return

        when (frame.type) {
            FramingProtocol.ACTION_CONNECT -> handleTcpOpen(frame)
            FramingProtocol.ACTION_DATA -> handleTcpData(frame)
            FramingProtocol.ACTION_CLOSE -> closeTcpFlow(frame.flowId)
            FramingProtocol.ACTION_UDP_DATA -> handleUdpData(frame)
        }
    }

    private fun handleTcpOpen(frame: FramingProtocol.Frame) {
        if (tcpSessions.size >= MAX_SESSIONS) {
            Log.w(TAG, "Max TCP sessions reached. Refusing flow ${frame.flowId}")
            sendToPeer(FramingProtocol.buildClose(frame.flowId), true)
            return
        }

        val host = frame.host ?: return
        val port = frame.port
        val flowId = frame.flowId

        // DoH Guard: Block Google DNS-over-HTTPS only. Allow Cloudflare (1.1.1.1) for web tests.
        if (port == 443 && (host == "8.8.8.8" || host == "8.8.4.4")) {
            Log.d(TAG, "Blocking DoH storm to $host:443 for Flow $flowId")
            sendToPeer(FramingProtocol.buildClose(flowId), true)
            return
        }

        relayScope.launch {
            try {
                val sock = Socket()
                // Prevent routing loops: Protect relay socket from being captured by local VPN
                val protected = protectSocket?.invoke(sock) ?: KamuiVpnService.instance?.protectSocket(sock) ?: false
                if (!protected && KamuiVpnService.isTunnelActive.get()) {
                    Log.w(TAG, "Relay socket not protected while VPN is active!")
                }
                
                sock.tcpNoDelay = true
                withContext(Dispatchers.IO) {
                    sock.connect(InetSocketAddress(host, port), 5000) // Increased to 5s for reliability
                    sock.sendBufferSize = 65536
                    sock.receiveBufferSize = 65536
                }

                val session = TcpSession(sock, flowId)
                tcpSessions[flowId] = session
                
                // Drain buffered data sequentially to preserve byte order
                pendingData.remove(flowId)?.let { bufferedList ->
                    val snapshot = synchronized(bufferedList) { bufferedList.toList() }
                    relayScope.launch {
                        log("Draining ${snapshot.size} buffered packets for Flow $flowId")
                        snapshot.forEach { payload ->
                            try {
                                if (!session.closed.get() && !session.writeChannel.isClosedForSend) {
                                    session.writeChannel.trySend(payload)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to send buffered packet to flow $flowId: ${e.message}")
                            }
                        }
                    }
                }
                
                // Confirm to peer that connection is ready
                sendToPeer(FramingProtocol.buildTcpAck(flowId), true)
                
                log("TCP Flow $flowId opened → $host:$port")
                startTcpReader(session)
            } catch (e: Exception) {
                log("TCP Flow $flowId failed: ${e.message}")
                pendingData.remove(flowId)
                sendToPeer(FramingProtocol.buildClose(flowId), true)
            }
        }
    }

    private fun handleTcpData(frame: FramingProtocol.Frame) {
        val payload = frame.payload ?: return
        val session = tcpSessions[frame.flowId]
        
        if (session == null) {
            // Buffer early data if connection is still in progress
            val list = pendingData.getOrPut(frame.flowId) { 
                java.util.Collections.synchronizedList(mutableListOf()) 
            }
            synchronized(list) { list.add(payload) }
            return
        }
        
        session.lastActivity.set(System.currentTimeMillis())
        
        try {
            if (!session.closed.get() && !session.writeChannel.isClosedForSend) {
                session.writeChannel.trySend(payload)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send packet to flow ${frame.flowId}: ${e.message}")
        }
    }

    private fun startTcpReader(session: TcpSession) {
        // Consumer for sequential writes to socket
        relayScope.launch {
            try {
                val out = session.socket.getOutputStream()
                for (data in session.writeChannel) {
                    withContext(Dispatchers.IO) {
                        out.write(data)
                        out.flush()
                    }
                }
            } catch (e: Exception) {
                if (!session.closed.get()) Log.d(TAG, "TCP writer ${session.flowId} error: ${e.message}")
            } finally {
                // Ensure peer knows we are closing before local cleanup
                sendToPeer(FramingProtocol.buildClose(session.flowId), true)
                closeTcpFlow(session.flowId)
            }
        }

        // Producer for reading from socket
        relayScope.launch {
            val buf = ByteArray(32 * 1024)
            try {
                val input: InputStream = session.socket.getInputStream()
                while (running.get() && !session.closed.get()) {
                    // Backpressure: If WebRTC buffer > 1MB, wait
                    while (running.get() && !session.closed.get() && getBufferedAmount() > 1024 * 1024) {
                        delay(20)
                    }

                    val n = withContext(Dispatchers.IO) { input.read(buf) }
                    if (n < 0) {
                        log("TCP Flow ${session.flowId}: EOF reached")
                        break
                    }
                    if (n == 0) continue

                    session.lastActivity.set(System.currentTimeMillis())
                    val payload = buf.copyOfRange(0, n)
                    val dataFrame = FramingProtocol.buildData(FramingProtocol.ACTION_DATA, session.flowId, payload)
                    sendToPeer(dataFrame, true)
                }
            } catch (e: Exception) {
                if (!session.closed.get()) Log.d(TAG, "TCP reader ${session.flowId} end: ${e.message}")
            } finally {
                // Explicitly notify peer on EOF or Exception before cleaning up
                sendToPeer(FramingProtocol.buildClose(session.flowId), true)
                closeTcpFlow(session.flowId)
            }
        }
    }

    private fun handleUdpData(frame: FramingProtocol.Frame) {
        val flowId = frame.flowId
        val payload = frame.payload ?: return
        val host = frame.host ?: return
        val port = frame.port

        var session = udpSessions[flowId]
        if (session == null) {
            if (udpSessions.size >= MAX_SESSIONS) return
            try {
                val ds = DatagramSocket()
                val protected = protectUdp?.invoke(ds) ?: KamuiVpnService.instance?.protectDatagramSocket(ds) ?: false
                if (!protected && KamuiVpnService.isTunnelActive.get()) {
                    Log.w(TAG, "UDP Relay socket not protected while VPN is active!")
                }
                
                session = UdpSession(ds, flowId, host, port)
                udpSessions[flowId] = session
                startUdpReader(session)
            } catch (e: Exception) {
                log("UDP Flow $flowId open failed: ${e.message}")
                return
            }
        }

        session.lastUsed.set(System.currentTimeMillis())
        relayScope.launch {
            try {
                val addr = InetAddress.getByName(host)
                val dp = DatagramPacket(payload, payload.size, addr, port)
                withContext(Dispatchers.IO) {
                    session.socket.send(dp)
                }
            } catch (e: Exception) {
                log("UDP Flow $flowId send error: ${e.message}")
            }
        }
    }

    private fun startUdpReader(session: UdpSession) {
        relayScope.launch {
            val buf = ByteArray(64 * 1024)
            try {
                while (running.get()) {
                    val dp = DatagramPacket(buf, buf.size)
                    withContext(Dispatchers.IO) {
                        session.socket.receive(dp)
                    }
                    session.lastUsed.set(System.currentTimeMillis())

                    val payload = dp.data.copyOfRange(dp.offset, dp.offset + dp.length)
                    
                    // WebRTC DataChannel limit check - relaxed to 2048 to support QUIC (1232b)
                    if (payload.size > 2048) {
                        Log.w(TAG, "UDP response from ${session.targetHost} truly too large (${payload.size}b) - dropping")
                        continue
                    }

                    log("UDP Flow ${session.flowId} received response (${payload.size}b)")
                    val response = FramingProtocol.buildUdp(session.flowId, session.targetHost, session.targetPort, payload)
                    sendToPeer(response, false)
                }
            } catch (e: Exception) {
            } finally {
                closeUdp(session.flowId)
            }
        }
    }

    private fun startJanitor() {
        relayScope.launch {
            while (running.get()) {
                delay(60_000)
                val now = System.currentTimeMillis()
                val tcpTimeout = 90_000L
                val udpTimeout = 15_000L // Fast cleanup for DNS queries

                tcpSessions.forEach { (id, _) ->
                    if (now - (tcpSessions[id]?.lastActivity?.get() ?: 0) > tcpTimeout) {
                        closeTcpFlow(id)
                    }
                }

                udpSessions.forEach { (id, s) ->
                    if (now - s.lastUsed.get() > udpTimeout) {
                        closeUdp(id)
                    }
                }
            }
        }
    }

    private fun startIdleReaper() {
        relayScope.launch {
            while (isActive) {
                delay(10_000)
                val now = System.currentTimeMillis()
                val idleSessions = tcpSessions.values.filter { now - it.lastActivity.get() > 15_000 }
                idleSessions.forEach { session ->
                    log("Reaping idle TCP flow ${session.flowId} (15s timeout)")
                    closeTcpFlow(session.flowId)
                }
            }
        }
    }

    private fun closeTcpFlow(flowId: Int) {
        val s = tcpSessions.remove(flowId) ?: return
        if (s.closed.compareAndSet(false, true)) {
            pendingData.remove(flowId)
            runCatching { s.writeChannel.close() }
            relayScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        if (!s.socket.isClosed) {
                            if (!s.socket.isInputShutdown) try { s.socket.shutdownInput() } catch (_: Exception) {}
                            if (!s.socket.isOutputShutdown) try { s.socket.shutdownOutput() } catch (_: Exception) {}
                            s.socket.close()
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Socket close error for Flow $flowId: ${e.message}")
                }
            }
            sendToPeer(FramingProtocol.buildClose(flowId), true)
            log("TCP Flow $flowId closed")
        }
    }

    private fun closeUdp(flowId: Int) {
        val s = udpSessions.remove(flowId) ?: return
        relayScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    s.socket.close()
                }
            } catch (_: Exception) {}
        }
    }

    fun shutdown() {
        running.set(false)
        tcpSessions.keys.toList().forEach { closeTcpFlow(it) }
        udpSessions.keys.toList().forEach { closeUdp(it) }
        relayScope.cancel()
    }
}
