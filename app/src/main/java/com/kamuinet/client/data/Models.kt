package com.kamuinet.client.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val plan: String = "free",
    @SerialName("max_peers") val maxPeers: Int = 2,
    @SerialName("monthly_gb_limit") val monthlyGbLimit: Double = 5.0
)

@Serializable
data class ActiveHost(
    val uid: String,
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("local_ip") val localIp: String? = null,
    @SerialName("socks_port") val socksPort: Int = 1080,
    val mode: String = "local",
    val status: String = "offline",
    @SerialName("mb_limit") val mbLimit: Int = 0,
    @SerialName("relay_host") val relayHost: String? = null,
    @SerialName("relay_port") val relayPort: Int? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class SignalingRow(
    val id: Long? = null,
    @SerialName("target_uid") val targetUid: String,
    @SerialName("from_uid") val fromUid: String,
    @SerialName("target_device_id") val targetDeviceId: String? = null,
    @SerialName("from_device_id") val fromDeviceId: String? = null,
    val kind: String,
    val payload: Map<String, String>,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class UsageLog(
    val id: Long? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("peer_id") val peerId: String? = null,
    @SerialName("bytes_sent") val bytesSent: Long = 0,
    @SerialName("bytes_received") val bytesReceived: Long = 0,
    @SerialName("session_start") val sessionStart: String? = null,
    @SerialName("session_end") val sessionEnd: String? = null
)

@Serializable
data class Plan(
    val id: String,
    val name: String,
    @SerialName("max_peers") val maxPeers: Int,
    @SerialName("monthly_gb") val monthlyGb: Double,
    @SerialName("price_cents") val priceCents: Int = 0
)

data class HostInfo(
    val uid: String,
    val deviceId: String,
    val mode: String,
    val socksHost: String,
    val socksPort: Int
)

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: android.graphics.drawable.Drawable?,
    var isSelected: Boolean
)
