package com.kamuinet.client.router

import java.nio.ByteBuffer

object FramingProtocol {

    const val ACTION_CONNECT = 0x01.toByte()
    const val ACTION_DATA = 0x02.toByte()
    const val ACTION_CLOSE = 0x03.toByte()
    const val ACTION_UDP_DATA = 0x04.toByte()
    const val ACTION_TCP_ACK = 0x05.toByte()

    /**
     * Standard Frame Structure:
     * [Stream ID (4b)] [Action (1b)] [Payload Length (2b)] [Payload (Nb)]
     */

    fun buildTcpOpen(flowId: Int, host: String, port: Int): ByteArray {
        val hostBytes = host.toByteArray()
        // Payload for CONNECT: [Port (2b)] [HostLen (2b)] [Host]
        val payloadSize = 2 + 2 + hostBytes.size
        val buffer = ByteBuffer.allocate(4 + 1 + 2 + payloadSize)
        buffer.putInt(flowId)
        buffer.put(ACTION_CONNECT)
        buffer.putShort(payloadSize.toShort())
        buffer.putShort(port.toShort())
        buffer.putShort(hostBytes.size.toShort())
        buffer.put(hostBytes)
        return buffer.array()
    }

    fun buildData(type: Byte, flowId: Int, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(4 + 1 + 2 + payload.size)
        buffer.putInt(flowId)
        buffer.put(type)
        buffer.putShort(payload.size.toShort())
        buffer.put(payload)
        return buffer.array()
    }

    fun buildClose(flowId: Int): ByteArray {
        val buffer = ByteBuffer.allocate(4 + 1 + 2)
        buffer.putInt(flowId)
        buffer.put(ACTION_CLOSE)
        buffer.putShort(0)
        return buffer.array()
    }

    fun buildTcpAck(flowId: Int): ByteArray {
        val buffer = ByteBuffer.allocate(4 + 1 + 2)
        buffer.putInt(flowId)
        buffer.put(ACTION_TCP_ACK)
        buffer.putShort(0)
        return buffer.array()
    }

    fun buildUdp(flowId: Int, host: String, port: Int, payload: ByteArray): ByteArray {
        val hostBytes = host.toByteArray()
        // Payload for UDP: [Port (2b)] [HostLen (2b)] [Host] [Data]
        val payloadSize = 2 + 2 + hostBytes.size + payload.size
        val buffer = ByteBuffer.allocate(4 + 1 + 2 + payloadSize)
        buffer.putInt(flowId)
        buffer.put(ACTION_UDP_DATA)
        buffer.putShort(payloadSize.toShort())
        buffer.putShort(port.toShort())
        buffer.putShort(hostBytes.size.toShort())
        buffer.put(hostBytes)
        buffer.put(payload)
        return buffer.array()
    }

    data class Frame(
        val type: Byte,
        val flowId: Int,
        val host: String? = null,
        val port: Int = 0,
        val payload: ByteArray? = null
    )

    fun parse(data: ByteArray): Frame? {
        if (data.size < 7) return null
        val buffer = ByteBuffer.wrap(data)
        val flowId = buffer.getInt()
        val type = buffer.get()
        val payloadLen = buffer.getShort().toInt() and 0xFFFF

        return when (type) {
            ACTION_CONNECT -> {
                if (buffer.remaining() < 4) return null
                val port = buffer.getShort().toInt() and 0xFFFF
                val hostLen = buffer.getShort().toInt() and 0xFFFF
                if (buffer.remaining() < hostLen) return null
                val hostBytes = ByteArray(hostLen)
                buffer.get(hostBytes)
                Frame(type, flowId, String(hostBytes), port)
            }
            ACTION_DATA, ACTION_TCP_ACK -> {
                val payload = if (payloadLen > 0) {
                    val p = ByteArray(payloadLen)
                    if (buffer.remaining() < payloadLen) return null
                    buffer.get(p)
                    p
                } else null
                Frame(type, flowId, payload = payload)
            }
            ACTION_CLOSE -> {
                Frame(type, flowId)
            }
            ACTION_UDP_DATA -> {
                if (buffer.remaining() < 4) return null
                val port = buffer.getShort().toInt() and 0xFFFF
                val hostLen = buffer.getShort().toInt() and 0xFFFF
                if (buffer.remaining() < hostLen) return null
                val hostBytes = ByteArray(hostLen)
                buffer.get(hostBytes)
                val remainingPayload = payloadLen - (4 + hostLen)
                val payload = if (remainingPayload > 0) {
                    val p = ByteArray(remainingPayload)
                    if (buffer.remaining() < remainingPayload) return null
                    buffer.get(p)
                    p
                } else ByteArray(0)
                Frame(type, flowId, String(hostBytes), port, payload)
            }
            else -> null
        }
    }
}
