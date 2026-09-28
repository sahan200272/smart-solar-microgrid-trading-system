package com.example.microgridsystem.ui.reservations

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.microgridsystem.R
import com.example.microgridsystem.models.QrGenerationResponse
import com.example.microgridsystem.models.ReservationResponse
import com.example.microgridsystem.network.ApiErrorParser
import com.example.microgridsystem.network.RetrofitClient
import com.example.microgridsystem.storage.SessionManager
import com.example.microgridsystem.utils.DateTimeUtils
import com.example.microgridsystem.utils.QrCodeGenerator
import com.google.android.material.appbar.MaterialToolbar
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ReservationQrActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private var reservation: ReservationResponse? = null
    private var reservationId: String = ""

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvQrStationName: TextView
    private lateinit var tvQrEnergy: TextView
    private lateinit var layoutAlreadyUsed: LinearLayout
    private lateinit var ivQrCode: ImageView
    private lateinit var pbQrLoading: ProgressBar
    private lateinit var viewUsedOverlay: View
    private lateinit var tvQrExpiresAt: TextView
    private lateinit var tvQrSlotWindow: TextView
    private lateinit var tvQrProsumerNic: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reservation_qr)

        sessionManager = SessionManager.getInstance(this)
        reservationId = intent.getStringExtra("EXTRA_RESERVATION_ID") ?: ""
        @Suppress("DEPRECATION")
        reservation = intent.getSerializableExtra("EXTRA_RESERVATION") as? ReservationResponse

        if (reservation != null && reservationId.isBlank()) {
            reservationId = reservation!!.id
        }

        if (reservationId.isBlank()) {
            Toast.makeText(this, "Reservation ID missing.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupToolbar()
        populateBasicDetails()
        loadQrData()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        tvQrStationName = findViewById(R.id.tvQrStationName)
        tvQrEnergy = findViewById(R.id.tvQrEnergy)
        layoutAlreadyUsed = findViewById(R.id.layoutAlreadyUsed)
        ivQrCode = findViewById(R.id.ivQrCode)
        pbQrLoading = findViewById(R.id.pbQrLoading)
        viewUsedOverlay = findViewById(R.id.viewUsedOverlay)
        tvQrExpiresAt = findViewById(R.id.tvQrExpiresAt)
        tvQrSlotWindow = findViewById(R.id.tvQrSlotWindow)
        tvQrProsumerNic = findViewById(R.id.tvQrProsumerNic)
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun populateBasicDetails() {
        val r = reservation ?: return
        tvQrStationName.text = if (r.stationName.isNotBlank()) r.stationName else "Microgrid Station (${r.nodeId.take(6)})"
        tvQrEnergy.text = String.format(java.util.Locale.US, "%.1f kWh Reserved", r.energyKWh)

        val startStr = DateTimeUtils.formatIsoToDisplay(r.slotStartTime)
        val endStr = DateTimeUtils.formatIsoToTimeOnly(r.slotEndTime)
        tvQrSlotWindow.text = "Slot Window: $startStr - $endStr"
        tvQrProsumerNic.text = "NIC: ${r.prosumerNic} | Ref: #${r.id.take(8)}"

        // Check if already used
        if (!r.qrUsedAt.isNullOrBlank() || r.status.equals("Completed", ignoreCase = true)) {
            showAlreadyUsedState()
        }
    }

    /**
     * Requirement 5: Call POST /api/reservations/{id}/qr to generate/fetch token and render with ZXing
     */
    private fun loadQrData() {
        // If reservation is already completed or used, do not show active QR
        val r = reservation
        if (r != null && (!r.qrUsedAt.isNullOrBlank() || r.status.equals("Completed", ignoreCase = true))) {
            showAlreadyUsedState()
            return
        }

        setLoading(true)

        val token = sessionManager.getBearerToken()
        RetrofitClient.instance.generateQr(token, reservationId)
            .enqueue(object : Callback<QrGenerationResponse> {
                override fun onResponse(
                    call: Call<QrGenerationResponse>,
                    response: Response<QrGenerationResponse>
                ) {
                    setLoading(false)
                    if (response.isSuccessful && response.body() != null) {
                        val qrData = response.body()!!
                        renderQrCode(qrData)
                    } else {
                        val errorMsg = ApiErrorParser.parseError(response)
                        tvQrExpiresAt.text = "Error: $errorMsg"
                        Toast.makeText(this@ReservationQrActivity, errorMsg, Toast.LENGTH_LONG).show()
                    }
                }

                override fun onFailure(call: Call<QrGenerationResponse>, t: Throwable) {
                    setLoading(false)
                    val errorMsg = "Could not fetch QR pass from server."
                    tvQrExpiresAt.text = errorMsg
                    Toast.makeText(this@ReservationQrActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            })
    }

    private fun renderQrCode(qrData: QrGenerationResponse) {
        val payloadToEncode = qrData.qrPayload.ifBlank { qrData.qrToken }
        val bitmap: Bitmap? = QrCodeGenerator.generateQrBitmap(payloadToEncode, 600)

        if (bitmap != null) {
            ivQrCode.setImageBitmap(bitmap)
            ivQrCode.visibility = View.VISIBLE
        } else {
            Toast.makeText(this, "Failed to render QR Code bitmap.", Toast.LENGTH_SHORT).show()
        }

        if (qrData.expiresAt.isNotBlank()) {
            tvQrExpiresAt.text = "Expires At: ${DateTimeUtils.formatIsoToDisplay(qrData.expiresAt)}"
        }

        if (qrData.slotStartTime != null && qrData.slotEndTime != null) {
            val startStr = DateTimeUtils.formatIsoToDisplay(qrData.slotStartTime)
            val endStr = DateTimeUtils.formatIsoToTimeOnly(qrData.slotEndTime)
            tvQrSlotWindow.text = "Slot Window: $startStr - $endStr"
        }

        if (!qrData.stationName.isNullOrBlank()) {
            tvQrStationName.text = qrData.stationName
        }

        if (qrData.energyKWh != null) {
            tvQrEnergy.text = String.format(java.util.Locale.US, "%.1f kWh Reserved", qrData.energyKWh)
        }
    }

    private fun showAlreadyUsedState() {
        layoutAlreadyUsed.visibility = View.VISIBLE
        viewUsedOverlay.visibility = View.VISIBLE
        tvQrExpiresAt.text = "Status: Used & Verified by Grid Operator"
        ivQrCode.alpha = 0.3f
    }

    private fun setLoading(loading: Boolean) {
        if (loading) {
            pbQrLoading.visibility = View.VISIBLE
            ivQrCode.visibility = View.INVISIBLE
        } else {
            pbQrLoading.visibility = View.GONE
            ivQrCode.visibility = View.VISIBLE
        }
    }
}
