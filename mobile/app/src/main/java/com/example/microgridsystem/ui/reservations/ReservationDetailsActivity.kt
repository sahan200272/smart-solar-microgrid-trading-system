package com.example.microgridsystem.ui.reservations

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.microgridsystem.R
import com.example.microgridsystem.models.ApiGenericResponse
import com.example.microgridsystem.models.ReservationResponse
import com.example.microgridsystem.network.ApiErrorParser
import com.example.microgridsystem.network.RetrofitClient
import com.example.microgridsystem.storage.SessionManager
import com.example.microgridsystem.utils.DateTimeUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ReservationDetailsActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private var reservation: ReservationResponse? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvDetailStationName: TextView
    private lateinit var tvDetailStatusBadge: TextView
    private lateinit var tvDetailReservationId: TextView
    private lateinit var tvDetailEnergy: TextView
    private lateinit var tvDetailNic: TextView
    private lateinit var tvDetailNodeId: TextView
    private lateinit var tvDetailStartTime: TextView
    private lateinit var tvDetailEndTime: TextView
    private lateinit var tvDetailCreatedAt: TextView
    private lateinit var layoutCancelledAt: LinearLayout
    private lateinit var tvDetailCancelledAt: TextView
    private lateinit var cardRuleNotice: MaterialCardView
    private lateinit var tvRuleNoticeText: TextView
    private lateinit var btnDetailViewQr: MaterialButton
    private lateinit var btnDetailModify: MaterialButton
    private lateinit var btnDetailCancel: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reservation_details)

        sessionManager = SessionManager.getInstance(this)
        @Suppress("DEPRECATION")
        reservation = intent.getSerializableExtra("EXTRA_RESERVATION") as? ReservationResponse

        val isNew = intent.getBooleanExtra("EXTRA_IS_NEW", false)
        val isModified = intent.getBooleanExtra("EXTRA_IS_MODIFIED", false)

        if (reservation == null) {
            val reservationId = intent.getStringExtra("EXTRA_RESERVATION_ID")
            if (!reservationId.isNullOrBlank()) {
                fetchReservationDetails(reservationId)
            } else {
                Toast.makeText(this, "No reservation data provided.", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        }

        initViews()
        setupToolbar()
        bindReservationData()

        if (isNew) {
            Toast.makeText(this, "✓ Reservation created and pending approval.", Toast.LENGTH_LONG).show()
        } else if (isModified) {
            Toast.makeText(this, "✓ Slot modified successfully.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        reservation?.id?.let {
            if (it.isNotBlank()) {
                fetchReservationDetails(it)
            }
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        tvDetailStationName = findViewById(R.id.tvDetailStationName)
        tvDetailStatusBadge = findViewById(R.id.tvDetailStatusBadge)
        tvDetailReservationId = findViewById(R.id.tvDetailReservationId)
        tvDetailEnergy = findViewById(R.id.tvDetailEnergy)
        tvDetailNic = findViewById(R.id.tvDetailNic)
        tvDetailNodeId = findViewById(R.id.tvDetailNodeId)
        tvDetailStartTime = findViewById(R.id.tvDetailStartTime)
        tvDetailEndTime = findViewById(R.id.tvDetailEndTime)
        tvDetailCreatedAt = findViewById(R.id.tvDetailCreatedAt)
        layoutCancelledAt = findViewById(R.id.layoutCancelledAt)
        tvDetailCancelledAt = findViewById(R.id.tvDetailCancelledAt)
        cardRuleNotice = findViewById(R.id.cardRuleNotice)
        tvRuleNoticeText = findViewById(R.id.tvRuleNoticeText)
        btnDetailViewQr = findViewById(R.id.btnDetailViewQr)
        btnDetailModify = findViewById(R.id.btnDetailModify)
        btnDetailCancel = findViewById(R.id.btnDetailCancel)
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun bindReservationData() {
        val r = reservation ?: return

        tvDetailStationName.text = if (r.stationName.isNotBlank()) r.stationName else "Station Node (${r.nodeId.take(6)})"
        tvDetailReservationId.text = "Booking Ref: ${r.id}"
        tvDetailEnergy.text = String.format(java.util.Locale.US, "%.1f kWh", r.energyKWh)
        tvDetailNic.text = r.prosumerNic
        tvDetailNodeId.text = r.nodeId
        tvDetailStartTime.text = DateTimeUtils.formatIsoToDisplay(r.slotStartTime)
        tvDetailEndTime.text = DateTimeUtils.formatIsoToDisplay(r.slotEndTime)
        tvDetailCreatedAt.text = DateTimeUtils.formatIsoToDisplay(r.createdAt)

        if (!r.cancelledAt.isNullOrBlank()) {
            layoutCancelledAt.visibility = View.VISIBLE
            tvDetailCancelledAt.text = DateTimeUtils.formatIsoToDisplay(r.cancelledAt)
        } else {
            layoutCancelledAt.visibility = View.GONE
        }

        // Status badge
        val status = r.status.trim()
        tvDetailStatusBadge.text = status

        when (status.lowercase(java.util.Locale.US)) {
            "approved" -> {
                tvDetailStatusBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_badge_approved)
                tvDetailStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_approved_text))
                btnDetailViewQr.visibility = View.VISIBLE
                btnDetailModify.visibility = View.VISIBLE
                btnDetailCancel.visibility = View.VISIBLE
            }
            "pending" -> {
                tvDetailStatusBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_badge_pending)
                tvDetailStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_pending_text))
                btnDetailViewQr.visibility = View.GONE
                btnDetailModify.visibility = View.VISIBLE
                btnDetailCancel.visibility = View.VISIBLE
            }
            "completed" -> {
                tvDetailStatusBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_badge_completed)
                tvDetailStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_completed_text))
                btnDetailViewQr.visibility = View.GONE
                btnDetailModify.visibility = View.GONE
                btnDetailCancel.visibility = View.GONE
            }
            "cancelled" -> {
                tvDetailStatusBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_badge_cancelled)
                tvDetailStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_cancelled_text))
                btnDetailViewQr.visibility = View.GONE
                btnDetailModify.visibility = View.GONE
                btnDetailCancel.visibility = View.GONE
            }
            "rejected" -> {
                tvDetailStatusBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_badge_rejected)
                tvDetailStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_rejected_text))
                btnDetailViewQr.visibility = View.GONE
                btnDetailModify.visibility = View.GONE
                btnDetailCancel.visibility = View.GONE
            }
            else -> {
                tvDetailStatusBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_badge_pending)
                tvDetailStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_pending_text))
                btnDetailViewQr.visibility = View.GONE
            }
        }

        // 12-hour soft notice
        val isLessThan12h = DateTimeUtils.isLessThan12HoursAway(r.slotStartTime)
        val isOpen = status.equals("Pending", ignoreCase = true) || status.equals("Approved", ignoreCase = true)
        if (isLessThan12h && isOpen) {
            val hours = DateTimeUtils.getHoursUntilSlot(r.slotStartTime)
            tvRuleNoticeText.text = "⚠️ Warning: This slot is scheduled in ~${hours} hours. Microgrid rules enforce a strict 12-hour notice cutoff for modifying or cancelling slots."
        } else {
            tvRuleNoticeText.text = getString(R.string.twelve_hour_rule_notice)
        }

        // Action Buttons Click Listeners
        btnDetailViewQr.setOnClickListener {
            val intent = Intent(this, ReservationQrActivity::class.java).apply {
                putExtra("EXTRA_RESERVATION_ID", r.id)
                putExtra("EXTRA_RESERVATION", r)
            }
            startActivity(intent)
        }

        btnDetailModify.setOnClickListener {
            val intent = Intent(this, ModifyReservationActivity::class.java).apply {
                putExtra("EXTRA_RESERVATION", r)
            }
            startActivity(intent)
        }

        btnDetailCancel.setOnClickListener {
            showCancelConfirmationDialog(r)
        }
    }

    private fun showCancelConfirmationDialog(r: ReservationResponse) {
        val isLessThan12h = DateTimeUtils.isLessThan12HoursAway(r.slotStartTime)
        val extra = if (isLessThan12h) {
            "\n\n⚠️ Notice: Slot is less than 12 hours away. The server will enforce cancellation policy."
        } else ""

        MaterialAlertDialogBuilder(this)
            .setTitle("Cancel Reservation?")
            .setMessage("Confirm cancellation of booking #${r.id.take(8)} at ${r.stationName}?$extra")
            .setPositiveButton("Yes, Cancel") { _, _ ->
                performCancel(r.id)
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun performCancel(id: String) {
        val token = sessionManager.getBearerToken()
        RetrofitClient.instance.cancelReservation(token, id)
            .enqueue(object : Callback<ApiGenericResponse> {
                override fun onResponse(
                    call: Call<ApiGenericResponse>,
                    response: Response<ApiGenericResponse>
                ) {
                    if (response.isSuccessful) {
                        Toast.makeText(this@ReservationDetailsActivity, "Reservation cancelled successfully.", Toast.LENGTH_LONG).show()
                        fetchReservationDetails(id)
                    } else {
                        val errMsg = ApiErrorParser.parseError(response)
                        Toast.makeText(this@ReservationDetailsActivity, errMsg, Toast.LENGTH_LONG).show()
                    }
                }

                override fun onFailure(call: Call<ApiGenericResponse>, t: Throwable) {
                    Toast.makeText(this@ReservationDetailsActivity, "Network failure while cancelling.", Toast.LENGTH_LONG).show()
                }
            })
    }

    private fun fetchReservationDetails(id: String) {
        val token = sessionManager.getBearerToken()
        RetrofitClient.instance.getReservationById(token, id)
            .enqueue(object : Callback<ReservationResponse> {
                override fun onResponse(
                    call: Call<ReservationResponse>,
                    response: Response<ReservationResponse>
                ) {
                    if (response.isSuccessful && response.body() != null) {
                        reservation = response.body()
                        bindReservationData()
                    }
                }

                override fun onFailure(call: Call<ReservationResponse>, t: Throwable) {}
            })
    }
}
