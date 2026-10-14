package com.kamuinet.client.router

object PacketRouter {

    const val PROTO_TCP = 6
    const val PROTO_UDP = 17
    const val PROTO_ICMP = 1

    data class FlowKey(
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        val protocol: Int
    ) {
        override fun toString() = "$protocol:$srcIp:$srcPort->$dstIp:$dstPort"
    }

    data class ParsedPacket(
        val version: Int,
        val protocol: Int,
        val srcIp: String,
        val dstIp: String,
        val srcPort: Int,
        val dstPort: Int,
        val ipHeaderLen: Int,
        val transportHeaderLen: Int,
        val payloadOffset: Int,
        val payloadLength: Int,
        val isTcpSyn: Boolean,
        val isTcpFin: Boolean,
        val isTcpRst: Boolean,
        val tcpSeq: Long,
        val tcpAck: Long
    )

    fun parse(packet: ByteArray): ParsedPacket? {
        if (packet.size < 20) return null
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return null

        val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
        if (packet.size < ipHeaderLen + 4) return null

        val protocol = packet[9].toInt() and 0xFF
        val srcIp = ipToString(packet, 12)
        val dstIp = ipToString(packet, 16)

        var srcPort = 0
        var dstPort = 0
        var transportHeaderLen = 0
        var isSyn = false
        var isFin = false
        var isRst = false
        var tcpSeq = 0L
        var tcpAck = 0L

        when (protocol) {
            PROTO_TCP -> {
                if (packet.size < ipHeaderLen + 20) return null
                srcPort = readPort(packet, ipHeaderLen)
                dstPort = readPort(packet, ipHeaderLen + 2)
                tcpSeq = readUInt32(packet, ipHeaderLen + 4)
                tcpAck = readUInt32(packet, ipHeaderLen + 8)
                val dataOffset = ((packet[ipHeaderLen + 12].toInt() shr 4) and 0x0F) * 4
                transportHeaderLen = dataOffset
                val flags = packet[ipHeaderLen + 13].toInt() and 0xFF
                isSyn = (flags and 0x02) != 0
                isFin = (flags and 0x01) != 0
                isRst = (flags and 0x04) != 0
            }
            PROTO_UDP -> {
                if (packet.size < ipHeaderLen + 8) return null
                srcPort = readPort(packet, ipHeaderLen)
                dstPort = readPort(packet, ipHeaderLen + 2)
                transportHeaderLen = 8
            }
            else -> return null
        }

        val payloadOffset = ipHeaderLen + transportHeaderLen
        val payloadLength = (packet.size - payloadOffset).coerceAtLeast(0)

        return ParsedPacket(
            version = version,
            protocol = protocol,
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            ipHeaderLen = ipHeaderLen,
            transportHeaderLen = transportHeaderLen,
            payloadOffset = payloadOffset,
            payloadLength = payloadLength,
            isTcpSyn = isSyn,
            isTcpFin = isFin,
            isTcpRst = isRst,
            tcpSeq = tcpSeq,
            tcpAck = tcpAck
        )
    }

    fun flowKey(p: ParsedPacket) = FlowKey(p.srcIp, p.srcPort, p.dstIp, p.dstPort, p.protocol)

    fun getDestAddress(packet: ByteArray): String? = parse(packet)?.dstIp
    fun getDestPort(packet: ByteArray): Int = parse(packet)?.dstPort ?: 0
    fun getProtocol(packet: ByteArray): Int = parse(packet)?.protocol ?: -1

    fun extractPayload(packet: ByteArray): ByteArray? {
        val p = parse(packet) ?: return null
        if (p.payloadLength <= 0) return ByteArray(0)
        return packet.copyOfRange(p.payloadOffset, p.payloadOffset + p.payloadLength)
    }

    private fun ipToString(buf: ByteArray, offset: Int): String =
        "${buf[offset].toInt() and 0xFF}.${buf[offset + 1].toInt() and 0xFF}." +
        "${buf[offset + 2].toInt() and 0xFF}.${buf[offset + 3].toInt() and 0xFF}"

