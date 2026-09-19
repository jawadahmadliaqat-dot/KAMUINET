package com.kamuinet.client.webrtc

import android.util.Log
import com.kamuinet.client.supabase.KamuiSupabase
import io.ktor.client.call.body
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.PeerConnection

object TurnCredentialsManager {

    private const val TAG = "TurnManager"

    @Serializable
    data class IceServerJson(
        val urls: JsonElement, // Can be String or Array
        val username: String? = null,
        val credential: String? = null
    )

    suspend fun fetchIceServers(): List<PeerConnection.IceServer> {
        return try {
            val response = KamuiSupabase.functions.invoke("turn-credentials")
            val iceList = response.body<List<IceServerJson>>()
            
            val servers = iceList.mapNotNull { json ->
                try {
                    val urlStrings = when (val urls = json.urls) {
                        is kotlinx.serialization.json.JsonArray -> urls.map { it.jsonPrimitive.content }
                        is kotlinx.serialization.json.JsonPrimitive -> listOf(urls.content)
                        else -> null
                    } ?: return@mapNotNull null
                    
                    val builder = PeerConnection.IceServer.builder(urlStrings)
                    if (json.username != null) builder.setUsername(json.username)
                    if (json.credential != null) builder.setPassword(json.credential)
                    builder.createIceServer()
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping invalid ICE server entry: ${e.message}")
                    null
                }
            }
            
            if (servers.isEmpty()) {
                Log.w(TAG, "Edge function returned empty ICE list. Falling back.")
                WebRtcConfig.iceServers
            } else {
                Log.d(TAG, "Fetched ${servers.size} ICE servers from Edge Function")
                servers
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch TURN credentials: ${e.message}. Using hardcoded fallback.")
            // CRITICAL: Always return fallback to ensure app can still attempt connection
            WebRtcConfig.iceServers 
        }
    }
}
