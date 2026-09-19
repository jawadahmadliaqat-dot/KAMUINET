package com.kamuinet.client.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.kamuinet.client.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_splash, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val content = view.findViewById<View>(R.id.splash_content)
        val logo = view.findViewById<ImageView>(R.id.splash_logo)
        val glow = view.findViewById<View>(R.id.splash_glow)

        // 1. Initial Fade-in and Rise
        content.translationY = 50f
        content.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(1000)
            .setInterpolator(LinearInterpolator())
            .start()

        // 2. Kamui Portal Rotation (Infinite Hypnotic Loop)
        glow.animate()
            .rotation(360f)
            .setDuration(8000)
            .setInterpolator(LinearInterpolator())
            .withEndAction { 
                glow.rotation = 0f
                startInfiniteRotation(glow)
            }
            .start()

        // 3. Breathing Energy Pulse
        logo.animate()
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(2000)
            .setInterpolator(LinearInterpolator())
            .withEndAction { pulseLogo(logo) }
            .start()

        // 4. Transition to Home after Delay
        lifecycleScope.launch {
            delay(3500)
            if (isAdded && !isStateSaved) {
                // Final Kamui Portal Zoom Effect
                logo.animate()
                    .scaleX(5f)
                    .scaleY(5f)
                    .alpha(0f)
                    .setDuration(600)
                    .setInterpolator(AccelerateInterpolator())
                    .start()
                
                content.animate()
                    .alpha(0f)
                    .setDuration(400)
                    .start()

                delay(500)
                if (isAdded) {
                    findNavController().navigate(R.id.action_splashFragment_to_homeFragment)
                }
            }
        }
    }

    private fun startInfiniteRotation(view: View) {
        if (!isAdded) return
        view.animate()
            .rotationBy(360f)
            .setDuration(8000)
            .setInterpolator(LinearInterpolator())
            .withEndAction { startInfiniteRotation(view) }
            .start()
    }

    private fun pulseLogo(view: View) {
        if (!isAdded) return
        val targetScale = if (view.scaleX > 1.0f) 0.98f else 1.05f
        view.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(2000)
            .setInterpolator(LinearInterpolator())
            .withEndAction { pulseLogo(view) }
            .start()
    }
}
