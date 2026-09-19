package com.kamuinet.client.router

import org.junit.Assert.*
import org.junit.Test

class PacketRouterTest {

    @Test
    fun testParseIpv4TcpSyn() {
        // Minimal IPv4 TCP SYN packet
        val packet = ByteArray(40)
        packet[0] = 0x45.toByte() // Version 4, Header length 20
        packet[9] = 6.toByte()    // Protocol TCP
        
        // Source IP: 10.0.0.1
        packet[12] = 10; packet[13] = 0; packet[14] = 0; packet[15] = 1
        // Dest IP: 8.8.8.8
        packet[16] = 8; packet[17] = 8; packet[18] = 8; packet[19] = 8
        
        // TCP Ports: 1234 -> 80
        packet[20] = 0x04.toByte(); packet[21] = 0xD2.toByte()
        packet[22] = 0x00.toByte(); packet[23] = 0x50.toByte()
        
        // Data offset: 20 bytes (0x50)
        packet[32] = 0x50.toByte()
        // Flags: SYN (0x02)
        packet[33] = 0x02.toByte()

        val p = PacketRouter.parse(packet)
        assertNotNull(p)
        assertEquals(4, p!!.version)
        assertEquals(6, p.protocol)
        assertEquals("10.0.0.1", p.srcIp)
        assertEquals("8.8.8.8", p.dstIp)
        assertEquals(1234, p.srcPort)
        assertEquals(80, p.dstPort)
        assertTrue(p.isTcpSyn)
        assertFalse(p.isTcpFin)
    }

    @Test
    fun testBuildResponsePacket() {
        val original = PacketRouter.ParsedPacket(
            version = 4, protocol = 6,
            srcIp = "1.2.3.4", dstIp = "5.6.7.8",
            srcPort = 111, dstPort = 222,
            ipHeaderLen = 20, transportHeaderLen = 20,
            payloadOffset = 40, payloadLength = 0,
            isTcpSyn = true, isTcpFin = false, isTcpRst = false,
            tcpSeq = 100, tcpAck = 200
        )
        
        val payload = "hello".toByteArray()
        val response = PacketRouter.buildResponsePacket(original, payload, seq = 500, ack = 600)
        
        val p = PacketRouter.parse(response)
        assertNotNull(p)
        assertEquals("5.6.7.8", p!!.srcIp) // Swapped
        assertEquals("1.2.3.4", p.dstIp)   // Swapped
        assertEquals(500L, p.tcpSeq)
        assertEquals(600L, p.tcpAck)
        assertEquals(5, p.payloadLength)

        // Verify Checksums are present
        val ipChecksum = ((response[10].toInt() and 0xFF) shl 8) or (response[11].toInt() and 0xFF)
        val tcpChecksum = ((response[20 + 16].toInt() and 0xFF) shl 8) or (response[20 + 17].toInt() and 0xFF)
        
        assertNotEquals(0, ipChecksum)
        assertNotEquals(0, tcpChecksum)
    }

    @Test
    fun testUdpChecksum() {
        val original = PacketRouter.ParsedPacket(
            version = 4, protocol = 17,
            srcIp = "10.0.0.2", dstIp = "8.8.8.8",
            srcPort = 5000, dstPort = 53,
            ipHeaderLen = 20, transportHeaderLen = 8,
            payloadOffset = 28, payloadLength = 0,
            isTcpSyn = false, isTcpFin = false, isTcpRst = false,
            tcpSeq = 0, tcpAck = 0
        )
        val payload = "dns".toByteArray()
        val response = PacketRouter.buildResponsePacket(original, payload)
        
        val udpChecksum = ((response[20 + 6].toInt() and 0xFF) shl 8) or (response[20 + 7].toInt() and 0xFF)
        assertNotEquals(0, udpChecksum)
    }
}
