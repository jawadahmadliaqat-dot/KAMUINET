package com.kamuinet.client.supabase

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow

object AuthManager {

    private const val TAG = "AuthManager"

    val sessionStatus: Flow<SessionStatus> = KamuiSupabase.auth.sessionStatus

    fun currentUserId(): String? = KamuiSupabase.auth.currentUserOrNull()?.id
    fun currentEmail(): String? = KamuiSupabase.auth.currentUserOrNull()?.email
    fun isLoggedIn(): Boolean = KamuiSupabase.auth.currentUserOrNull() != null

    suspend fun signInWithEmail(emailValue: String, passValue: String): Result<Unit> {
        return try {
            KamuiSupabase.auth.signInWith(Email) {
                email = emailValue
                password = passValue
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Email sign-in failed", e)
            val message = when {
                e is java.net.UnknownHostException || 
                e.message?.contains("Unable to resolve host") == true ||
                e.message?.contains("No address associated with hostname") == true -> 
                    "Network error: Unable to resolve server address. Please check your internet connection or DNS settings."
                e.message?.contains("Email not confirmed") == true -> "Please check your inbox and confirm your email."
                e.message?.contains("Invalid login credentials") == true -> "Invalid email or password."
                else -> e.message ?: "Authentication failed"
            }
            Result.failure(Exception(message))
        }
    }

    suspend fun signUpWithEmail(emailValue: String, passValue: String): Result<Unit> {
        return try {
            KamuiSupabase.auth.signUpWith(Email) {
                email = emailValue
                password = passValue
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Email sign-up failed", e)
            val message = when {
                e is java.net.UnknownHostException || 
                e.message?.contains("Unable to resolve host") == true -> 
                    "Network error: Unable to reach server. Please check your internet connection."
                else -> e.message ?: "Registration failed"
            }
            Result.failure(Exception(message))
        }
    }

    suspend fun signInWithGoogle(context: Context, webClientId: String): Result<Unit> {
        return try {
            val credentialManager = CredentialManager.create(context)
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val result = credentialManager.getCredential(context, request)
            val credential = result.credential
            if (credential is GoogleIdTokenCredential) {
                val token = credential.idToken
                Log.d(TAG, "Google ID Token received: ${token.take(20)}...")
                KamuiSupabase.auth.signInWith(IDToken) {
                    idToken = token
                    provider = Google
                }
                Log.d(TAG, "Supabase Google sign-in successful")
                Result.success(Unit)
            } else {
                Result.failure(Exception("Unexpected credential type"))
            }
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Google credential error: ${e.message} (Type: ${e.type})")
            Result.failure(e)
        } catch (e: io.github.jan.supabase.exceptions.RestException) {
            Log.e(TAG, "Supabase Auth Rest Error: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Google sign-in failed", e)
            Result.failure(e)
        }
    }

    suspend fun signOut() {
        try {
            KamuiSupabase.auth.signOut()
        } catch (e: Exception) {
            Log.e(TAG, "Sign-out error", e)
        }
    }
}