    private fun readPort(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun readUInt32(buf: ByteArray, offset: Int): Long {
        return ((buf[offset].toLong() and 0xFF) shl 24) or
                ((buf[offset + 1].toLong() and 0xFF) shl 16) or
                ((buf[offset + 2].toLong() and 0xFF) shl 8) or
                (buf[offset + 3].toLong() and 0xFF)
    }

    private var ipIdCounter = 0

    fun buildResponsePacket(
        original: ParsedPacket,
        payload: ByteArray,
        seq: Long = 0,
        ack: Long = 0,
        flags: Int = 0x18, // Default to ACK + PSH
        overrideSrcIp: String? = null
    ): ByteArray {
        val proto = original.protocol
        val isSynAck = (flags and 0x12) == 0x12
        val tcpHeaderLen = if (isSynAck) 24 else 20
        val transportLen = if (proto == PROTO_TCP) tcpHeaderLen else 8
        val totalLen = 20 + transportLen + payload.size
        val out = ByteArray(totalLen)

        out[0] = 0x45.toByte()
        out[1] = 0
        out[2] = ((totalLen shr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        
        // Rolling IP Identification - some stacks drop packets with ID 0
        val id = (ipIdCounter++ and 0xFFFF)
        out[4] = ((id shr 8) and 0xFF).toByte()
        out[5] = (id and 0xFF).toByte()
        
        out[8] = 64
        out[9] = proto.toByte()
        
        val srcIp = overrideSrcIp ?: original.dstIp
        writeIp(out, 12, srcIp)
        writeIp(out, 16, original.srcIp)

        val th = 20
        writePort(out, th, original.dstPort)
        writePort(out, th + 2, original.srcPort)

        if (proto == PROTO_TCP) {
            writeUInt32(out, th + 4, seq)
            writeUInt32(out, th + 8, ack)
            val dataOffset = (tcpHeaderLen / 4) shl 4
            out[th + 12] = dataOffset.toByte()
            out[th + 13] = flags.toByte()
            out[th + 14] = 0xFF.toByte() // Window size
            out[th + 15] = 0xFF.toByte()
            
            if (isSynAck) {
                // MSS Option: Type=2, Len=4, Value=1200 (0x04B0) - Optimized for 1280 MTU tunnel
                out[th + 20] = 2
                out[th + 21] = 4
                out[th + 22] = 0x04.toByte()
                out[th + 23] = 0xB0.toByte()
            }
        } else {
            val udpLen = 8 + payload.size
            out[th + 4] = ((udpLen shr 8) and 0xFF).toByte()
            out[th + 5] = (udpLen and 0xFF).toByte()
        }

        System.arraycopy(payload, 0, out, 20 + transportLen, payload.size)

        // IP Checksum
        val ipCsum = checksum(out, 0, 20)
        out[10] = ((ipCsum shr 8) and 0xFF).toByte()
        out[11] = (ipCsum and 0xFF).toByte()

        // Transport Checksum (TCP/UDP)
        val pSum = pseudoHeaderSum(srcIp, original.srcIp, proto, transportLen + payload.size)
        val tCsum = checksum(out, 20, transportLen + payload.size, pSum)
        if (proto == PROTO_TCP) {
            out[20 + 16] = ((tCsum shr 8) and 0xFF).toByte()
            out[20 + 17] = (tCsum and 0xFF).toByte()
        } else if (proto == PROTO_UDP) {
            // Standard: Checksum 0 in UDP means no checksum. If calculation is 0, use 0xFFFF.
            val finalCsum = if (tCsum == 0) 0xFFFF else tCsum
            out[20 + 6] = ((finalCsum shr 8) and 0xFF).toByte()
            out[20 + 7] = (finalCsum and 0xFF).toByte()
        }

        return out
    }

    private fun pseudoHeaderSum(srcIp: String, dstIp: String, protocol: Int, length: Int): Int {
        var sum = 0
        val srcParts = srcIp.split(".")
        val dstParts = dstIp.split(".")
        if (srcParts.size == 4 && dstParts.size == 4) {
            sum += (srcParts[0].toInt() and 0xFF shl 8) or (srcParts[1].toInt() and 0xFF)
            sum += (srcParts[2].toInt() and 0xFF shl 8) or (srcParts[3].toInt() and 0xFF)
            sum += (dstParts[0].toInt() and 0xFF shl 8) or (dstParts[1].toInt() and 0xFF)
            sum += (dstParts[2].toInt() and 0xFF shl 8) or (dstParts[3].toInt() and 0xFF)
        }
        sum += protocol
        sum += length
        return sum
    }

    private fun writeIp(buf: ByteArray, offset: Int, ip: String) {
        val parts = ip.split(".")
        if (parts.size == 4) {
            for (i in 0 until 4) buf[offset + i] = parts[i].toInt().toByte()
        }
    }

    private fun writePort(buf: ByteArray, offset: Int, port: Int) {
        buf[offset] = ((port shr 8) and 0xFF).toByte()
        buf[offset + 1] = (port and 0xFF).toByte()
    }

    private fun writeUInt32(buf: ByteArray, offset: Int, v: Long) {
        buf[offset] = ((v shr 24) and 0xFF).toByte()
        buf[offset + 1] = ((v shr 16) and 0xFF).toByte()
        buf[offset + 2] = ((v shr 8) and 0xFF).toByte()
        buf[offset + 3] = (v and 0xFF).toByte()
    }

    private fun checksum(buf: ByteArray, offset: Int, length: Int, initialSum: Int = 0): Int {
        var sum = initialSum
        var i = offset
        while (i < offset + length - 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (length % 2 != 0) sum += (buf[offset + length - 1].toInt() and 0xFF) shl 8
        
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    fun buildIcmpFragmentationNeeded(original: ParsedPacket, mtu: Int): ByteArray {
        val totalLen = 20 + 8 + original.ipHeaderLen + 8
        val out = ByteArray(totalLen)
        
        out[0] = 0x45.toByte()
        out[1] = 0
        out[2] = ((totalLen shr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[8] = 64
        out[9] = PROTO_ICMP.toByte()
        writeIp(out, 12, original.dstIp)
        writeIp(out, 16, original.srcIp)
        
        val th = 20
        out[th] = 3 // Type 3
        out[th + 1] = 4 // Code 4
        out[th + 6] = ((mtu shr 8) and 0xFF).toByte()
        out[th + 7] = (mtu and 0xFF).toByte()
        
        val icmpSum = checksum(out, th, totalLen - th)
        out[th + 2] = ((icmpSum shr 8) and 0xFF).toByte()
        out[th + 3] = (icmpSum and 0xFF).toByte()
        
        val ipCsum = checksum(out, 0, 20)
        out[10] = ((ipCsum shr 8) and 0xFF).toByte()
        out[11] = (ipCsum and 0xFF).toByte()
        
        return out
    }
}
