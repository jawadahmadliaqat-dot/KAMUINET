package com.kamuinet.client.supabase

import android.util.Base64
import com.kamuinet.client.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.serialization.json.Json

object KamuiSupabase {

    private val SUPABASE_URL = BuildConfig.SUPABASE_URL
    private val SUPABASE_ANON_KEY = BuildConfig.SUPABASE_KEY

    init {
        android.util.Log.d("KamuiSupabase", "--- SUPABASE INIT ---")
        android.util.Log.d("KamuiSupabase", "URL: '$SUPABASE_URL'")
        android.util.Log.d("KamuiSupabase", "URL Length: ${SUPABASE_URL.length}")
        
        if (SUPABASE_URL.contains(" ") || SUPABASE_URL == "https://supabase.co" || SUPABASE_URL.length < 30) {
            android.util.Log.e("KamuiSupabase", "CRITICAL ERROR: Invalid Supabase URL configuration detected!")
            android.util.Log.e("KamuiSupabase", "Please check local.properties and REBUILD the project.")
        }
        android.util.Log.d("KamuiSupabase", "---------------------")
    }

    val client: SupabaseClient by lazy {
        val url = if (BuildConfig.SUPABASE_URL.isNotBlank()) BuildConfig.SUPABASE_URL 
                  else com.kamuinet.client.util.CryptoUtils.reveal("DA0fDBoMCgwQCBA=") // Placeholder for obfuscated fallback
        
        val key = if (BuildConfig.SUPABASE_KEY.isNotBlank()) BuildConfig.SUPABASE_KEY
                  else com.kamuinet.client.util.CryptoUtils.reveal("DA0fDBoMCgwQCBA=")

        createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = key
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
            install(Functions)
            defaultSerializer = KotlinXSerializer(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    val auth get() = client.auth
    val db get() = client.postgrest
    val realtime get() = client.realtime
    val functions get() = client.functions
}
