package com.example.microgridsystem

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.microgridsystem.data.UserDatabaseHelper
import com.example.microgridsystem.models.ProsumerProfileResponse
import com.example.microgridsystem.network.RetrofitClient
import com.example.microgridsystem.util.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ProsumerDashboardActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var userDb: UserDatabaseHelper

    private lateinit var tvWelcomeName: TextView
    private lateinit var tvUserNic: TextView
    private lateinit var tvStatusBadge: TextView
    private lateinit var bannerRestricted: LinearLayout
    private lateinit var tvRestrictedNotice: TextView

    private lateinit var cardProfile: MaterialCardView
    private lateinit var cardEditProfile: MaterialCardView
    private lateinit var cardAccountStatus: MaterialCardView
    private lateinit var cardNearbyNodes: MaterialCardView
    private lateinit var cardBookings: MaterialCardView
    private lateinit var cardMyBookings: MaterialCardView

    private lateinit var btnLogoutTop: ImageButton
    private lateinit var btnLogout: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_prosumer_dashboard)

        userDb = UserDatabaseHelper.getInstance(this)
        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            redirectToLogin()
            return
        }

        initViews()
        setupListeners()
        displayCachedUserInfo()
    }

    override fun onResume() {
        super.onResume()
        if (sessionManager.isLoggedIn()) {
            displayCachedUserInfo()
            fetchLatestProfile()
        }
    }

    private fun initViews() {
        tvWelcomeName = findViewById(R.id.tvWelcomeName)
        tvUserNic = findViewById(R.id.tvUserNic)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        bannerRestricted = findViewById(R.id.bannerRestricted)
        tvRestrictedNotice = findViewById(R.id.tvRestrictedNotice)

        cardProfile = findViewById(R.id.cardProfile)
        cardEditProfile = findViewById(R.id.cardEditProfile)
        cardAccountStatus = findViewById(R.id.cardAccountStatus)
        cardNearbyNodes = findViewById(R.id.cardNearbyNodes)
        cardBookings = findViewById(R.id.cardBookings)
        cardMyBookings = findViewById(R.id.cardMyBookings)

        btnLogoutTop = findViewById(R.id.btnLogoutTop)
        btnLogout = findViewById(R.id.btnLogout)
    }

    private fun setupListeners() {
        btnLogoutTop.setOnClickListener { confirmLogout() }
        btnLogout.setOnClickListener { confirmLogout() }

        cardProfile.setOnClickListener {
            val intent = Intent(this, ProfileActivity::class.java)
            startActivity(intent)
        }

        cardEditProfile.setOnClickListener {
            val intent = Intent(this, EditProfileActivity::class.java)
            startActivity(intent)
        }

        cardAccountStatus.setOnClickListener {
            val intent = Intent(this, AccountStatusActivity::class.java)
            startActivity(intent)
        }

        cardNearbyNodes.setOnClickListener {
            handleOperationalFeatureClick {
                val intent = Intent(this, NearbyNodesActivity::class.java)
                startActivity(intent)
            }
        }

        cardBookings.setOnClickListener {
            handleOperationalFeatureClick {
                val intent = Intent(this, com.example.microgridsystem.ui.reservations.ReservationsListActivity::class.java)
                startActivity(intent)
            }
        }

        cardMyBookings.setOnClickListener {
            handleOperationalFeatureClick {
                val intent = Intent(this, com.example.microgridsystem.ui.bookings.MyBookingsActivity::class.java)
                startActivity(intent)
            }
        }
    }

    private fun displayCachedUserInfo() {
        val nic = sessionManager.getNic() ?: ""
        val localUser = userDb.getLoggedInUser() ?: if (nic.isNotBlank()) userDb.getUserByNic(nic) else null

        val fullName = localUser?.fullName ?: sessionManager.getFullName() ?: "Prosumer"
        val displayNic = localUser?.nic ?: nic
        val status = localUser?.status ?: sessionManager.getStatus() ?: "Active"

        tvWelcomeName.text = fullName
        tvUserNic.text = "NIC: $displayNic"

        updateStatusUI(status)
    }

    private fun updateStatusUI(status: String) {
        when {
            status.equals("Active", ignoreCase = true) -> {
                tvStatusBadge.text = "● Active"
                tvStatusBadge.setBackgroundResource(R.drawable.bg_status_active)
                tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_active))
                bannerRestricted.visibility = View.GONE
            }
            status.equals("Pending", ignoreCase = true) -> {
                tvStatusBadge.text = "⏳ Pending"
                tvStatusBadge.setBackgroundResource(R.drawable.bg_status_pending)
                tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_pending))
                bannerRestricted.visibility = View.VISIBLE
                tvRestrictedNotice.text = "Your account is Pending activation by Backoffice. Active services (Nodes & Trading) are restricted until approved."
            }
            else -> {
                tvStatusBadge.text = "✖ Deactivated"
                tvStatusBadge.setBackgroundResource(R.drawable.bg_status_deactivated)
                tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_deactivated))
                bannerRestricted.visibility = View.VISIBLE
                tvRestrictedNotice.text = "Your account is Deactivated. Active services are suspended. Contact Backoffice for assistance."
            }
        }
    }

    private fun handleOperationalFeatureClick(onActiveAction: () -> Unit) {
        val status = sessionManager.getStatus() ?: ""
        if (status.equals("Active", ignoreCase = true)) {
            onActiveAction()
        } else {
            val statusDisplay = if (status.equals("Pending", ignoreCase = true)) "Pending Backoffice Activation" else "Deactivated"
            AlertDialog.Builder(this)
                .setTitle("Access Restricted")
                .setMessage("This feature is only available to active prosumers.\n\nYour current account status is: $statusDisplay.\n\nPlease visit the Account Status screen or contact Backoffice support.")
                .setPositiveButton("View Account Status") { _, _ ->
                    startActivity(Intent(this, AccountStatusActivity::class.java))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun fetchLatestProfile() {
        val nic = sessionManager.getNic() ?: return
        val token = sessionManager.getAuthHeader()

        RetrofitClient.getService(this).getProsumerProfile(token, nic)
            .enqueue(object : Callback<ProsumerProfileResponse> {
                override fun onResponse(
                    call: Call<ProsumerProfileResponse>,
                    response: Response<ProsumerProfileResponse>
                ) {
                    if (response.isSuccessful) {
                        val profile = response.body()
                        profile?.let {
                            // Update local SQLite persistence
                            userDb.saveOrUpdateFullProfile(it)

                            if (!it.fullName.isNullOrBlank()) {
                                sessionManager.updateFullName(it.fullName)
                                tvWelcomeName.text = it.fullName
                            }
                            if (!it.status.isNullOrBlank()) {
                                sessionManager.updateStatus(it.status)
                                updateStatusUI(it.status)
                            }
                        }
                    }
                }

                override fun onFailure(call: Call<ProsumerProfileResponse>, t: Throwable) {
                    // Silently ignore background refresh failure, cached SQLite state remains
                }
            })
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Confirm Logout")
            .setMessage("Are you sure you want to sign out?")
            .setPositiveButton("Logout") { _, _ ->
                userDb.clearLoggedInSession()
                sessionManager.logout()
                Toast.makeText(this, "Signed out successfully", Toast.LENGTH_SHORT).show()
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
}
