package com.kamuinet.client.router

import java.nio.ByteBuffer

object FramingProtocol {

    const val ACTION_CONNECT = 0x01.toByte()
    const val ACTION_DATA = 0x02.toByte()
    const val ACTION_CLOSE = 0x03.toByte()
    const val ACTION_UDP_DATA = 0x04.toByte()
    const val ACTION_TCP_ACK = 0x05.toByte()
    const val ACTION_FIN = 0x06.toByte()
    const val ACTION_FRAG_START = 0x07.toByte() // Start of a fragmented sequence
    const val FLAG_COMPRESSED = 0x80.toInt()
    const val FLAG_PADDED = 0x40.toInt()

    /**
     * Standard Frame Structure:
     * [Stream ID (4b)] [Action & Flags (1b)] [Payload Length (2b)] [Payload (Nb)] [Optional Padding]
     * 
     * If (Action & FLAG_COMPRESSED) != 0, the payload is GZIP compressed.
     * If (Action & FLAG_PADDED) != 0, the next byte after Short Length is the Padding Length (1b).
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

    fun buildData(type: Byte, flowId: Int, payload: ByteArray, isHighThroughput: Boolean = false): ByteArray {
        val (finalPayload, isCompressed) = DataOptimizer.compress(payload)
        
        // Joker Stealth: Adaptive padding based on throughput.
        // If high throughput (e.g. streaming), use minimal padding to save overhead.
        // If low throughput, use more padding to obfuscate traffic patterns.
        val padLen = if (type == ACTION_DATA) {
            if (isHighThroughput) (0..8).random() else (4..48).random()
        } else 0
        
        var actionWithFlags = if (isCompressed) (type.toInt() or FLAG_COMPRESSED).toByte() else type
        if (padLen > 0) actionWithFlags = (actionWithFlags.toInt() or FLAG_PADDED).toByte()

        val headerSize = 4 + 1 + 2 + (if (padLen > 0) 1 else 0)
        val buffer = ByteBuffer.allocate(headerSize + finalPayload.size + padLen)
        buffer.putInt(flowId)
        buffer.put(actionWithFlags)
        buffer.putShort(finalPayload.size.toShort())
        if (padLen > 0) {
            buffer.put(padLen.toByte())
        }
        buffer.put(finalPayload)
        if (padLen > 0) {
            val padding = ByteArray(padLen)
            java.util.Random().nextBytes(padding)
            buffer.put(padding)
        }
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
        val payload: ByteArray? = null,
        val originalPayloadSize: Int = 0
    )

    fun parse(data: ByteArray): Frame? {
        if (data.size < 7) return null
        val buffer = ByteBuffer.wrap(data)
        val flowId = buffer.getInt()
        val actionByte = buffer.get().toInt()
        val isCompressed = (actionByte and FLAG_COMPRESSED) != 0
        val isPadded = (actionByte and FLAG_PADDED) != 0
        val type = (actionByte and (FLAG_COMPRESSED or FLAG_PADDED).inv()).toByte()
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
            ACTION_DATA, ACTION_TCP_ACK, ACTION_FIN, ACTION_FRAG_START -> {
                if (isPadded) {
                    if (buffer.remaining() < 1) return null
                    buffer.get() // Skip padding length byte
                }
                
                var payload = if (payloadLen > 0) {
                    val p = ByteArray(payloadLen)
                    if (buffer.remaining() < payloadLen) return null
                    buffer.get(p)
                    p
                } else null
                
                var originalSize = payload?.size ?: 0
                if (isCompressed && payload != null) {
                    payload = DataOptimizer.decompress(payload)
                    originalSize = payload.size
                }
                
                Frame(type, flowId, payload = payload, originalPayloadSize = originalSize)
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
                val remainingPayloadLen = payloadLen - (4 + hostLen)
                val rawPayload = if (remainingPayloadLen > 0) {
                    val p = ByteArray(remainingPayloadLen)
                    if (buffer.remaining() < remainingPayloadLen) return null
                    buffer.get(p)
                    p
                } else ByteArray(0)
                
                var originalSize = rawPayload.size
                val finalPayload = if (isCompressed) {
                    val decompressed = DataOptimizer.decompress(rawPayload)
                    originalSize = decompressed.size
                    decompressed
                } else rawPayload
                
                Frame(type, flowId, String(hostBytes), port, finalPayload, originalSize)
            }
            else -> null
        }
    }


}
