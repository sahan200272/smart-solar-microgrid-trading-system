package com.example.microgridsystem

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.microgridsystem.data.LocalUserSession
import com.example.microgridsystem.data.UserDatabaseHelper
import com.example.microgridsystem.models.ProsumerProfileResponse
import com.example.microgridsystem.network.RetrofitClient
import com.example.microgridsystem.util.ApiErrorUtils
import com.example.microgridsystem.util.SessionManager
import com.google.android.material.button.MaterialButton
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ProfileActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var userDb: UserDatabaseHelper

    private lateinit var btnBack: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvErrorMessage: TextView

    private lateinit var tvProfileFullName: TextView
    private lateinit var tvProfileStatus: TextView
    private lateinit var tvDetailNic: TextView
    private lateinit var tvDetailEmail: TextView
    private lateinit var tvDetailPhone: TextView
    private lateinit var tvDetailAddress: TextView
    private lateinit var tvDetailCreatedAt: TextView

    private lateinit var btnEditProfile: MaterialButton
    private lateinit var btnStatusManagement: MaterialButton
    private lateinit var btnLogout: MaterialButton

    private var currentProfile: ProsumerProfileResponse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        userDb = UserDatabaseHelper.getInstance(this)
        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            redirectToLogin()
            return
        }

        initViews()
        setupListeners()
        loadProfileData()
    }

    override fun onResume() {
        super.onResume()
        if (sessionManager.isLoggedIn()) {
            loadProfileData()
        }
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        btnRefresh = findViewById(R.id.btnRefresh)
        progressBar = findViewById(R.id.progressBar)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)

        tvProfileFullName = findViewById(R.id.tvProfileFullName)
        tvProfileStatus = findViewById(R.id.tvProfileStatus)
        tvDetailNic = findViewById(R.id.tvDetailNic)
        tvDetailEmail = findViewById(R.id.tvDetailEmail)
        tvDetailPhone = findViewById(R.id.tvDetailPhone)
        tvDetailAddress = findViewById(R.id.tvDetailAddress)
        tvDetailCreatedAt = findViewById(R.id.tvDetailCreatedAt)

        btnEditProfile = findViewById(R.id.btnEditProfile)
        btnStatusManagement = findViewById(R.id.btnStatusManagement)
        btnLogout = findViewById(R.id.btnLogout)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        btnRefresh.setOnClickListener { loadProfileData() }

        btnEditProfile.setOnClickListener {
            val intent = Intent(this, EditProfileActivity::class.java).apply {
                putExtra("EXTRA_NIC", currentProfile?.nic ?: sessionManager.getNic())
                putExtra("EXTRA_FULL_NAME", currentProfile?.fullName ?: sessionManager.getFullName())
                putExtra("EXTRA_EMAIL", currentProfile?.email ?: "")
                putExtra("EXTRA_PHONE", currentProfile?.phone ?: "")
                putExtra("EXTRA_ADDRESS", currentProfile?.address ?: "")
            }
            startActivity(intent)
        }

        btnStatusManagement.setOnClickListener {
            val intent = Intent(this, AccountStatusActivity::class.java)
            startActivity(intent)
        }

        btnLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Confirm Logout")
                .setMessage("Are you sure you want to sign out?")
                .setPositiveButton("Logout") { _, _ ->
                    userDb.clearLoggedInSession()
                    sessionManager.logout()
                    redirectToLogin()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun loadProfileData() {
        val nic = sessionManager.getNic() ?: return
        val token = sessionManager.getAuthHeader()

        // 1. Immediately display locally cached profile from native SQLite database
        val cachedUser = userDb.getUserByNic(nic)
        if (cachedUser != null) {
            bindLocalProfile(cachedUser)
        }

        setLoading(true)
        tvErrorMessage.visibility = View.GONE

        // 2. Fetch latest profile from API to refresh local SQLite database
        RetrofitClient.getService(this).getProsumerProfile(token, nic)
            .enqueue(object : Callback<ProsumerProfileResponse> {
                override fun onResponse(
                    call: Call<ProsumerProfileResponse>,
                    response: Response<ProsumerProfileResponse>
                ) {
                    setLoading(false)

                    if (response.isSuccessful) {
                        val profile = response.body()
                        if (profile != null) {
                            currentProfile = profile
                            // Persist fresh profile to SQLite
                            userDb.saveOrUpdateFullProfile(profile)
                            bindProfile(profile)
                        } else {
                            if (cachedUser == null) {
                                showError("Empty profile response from server.")
                            }
                        }
                    } else {
                        val errorMsg = ApiErrorUtils.parseErrorMessage(
                            response,
                            "Failed to load prosumer profile."
                        )
                        if (cachedUser != null) {
                            Toast.makeText(this@ProfileActivity, "Showing cached profile", Toast.LENGTH_SHORT).show()
                        } else {
                            showError(errorMsg)
                        }
                    }
                }

                override fun onFailure(call: Call<ProsumerProfileResponse>, t: Throwable) {
                    setLoading(false)
                    if (cachedUser != null) {
                        Toast.makeText(this@ProfileActivity, "Offline: showing cached profile", Toast.LENGTH_SHORT).show()
                    } else {
                        showError("Connection failed: ${t.localizedMessage}")
                    }
                }
            })
    }

    private fun bindLocalProfile(user: LocalUserSession) {
        tvProfileFullName.text = user.fullName ?: "Prosumer"
        tvDetailNic.text = user.nic
        tvDetailEmail.text = if (!user.email.isNullOrBlank()) user.email else "-"
        tvDetailPhone.text = if (!user.phone.isNullOrBlank()) user.phone else "-"
        tvDetailAddress.text = if (!user.address.isNullOrBlank()) user.address else "-"
        tvDetailCreatedAt.text = user.createdAt?.take(10) ?: "-"

        applyStatusBadge(user.status ?: "Active")
    }

    private fun bindProfile(profile: ProsumerProfileResponse) {
        tvProfileFullName.text = profile.fullName ?: "Prosumer"
        tvDetailNic.text = profile.nic ?: ""
        tvDetailEmail.text = if (!profile.email.isNullOrBlank()) profile.email else "-"
        tvDetailPhone.text = if (!profile.phone.isNullOrBlank()) profile.phone else "-"
        tvDetailAddress.text = if (!profile.address.isNullOrBlank()) profile.address else "-"

        val formattedDate = profile.createdAt?.take(10) ?: "-"
        tvDetailCreatedAt.text = formattedDate

        val status = profile.status ?: "Active"
        sessionManager.updateStatus(status)
        if (!profile.fullName.isNullOrBlank()) {
            sessionManager.updateFullName(profile.fullName)
        }

        applyStatusBadge(status)
    }

    private fun applyStatusBadge(status: String) {
        when {
            status.equals("Active", ignoreCase = true) -> {
                tvProfileStatus.text = "● Active"
                tvProfileStatus.setBackgroundResource(R.drawable.bg_status_active)
                tvProfileStatus.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            }
            status.equals("Pending", ignoreCase = true) -> {
                tvProfileStatus.text = "⏳ Pending"
                tvProfileStatus.setBackgroundResource(R.drawable.bg_status_pending)
                tvProfileStatus.setTextColor(ContextCompat.getColor(this, R.color.status_pending))
            }
            else -> {
                tvProfileStatus.text = "✖ Deactivated"
                tvProfileStatus.setBackgroundResource(R.drawable.bg_status_deactivated)
                tvProfileStatus.setTextColor(ContextCompat.getColor(this, R.color.status_deactivated))
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showError(msg: String) {
        tvErrorMessage.text = msg
        tvErrorMessage.visibility = View.VISIBLE
    }

    private fun redirectToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
