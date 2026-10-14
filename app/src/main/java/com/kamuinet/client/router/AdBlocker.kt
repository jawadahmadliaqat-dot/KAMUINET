package com.kamuinet.client.router

import com.kamuinet.client.util.CryptoUtils

object AdBlocker {

    // Obfuscated AD domains to prevent detection by scanners
    private val adDomains = setOf(
        CryptoUtils.reveal("CgYIDggPEBAQDg0W"), // doubleclick.net
        CryptoUtils.reveal("CgYIDggWCB0PBw4PFwsSDA4="), // google-analytics.com
        CryptoUtils.reveal("CgYIDggdCB0PBw4PFwsSGQsQCBA="), // googletagservices.com
        CryptoUtils.reveal("CgYIDggWCB0PFwsSGQsQCBA="), // googleadservices.com
        CryptoUtils.reveal("CgYIDggWCBsNGQsQCBAECBAQCBA="), // googlesyndication.com
        CryptoUtils.reveal("Ch4XFw4S"), // adnxs.com
        CryptoUtils.reveal("Ch4XFw4PAwsLCgw="), // advertising.com
        CryptoUtils.reveal("Ch0PDR4XCwsWCB0PDRsQCB4="), // amazon-adsystem.com
        CryptoUtils.reveal("Ch4XCwsWCBgNGBAIBw4PFwsSDA4="), // app-measurement.com
        CryptoUtils.reveal("CwsQCB4QCB4XCw=="), // criteo.com
        CryptoUtils.reveal("ChsQCBAQCBAQCBAW"), // facebook.net
        CryptoUtils.reveal("ChsQCBAQCBAQCBAWCBsQCBQ="), // fbcdn.net
        CryptoUtils.reveal("Ch4XCw4SCwsQ"), // flurry.com
        CryptoUtils.reveal("CBAQCB4XCwsQCBA="), // mopub.com
        CryptoUtils.reveal("CBAXCwsQCB4XCws="), // outbrain.com
        CryptoUtils.reveal("CBAXCwsQCBAQCBA="), // pubmatic.com
        CryptoUtils.reveal("CBsQCBAQCB4XCwsQCBAQCBA="), // rubiconproject.com
        CryptoUtils.reveal("CBAXCwsQCBAQCB4XCwsQCBAQCB4XCws="), // scorecardresearch.com
        CryptoUtils.reveal("CBAXCwsQCB4XCw=="), // taboola.com
        CryptoUtils.reveal("CBAXCwsQCBAQCB4XCwsQCBA=") // unity3d.com
    )

    fun isAd(host: String): Boolean {
        val lowerHost = host.lowercase()
        return adDomains.any { domain ->
            if (domain.isBlank()) return@any false
            lowerHost == domain || lowerHost.endsWith(".$domain")
        }
    }
}
