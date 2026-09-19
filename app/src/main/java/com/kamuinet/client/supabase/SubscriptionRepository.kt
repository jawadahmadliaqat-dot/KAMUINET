package com.kamuinet.client.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionRepository @Inject constructor(
    private val supabase: SupabaseClient
) {
    suspend fun redeemPromoCode(code: String): Result<Boolean> = runCatching {
        val raw: JsonObject = supabase.postgrest.rpc(
            function = "redeem_promo_code",
            parameters = buildJsonObject { 
                put("p_code", code.trim()) 
            }
        ).decodeAs()

        val ok = raw["ok"]?.jsonPrimitive?.booleanOrNull == true
        if (!ok) {
            val err = raw["error"]?.jsonPrimitive?.contentOrNull ?: "redeem_failed"
            error(err)
        }
        true
    }
}
