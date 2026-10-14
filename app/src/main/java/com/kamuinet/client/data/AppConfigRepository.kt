package com.kamuinet.client.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("kamui_app_config", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALLOWED_APPS = "allowed_apps"
    }

    fun getAllowedApps(): Set<String> {
        return prefs.getStringSet(KEY_ALLOWED_APPS, emptySet()) ?: emptySet()
    }

    fun setAllowedApps(apps: Set<String>) {
        prefs.edit().putStringSet(KEY_ALLOWED_APPS, apps).apply()
    }

    fun addApp(packageName: String) {
        val apps = getAllowedApps().toMutableSet()
        apps.add(packageName)
        setAllowedApps(apps)
    }

    fun removeApp(packageName: String) {
        val apps = getAllowedApps().toMutableSet()
        apps.remove(packageName)
        setAllowedApps(apps)
    }

    fun isAppAllowed(packageName: String): Boolean {
        return getAllowedApps().contains(packageName)
    }
}
