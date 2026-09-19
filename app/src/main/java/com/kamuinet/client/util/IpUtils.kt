package com.kamuinet.client.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object IpUtils {

    private const val TAG = "IpUtils"

    fun getNetworkType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = cm.activeNetwork ?: return "None"
        val actNw = cm.getNetworkCapabilities(nw) ?: return "None"
        return when {
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
    }

    /**
     * Public IP nikalta hai (simple + reliable)
     */
    suspend fun getPublicIp(): String = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.ipify.org")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"

            val ip = conn.inputStream.bufferedReader().readText().trim()
            conn.disconnect()

            if (ip.isNotBlank()) ip else "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get public IP", e)
            "unknown"
        }
    }
}
