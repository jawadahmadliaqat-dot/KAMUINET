package com.kamuinet.client.router

import org.junit.Assert.*
import org.junit.Test

class FramingProtocolTest {

    @Test
    fun testTcpOpenFrame() {
        val flowId = 99
        val host = "example.com"
        val port = 443
        
        val bytes = FramingProtocol.buildTcpOpen(flowId, host, port)
        val frame = FramingProtocol.parse(bytes)
        
        assertNotNull(frame)
        assertEquals(FramingProtocol.ACTION_CONNECT, frame!!.type)
        assertEquals(flowId, frame.flowId)
        assertEquals(host, frame.host)
        assertEquals(port, frame.port)
    }

    @Test
    fun testTcpDataFrame() {
        val flowId = 55
        val payload = "packet data".toByteArray()
        
        val bytes = FramingProtocol.buildData(FramingProtocol.ACTION_DATA, flowId, payload)
        val frame = FramingProtocol.parse(bytes)
        
        assertNotNull(frame)
        assertEquals(FramingProtocol.ACTION_DATA, frame!!.type)
        assertEquals(flowId, frame.flowId)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun testUdpFrame() {
        val flowId = 77
        val host = "8.8.8.8"
        val port = 53
        val payload = byteArrayOf(1, 2, 3)
        
        val bytes = FramingProtocol.buildUdp(flowId, host, port, payload)
        val frame = FramingProtocol.parse(bytes)
        
        assertNotNull(frame)
        assertEquals(FramingProtocol.ACTION_UDP_DATA, frame!!.type)
        assertEquals(flowId, frame.flowId)
        assertEquals(host, frame.host)
        assertEquals(port, frame.port)
        assertArrayEquals(payload, frame.payload)
    }
}
