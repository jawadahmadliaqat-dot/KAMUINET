package com.kamuinet.client.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.kamuinet.client.supabase.AuthManager
import com.kamuinet.client.MainActivity
import com.kamuinet.client.R
import com.kamuinet.client.signaling.HostDiscovery
import com.kamuinet.client.vpn.KamuiVpnService
import com.kamuinet.client.webrtc.WebRtcManager
import com.kamuinet.client.router.HostRelayManager
import com.kamuinet.client.router.KamuiHostService
import com.kamuinet.client.router.SharingManager
import com.kamuinet.client.supabase.SubscriptionManager
import com.kamuinet.client.webrtc.TurnCredentialsManager
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.webrtc.DataChannel

import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private val viewModel: HomeViewModel by viewModels()

    @Inject
    lateinit var sharingManager: SharingManager

    private lateinit var sharingTitle: TextView
    private lateinit var kamuiIdLabel: TextView
    private lateinit var sharingDetail: TextView
    private lateinit var peerLabel: TextView
    private lateinit var endpointInfo: TextView
    private lateinit var linkTypeLabel: TextView
    private lateinit var logText: TextView
    private lateinit var btnShare: Button
    private lateinit var btnReceive: Button
    private lateinit var cardUpgrade: View
    private lateinit var activityLabel: TextView
    private lateinit var btnRetry: Button
    private lateinit var loadingIndicator: View
    
    private lateinit var lightRed: View
    private lateinit var lightYellow: View
    private lateinit var lightGreen: View

    private var pulseAnimator: ObjectAnimator? = null
    private var lastActiveLight: View? = null
    
    private var paywallDialog: AlertDialog? = null

    private lateinit var authPanel: View
    private lateinit var actionPanel: View
    private lateinit var btnGoogleLogin: Button
    private lateinit var btnSignIn: Button
    private lateinit var btnSwitchAuth: TextView
    private lateinit var emailField: TextInputEditText
    private lateinit var passField: TextInputEditText

    private var isSignUpMode = false

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            startVpnService()
        }
    }

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            val qrText = result.data?.getStringExtra("SCAN_RESULT")
            if (qrText != null) {
                showSecurityShield { connectToQrPayload(qrText) }
            }
        }
    }

    private var pendingHost: String? = null
    private var pendingPort: Int = 1080
    private var targetUid: String? = null
    private var targetDeviceId: String? = null
    private var listeningStarted = false

    private var pendingRemoteSdp: String? = null // Removed usage
    private var pendingRemoteDeviceId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)
        
        sharingTitle = view.findViewById(R.id.sharing_title)
        kamuiIdLabel = view.findViewById(R.id.kamui_id_label)
        sharingDetail = view.findViewById(R.id.sharing_detail)
        peerLabel = view.findViewById(R.id.peer_label)
        endpointInfo = view.findViewById(R.id.endpoint_info)
        linkTypeLabel = view.findViewById(R.id.link_type_label)
        logText = view.findViewById(R.id.log_text)
        btnShare = view.findViewById(R.id.btn_share)
        btnReceive = view.findViewById(R.id.btn_receive)
        cardUpgrade = view.findViewById(R.id.card_upgrade)
        activityLabel = view.findViewById(R.id.activity_label)
        btnRetry = view.findViewById(R.id.btn_retry)
        loadingIndicator = view.findViewById(R.id.loading_indicator)

        lightRed = view.findViewById(R.id.light_red)
        lightYellow = view.findViewById(R.id.light_yellow)
        lightGreen = view.findViewById(R.id.light_green)
        
        authPanel = view.findViewById(R.id.auth_panel)
        actionPanel = view.findViewById(R.id.action_panel)
        btnGoogleLogin = view.findViewById(R.id.btn_google_login)
        btnSignIn = view.findViewById(R.id.btn_sign_in)
        btnSwitchAuth = view.findViewById(R.id.btn_switch_auth)
        emailField = view.findViewById(R.id.email_field)
        passField = view.findViewById(R.id.pass_field)

        applyTranslations()
        observeViewModel()
        setupSignalingListener()
        checkBatteryOptimization()

        kamuiIdLabel.text = "Your ID: ${HostDiscovery.myDeviceId.take(12)}"

        btnShare.setOnClickListener {
            lifecycleScope.launch {
                val entitled = SubscriptionManager.isEntitled()
                if (!entitled) {
                    showPaywall()
                    return@launch
                }
                if (!viewModel.uiState.value.isSharing) {
                    log("Starting sharing...")
                    startLocalSharing()
                } else {
                    stopLocalSharing()
                }
            }
        }

        btnReceive.setOnClickListener {
            lifecycleScope.launch {
                val entitled = SubscriptionManager.isEntitled()
                if (!entitled) {
                    showPaywall()
                    return@launch
                }
                showReceiveOptions()
            }
        }

        btnRetry.setOnClickListener {
            btnRetry.visibility = View.GONE
            discoverAndConnect(targetUid)
        }

        cardUpgrade.setOnClickListener {
            showPaywall()
        }
        
        btnGoogleLogin.setOnClickListener {
            (activity as? MainActivity)?.signInWithGoogle()
        }

        btnSignIn.setOnClickListener {
            if (isSignUpMode) handleEmailSignUp()
            else handleEmailSignIn()
        }

        btnSwitchAuth.setOnClickListener {
            isSignUpMode = !isSignUpMode
            if (isSignUpMode) {
                btnSignIn.text = "Sign up"
                btnSwitchAuth.text = "Already have an account? Sign in"
            } else {
                btnSignIn.text = "Sign in"
                btnSwitchAuth.text = "Need an account? Sign up here"
            }
        }

        setupMenu()

        return view
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun handleEmailSignIn() {
        val email = emailField.text.toString().trim()
        val pass = passField.text.toString().trim()
        if (email.isEmpty() || pass.length < 6) {
            Toast.makeText(requireContext(), "Enter valid email & password (min 6 chars)", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isNetworkAvailable()) {
            Toast.makeText(requireContext(), "No internet connection detected.", Toast.LENGTH_LONG).show()
            return
        }

        log("Authenticating as $email...")
        lifecycleScope.launch {
            val result = AuthManager.signInWithEmail(email, pass)
            if (result.isSuccess) {
                log("Signed in successfully!")
            } else {
                log("Auth failed: ${result.exceptionOrNull()?.message}")
                Toast.makeText(requireContext(), "Auth failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleEmailSignUp() {
        val email = emailField.text.toString().trim()
        val pass = passField.text.toString().trim()
        if (email.isEmpty() || pass.length < 6) {
            Toast.makeText(requireContext(), "Enter valid email & password (min 6 chars)", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isNetworkAvailable()) {
            Toast.makeText(requireContext(), "No internet connection detected.", Toast.LENGTH_LONG).show()
            return
        }

        log("Creating account for $email...")
        lifecycleScope.launch {
            val result = AuthManager.signUpWithEmail(email, pass)
            if (result.isSuccess) {
                log("Account created and signed in!")
            } else {
                log("Sign up failed: ${result.exceptionOrNull()?.message}")
                Toast.makeText(requireContext(), "Sign up failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showPaywall() {
        paywallDialog?.dismiss()
        paywallDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Upgrade to Pro")
            .setMessage(
                "Your 3-day free trial has expired.\n\n" +
                "Unlock KamuiNet Pro for unlimited speed, no data limits, and priority support."
            )
            .setPositiveButton("Upgrade – $2.99") { _, _ ->
                openLemonCheckout()
            }
            .setNeutralButton("Redeem Code") { _, _ ->
                showPromoCodeDialog()
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun showPromoCodeDialog() {
        val input = TextInputEditText(requireContext()).apply {
            hint = "Enter VIP Promo Code"
        }
        val container = android.widget.FrameLayout(requireContext())
        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(48, 24, 48, 24)
        input.layoutParams = params
        container.addView(input)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Redeem VIP Pass")
            .setView(container)
            .setPositiveButton("Redeem") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isNotEmpty()) {
                    viewModel.redeemCode(code) { success, msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openLemonCheckout() {
        val userId = AuthManager.currentUserId()
        if (userId == null) {
            Toast.makeText(requireContext(), "Please sign in first", Toast.LENGTH_SHORT).show()
            return
        }

        val checkoutUrl = "https://jawadpyt.lemonsqueezy.com/checkout/buy/b7047dd0-e44e-482a-8ca0-933b53b64fec" +
                "?checkout[custom][user_id]=$userId"

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(checkoutUrl))
        startActivity(intent)
    }

    private fun showReceiveOptions() {
        val options = arrayOf("Pair via Kamui ID", "Scan QR Code", "Scan from Gallery", "Quick Connect (Same Account)")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Connect to Peer")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showPairingIdDialog()
                    1 -> qrScannerLauncher.launch(Intent(requireContext(), QrScannerActivity::class.java))
                    2 -> openGalleryForQr()
                    3 -> showSecurityShield { discoverAndConnect(null) }
                }
            }
            .show()
    }

    private fun openGalleryForQr() {
        val intent = Intent(requireContext(), QrScannerActivity::class.java).apply {
            putExtra("OPEN_GALLERY", true)
        }
        qrScannerLauncher.launch(intent)
    }

    private fun showPairingIdDialog() {
        val input = TextInputEditText(requireContext())
        input.hint = "Paste Peer ID here"
        val container = android.widget.FrameLayout(requireContext())
        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(48, 24, 48, 24)
        input.layoutParams = params
        container.addView(input)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Pair via Cloud")
            .setView(container)
            .setPositiveButton("Connect") { _, _ ->
                val id = input.text.toString().trim()
                if (id.isNotEmpty()) showSecurityShield { discoverAndConnect(id) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSecurityShield(onAccept: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.security_shield_title)
            .setMessage(R.string.security_shield_message)
            .setPositiveButton("Accept & Connect") { _, _ -> onAccept() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupMenu() {
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menu.add(0, 1, 0, TranslationManager.tr("show_qr"))
                    .setIcon(android.R.drawable.ic_menu_share)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(0, 2, 0, "Clear Log")
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    1 -> {
                        showMyQrDialog()
                        true
                    }
                    2 -> {
                        viewModel.clearLogs()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun applyTranslations() {
        btnShare.text = TranslationManager.tr("share")
        btnReceive.text = TranslationManager.tr("receive")
        peerLabel.text = TranslationManager.tr("peers", "n" to 0, "limit" to 2)
        activityLabel.text = TranslationManager.tr("activity")
        
        sharingTitle.text = TranslationManager.tr("ready")
        sharingDetail.text = TranslationManager.tr("host_first")
    }

    private fun setupSignalingListener() {
        viewLifecycleOwner.lifecycleScope.launch {
            HostDiscovery.listenForSignaling(
                id = "HomeUI",
                explicitUid = AuthManager.currentUserId(),
                onSdp = { sdp, type, fromUid, fromDeviceId ->
                    // Client-side: Handle answer from Host
                    if (type == "answer" && !viewModel.uiState.value.isSharing) {
                        log("📩 Received P2P Answer from $fromUid")
                        // The VPN Service handles this, but we log it here
                    }
                },
                onIce = { sdp, mid, index, fromUid, fromDeviceId ->
                    // Client-side ICE handled by KamuiVpnService
                }
            )
        }
    }

    private fun showMyQrDialog() {
        val myUid = AuthManager.currentUserId() ?: "unknown"
        val payload = "KAMUINET:P2P:$myUid:${HostDiscovery.myDeviceId}"
        
        log("Generating QR Code for pairing...")
        
        try {
            val writer = MultiFormatWriter()
            val matrix = writer.encode(payload, BarcodeFormat.QR_CODE, 512, 512)
            val encoder = BarcodeEncoder()
            val bitmap = encoder.createBitmap(matrix)
            
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_qr_share, null)
            val imageView = dialogView.findViewById<ImageView>(R.id.qr_image)
            val btnSave = dialogView.findViewById<Button>(R.id.btn_save_qr)
            val btnShareQr = dialogView.findViewById<Button>(R.id.btn_share_qr)
            
            imageView.setImageBitmap(bitmap)
            
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Your Connection QR")
                .setMessage("Let your friend scan this to connect.")
                .setView(dialogView)
                .setPositiveButton("Done", null)
                .show()

            btnSave.setOnClickListener {
                saveQrToGallery(bitmap)
            }

            btnShareQr.setOnClickListener {
                shareQrImage(bitmap)
            }
        } catch (e: Exception) {
            log("QR Generation failed: ${e.message}")
            Log.e("HomeFragment", "QR Error", e)
        }
    }

    private fun saveQrToGallery(bitmap: android.graphics.Bitmap) {
        val filename = "KamuiNet_QR_${System.currentTimeMillis()}.png"
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
            }
        }

        val uri = requireContext().contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            requireContext().contentResolver.openOutputStream(uri)?.use { 
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            Toast.makeText(requireContext(), "QR saved to gallery", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Failed to save QR", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareQrImage(bitmap: android.graphics.Bitmap) {
        try {
            val cachePath = java.io.File(requireContext().cacheDir, "images")
            cachePath.mkdirs()
            val stream = java.io.FileOutputStream(cachePath.toString() + "/shared_qr.png")
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            stream.close()

            val imageFile = java.io.File(cachePath, "shared_qr.png")
            val contentUri = androidx.core.content.FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", imageFile)

            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setDataAndType(contentUri, requireContext().contentResolver.getType(contentUri))
                putExtra(Intent.EXTRA_STREAM, contentUri)
                type = "image/png"
            }
            startActivity(Intent.createChooser(shareIntent, "Share Connection QR"))
        } catch (e: Exception) {
            log("Sharing failed: ${e.message}")
        }
    }

    private fun connectToQrPayload(payload: String) {
        try {
            val parts = payload.removePrefix("KAMUINET:").split(":")
            if (parts.size >= 2 && parts[0] == "P2P") {
                val uid = parts[1]
                val deviceId = if (parts.size >= 3) parts[2] else null
                
                log("Connecting to P2P host: $uid (device: $deviceId)")
                targetUid = uid
                
                // Direct discovery with specific device ID for speed
                discoverAndConnect(deviceId)
            } else if (parts.size >= 4) {
                // Legacy format: local:ip:port:uid:deviceId
                pendingHost = parts[1]
                pendingPort = parts[2].toInt()
                targetUid = parts[3]
                val remoteDeviceId = if (parts.size >= 5) parts[4] else ""
                
                log("Connecting to Legacy QR host: $targetUid")
                try {
                    val intent = VpnService.prepare(requireContext())
                    if (intent != null) vpnLauncher.launch(intent)
                    else startVpnService()
                } catch (e: SecurityException) {
                    handleVpnSecurityException(e)
                }
            }
        } catch (e: Exception) {
            log("Invalid QR Payload")
        }
    }


    private fun checkBatteryOptimization() {
        val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(requireContext().packageName)) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Battery Optimization")
                .setMessage("KamuiNet needs to run in the background to maintain the P2P tunnel. Please set Battery usage to 'Unrestricted' in the next screen.")
                .setPositiveButton("Open Settings") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${requireContext().packageName}")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        // Fallback to general battery settings
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    }
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                sharingTitle.text = state.sharingTitle
                sharingDetail.text = state.sharingDetail
                logText.text = state.logs
                peerLabel.text = state.peerInfo
                endpointInfo.text = state.endpointInfo
                
                authPanel.visibility = if (state.isLoggedIn) View.GONE else View.VISIBLE
                actionPanel.visibility = if (state.isLoggedIn) View.VISIBLE else View.GONE

                // Update Share Button UI based on state
                if (state.isSharing) {
                    btnShare.text = "🛑 Stop Sharing"
                    btnShare.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#f07171"))
                } else {
                    btnShare.text = TranslationManager.tr("share")
                    btnShare.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.kamui_accent))
                }

                // RETRY LISTENER ONCE LOGGED IN
                if (state.isLoggedIn && !listeningStarted) {
                    listeningStarted = true
                    setupSignalingListener()
                }

                if (state.linkState == "active") {
                    loadingIndicator.visibility = View.GONE
                    btnRetry.visibility = View.GONE
                    linkTypeLabel.visibility = View.VISIBLE
                    linkTypeLabel.text = state.linkType
                    linkTypeLabel.setTextColor(if (state.linkType.contains("Relay")) Color.parseColor("#facc15") else Color.parseColor("#4ade80"))
                } else {
                    linkTypeLabel.visibility = View.GONE
                }

                // Auto-scroll logs
                view?.findViewById<android.widget.ScrollView>(R.id.log_scroll)?.let { scroll ->
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }

                updateTrafficLights(state.linkState)
            }
        }
    }

    private var lastBytes: Long = 0
    private fun updateTrafficLights(linkState: String) {
        if (linkState != "active") {
            lightRed.alpha = 0.2f
            lightYellow.alpha = 0.2f
            lightGreen.alpha = 0.2f
            return
        }

        val currentBytes = KamuiVpnService.bytesSent.get() + KamuiVpnService.bytesReceived.get()
        val delta = currentBytes - lastBytes
        lastBytes = currentBytes

        var currentLight: View? = null

        when {
            delta > 500 * 1024 -> currentLight = lightGreen
            delta > 50 * 1024 -> currentLight = lightYellow
            else -> currentLight = lightRed
        }

        // Reset all alphas first
        lightRed.alpha = 0.2f
        lightYellow.alpha = 0.2f
        lightGreen.alpha = 0.2f

        // Highlight the active light
        currentLight?.alpha = 1.0f

        // Handle Pulse Animation
        if (currentLight != lastActiveLight) {
            stopPulse()
            if (currentLight != null) {
                startPulse(currentLight)
            }
            lastActiveLight = currentLight
        }
    }

    private fun startPulse(view: View) {
        pulseAnimator = ObjectAnimator.ofFloat(view, "alpha", 0.4f, 1.0f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    override fun onDestroyView() {
        stopPulse()
        paywallDialog?.dismiss()
        paywallDialog = null
        HostDiscovery.stopListening("HomeUI")
        super.onDestroyView()
    }

    private fun log(msg: String) {
        viewModel.addLog(msg)
    }

    private fun startLocalSharing() {
        val localIp = getLocalIpAddress()
        if (localIp == null) {
            Toast.makeText(requireContext(), "Error: No Local IP found.", Toast.LENGTH_LONG).show()
            return
        }

        val prefs = requireContext().getSharedPreferences("kamui_settings", Context.MODE_PRIVATE)
        val mbLimit = prefs.getInt("data_limit_mb", 0)
        
        val intent = Intent(requireContext(), KamuiHostService::class.java).apply {
            putExtra(KamuiHostService.EXTRA_IP, localIp)
            putExtra(KamuiHostService.EXTRA_PORT, 1080)
            putExtra(KamuiHostService.EXTRA_LIMIT, mbLimit)
            putExtra(KamuiHostService.EXTRA_USER_ID, AuthManager.currentUserId())
        }
        
        log("🚀 Launching KamuiHostService...")
        Log.d("HomeFragment", "Executing startForegroundService for KamuiHostService")
        
        ContextCompat.startForegroundService(requireContext(), intent)
        
        // AUTO-SHOW QR CODE for easier pairing
        showMyQrDialog()
    }

    private fun stopLocalSharing() {
        viewModel.updateLinkState("idle", TranslationManager.tr("ready"), "Sharing Stopped", "Signed in")
        
        val intent = Intent(requireContext(), KamuiHostService::class.java).apply {
            action = KamuiHostService.ACTION_STOP
        }
        requireContext().stopService(intent)
        log("Host Service stopped")
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address.hostAddress.indexOf(':') < 0) {
                        val ip = address.hostAddress
                        if (ip.startsWith("192.168.") || ip.startsWith("10.")) return ip
                    }
                }
            }
        } catch (ex: Exception) {}
        return null
    }

    private fun discoverAndConnect(target: String? = null) {
        val searchMsg = if (target == null) "Searching for your hosts..." else "Connecting to peer $target..."
        log(searchMsg)
        viewModel.updateLinkState("searching", TranslationManager.tr("searching"), TranslationManager.tr("waiting_client"), searchMsg)
        loadingIndicator.visibility = View.VISIBLE
        btnRetry.visibility = View.GONE
        
        lifecycleScope.launch {
            try {
                // Same Account or specific peer discovery
                val hostInfo = HostDiscovery.discover(target)
                loadingIndicator.visibility = View.GONE
                
                if (hostInfo != null && !hostInfo.socksHost.isNullOrEmpty()) {
                    pendingHost = hostInfo.socksHost
                    pendingPort = hostInfo.socksPort
                    targetUid = hostInfo.uid
                    targetDeviceId = hostInfo.deviceId
                    
                    log("✅ Found online host: $targetUid ($targetDeviceId). Starting tunnel...")
                    
                    try {
                        val intent = VpnService.prepare(requireContext())
                        if (intent != null) vpnLauncher.launch(intent)
                        else startVpnService()
                    } catch (e: SecurityException) {
                        handleVpnSecurityException(e)
                    }
                } else {
                    val errorMsg = if (target == null) "No active host found on your account." else "Peer $target is offline."
                    viewModel.updateLinkState("idle", "Host not found", errorMsg, "Idle")
                    log("Discovery: $errorMsg")
                    btnRetry.visibility = View.VISIBLE
                    
                    if (target == null) {
                        Toast.makeText(requireContext(), "Ensure 'Share' is active on your other device.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                loadingIndicator.visibility = View.GONE
                btnRetry.visibility = View.VISIBLE
                log("❌ Connection error: ${e.message}")
            }
        }
    }

    private fun startVpnService(limitMb: Int = 0) {
        if (pendingHost.isNullOrEmpty()) return
        val intent = Intent(requireContext(), KamuiVpnService::class.java).apply {
            putExtra(KamuiVpnService.EXTRA_HOST, pendingHost)
            putExtra(KamuiVpnService.EXTRA_PORT, pendingPort)
            putExtra(KamuiVpnService.EXTRA_TARGET_UID, targetUid)
            putExtra(KamuiVpnService.EXTRA_TARGET_DEVICE_ID, targetDeviceId)
            putExtra(KamuiVpnService.EXTRA_LIMIT_MB, limitMb)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
        viewModel.updateLinkState("active", TranslationManager.tr("access_active"), TranslationManager.tr("link_ok"), TranslationManager.tr("secure_link"))
        log("P2P Handshake started with $targetUid")
    }

    private fun handleVpnSecurityException(e: SecurityException) {
        Log.e("HomeFragment", "VPN SecurityException: ${e.message}")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("System Connection Error")
            .setMessage(
                "Android is reporting a security conflict with the VPN configuration. " +
                "This usually happens if the app was recently updated.\n\n" +
                "Please try restarting the app, or clear the app's cache/data if the issue persists."
            )
            .setPositiveButton("I Understand", null)
            .show()
    }
}
