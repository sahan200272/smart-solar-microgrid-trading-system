package com.example.microgridsystem

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.microgridsystem.data.UserDatabaseHelper
import com.example.microgridsystem.network.RetrofitClient
import com.example.microgridsystem.ui.operator.OperatorConsoleActivity
import com.example.microgridsystem.ui.reservations.ReservationsListActivity
import com.example.microgridsystem.util.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Grid Operator Dashboard Activity
 *
 * Screen 1 for User & Access Management:
 * - Displays welcome message and explicit Grid Operator role.
 * - Provides access to permitted operational tools.
 * - Provides button to view the Prosumer Directory (Screen 2).
 * - Shortcuts to Booking Management and QR Scanning (developed by group members).
 * - Shows only features permitted for Grid Operators (no Backoffice activation/reactivation authority).
 */
class GridOperatorDashboardActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var userDb: UserDatabaseHelper

    private lateinit var tvOperatorName: TextView
    private lateinit var tvOperatorNic: TextView
    private lateinit var tvRoleBadge: TextView

    private lateinit var cardProsumers: MaterialCardView
    private lateinit var cardBookings: MaterialCardView
    private lateinit var cardQrScanner: MaterialCardView
    private lateinit var cardNearbyNodes: MaterialCardView

    private lateinit var btnLogoutTop: ImageButton
    private lateinit var btnLogout: MaterialButton
    private lateinit var tvServerConfig: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_grid_operator_dashboard)

        userDb = UserDatabaseHelper.getInstance(this)
        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            redirectToLogin()
            return
        }

        val role = sessionManager.getRole() ?: "Prosumer"
        if (!role.equals("GridOperator", ignoreCase = true) && !role.equals("Grid Operator", ignoreCase = true)) {
            // Not a Grid Operator; send to appropriate prosumer screen
            val destination = if (sessionManager.isActive()) {
                Intent(this, ProsumerDashboardActivity::class.java)
            } else {
                Intent(this, AccountStatusActivity::class.java)
            }
            startActivity(destination)
            finish()
            return
        }

        initViews()
        setupListeners()
        displayOperatorInfo()
        updateServerConfigText()
    }

    override fun onResume() {
        super.onResume()
        if (sessionManager.isLoggedIn()) {
            displayOperatorInfo()
            updateServerConfigText()
        }
    }

    private fun initViews() {
        tvOperatorName = findViewById(R.id.tvOperatorName)
        tvOperatorNic = findViewById(R.id.tvOperatorNic)
        tvRoleBadge = findViewById(R.id.tvRoleBadge)

        cardProsumers = findViewById(R.id.cardProsumers)
        cardBookings = findViewById(R.id.cardBookings)
        cardQrScanner = findViewById(R.id.cardQrScanner)
        cardNearbyNodes = findViewById(R.id.cardNearbyNodes)

        btnLogoutTop = findViewById(R.id.btnLogoutTop)
        btnLogout = findViewById(R.id.btnLogout)
        tvServerConfig = findViewById(R.id.tvServerConfig)
    }

    private fun setupListeners() {
        // Top and bottom logout
        btnLogoutTop.setOnClickListener { confirmLogout() }
        btnLogout.setOnClickListener { confirmLogout() }

        // Operational Tool 1: Prosumer Directory (User & Access Management Screen 2)
        cardProsumers.setOnClickListener {
            val intent = Intent(this, ProsumerListActivity::class.java)
            startActivity(intent)
        }

        // Operational Tool 2: Booking Management (Group member component shortcut)
        cardBookings.setOnClickListener {
            val intent = Intent(this, ReservationsListActivity::class.java)
            startActivity(intent)
        }

        // Operational Tool 3: QR Transfer Verification (Group member component shortcut)
        cardQrScanner.setOnClickListener {
            redirectToQrModule()
        }

        // Operational Tool 4: Microgrid Stations & Nodes
        cardNearbyNodes.setOnClickListener {
            val intent = Intent(this, NearbyNodesActivity::class.java)
            startActivity(intent)
        }

        tvServerConfig.setOnClickListener {
            showServerConfigDialog()
        }
    }

    private fun displayOperatorInfo() {
        val nic = sessionManager.getNic() ?: ""
        val localUser = userDb.getLoggedInUser() ?: if (nic.isNotBlank()) userDb.getUserByNic(nic) else null

        val fullName = localUser?.fullName ?: sessionManager.getFullName()
        val displayNic = localUser?.nic ?: nic
        val role = localUser?.role ?: sessionManager.getRole() ?: "GridOperator"

        tvOperatorName.text = if (!fullName.isNullOrBlank()) fullName else "Grid Operator"
        tvOperatorNic.text = if (displayNic.isNotBlank()) "NIC: $displayNic" else "NIC: Not Available"
        tvRoleBadge.text = "⚡ $role"
    }

    /**
     * Opens the Operator Console (Booking Views & Grid Operator Verification component),
     * which shows today's bookings and launches the QR scanner.
     */
    private fun redirectToQrModule() {
        val intent = Intent(this, OperatorConsoleActivity::class.java)
        startActivity(intent)
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Sign Out")
            .setMessage("Are you sure you want to log out of the Grid Operator Terminal?")
            .setPositiveButton("Log Out") { _, _ ->
                userDb.clearLoggedInSession()
                sessionManager.logout()
                com.example.microgridsystem.storage.SessionManager.getInstance(this).clearSession()
                Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
                redirectToLogin()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun redirectToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun updateServerConfigText() {
        val currentUrl = sessionManager.getBaseUrl()
        tvServerConfig.text = "Server: $currentUrl (Tap to change)"
    }

    private fun showServerConfigDialog() {
        val input = EditText(this)
        input.setText(sessionManager.getBaseUrl())
        input.setSelection(input.text.length)

        AlertDialog.Builder(this)
            .setTitle("API Server URL")
            .setMessage("For Android Emulator use: http://10.0.2.2:5098/\nFor Physical Phone use your PC IP: http://192.168.x.x:5098/\nLeave blank to reset to ${SessionManager.DEFAULT_BASE_URL}")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                sessionManager.setBaseUrl(input.text.toString())
                RetrofitClient.updateBaseUrl(sessionManager.getBaseUrl())
                updateServerConfigText()
                Toast.makeText(this, "Base URL updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
