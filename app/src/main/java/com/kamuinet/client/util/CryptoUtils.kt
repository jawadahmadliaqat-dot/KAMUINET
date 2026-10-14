package com.kamuinet.client.util

import android.util.Base64

/**
 * Joker Stealth: Obfuscates sensitive strings to prevent simple string-search detection
 * by ISPs or reverse-engineers.
 */
object CryptoUtils {

    private const val MASK_KEY = 0x55.toByte()

    /**
     * Obfuscates a string using XOR + Base64
     */
    fun hide(input: String): String {
        val bytes = input.toByteArray()
        val masked = ByteArray(bytes.size)
        for (i in bytes.indices) {
            masked[i] = (bytes[i].toInt() xor MASK_KEY.toInt()).toByte()
        }
        return Base64.encodeToString(masked, Base64.NO_WRAP)
    }

    /**
     * Reveals the original string
     */
    fun reveal(input: String): String {
        return try {
            val bytes = Base64.decode(input, Base64.DEFAULT)
            val unmasked = ByteArray(bytes.size)
            for (i in bytes.indices) {
                unmasked[i] = (bytes[i].toInt() xor MASK_KEY.toInt()).toByte()
            }
            String(unmasked)
        } catch (e: Exception) {
            ""
        }
    }
}
