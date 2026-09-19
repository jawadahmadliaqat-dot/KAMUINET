package com.kamuinet.client.webrtc

import org.webrtc.PeerConnection

object WebRtcConfig {
    val iceServers: List<PeerConnection.IceServer> = listOf(
        // STUN Server
        PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80")
            .createIceServer(),

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
