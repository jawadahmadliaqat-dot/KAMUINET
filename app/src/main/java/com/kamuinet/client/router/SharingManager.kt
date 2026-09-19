package com.kamuinet.client.router

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SharingManager @Inject constructor() {

    private val TAG = "SharingManager"
    private val scope = CoroutineScope(Dispatchers.IO)
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var isSharing = false

    fun startSharing(port: Int, onClientRequest: (targetAddr: InetAddress, targetPort: Int) -> Unit, onLog: (String) -> Unit) {
        if (isSharing) return
        isSharing = true
        
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                onLog("Host Bridge active on port $port")
                while (isSharing) {
                    val clientSocket = serverSocket?.accept() ?: break
                    handleClientTraffic(clientSocket, onClientRequest, onLog)
                }
            } catch (e: Exception) {
                onLog("Bridge error: ${e.message}")
            }
        }
    }

    fun stopSharing() {
        isSharing = false
        serverSocket?.close()
        serverJob?.cancel()
        serverSocket = null
        serverJob = null
    }

    private fun handleClientTraffic(
        clientSocket: Socket, 
        onClientRequest: (InetAddress, Int) -> Unit,
        onLog: (String) -> Unit
    ) {
        scope.launch {
            try {
                val clientIn = clientSocket.getInputStream()
                val clientOut = clientSocket.getOutputStream()

                val buf = ByteArray(1024)
                var len = clientIn.read(buf)
                if (len < 2 || buf[0] != 0x05.toByte()) {
                    clientSocket.close()
                    return@launch
                }
                clientOut.write(byteArrayOf(0x05.toByte(), 0x00.toByte()))
                clientOut.flush()

                val addressType = buf[3].toInt()
                val targetAddr: InetAddress = when (addressType) {
                    0x01 -> { // IPv4
                        InetAddress.getByAddress(buf.copyOfRange(4, 8))
                    }
                    0x03 -> { // Domain Name
                        val domainLen = buf[4].toInt() and 0xFF
                        val domain = String(buf.copyOfRange(5, 5 + domainLen))
                        // Note: SOCKS5 says port follows domain. Update len check.
                        InetAddress.getByName(domain)
                    }
                    0x04 -> { // IPv6
                        InetAddress.getByAddress(buf.copyOfRange(4, 20))
                    }
                    else -> {
                        clientSocket.close()
                        return@launch
                    }
                }

                val portOffset = when (addressType) {
                    0x01 -> 8
                    0x03 -> 5 + (buf[4].toInt() and 0xFF)
                    0x04 -> 20
                    else -> 8
                }
                val targetPort = ((buf[portOffset].toInt() and 0xff) shl 8) or (buf[portOffset + 1].toInt() and 0xff)

                onClientRequest(targetAddr, targetPort)
                onLog("Peer request to $targetAddr:$targetPort")

                val targetSocket = Socket(targetAddr, targetPort)
                val targetIn = targetSocket.getInputStream()
                val targetOut = targetSocket.getOutputStream()

                val response = byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
                clientOut.write(response)
                clientOut.flush()

                launch {
                    try {
                        val b = ByteArray(32768)
                        var r: Int
                        while (clientIn.read(b).also { r = it } != -1) {
                            targetOut.write(b, 0, r)
                        }
                    } catch (e: Exception) {} finally { targetSocket.close(); clientSocket.close() }
                }

                try {
                    val b = ByteArray(32768)
                    var r: Int
                    while (targetIn.read(b).also { r = it } != -1) {
                        clientOut.write(b, 0, r)
                    }
                } catch (e: Exception) {} finally { targetSocket.close(); clientSocket.close() }

            } catch (e: Exception) {
                try { clientSocket.close() } catch (ex: Exception) {}
            }
        }
    }
}
