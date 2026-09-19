package com.kamuinet.client.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Subscription(
    val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("plan_id") val planId: String? = null,
    val status: String? = null,
    @SerialName("trial_end") val trialEnd: String? = null,
    @SerialName("current_period_end") val currentPeriodEnd: String? = null
)
