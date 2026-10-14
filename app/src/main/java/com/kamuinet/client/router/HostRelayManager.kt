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
    
    // JOKER OPTIMIZATION: Limit total threads to 32 to prevent Infinix from heating up
    // Dispatchers.IO by default can spawn 64+ threads which causes GC pressure.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(32))
    
    private val running = AtomicBoolean(true)
    private val MAX_SESSIONS = 500 // Increased for modern browsers
    private val resumeSignal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    private data class TcpSession(
        val socket: Socket,
        val flowId: Int,
        val closed: AtomicBoolean = AtomicBoolean(false),
        val serverEof: AtomicBoolean = AtomicBoolean(false),
        val clientFin: AtomicBoolean = AtomicBoolean(false),
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

    fun onBufferedAmountLow() {
        resumeSignal.trySend(Unit)
    }

    /**
     * Handle framed commands from the peer (Client)
     */
    fun onClientPacket(data: ByteArray) {
        if (!running.get()) return
        val frame = FramingProtocol.parse(data) ?: return

        when (frame.type) {
            FramingProtocol.ACTION_CONNECT -> handleTcpOpen(frame)
            FramingProtocol.ACTION_DATA, FramingProtocol.ACTION_FRAG_START -> handleTcpData(frame)
            FramingProtocol.ACTION_CLOSE -> closeTcpFlow(frame.flowId)
            FramingProtocol.ACTION_FIN -> handleTcpFin(frame.flowId)
            FramingProtocol.ACTION_UDP_DATA -> handleUdpData(frame)
        }
    }

    private fun handleTcpFin(flowId: Int) {
        val session = tcpSessions[flowId] ?: return
        if (session.clientFin.compareAndSet(false, true)) {
            relayScope.launch(Dispatchers.IO) {
                try {
                    if (!session.socket.isOutputShutdown) {
                        session.socket.shutdownOutput()
                    }
                } catch (_: Exception) {}
                checkTeardown(session)
            }
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

        // Aggressive Data Savings: Host-side Ad-Blocking
        if (AdBlocker.isAd(host)) {
            Log.d(TAG, "Aggressive Savings: Blocking AD flow to $host (Flow $flowId)")
            sendToPeer(FramingProtocol.buildClose(flowId), true)
            return
        }

        // DoH Guard: Block Google DNS-over-HTTPS only.

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
                sock.setSoLinger(true, 0) // Immediate release of file descriptor
                sock.receiveBufferSize = 64 * 1024
                sock.sendBufferSize = 64 * 1024
                withContext(Dispatchers.IO) {
                    // 3-second fast fail: don't let dead/speculative connects hang the thread pool
                    sock.connect(InetSocketAddress(host, port), 3000)
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
                                if (!session.closed.get()) {
                                    session.writeChannel.send(payload)
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
            } catch (e: java.net.SocketException) {
                // Silently handle common Chrome aborts (Broken pipe, Connection reset)
                pendingData.remove(flowId)
                sendToPeer(FramingProtocol.buildClose(flowId), true)
            } catch (e: java.io.IOException) {
                // Silently handle IO errors during rapid browser connection storms
                pendingData.remove(flowId)
                sendToPeer(FramingProtocol.buildClose(flowId), true)
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
        
        // JOKER OPTIMIZATION: Direct channel send without per-packet coroutine launch.
        // This eliminates coroutine explosion during high-speed 720p streams.
        val result = session.writeChannel.trySend(payload)
        if (!result.isSuccess) {
            // If channel is full, then launch a coroutine to wait (Backpressure)
            relayScope.launch {
                try {
                    if (!session.closed.get()) {
                        session.writeChannel.send(payload)
                    }
                } catch (_: Exception) {}
            }
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
                        // Joker Stealth: Force small writes into separate segments
                        if (data.size <= 5) {
                            out.flush() 
                        }
                    }
                }
            } catch (e: java.net.SocketException) {
                // Normal for Chrome aborting speculative connections
            } catch (e: java.io.IOException) {
                // Silently handle IO errors during connection storms
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
                while (running.get() && !session.closed.get() && !session.serverEof.get()) {
                    // Backpressure: If WebRTC buffer > 512KB, pause reading
                    if (getBufferedAmount() > 512 * 1024) {
                        resumeSignal.receive()
                    }

                    val n = withContext(Dispatchers.IO) { input.read(buf) }
                    if (n < 0) {
                        log("TCP Flow ${session.flowId}: EOF reached (Server)")
                        session.serverEof.set(true)
                        break
                    }
                    if (n == 0) continue

                    session.lastActivity.set(System.currentTimeMillis())
                    val payload = buf.copyOfRange(0, n)
                    val dataFrame = FramingProtocol.buildData(FramingProtocol.ACTION_DATA, session.flowId, payload)
                    sendToPeer(dataFrame, true)
                }
            } catch (e: java.net.SocketException) {
                // Normal for aborted browser pre-connections
            } catch (e: java.io.IOException) {
                // Silently handle IO errors during storms
            } catch (e: Exception) {
                if (!session.closed.get()) Log.d(TAG, "TCP reader ${session.flowId} end: ${e.message}")
            } finally {
                if (!session.closed.get()) {
                    if (session.serverEof.get()) {
                        // Notify peer of half-close
                        sendToPeer(FramingProtocol.buildData(FramingProtocol.ACTION_FIN, session.flowId, ByteArray(0)), true)
                        checkTeardown(session)
                    } else {
                        // Error or force close
                        sendToPeer(FramingProtocol.buildClose(session.flowId), true)
                        closeTcpFlow(session.flowId)
                    }
                }
            }
        }
    }

    private fun checkTeardown(session: TcpSession) {
        if (session.serverEof.get() && session.clientFin.get()) {
            closeTcpFlow(session.flowId)
        }
    }

    private fun handleUdpData(frame: FramingProtocol.Frame) {
        val flowId = frame.flowId
        val payload = frame.payload ?: return
        val host = frame.host ?: return
        val port = frame.port

        if (port == 443) {
            Log.d(TAG, "Blocking QUIC (UDP 443) for Flow $flowId - Force TCP fallback")
            return
        }

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
                val tcpTimeout = 300_000L // Increased to 5 mins for better Keep-Alive
                val udpTimeout = 30_000L 

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
                delay(30_000)
                val now = System.currentTimeMillis()
                // Stealth Optimization: Only reap truly dead flows (2 mins)
                val idleSessions = tcpSessions.values.filter { now - it.lastActivity.get() > 120_000 }
                idleSessions.forEach { session ->
                    log("Reaping dead TCP flow ${session.flowId} (120s timeout)")
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
