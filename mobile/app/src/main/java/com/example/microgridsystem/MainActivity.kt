package com.example.microgridsystem

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.microgridsystem.util.SessionManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.microgridsystem.ui.reservations.ReservationsListActivity
import com.google.android.material.button.MaterialButton

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
            val role = sessionManager.getRole() ?: "Prosumer"
            if (role.equals("GridOperator", ignoreCase = true) || role.equals("Grid Operator", ignoreCase = true)) {
                Intent(this, GridOperatorDashboardActivity::class.java)
            } else {
                val status = sessionManager.getStatus() ?: ""
                if (status.equals("Active", ignoreCase = true)) {
                    Intent(this, ProsumerDashboardActivity::class.java)
                } else {
                    Intent(this, AccountStatusActivity::class.java)
                }
            }
        }

        destinationIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(destinationIntent)
        finish()
        findViewById<MaterialButton>(R.id.btnOpenReservations).setOnClickListener {
            startActivity(Intent(this, ReservationsListActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnOpenNearbyNodes).setOnClickListener {
            startActivity(Intent(this, NearbyNodesActivity::class.java))
        }
    }
}