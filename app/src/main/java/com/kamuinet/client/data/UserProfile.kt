package com.kamuinet.client.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    @SerialName("id") val id: String = "",
    @SerialName("is_pro") val isPro: Boolean = false,
    @SerialName("pro_until") val proUntil: String? = null,
    @SerialName("token_used") val tokenUsed: String? = null
)
