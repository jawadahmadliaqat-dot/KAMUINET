package com.kamuinet.client.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.kamuinet.client.R

import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.kamuinet.client.vpn.KamuiVpnService
import android.content.Context

class UsageFragment : Fragment() {

    private lateinit var sendRate: TextView
    private lateinit var recvRate: TextView
    private lateinit var sendBar: LinearProgressIndicator
    private lateinit var recvBar: LinearProgressIndicator
    private lateinit var limitField: TextInputEditText
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var updateRunnable: Runnable

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_usage, container, false)
        
        sendRate = view.findViewById(R.id.send_rate)
        recvRate = view.findViewById(R.id.recv_rate)
        sendBar = view.findViewById(R.id.send_bar)
        recvBar = view.findViewById(R.id.recv_bar)
        limitField = view.findViewById(R.id.usage_limit_field)

        setupLimitField()
        
        updateRunnable = object : Runnable {
            override fun run() {
                updateUsage()
                handler.postDelayed(this, 1000)
            }
        }
        
        return view
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(updateRunnable)
        super.onPause()
    }

    private var lastSent: Long = 0
    private var lastReceived: Long = 0

    private fun updateUsage() {
        val sent = KamuiVpnService.bytesSent.get()
        val received = KamuiVpnService.bytesReceived.get()
        
        val sentRateVal = sent - lastSent
        val recvRateVal = received - lastReceived
        
        lastSent = sent
        lastReceived = received
        
        sendRate.text = "${formatBytes(sent)} (${formatRate(sentRateVal)})"
        recvRate.text = "${formatBytes(received)} (${formatRate(recvRateVal)})"
        
        // Progress bars (max 1MB per second for visualization)
        sendBar.progress = (Math.min(sentRateVal / 1024, 1000L)).toInt() / 10
        recvBar.progress = (Math.min(recvRateVal / 1024, 1000L)).toInt() / 10
    }

    private fun formatRate(bytesPerSec: Long): String {
        val bitsPerSec = bytesPerSec * 8
        if (bitsPerSec < 1000) return "$bitsPerSec bps"
        val kbps = bitsPerSec / 1000.0
        if (kbps < 1000) return String.format("%.1f Kbps", kbps)
        val mbps = kbps / 1000.0
        return String.format("%.1f Mbps", mbps)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format("%.1f MB", mb)
    }

    private fun setupLimitField() {
        val prefs = requireContext().getSharedPreferences("kamui_settings", Context.MODE_PRIVATE)
        val currentLimit = prefs.getInt("data_limit_mb", 0)
        limitField.setText(currentLimit.toString())

        limitField.addTextChangedListener { text ->
            val limit = text.toString().toIntOrNull() ?: 0
            prefs.edit().putInt("data_limit_mb", limit).apply()
        }
    }
}
