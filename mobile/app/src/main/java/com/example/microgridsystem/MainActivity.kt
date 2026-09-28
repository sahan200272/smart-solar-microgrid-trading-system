package com.example.microgridsystem

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.microgridsystem.util.SessionManager

/**
 * Entry / Splash Router for the application.
 * Evaluates session state and routes to the appropriate screen:
 * - Not logged in: LoginActivity
 * - Active prosumer: ProsumerDashboardActivity
 * - Pending or Deactivated prosumer: AccountStatusActivity
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionManager = SessionManager(this)

        val destinationIntent = if (!sessionManager.isLoggedIn()) {
            Intent(this, LoginActivity::class.java)
        } else {
            val status = sessionManager.getStatus() ?: ""
            if (status.equals("Active", ignoreCase = true)) {
                Intent(this, ProsumerDashboardActivity::class.java)
            } else {
                Intent(this, AccountStatusActivity::class.java)
            }
        }

        destinationIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(destinationIntent)
        finish()
    }
}