package com.kamuinet.client.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.kamuinet.client.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SecurityFragment : Fragment() {

    private val viewModel: SecurityViewModel by viewModels()

    private lateinit var tvEncryption: TextView
    private lateinit var tvHealth: TextView
    private lateinit var tvDataSecured: TextView
    private lateinit var tvTrustScore: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_security, container, false)
        
        tvEncryption = view.findViewById(R.id.tv_encryption_type)
        tvHealth = view.findViewById(R.id.tv_tunnel_health)
        tvDataSecured = view.findViewById(R.id.tv_data_secured)
        tvTrustScore = view.findViewById(R.id.tv_trust_score)

        observeViewModel()
        return view
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                tvEncryption.text = state.encryptionStatus
                tvHealth.text = state.tunnelHealth
                tvDataSecured.text = state.totalDataSecured
                tvTrustScore.text = "${state.hostTrustScore}%"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.updateStats()
    }
}
