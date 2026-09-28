package com.example.microgridsystem.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages user session state, JWT Bearer tokens, and prosumer identifiers.
 * Minimal non-intrusive session storage designed to be compatible with whatever
 * login screen/module provides the token.
 */
class SessionManager(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "app_prefs"
        private const val KEY_JWT_TOKEN = "jwt_token"
        private const val KEY_PROSUMER_NIC = "prosumer_nic"
        private const val KEY_USER_ROLE = "user_role"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_CUSTOM_SERVER_URL = "custom_server_url"

        @Volatile
        private var INSTANCE: SessionManager? = null

        fun getInstance(context: Context): SessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SessionManager(context).also { INSTANCE = it }
            }
        }
    }

    fun saveSession(token: String, prosumerNic: String, role: String = "Prosumer", fullName: String = "") {
        prefs.edit()
            .putString(KEY_JWT_TOKEN, token)
            .putString(KEY_PROSUMER_NIC, prosumerNic)
            .putString(KEY_USER_ROLE, role)
            .putString(KEY_USER_NAME, fullName)
            .apply()
    }

    fun getToken(): String? {
        return prefs.getString(KEY_JWT_TOKEN, null)
    }

    fun getBearerToken(): String {
        val token = getToken() ?: ""
        return if (token.isNotBlank() && !token.startsWith("Bearer ", ignoreCase = true)) {
            "Bearer $token"
        } else {
            token
        }
    }

    fun getProsumerNic(): String {
        return prefs.getString(KEY_PROSUMER_NIC, "") ?: ""
    }

    fun getUserRole(): String {
        return prefs.getString(KEY_USER_ROLE, "Prosumer") ?: "Prosumer"
    }

    fun getUserName(): String {
        return prefs.getString(KEY_USER_NAME, "") ?: ""
    }

    fun setCustomServerUrl(url: String) {
        prefs.edit().putString(KEY_CUSTOM_SERVER_URL, url).apply()
    }

    fun getCustomServerUrl(): String? {
        return prefs.getString(KEY_CUSTOM_SERVER_URL, null)
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }
}
