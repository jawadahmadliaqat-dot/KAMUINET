package com.kamuinet.client.router

import java.nio.ByteBuffer

object SniSniffer {

    /**
     * Extracts the SNI (Server Name Indication) from a TLS Client Hello packet.
     * Returns the hostname or null if not found.
     */
    fun getSnihostname(payload: ByteArray): String? {
        if (payload.size < 43) return null
        
        try {
            val buffer = ByteBuffer.wrap(payload)
            
            // TLS Header: Type(1), Version(2), Length(2)
            val type = buffer.get().toInt() and 0xFF
            if (type != 0x16) return null // Handshake
            
            buffer.getShort() // Version
            val length = buffer.getShort().toInt() and 0xFFFF
            if (payload.size < 5 + length) return null
            
            // Handshake Header: Type(1), Length(3)
            val handshakeType = buffer.get().toInt() and 0xFF
            if (handshakeType != 0x01) return null // Client Hello
            
            buffer.getShort(); buffer.get() // Handshake Length (3 bytes)
            
            // Client Hello: Version(2), Random(32)
            buffer.getShort() // Version
            buffer.position(buffer.position() + 32) // Skip Random
            
            // Session ID (1 + length)
            val sessionIdLen = buffer.get().toInt() and 0xFF
            buffer.position(buffer.position() + sessionIdLen)
            
            // Cipher Suites (2 + length)
            val cipherSuitesLen = buffer.getShort().toInt() and 0xFFFF
            buffer.position(buffer.position() + cipherSuitesLen)
            
            // Compression Methods (1 + length)
            val compressionMethodsLen = buffer.get().toInt() and 0xFF
            buffer.position(buffer.position() + compressionMethodsLen)
            
            if (buffer.remaining() < 2) return null
            val extensionsLen = buffer.getShort().toInt() and 0xFFFF
            if (buffer.remaining() < extensionsLen) return null
            
            val extensionsEnd = buffer.position() + extensionsLen
            while (buffer.position() < extensionsEnd) {
                val extType = buffer.getShort().toInt() and 0xFFFF
                val extLen = buffer.getShort().toInt() and 0xFFFF
                
                if (extType == 0x00) { // Server Name
                    if (buffer.remaining() < 2) return null
                    val sniListLen = buffer.getShort().toInt() and 0xFFFF
                    if (buffer.remaining() < sniListLen) return null
                    
                    val sniEnd = buffer.position() + sniListLen
                    while (buffer.position() < sniEnd) {
                        val nameType = buffer.get().toInt() and 0xFF
                        val nameLen = buffer.getShort().toInt() and 0xFFFF
                        if (nameType == 0x00) { // Hostname
                            val hostBytes = ByteArray(nameLen)
                            buffer.get(hostBytes)
                            return String(hostBytes)
                        }
                        buffer.position(buffer.position() + nameLen)
                    }
                }
                buffer.position(buffer.position() + extLen)
            }
        } catch (e: Exception) {
            // Parsing failure, not a standard Client Hello or malformed
        }
        
        return null
    }
}
