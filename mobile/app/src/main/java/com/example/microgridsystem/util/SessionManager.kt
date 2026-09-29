package com.example.microgridsystem.util

import android.content.Context
import android.content.SharedPreferences

class SessionManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "app_prefs"
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_NIC = "user_nic"
        private const val KEY_FULL_NAME = "user_full_name"
        private const val KEY_ROLE = "user_role"
        private const val KEY_STATUS = "user_status"
        private const val KEY_BASE_URL = "api_base_url"

        // Your PC's current Wi-Fi IP (detected via ipconfig).
        // Since you are running on a physical phone (192.168.8.182),
        // the app must connect to this IP instead of 10.0.2.2 (which only works on emulator).
        const val DEFAULT_BASE_URL = "http://192.168.8.153:5098/"
    }

    fun saveLoginSession(
        token: String,
        nic: String,
        fullName: String,
        role: String,
        status: String
    ) {
        prefs.edit().apply {
            putString(KEY_TOKEN, token)
            putString(KEY_NIC, nic)
            putString("prosumer_nic", nic)
            putString(KEY_FULL_NAME, fullName)
            putString("user_name", fullName)
            putString(KEY_ROLE, role)
            putString(KEY_STATUS, status)
            apply()
        }
    }

    fun updateStatus(status: String) {
        prefs.edit().putString(KEY_STATUS, status).apply()
    }

    fun updateFullName(fullName: String) {
        prefs.edit().putString(KEY_FULL_NAME, fullName).apply()
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun getAuthHeader(): String {
        val token = getToken() ?: ""
        return if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
    }

    fun getNic(): String? = prefs.getString(KEY_NIC, null)

    fun getFullName(): String? = prefs.getString(KEY_FULL_NAME, null)

    fun getRole(): String? = prefs.getString(KEY_ROLE, null)

    fun getStatus(): String? = prefs.getString(KEY_STATUS, null)

    fun isLoggedIn(): Boolean = !getToken().isNullOrBlank()

    fun isActive(): Boolean = getStatus().equals("Active", ignoreCase = true)

    fun getBaseUrl(): String {
        var url = prefs.getString(KEY_BASE_URL, null)
        // Automatically migrate if unset or if pointing to 10.0.2.2 (emulator only)
        if (url.isNullOrBlank() || url.contains("10.0.2.2")) {
            url = DEFAULT_BASE_URL
            setBaseUrl(url)
        }
        if (!url.endsWith("/")) {
            url += "/"
        }
        return url
    }

    fun setBaseUrl(url: String) {
        var formatted = url.trim()
        if (!formatted.endsWith("/")) {
            formatted += "/"
        }
        prefs.edit().putString(KEY_BASE_URL, formatted).apply()
    }

    fun logout() {
        val currentBaseUrl = getBaseUrl()
        prefs.edit().clear().apply()
        // Preserve base URL setting across logouts
        setBaseUrl(currentBaseUrl)
    }
}
