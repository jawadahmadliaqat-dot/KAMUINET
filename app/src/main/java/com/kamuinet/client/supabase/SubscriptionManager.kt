package com.kamuinet.client.supabase

import android.util.Log
import com.kamuinet.client.data.Subscription
import com.kamuinet.client.data.UserProfile
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import java.time.Instant

object SubscriptionManager {

    private const val TAG = "SubscriptionManager"

    /**
     * Returns true if user is in trial OR has active pro plan.
     */
    suspend fun isEntitled(): Boolean {
        val userId = AuthManager.currentUserId() ?: return false

        return try {
            // 1. Check permanent Pro status from profiles table
            val profile = KamuiSupabase.db
                .from("profiles")
                .select(Columns.ALL) {
                    filter {
                        eq("id", userId)
                    }
                }
                .decodeSingleOrNull<UserProfile>()

            if (profile?.isPro == true) return true

            // 2. Check active subscription or trial
            val sub = KamuiSupabase.db
                .from("subscriptions")
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }
                .decodeSingleOrNull<Subscription>()

            if (sub == null) {
                // Roadmap: Missing subscription row should NOT auto-grant trial from the client.
                // Trials should be handled by a DB trigger or Edge Function on signup.
                return false
            }

            when {
                // Active Pro
                sub.planId == "pro" && sub.status == "active" -> true

                // Still in trial
                sub.trialEnd != null && Instant.parse(sub.trialEnd).isAfter(Instant.now()) -> true

                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "isEntitled failed", e)
            false
        }
    }

    /**
     * How many days left in trial (for UI).
     */
    suspend fun trialDaysLeft(): Long {
        val userId = AuthManager.currentUserId() ?: return 0
        return try {
            val sub = KamuiSupabase.db
                .from("subscriptions")
                .select { filter { eq("user_id", userId) } }
                .decodeSingleOrNull<Subscription>()

            val end = sub?.trialEnd?.let { Instant.parse(it) } ?: return 0
            val left = end.epochSecond - Instant.now().epochSecond
            if (left <= 0) 0 else left / (24 * 60 * 60)
        } catch (e: Exception) {
            0
        }
    }
}
