package com.example.microgridsystem

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.microgridsystem.models.ProsumerProfileResponse
import com.example.microgridsystem.network.RetrofitClient
import com.example.microgridsystem.ui.reservations.ReservationsListActivity
import com.example.microgridsystem.util.ApiErrorUtils
import com.example.microgridsystem.utils.DateTimeUtils
import com.example.microgridsystem.util.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Prosumer Details Screen for Grid Operator
 *
 * Screen 2B for User & Access Management:
 * - Calls GET /api/prosumers/{nic} for the selected prosumer.
 * - Displays complete individual prosumer details and account status.
 * - Strictly prevents reactivation of deactivated prosumer accounts:
 *   Reactivation and pending activation authority belongs exclusively to Backoffice.
 * - Provides shortcut to inspect bookings for this prosumer.
 */
class ProsumerDetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROSUMER_NIC = "extra_prosumer_nic"
        const val EXTRA_PRELOADED_NAME = "extra_preloaded_name"
        const val EXTRA_PRELOADED_STATUS = "extra_preloaded_status"
    }

    private lateinit var sessionManager: SessionManager
    private var prosumerNic: String = ""

    private lateinit var btnBack: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var tvToolbarSubtitle: TextView

    private lateinit var scrollDetails: ScrollView
    private lateinit var tvDetailFullName: TextView
    private lateinit var tvDetailNic: TextView
    private lateinit var tvDetailStatusBadge: TextView

    private lateinit var cardAuthorityNotice: MaterialCardView
    private lateinit var ivNoticeIcon: ImageView
    private lateinit var tvNoticeTitle: TextView
    private lateinit var tvNoticeMessage: TextView

    private lateinit var tvInfoNic: TextView
    private lateinit var tvInfoPhone: TextView
    private lateinit var tvInfoEmail: TextView
    private lateinit var tvInfoAddress: TextView
    private lateinit var tvInfoId: TextView
    private lateinit var tvInfoCreatedAt: TextView

    private lateinit var btnViewBookings: MaterialButton

    private lateinit var layoutLoading: LinearLayout
    private lateinit var layoutError: LinearLayout
    private lateinit var tvErrorMessage: TextView
    private lateinit var btnRetry: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_prosumer_details)

        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            redirectToLogin()
            return
        }

        prosumerNic = intent.getStringExtra(EXTRA_PROSUMER_NIC) ?: ""
        if (prosumerNic.isBlank()) {
            Toast.makeText(this, "Prosumer NIC was not specified.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupListeners()

        // Populate any preloaded data while fetching fresh details
        val preloadedName = intent.getStringExtra(EXTRA_PRELOADED_NAME)
        val preloadedStatus = intent.getStringExtra(EXTRA_PRELOADED_STATUS)
        if (!preloadedName.isNullOrBlank()) {
            tvDetailFullName.text = preloadedName
        }
        tvDetailNic.text = "NIC: $prosumerNic"
        tvInfoNic.text = prosumerNic
        tvToolbarSubtitle.text = "NIC: $prosumerNic"

        if (!preloadedStatus.isNullOrBlank()) {
            updateStatusAndAuthorityCard(preloadedStatus)
        }

        fetchProsumerDetails()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        btnRefresh = findViewById(R.id.btnRefresh)
        tvToolbarSubtitle = findViewById(R.id.tvToolbarSubtitle)

        scrollDetails = findViewById(R.id.scrollDetails)
        tvDetailFullName = findViewById(R.id.tvDetailFullName)
        tvDetailNic = findViewById(R.id.tvDetailNic)
        tvDetailStatusBadge = findViewById(R.id.tvDetailStatusBadge)

        cardAuthorityNotice = findViewById(R.id.cardAuthorityNotice)
        ivNoticeIcon = findViewById(R.id.ivNoticeIcon)
        tvNoticeTitle = findViewById(R.id.tvNoticeTitle)
        tvNoticeMessage = findViewById(R.id.tvNoticeMessage)

        tvInfoNic = findViewById(R.id.tvInfoNic)
        tvInfoPhone = findViewById(R.id.tvInfoPhone)
        tvInfoEmail = findViewById(R.id.tvInfoEmail)
        tvInfoAddress = findViewById(R.id.tvInfoAddress)
        tvInfoId = findViewById(R.id.tvInfoId)
        tvInfoCreatedAt = findViewById(R.id.tvInfoCreatedAt)

        btnViewBookings = findViewById(R.id.btnViewBookings)

        layoutLoading = findViewById(R.id.layoutLoading)
        layoutError = findViewById(R.id.layoutError)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)
        btnRetry = findViewById(R.id.btnRetry)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        btnRefresh.setOnClickListener { fetchProsumerDetails() }
        btnRetry.setOnClickListener { fetchProsumerDetails() }

        // Operational Shortcut: View bookings for this prosumer
        btnViewBookings.setOnClickListener {
            // Update storage session prosumer nic so reservations list filters or references this prosumer
            com.example.microgridsystem.storage.SessionManager.getInstance(this).apply {
                saveSession(
                    token = sessionManager.getToken() ?: "",
                    prosumerNic = prosumerNic,
                    role = sessionManager.getRole() ?: "GridOperator",
                    fullName = tvDetailFullName.text.toString()
                )
            }
            val intent = Intent(this, ReservationsListActivity::class.java)
            startActivity(intent)
        }
    }

    /**
     * Requirement: Call GET /api/prosumers/{nic}
     */
    private fun fetchProsumerDetails() {
        showLoading(true)
        hideError()

        val token = sessionManager.getAuthHeader()
        val apiService = RetrofitClient.getService(this)

        apiService.getProsumerProfile(token, prosumerNic)
            .enqueue(object : Callback<ProsumerProfileResponse> {
                override fun onResponse(
                    call: Call<ProsumerProfileResponse>,
                    response: Response<ProsumerProfileResponse>
                ) {
                    showLoading(false)

                    if (response.isSuccessful) {
                        val prosumer = response.body()
                        if (prosumer != null) {
                            renderProsumerDetails(prosumer)
                        } else {
                            showError("Empty profile response received.")
                        }
                    } else {
                        val errorMsg = ApiErrorUtils.parseErrorMessage(response, "Failed to load prosumer details.")
                        showError(errorMsg)
                    }
                }

                override fun onFailure(call: Call<ProsumerProfileResponse>, t: Throwable) {
                    showLoading(false)
                    showError("Unable to connect to server: ${t.localizedMessage}. Check network connection.")
                }
            })
    }

    private fun renderProsumerDetails(prosumer: ProsumerProfileResponse) {
        tvDetailFullName.text = if (!prosumer.fullName.isNullOrBlank()) prosumer.fullName else "Unknown Name"
        tvDetailNic.text = "NIC: ${prosumer.nic ?: prosumerNic}"

        tvInfoNic.text = prosumer.nic ?: prosumerNic
        tvInfoPhone.text = if (!prosumer.phone.isNullOrBlank()) prosumer.phone else "Not Provided"
        tvInfoEmail.text = if (!prosumer.email.isNullOrBlank()) prosumer.email else "Not Provided"
        tvInfoAddress.text = if (!prosumer.address.isNullOrBlank()) prosumer.address else "Not Provided"
        tvInfoId.text = if (!prosumer.id.isNullOrBlank()) prosumer.id else "N/A"

        tvInfoCreatedAt.text = formatCreatedDate(prosumer.createdAt)

        updateStatusAndAuthorityCard(prosumer.status ?: "Pending")
    }

    /**
     * Explicitly enforces role authority rules:
     * - Deactivated accounts cannot be reactivated by Grid Operators.
     * - Pending accounts cannot be activated by Grid Operators.
     * - Clear visual indication of Backoffice authority.
     */
    private fun updateStatusAndAuthorityCard(status: String) {
        when {
            status.equals("Active", ignoreCase = true) -> {
                tvDetailStatusBadge.text = "● Active"
                tvDetailStatusBadge.setBackgroundResource(R.drawable.bg_status_active)
                tvDetailStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_active))

                cardAuthorityNotice.setCardBackgroundColor(Color.parseColor("#F0FDF4"))
                cardAuthorityNotice.strokeColor = Color.parseColor("#BBF7D0")
                ivNoticeIcon.setImageResource(R.drawable.ic_check_circle)
                ivNoticeIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_active))
                tvNoticeTitle.setTextColor(Color.parseColor("#166534"))
                tvNoticeTitle.text = "Active & Verified Prosumer"
                tvNoticeMessage.setTextColor(Color.parseColor("#14532D"))
                tvNoticeMessage.text = "This prosumer account is verified and operational. The prosumer is authorized for microgrid slot bookings, battery charging, and energy transfers."
            }

            status.equals("Pending", ignoreCase = true) -> {
                tvDetailStatusBadge.text = "⏳ Pending"
                tvDetailStatusBadge.setBackgroundResource(R.drawable.bg_status_pending)
                tvDetailStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_pending))

                cardAuthorityNotice.setCardBackgroundColor(Color.parseColor("#FEF3C7"))
                cardAuthorityNotice.strokeColor = Color.parseColor("#FDE68A")
                ivNoticeIcon.setImageResource(R.drawable.ic_hourglass)
                ivNoticeIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_pending))
                tvNoticeTitle.setTextColor(Color.parseColor("#92400E"))
                tvNoticeTitle.text = "Pending Backoffice Activation"
                tvNoticeMessage.setTextColor(Color.parseColor("#78350F"))
                tvNoticeMessage.text = "This prosumer registration is awaiting Backoffice verification. Per specification, account activation authority belongs exclusively to the Backoffice web administration application."
            }

            else -> {
                // Deactivated
                tvDetailStatusBadge.text = "✖ Deactivated"
                tvDetailStatusBadge.setBackgroundResource(R.drawable.bg_status_deactivated)
                tvDetailStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_deactivated))

                cardAuthorityNotice.setCardBackgroundColor(Color.parseColor("#FEE2E2"))
                cardAuthorityNotice.strokeColor = Color.parseColor("#FCA5A5")
                ivNoticeIcon.setImageResource(R.drawable.ic_warning)
                ivNoticeIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_deactivated))
                tvNoticeTitle.setTextColor(Color.parseColor("#991B1B"))
                tvNoticeTitle.text = "Deactivated Account (Read-Only)"
                tvNoticeMessage.setTextColor(Color.parseColor("#7F1D1D"))
                tvNoticeMessage.text = "This account is currently deactivated and restricted from energy transactions.\n\n⚠️ Grid Operators are not permitted to reactivate deactivated accounts. Reactivation authority belongs exclusively to the Backoffice web application."
            }
        }
    }

    private fun formatCreatedDate(rawDate: String?): String {
        if (rawDate.isNullOrBlank()) return "Date not recorded"
        return try {
            DateTimeUtils.formatIsoToDisplay(rawDate)
        } catch (e: Exception) {
            rawDate.take(10)
        }
    }

    private fun showLoading(loading: Boolean) {
        layoutLoading.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) {
            scrollDetails.visibility = View.GONE
            layoutError.visibility = View.GONE
        } else {
            scrollDetails.visibility = View.VISIBLE
        }
    }

    private fun showError(message: String) {
        tvErrorMessage.text = message
        layoutError.visibility = View.VISIBLE
        scrollDetails.visibility = View.GONE
        layoutLoading.visibility = View.GONE
    }

    private fun hideError() {
        layoutError.visibility = View.GONE
    }

    private fun redirectToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
