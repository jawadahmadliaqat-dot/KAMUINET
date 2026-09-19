package com.kamuinet.client.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.kamuinet.client.MainActivity
import com.kamuinet.client.R
import com.kamuinet.client.signaling.HostDiscovery
import com.kamuinet.client.supabase.AuthManager
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)

        val accountLabel = view.findViewById<TextView>(R.id.account_label)
        val btnSignOut = view.findViewById<Button>(R.id.btn_sign_out)
        val themeSpinner = view.findViewById<Spinner>(R.id.theme_spinner)
        val stunField = view.findViewById<EditText>(R.id.stun_field)
        val portField = view.findViewById<EditText>(R.id.port_field)
        val upnpCheck = view.findViewById<CheckBox>(R.id.upnp_check)
        val directOnlyCheck = view.findViewById<CheckBox>(R.id.direct_only_check)

        val prefs = requireContext().getSharedPreferences("kamui_settings", Context.MODE_PRIVATE)

        val themeOptions = listOf("System Default", "Light Mode", "Dark Mode")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, themeOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        themeSpinner.adapter = adapter

        val currentTheme = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val selection = when (currentTheme) {
            AppCompatDelegate.MODE_NIGHT_NO -> 1
            AppCompatDelegate.MODE_NIGHT_YES -> 2
            else -> 0
        }
        themeSpinner.setSelection(selection)

        themeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newMode = when (position) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                if (newMode != currentTheme) {
                    prefs.edit().putInt("theme_mode", newMode).apply()
                    AppCompatDelegate.setDefaultNightMode(newMode)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        stunField.setText(prefs.getString("stun", "stun.l.google.com:19302"))
        portField.setText(prefs.getString("port", "54320"))
        upnpCheck.isChecked = prefs.getBoolean("upnp", true)
        directOnlyCheck.isChecked = prefs.getBoolean("direct_only", false)

        stunField.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) prefs.edit().putString("stun", stunField.text.toString()).apply()
        }
        portField.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) prefs.edit().putString("port", portField.text.toString()).apply()
        }
        upnpCheck.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("upnp", isChecked).apply()
        }
        directOnlyCheck.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("direct_only", isChecked).apply()
        }

        if (AuthManager.isLoggedIn()) {
            accountLabel.text = AuthManager.currentEmail() ?: AuthManager.currentUserId()
            btnSignOut.text = TranslationManager.tr("sign_out")
            btnSignOut.setOnClickListener {
                lifecycleScope.launch {
                    HostDiscovery.stopAll()
                    AuthManager.signOut()
                    activity?.recreate()
                }
            }
        } else {
            accountLabel.text = "Not signed in"
            btnSignOut.text = "Sign in with Google"
            btnSignOut.setOnClickListener {
                (activity as? MainActivity)?.signInWithGoogle()
            }
        }

        return view
    }
}
