// File:        TransferReceiptActivity.kt
// Component:   Booking Views & Grid Operator Verification
// Description: Receipt shown after PUT /api/reservations/{id}/finalize succeeds. Confirms the
//              booking is now Completed and leads to the next scan or back to the console.
// Author:      Gunathilaka K.K.N.M.

package com.example.microgridsystem.ui.operator

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.microgridsystem.R
import com.example.microgridsystem.models.FinalizeReservationResponse
import com.example.microgridsystem.models.VerifyQrResponse
import com.example.microgridsystem.util.SessionManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class TransferReceiptActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESULT = "com.example.microgridsystem.extra.FINALIZE_RESULT"
        const val EXTRA_BOOKING = "com.example.microgridsystem.extra.VERIFIED_BOOKING"
    }

    // Shows the finalised booking details.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpsUi.enableEdgeToEdge(this)
        setContentView(R.layout.activity_transfer_receipt)

        val session = SessionManager(this)
        if (!session.isLoggedIn()) {
            OpsUi.goToLogin(this)
            return
        }

        OpsUi.applySystemBarPadding(findViewById(R.id.receiptRoot))
        OpsStepper(findViewById(R.id.stepper)).setStep(4)

        @Suppress("DEPRECATION")
        val result = intent.getSerializableExtra(EXTRA_RESULT) as? FinalizeReservationResponse
        @Suppress("DEPRECATION")
        val booking = intent.getSerializableExtra(EXTRA_BOOKING) as? VerifyQrResponse

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { openConsole() }
        onBackPressedDispatcher.addCallback(this) { openConsole() }
        findViewById<MaterialButton>(R.id.btnBackToConsole).setOnClickListener { openConsole() }
        findViewById<MaterialButton>(R.id.btnScanNext).setOnClickListener { scanNext() }

        render(result, booking, session.getNic())
    }

    // Fills the receipt from the finalize response, falling back to the verified booking.
    private fun render(result: FinalizeReservationResponse?, booking: VerifyQrResponse?, operatorNic: String?) {
        val reservation = result?.reservation
        val placeholder = getString(R.string.ops_placeholder)

        val completedAt = OpsFormat.parse(reservation?.completedAt)
        findViewById<TextView>(R.id.tvReceiptSubtitle).text = if (completedAt != null) {
            getString(R.string.ops_receipt_subtitle, OpsFormat.time(completedAt))
        } else {
            getString(R.string.ops_receipt_subtitle_no_time)
        }

        OpsUi.statusBadge(findViewById(R.id.tvReceiptStatus), reservation?.status ?: "Completed")

        val prosumer = booking?.prosumer
        val prosumerName = prosumer?.fullName?.takeIf { it.isNotBlank() }
            ?: prosumer?.nic ?: reservation?.nic ?: placeholder
        val nic = prosumer?.nic ?: reservation?.nic ?: placeholder
        val station = reservation?.stationName?.takeIf { it.isNotBlank() }
            ?: booking?.station?.name ?: getString(R.string.ops_unknown_station)

        val start = OpsFormat.parse(reservation?.slotStartTime ?: booking?.slotStartTime)
        val end = OpsFormat.parse(reservation?.slotEndTime ?: booking?.slotEndTime)
        val slot = if (start != null) {
            getString(R.string.ops_date_and_range, OpsFormat.shortDate(start), OpsFormat.slotRange(this, start, end))
        } else {
            getString(R.string.ops_not_scheduled)
        }

        val delivered = reservation?.deliveredKWh
        val deliveredText = if (delivered != null && delivered > 0) {
            getString(R.string.ops_energy_value, OpsFormat.number(delivered))
        } else {
            getString(R.string.ops_not_recorded)
        }

        val rows = findViewById<LinearLayout>(R.id.receiptRows)
        OpsUi.addDetailRow(rows, getString(R.string.ops_label_prosumer), prosumerName)
        OpsUi.addDetailRow(rows, getString(R.string.ops_label_nic), nic)
        OpsUi.addDetailRow(rows, getString(R.string.ops_label_station), station)
        OpsUi.addDetailRow(rows, getString(R.string.ops_label_slot), slot)
        OpsUi.addDetailRow(
            rows, getString(R.string.ops_label_reserved_energy),
            OpsFormat.energy(this, reservation?.energyKWh ?: booking?.energyKWh)
        )
        OpsUi.addDetailRow(rows, getString(R.string.ops_label_delivered), deliveredText)
        OpsUi.addDetailRow(
            rows, getString(R.string.ops_label_completed_at),
            completedAt?.let { OpsFormat.dateTime(it) } ?: placeholder
        )
        OpsUi.addDetailRow(rows, getString(R.string.ops_label_completed_by), reservation?.completedByNic ?: operatorNic ?: placeholder)
        OpsUi.addDetailRow(
            rows, getString(R.string.ops_label_reservation_id),
            reservation?.id ?: booking?.reservationId ?: placeholder, monospace = true
        )
    }

    // Returns to the scanner for the next prosumer.
    private fun scanNext() {
        startActivity(
            Intent(this, ScanQrActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    // Returns to the operator console, which refreshes its counts when it resumes.
    private fun openConsole() {
        startActivity(
            Intent(this, OperatorConsoleActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
