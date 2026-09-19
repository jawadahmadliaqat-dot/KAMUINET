package com.kamuinet.client

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.kamuinet.client.supabase.AuthManager
import com.kamuinet.client.ui.HomeFragment
import com.kamuinet.client.ui.SplashFragment
import com.kamuinet.client.ui.SecurityFragment
import com.kamuinet.client.ui.SettingsFragment
import com.kamuinet.client.ui.UsageFragment
import com.kamuinet.client.util.SecurityUtils
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission required for VPN status", Toast.LENGTH_LONG).show()
        }
    }

    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("kamui_settings", Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(themeMode)

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        appBarConfiguration = AppBarConfiguration(setOf(R.id.splashFragment, R.id.homeFragment))
        
        setupActionBarWithNavController(navController, appBarConfiguration)

        // Ensure the AppBarLayout (Top Bar) is pushed down below the status bar
        val appBarLayout = findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.app_bar_layout)
        ViewCompat.setOnApplyWindowInsetsListener(appBarLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        // Ensure the fragment content doesn't get hidden behind bottom navigation
        val container = findViewById<android.view.View>(R.id.nav_host_fragment)
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            insets
        }

        performSecurityCheck()
        checkNotificationPermission()
        observeAuthStatus()
    }

    private fun observeAuthStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AuthManager.sessionStatus.collect { status ->
                    when (status) {
                        is SessionStatus.NotAuthenticated -> {
                            Log.d(TAG, "User explicitly not logged in. Force stopping VPN service.")
                            val intent = android.content.Intent(this@MainActivity, com.kamuinet.client.vpn.KamuiVpnService::class.java)
                            stopService(intent)
                            
                            val hostIntent = android.content.Intent(this@MainActivity, com.kamuinet.client.router.KamuiHostService::class.java)
                            stopService(hostIntent)
                        }
                        is SessionStatus.Authenticated -> {
                            Log.d(TAG, "User is authenticated.")
                        }
                        is SessionStatus.Initializing -> {
                            Log.d(TAG, "Auth session initializing (loading from storage)... waiting.")
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_usage -> {
                navController.navigate(R.id.usageFragment)
                true
            }
            R.id.menu_security -> {
                navController.navigate(R.id.securityFragment)
                true
            }
            R.id.menu_settings -> {
                navController.navigate(R.id.settingsFragment)
                true
            }
            R.id.menu_help -> {
                navController.navigate(R.id.helpFragment)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun performSecurityCheck() {
        if (SecurityUtils.isDeviceRooted()) {
            Log.w(TAG, "Device is rooted. Security risk detected.")
        }
        
        if (SecurityUtils.isEmulator()) {
            Log.d(TAG, "Running on emulator.")
        }
    }

    fun signInWithGoogle() {
        val webClientId = getString(R.string.default_web_client_id)
        lifecycleScope.launch {
            val result = AuthManager.signInWithGoogle(this@MainActivity, webClientId)
            if (result.isSuccess) {
                Toast.makeText(this@MainActivity, "Signed in successfully!", Toast.LENGTH_SHORT).show()
                recreate()
            } else {
                val exception = result.exceptionOrNull()
                Log.e(TAG, "Google sign-in failed", exception)
                val userMessage = when (exception) {
                    is androidx.credentials.exceptions.GetCredentialException -> "Google Sign-in was cancelled or failed."
                    is io.github.jan.supabase.exceptions.RestException -> "Supabase rejected the Google token. Check API settings."
                    else -> exception?.message ?: "Unknown error"
                }
                Toast.makeText(this@MainActivity, "Sign-in failed: $userMessage", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
