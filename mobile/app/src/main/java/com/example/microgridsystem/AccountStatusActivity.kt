package com.example.microgridsystem

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.microgridsystem.models.ApiResponseMessage
import com.example.microgridsystem.models.ProsumerProfileResponse
import com.example.microgridsystem.network.RetrofitClient
import com.example.microgridsystem.util.ApiErrorUtils
import com.example.microgridsystem.util.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class AccountStatusActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    private lateinit var btnBack: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvErrorMessage: TextView

    private lateinit var ivStatusIcon: ImageView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvUserNic: TextView
    private lateinit var tvStatusDescription: TextView

    private lateinit var cardDeactivate: MaterialCardView
    private lateinit var btnDeactivate: MaterialButton
    private lateinit var btnGoDashboard: MaterialButton
    private lateinit var btnLogout: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_status)

        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            redirectToLogin()
            return
        }

        initViews()
        setupListeners()
        displayStatus(sessionManager.getStatus() ?: "Pending")
        fetchCurrentStatus()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        btnRefresh = findViewById(R.id.btnRefresh)
        progressBar = findViewById(R.id.progressBar)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)

        ivStatusIcon = findViewById(R.id.ivStatusIcon)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvUserNic = findViewById(R.id.tvUserNic)
        tvStatusDescription = findViewById(R.id.tvStatusDescription)

        cardDeactivate = findViewById(R.id.cardDeactivate)
        btnDeactivate = findViewById(R.id.btnDeactivate)
        btnGoDashboard = findViewById(R.id.btnGoDashboard)
        btnLogout = findViewById(R.id.btnLogout)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        btnRefresh.setOnClickListener { fetchCurrentStatus() }

        btnDeactivate.setOnClickListener {
            promptDeactivationConfirmation()
        }

        btnGoDashboard.setOnClickListener {
            val status = sessionManager.getStatus() ?: ""
            if (status.equals("Active", ignoreCase = true)) {
                val intent = Intent(this, ProsumerDashboardActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(
                    this,
                    "Active features are locked until Backoffice activates your account.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        btnLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Confirm Logout")
                .setMessage("Are you sure you want to sign out?")
                .setPositiveButton("Logout") { _, _ ->
                    sessionManager.logout()
                    redirectToLogin()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun fetchCurrentStatus() {
        val nic = sessionManager.getNic() ?: return
        val token = sessionManager.getAuthHeader()

        setLoading(true)
        tvErrorMessage.visibility = View.GONE

        RetrofitClient.getService(this).getProsumerProfile(token, nic)
            .enqueue(object : Callback<ProsumerProfileResponse> {
                override fun onResponse(
                    call: Call<ProsumerProfileResponse>,
                    response: Response<ProsumerProfileResponse>
                ) {
                    setLoading(false)
                    if (response.isSuccessful) {
                        val status = response.body()?.status ?: sessionManager.getStatus() ?: "Active"
                        sessionManager.updateStatus(status)
                        displayStatus(status)
                    } else {
                        val errorMsg = ApiErrorUtils.parseErrorMessage(response, "Could not fetch current status.")
                        showError(errorMsg)
                    }
                }

                override fun onFailure(call: Call<ProsumerProfileResponse>, t: Throwable) {
                    setLoading(false)
                    // If offline, continue showing local cached status
                }
            })
    }

    private fun displayStatus(status: String) {
        val nic = sessionManager.getNic() ?: ""
        tvUserNic.text = "NIC: $nic"

        when {
            status.equals("Active", ignoreCase = true) -> {
                ivStatusIcon.setImageResource(R.drawable.ic_check_circle)
                tvStatusTitle.text = "Account Status: ACTIVE"
                tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_active))
                tvStatusDescription.text =
                    "Your account is verified and fully active. You have full access to microgrid services, node navigation, and energy trading."
                cardDeactivate.visibility = View.VISIBLE
            }
            status.equals("Pending", ignoreCase = true) -> {
                ivStatusIcon.setImageResource(R.drawable.ic_hourglass)
                tvStatusTitle.text = "Account Status: PENDING"
                tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_pending))
                tvStatusDescription.text =
                    "Your account has been registered and is waiting for Backoffice activation.\n\nDuring the Pending state, operational microgrid features (such as node map navigation and battery reservations) are locked for safety."
                cardDeactivate.visibility = View.GONE
            }
            else -> {
                ivStatusIcon.setImageResource(R.drawable.ic_warning)
                tvStatusTitle.text = "Account Status: DEACTIVATED"
                tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_deactivated))
                tvStatusDescription.text =
                    "Your prosumer account has been deactivated. Access to all active microgrid operations has been suspended.\n\nPer microgrid policy, self-reactivation is not permitted. Please contact Backoffice support if you require reactivation."
                cardDeactivate.visibility = View.GONE
            }
        }
    }

    private fun promptDeactivationConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Confirm Account Deactivation")
            .setMessage("Are you sure you want to deactivate your Prosumer account?\n\n• Your access to active trading and node navigation will be immediately revoked.\n• You will NOT be able to self-reactivate your account.\n• Only Backoffice administrators can reactivate accounts.")
            .setIcon(R.drawable.ic_warning)
            .setPositiveButton("Yes, Deactivate") { _, _ ->
                performDeactivation()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performDeactivation() {
        val nic = sessionManager.getNic() ?: return
        val token = sessionManager.getAuthHeader()

        setLoading(true)
        tvErrorMessage.visibility = View.GONE

        RetrofitClient.getService(this).deactivateProsumer(token, nic)
            .enqueue(object : Callback<ApiResponseMessage> {
                override fun onResponse(
                    call: Call<ApiResponseMessage>,
                    response: Response<ApiResponseMessage>
                ) {
                    setLoading(false)

                    if (response.isSuccessful) {
                        sessionManager.updateStatus("Deactivated")
                        displayStatus("Deactivated")

                        AlertDialog.Builder(this@AccountStatusActivity)
                            .setTitle("Account Deactivated")
                            .setMessage("Your account has been deactivated successfully. All active features have been restricted. Remember that self-reactivation is not permitted.")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        val errorMsg = ApiErrorUtils.parseErrorMessage(
                            response,
                            "Failed to deactivate account."
                        )
                        showError(errorMsg)
                    }
                }

                override fun onFailure(call: Call<ApiResponseMessage>, t: Throwable) {
                    setLoading(false)
                    showError("Network connection failure: ${t.localizedMessage}")
                }
            })
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnDeactivate.isEnabled = !loading
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
