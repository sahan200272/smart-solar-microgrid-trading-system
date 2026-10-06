package com.example.microgridsystem.util

import android.content.Context
import android.content.SharedPreferences
import com.example.microgridsystem.BuildConfig

class SessionManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val userDb = com.example.microgridsystem.data.UserDatabaseHelper.getInstance(context)

    init {
        // Restore session from SQLite if prefs is cleared but SQLite has active session
        if (prefs.getString(KEY_TOKEN, null).isNullOrBlank()) {
            val localUser = userDb.getLoggedInUser()
            if (localUser != null && !localUser.token.isNullOrBlank()) {
                prefs.edit().apply {
                    putString(KEY_TOKEN, localUser.token)
                    putString(KEY_NIC, localUser.nic)
                    putString("prosumer_nic", localUser.nic)
                    putString(KEY_FULL_NAME, localUser.fullName.orEmpty())
                    putString("user_name", localUser.fullName.orEmpty())
                    putString(KEY_ROLE, localUser.role ?: "Prosumer")
                    putString(KEY_STATUS, localUser.status ?: "Active")
                    apply()
                }
            }
        }
    }

    companion object {
        private const val PREF_NAME = "app_prefs"
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_NIC = "user_nic"
        private const val KEY_FULL_NAME = "user_full_name"
        private const val KEY_ROLE = "user_role"
        private const val KEY_STATUS = "user_status"
        // Only holds a URL the user typed in. (The old "api_base_url" key also stored the
        // hard-coded default, which went stale when the PC's IP changed, so it is no longer read.)
        private const val KEY_BASE_URL_OVERRIDE = "api_base_url_override"

        // The building PC's Wi-Fi IP, detected at build time (see app/build.gradle.kts).
        val DEFAULT_BASE_URL: String = BuildConfig.API_BASE_URL
    }

    fun saveLoginSession(
        token: String,
        nic: String,
        fullName: String,
        role: String,
        status: String
    ) {
        // Persist to native SQLite database
        userDb.saveLoginSession(
            nic = nic,
            token = token,
            fullName = fullName,
            role = role,
            status = status
        )

        // Persist to SharedPreferences for backwards compatibility with shared components
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
        getNic()?.let { nic ->
            userDb.updateAccountStatus(nic, status)
        }
        prefs.edit().putString(KEY_STATUS, status).apply()
    }

    fun updateFullName(fullName: String) {
        getNic()?.let { nic ->
            userDb.updateFullName(nic, fullName)
        }
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
        val url = prefs.getString(KEY_BASE_URL_OVERRIDE, null) ?: DEFAULT_BASE_URL
        return if (url.endsWith("/")) url else "$url/"
    }

    // A blank URL (or the default itself) clears the override so the build's detected IP is used again
    fun setBaseUrl(url: String) {
        var formatted = url.trim()
        if (formatted.isNotEmpty() && !formatted.endsWith("/")) {
            formatted += "/"
        }
        if (formatted.isEmpty() || formatted == DEFAULT_BASE_URL) {
            prefs.edit().remove(KEY_BASE_URL_OVERRIDE).apply()
        } else {
            prefs.edit().putString(KEY_BASE_URL_OVERRIDE, formatted).apply()
        }
    }

    fun logout() {
        val currentBaseUrl = getBaseUrl()
        // Clear active session in SQLite database
        userDb.clearLoggedInSession()
        prefs.edit().clear().apply()
        // Preserve base URL setting across logouts
        setBaseUrl(currentBaseUrl)
    }
}
