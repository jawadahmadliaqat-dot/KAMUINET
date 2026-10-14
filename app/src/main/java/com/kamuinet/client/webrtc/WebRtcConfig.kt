package com.kamuinet.client.webrtc

import com.kamuinet.client.util.CryptoUtils
import org.webrtc.PeerConnection

object WebRtcConfig {
    
    // Obfuscated Carrier Masking Hosts
    private val WA_HOST = CryptoUtils.reveal("DA0dDA4SDAseDBoN") // "m.whatsapp.net"
    private val GOOGLE_STUN = CryptoUtils.reveal("Cg4QEB4dCBIODw==") // "stun.l.google.com"

    val iceServers: List<PeerConnection.IceServer> = listOf(
        // Public STUNs for Direct P2P (Priority)
        PeerConnection.IceServer.builder("stun:$GOOGLE_STUN:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        
        // WhatsApp Masked Relay (Only if Direct fails)
        PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer(),

        // TURN UDP (Port 80)
        PeerConnection.IceServer.builder("turn:standard.relay.metered.ca:80")
            .setUsername("fd1843c3a069b862d72d3bf0")
            .setPassword("3oVwcvErCVNFkPrp")
            .createIceServer(),

        // TURN TCP (Port 80)
        PeerConnection.IceServer.builder("turn:standard.relay.metered.ca:80?transport=tcp")
            .setUsername("fd1843c3a069b862d72d3bf0")
            .setPassword("3oVwcvErCVNFkPrp")
            .createIceServer(),

        // TURN UDP (Port 443 - SSL Bypass)
        PeerConnection.IceServer.builder("turn:standard.relay.metered.ca:443")
            .setUsername("fd1843c3a069b862d72d3bf0")
            .setPassword("3oVwcvErCVNFkPrp")
            .createIceServer(),

        // TURNS TCP (Port 443 - Secure)
        PeerConnection.IceServer.builder("turns:standard.relay.metered.ca:443?transport=tcp")
            .setUsername("fd1843c3a069b862d72d3bf0")
            .setPassword("3oVwcvErCVNFkPrp")
            .createIceServer()
    )
}
