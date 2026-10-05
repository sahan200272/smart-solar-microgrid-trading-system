// File:        VerifyBookingActivity.kt
// Component:   Booking Views & Grid Operator Verification
// Description: Checks a scanned booking QR code with the server
//              (POST /api/reservations/verify-qr), shows the prosumer and booking details for
//              the operator to confirm, and finalises the energy transfer
//              (PUT /api/reservations/{id}/finalize).
// Author:      Gunathilaka K.K.N.M.

package com.example.microgridsystem.ui.operator

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import com.example.microgridsystem.R
import com.example.microgridsystem.data.OpsCacheDb
import com.example.microgridsystem.models.FinalizeReservationRequest
import com.example.microgridsystem.models.FinalizeReservationResponse
import com.example.microgridsystem.models.VerifyQrRequest
import com.example.microgridsystem.models.VerifyQrResponse
import com.example.microgridsystem.network.RetrofitClient
import com.example.microgridsystem.util.SessionManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class VerifyBookingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QR_VALUE = "com.example.microgridsystem.extra.QR_VALUE"
        const val EXTRA_NODE_ID = "com.example.microgridsystem.extra.NODE_ID"

        private const val STATE_VERIFIED = "verified_booking"

        // Mirrors the API rule: delivered energy may exceed the reserved amount by at most 10%.
        private const val DELIVERY_TOLERANCE = 1.1
    }

    private enum class ScreenState { VERIFYING, VERIFIED, REJECTED }

    private lateinit var session: SessionManager
    private lateinit var cacheDb: OpsCacheDb
    private lateinit var stepper: OpsStepper

    private lateinit var rootView: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var scrollView: NestedScrollView
    private lateinit var stateVerifying: View
    private lateinit var stateVerified: View
    private lateinit var stateRejected: View

    private lateinit var tvVerifiedStatus: TextView
    private lateinit var tvProsumerAvatar: TextView
    private lateinit var tvProsumerName: TextView
    private lateinit var tvProsumerNic: TextView
    private lateinit var tvProsumerPhone: TextView
    private lateinit var tvStation: TextView
    private lateinit var tvReserved: TextView
    private lateinit var tvSlotDate: TextView
    private lateinit var tvSlotTime: TextView
    private lateinit var tvPhase: TextView
    private lateinit var tvReservationId: TextView
    private lateinit var btnCopyId: ImageButton
    private lateinit var tilDelivered: TextInputLayout
    private lateinit var etDelivered: TextInputEditText
    private lateinit var layoutFinalizeError: LinearLayout
    private lateinit var tvFinalizeError: TextView

    private lateinit var cardRejected: MaterialCardView
    private lateinit var ivRejectedIcon: ImageView
    private lateinit var tvRejectedTitle: TextView
    private lateinit var tvRejectedMessage: TextView
    private lateinit var tvRejectedBadge: TextView
    private lateinit var cardRejectedHint: MaterialCardView
    private lateinit var tvRejectedHint: TextView
    private lateinit var tvScannedCode: TextView

    private lateinit var actionBar: LinearLayout
    private lateinit var btnSecondary: MaterialButton
    private lateinit var btnPrimary: MaterialButton
    private var primaryIdleIcon: Drawable? = null

    private var qrValue = ""
    private var nodeId: String? = null
    private var verified: VerifyQrResponse? = null
    private var isBusy = false
    private var verifyCall: Call<VerifyQrResponse>? = null
    private var finalizeCall: Call<FinalizeReservationResponse>? = null

    // Leaving mid-finalise would hide the outcome, so Back waits until the request returns.
    private val backWhileBusy = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            Snackbar.make(rootView, R.string.ops_finalise_wait, Snackbar.LENGTH_SHORT)
                .setAnchorView(actionBar)
                .show()
        }
    }

    // Reads the scanned code and starts verification (or restores a verified booking).
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpsUi.enableEdgeToEdge(this)
        setContentView(R.layout.activity_verify_booking)

        session = SessionManager(this)
        if (!session.isLoggedIn()) {
            OpsUi.goToLogin(this)
            return
        }

        cacheDb = OpsCacheDb(this)
        qrValue = intent.getStringExtra(EXTRA_QR_VALUE).orEmpty()
        nodeId = intent.getStringExtra(EXTRA_NODE_ID)

        bindViews()
        OpsUi.applySystemBarPadding(rootView)

        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, backWhileBusy)

        btnCopyId.setOnClickListener { copyReservationId() }
        etDelivered.doAfterTextChanged { tilDelivered.error = null }
        etDelivered.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onFinaliseClicked()
                true
            } else {
                false
            }
        }

        tvScannedCode.text = getString(R.string.ops_scanned_code, qrValue)

        // After rotation, show the already verified booking instead of calling the API again
        @Suppress("DEPRECATION")
        val restored = savedInstanceState?.getSerializable(STATE_VERIFIED) as? VerifyQrResponse

        if (restored != null) {
            verified = restored
            renderVerified(restored)
        } else {
            verify()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        verified?.let { outState.putSerializable(STATE_VERIFIED, it) }
    }

    override fun onDestroy() {
        verifyCall?.cancel()
        finalizeCall?.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        rootView = findViewById(R.id.verifyRoot)
        toolbar = findViewById(R.id.toolbar)
        scrollView = findViewById(R.id.verifyScroll)
        stepper = OpsStepper(findViewById(R.id.stepper))
        stateVerifying = findViewById(R.id.stateVerifying)
        stateVerified = findViewById(R.id.stateVerified)
        stateRejected = findViewById(R.id.stateRejected)

        tvVerifiedStatus = findViewById(R.id.tvVerifiedStatus)
        tvProsumerAvatar = findViewById(R.id.tvProsumerAvatar)
        tvProsumerName = findViewById(R.id.tvProsumerName)
        tvProsumerNic = findViewById(R.id.tvProsumerNic)
        tvProsumerPhone = findViewById(R.id.tvProsumerPhone)
        tvStation = findViewById(R.id.tvStation)
        tvReserved = findViewById(R.id.tvReserved)
        tvSlotDate = findViewById(R.id.tvSlotDate)
        tvSlotTime = findViewById(R.id.tvSlotTime)
        tvPhase = findViewById(R.id.tvPhase)
        tvReservationId = findViewById(R.id.tvReservationId)
        btnCopyId = findViewById(R.id.btnCopyId)
        tilDelivered = findViewById(R.id.tilDelivered)
        etDelivered = findViewById(R.id.etDelivered)
        layoutFinalizeError = findViewById(R.id.layoutFinalizeError)
        tvFinalizeError = findViewById(R.id.tvFinalizeError)

        cardRejected = findViewById(R.id.cardRejected)
        ivRejectedIcon = findViewById(R.id.ivRejectedIcon)
        tvRejectedTitle = findViewById(R.id.tvRejectedTitle)
        tvRejectedMessage = findViewById(R.id.tvRejectedMessage)
        tvRejectedBadge = findViewById(R.id.tvRejectedBadge)
        cardRejectedHint = findViewById(R.id.cardRejectedHint)
        tvRejectedHint = findViewById(R.id.tvRejectedHint)
        tvScannedCode = findViewById(R.id.tvScannedCode)

        actionBar = findViewById(R.id.actionBar)
        btnSecondary = findViewById(R.id.btnSecondary)
        btnPrimary = findViewById(R.id.btnPrimary)
    }

    private fun showState(state: ScreenState) {
        stateVerifying.isVisible = state == ScreenState.VERIFYING
        stateVerified.isVisible = state == ScreenState.VERIFIED
        stateRejected.isVisible = state == ScreenState.REJECTED
        actionBar.isVisible = state != ScreenState.VERIFYING
        scrollView.scrollTo(0, 0)
    }

    // ---------- Verification ----------

    // Sends the scanned code to the server and shows the booking or the reason it was rejected.
    private fun verify() {
        showState(ScreenState.VERIFYING)
        stepper.setStep(2)

        verifyCall = RetrofitClient.getService(this)
            .verifyQr(session.getAuthHeader(), VerifyQrRequest(qrValue, nodeId))

        verifyCall?.enqueue(object : Callback<VerifyQrResponse> {
            override fun onResponse(call: Call<VerifyQrResponse>, response: Response<VerifyQrResponse>) {
                if (isFinishing || isDestroyed) return

                val body = response.body()
                if (response.isSuccessful && body != null && body.valid != false && !body.reservationId.isNullOrBlank()) {
                    verified = body
                    renderVerified(body)
                    return
                }

                val failure = if (response.isSuccessful) {
                    OpsFailure(response.code(), null, getString(R.string.ops_error_unexpected))
                } else {
                    OpsApiError.fromResponse(this@VerifyBookingActivity, response)
                }

                logRejection(failure)
                renderRejected(failure, canRetryVerify = true)
            }

            override fun onFailure(call: Call<VerifyQrResponse>, error: Throwable) {
                if (call.isCanceled || isFinishing || isDestroyed) return
                renderRejected(OpsApiError.fromThrowable(this@VerifyBookingActivity, error), canRetryVerify = true)
            }
        })
    }

    // Fills the prosumer and booking cards for the operator to check.
    private fun renderVerified(booking: VerifyQrResponse) {
        showState(ScreenState.VERIFIED)
        stepper.setStep(2)

        val name = prosumerName(booking)
        val nic = booking.prosumer?.nic
        val phone = booking.prosumer?.phone?.trim().orEmpty()

        tvProsumerAvatar.text = OpsFormat.initials(name)
        tvProsumerName.text = name
        tvProsumerNic.text = getString(R.string.ops_nic_value, nic ?: getString(R.string.ops_placeholder))

        tvProsumerPhone.isVisible = phone.isNotEmpty()
        tvProsumerPhone.text = phone
        tvProsumerPhone.contentDescription = getString(R.string.ops_action_call, phone)
        tvProsumerPhone.setOnClickListener { dial(phone) }

        OpsUi.statusBadge(tvVerifiedStatus, booking.status)

        val start = OpsFormat.parse(booking.slotStartTime)
        val end = OpsFormat.parse(booking.slotEndTime)
        val reserved = booking.energyKWh ?: 0.0

        tvStation.text = stationName(booking)
        tvReserved.text = OpsFormat.energy(this, reserved)
        tvSlotDate.text = start?.let { OpsFormat.date(it) } ?: getString(R.string.ops_not_scheduled)
        tvSlotTime.text = OpsFormat.slotRange(this, start, end)
        OpsUi.phaseBadge(tvPhase, OpsFormat.phase(this, start, end))
        tvReservationId.text = booking.reservationId

        tilDelivered.helperText = if (reserved > 0) {
            getString(R.string.ops_delivered_helper_max, OpsFormat.number(reserved * DELIVERY_TOLERANCE))
        } else {
            getString(R.string.ops_delivered_helper)
        }
        tilDelivered.placeholderText = if (reserved > 0) OpsFormat.number(reserved) else null
        setFinaliseError(null)

        configureActions(
            primaryLabel = getString(R.string.ops_action_finalise),
            primaryIcon = R.drawable.ic_bolt,
            primaryColor = R.color.ops_approved,
            onPrimary = { onFinaliseClicked() },
            secondaryLabel = getString(R.string.ops_action_cancel),
            onSecondary = { finish() }
        )
    }

    // Explains why the code was rejected and offers the next step.
    private fun renderRejected(failure: OpsFailure, canRetryVerify: Boolean) {
        showState(ScreenState.REJECTED)
        stepper.setStep(1)

        val details = OpsApiError.describe(failure)

        OpsUi.tintCard(cardRejected, details.tone)
        ivRejectedIcon.setImageResource(details.icon)
        ivRejectedIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, details.tone.foreground))
        tvRejectedTitle.setText(details.title)
        tvRejectedMessage.text = failure.message
        OpsUi.badge(tvRejectedBadge, getString(R.string.ops_badge_rejected), OpsTone.DANGER)

        cardRejectedHint.isVisible = details.hint != null
        details.hint?.let { tvRejectedHint.setText(it) }

        when {
            failure.status == 401 -> configureActions(
                primaryLabel = getString(R.string.ops_action_sign_in),
                primaryIcon = R.drawable.ic_lock,
                onPrimary = { OpsUi.signOut(this) }
            )
            canRetryVerify && failure.isRetryable -> configureActions(
                primaryLabel = getString(R.string.ops_action_try_again),
                primaryIcon = R.drawable.ic_refresh,
                onPrimary = { verify() },
                secondaryLabel = getString(R.string.ops_action_scan_another),
                onSecondary = { finish() }
            )
            else -> configureActions(
                primaryLabel = getString(R.string.ops_action_scan_another),
                primaryIcon = R.drawable.ic_qr,
                onPrimary = { finish() }
            )
        }
    }

    // Sets up the sticky action bar. The secondary button is hidden when no label is given.
    private fun configureActions(
        primaryLabel: String,
        primaryIcon: Int,
        onPrimary: () -> Unit,
        primaryColor: Int = R.color.primary,
        secondaryLabel: String? = null,
        onSecondary: (() -> Unit)? = null
    ) {
        btnPrimary.text = primaryLabel
        btnPrimary.setIconResource(primaryIcon)
        // Stay white while disabled during finalising (the button keeps its colour and shows a spinner)
        btnPrimary.setTextColor(Color.WHITE)
        btnPrimary.iconTint = ColorStateList.valueOf(Color.WHITE)
        btnPrimary.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, primaryColor))
        btnPrimary.setOnClickListener { onPrimary() }
        primaryIdleIcon = btnPrimary.icon

        btnSecondary.isVisible = secondaryLabel != null
        btnSecondary.text = secondaryLabel
        btnSecondary.setOnClickListener { onSecondary?.invoke() }
    }

    // ---------- Finalise ----------

    // Validates the delivered energy and asks for confirmation.
    private fun onFinaliseClicked() {
        val booking = verified ?: return
        if (isBusy) return

        val delivered = readDeliveredEnergy(booking.energyKWh ?: 0.0)
        if (delivered.isFailure) return

        confirmFinalise(booking, delivered.getOrNull())
    }

    // Reads the optional delivered kWh. Blank gives null; invalid input shows an error and fails.
    // The API applies the same rules and remains the final check.
    private fun readDeliveredEnergy(reserved: Double): Result<Double?> {
        val raw = etDelivered.text?.toString()?.trim()?.replace(',', '.').orEmpty()

        if (raw.isEmpty()) {
            tilDelivered.error = null
            return Result.success(null)
        }

        val value = raw.toDoubleOrNull()
        val maxAllowed = reserved * DELIVERY_TOLERANCE

        val error = when {
            value == null || value.isNaN() || value.isInfinite() || value <= 0 ->
                getString(R.string.ops_delivered_error_positive)
            raw.substringAfter('.', "").length > 2 ->
                getString(R.string.ops_delivered_error_decimals)
            reserved > 0 && value > maxAllowed + 1e-9 ->
                getString(R.string.ops_delivered_error_max, OpsFormat.number(maxAllowed))
            else -> null
        }

        if (error != null) {
            tilDelivered.error = error
            etDelivered.requestFocus()
            return Result.failure(IllegalArgumentException(error))
        }

        tilDelivered.error = null
        return Result.success(value)
    }

    // Shows what will be recorded before the irreversible finalise call.
    private fun confirmFinalise(booking: VerifyQrResponse, delivered: Double?) {
        val deliveredText = if (delivered == null) {
            getString(R.string.ops_not_recorded)
        } else {
            getString(R.string.ops_energy_value, OpsFormat.number(delivered))
        }

        val message = getString(
            R.string.ops_finalise_confirm_message,
            prosumerName(booking),
            stationName(booking),
            OpsFormat.energy(this, booking.energyKWh),
            deliveredText
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ops_finalise_confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.ops_action_cancel, null)
            .setPositiveButton(R.string.ops_action_finalise) { _, _ -> submitFinalise(booking, delivered) }
            .show()
    }

    // Marks the booking as completed on the server, then opens the receipt.
    private fun submitFinalise(booking: VerifyQrResponse, delivered: Double?) {
        val reservationId = booking.reservationId ?: return

        setBusy(true)
        setFinaliseError(null)

        finalizeCall = RetrofitClient.getService(this).finalizeReservation(
            session.getAuthHeader(),
            reservationId,
            FinalizeReservationRequest(qrValue, nodeId, delivered)
        )

        finalizeCall?.enqueue(object : Callback<FinalizeReservationResponse> {
            override fun onResponse(
                call: Call<FinalizeReservationResponse>,
                response: Response<FinalizeReservationResponse>
            ) {
                if (isFinishing || isDestroyed) return

                if (response.isSuccessful) {
                    val result = response.body()
                    cacheDb.logVerification(
                        operatorNic = session.getNic().orEmpty(),
                        reservationId = reservationId,
                        prosumerName = prosumerName(booking),
                        stationName = stationName(booking),
                        outcome = OpsCacheDb.OUTCOME_COMPLETED,
                        reason = null,
                        deliveredKWh = result?.reservation?.deliveredKWh ?: delivered
                    )
                    openReceipt(booking, result)
                    return
                }

                setBusy(false)
                val failure = OpsApiError.fromResponse(this@VerifyBookingActivity, response)

                // A reason code or an auth problem means this booking can't be finalised any more
                if (failure.reason != null || failure.status in listOf(401, 403, 404)) {
                    verified = null
                    logRejection(failure)
                    renderRejected(failure, canRetryVerify = false)
                } else {
                    // Validation or server problems: keep the booking on screen so the operator can retry
                    showFinaliseRetry(failure.message, booking, delivered)
                }
            }

            override fun onFailure(call: Call<FinalizeReservationResponse>, error: Throwable) {
                if (call.isCanceled || isFinishing || isDestroyed) return

                setBusy(false)
                showFinaliseRetry(
                    OpsApiError.fromThrowable(this@VerifyBookingActivity, error).message, booking, delivered
                )
            }
        })
    }

    private fun showFinaliseRetry(message: String, booking: VerifyQrResponse, delivered: Double?) {
        setFinaliseError(message)
        Snackbar.make(rootView, R.string.ops_finalise_failed, Snackbar.LENGTH_LONG)
            .setAnchorView(actionBar)
            .setAction(R.string.ops_action_retry) { submitFinalise(booking, delivered) }
            .show()
    }

    // Opens the receipt and closes this screen, so Back can't return to a used code.
    private fun openReceipt(booking: VerifyQrResponse, result: FinalizeReservationResponse?) {
        startActivity(
            Intent(this, TransferReceiptActivity::class.java)
                .putExtra(TransferReceiptActivity.EXTRA_BOOKING, booking)
                .putExtra(TransferReceiptActivity.EXTRA_RESULT, result)
        )
        finish()
    }

    // Shows a spinner on the finalise button and locks the form while the request runs.
    private fun setBusy(busy: Boolean) {
        isBusy = busy
        backWhileBusy.isEnabled = busy
        etDelivered.isEnabled = !busy
        btnSecondary.isEnabled = !busy
        btnPrimary.isEnabled = !busy

        if (busy) {
            btnPrimary.text = getString(R.string.ops_finalising)
            btnPrimary.icon = CircularProgressDrawable(this).apply {
                setStyle(CircularProgressDrawable.DEFAULT)
                strokeWidth = OpsUi.dp(this@VerifyBookingActivity, 2f).toFloat()
                centerRadius = OpsUi.dp(this@VerifyBookingActivity, 6f).toFloat()
                setColorSchemeColors(Color.WHITE)
                start()
            }
        } else {
            btnPrimary.text = getString(R.string.ops_action_finalise)
            btnPrimary.icon = primaryIdleIcon
        }
    }

    private fun setFinaliseError(message: String?) {
        layoutFinalizeError.isVisible = message != null
        tvFinalizeError.text = message
    }

    // ---------- Helpers ----------

    // Stores rejections decided by the server in the on-device verification log.
    private fun logRejection(failure: OpsFailure) {
        val reason = failure.reason ?: return
        val booking = verified

        cacheDb.logVerification(
            operatorNic = session.getNic().orEmpty(),
            reservationId = booking?.reservationId,
            prosumerName = booking?.let { prosumerName(it) },
            stationName = booking?.let { stationName(it) },
            outcome = OpsCacheDb.OUTCOME_REJECTED,
            reason = reason,
            deliveredKWh = null
        )
    }

    private fun prosumerName(booking: VerifyQrResponse): String {
        return booking.prosumer?.fullName?.takeIf { it.isNotBlank() }
            ?: booking.prosumer?.nic?.takeIf { it.isNotBlank() }
            ?: getString(R.string.ops_unknown_prosumer)
    }

    private fun stationName(booking: VerifyQrResponse): String {
        return booking.station?.name?.takeIf { it.isNotBlank() } ?: getString(R.string.ops_unknown_station)
    }

    private fun dial(phone: String) {
        val number = phone.filter { it.isDigit() || it == '+' }

        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(rootView, R.string.ops_no_dialer, Snackbar.LENGTH_SHORT).setAnchorView(actionBar).show()
        }
    }

    private fun copyReservationId() {
        val id = verified?.reservationId ?: return
        val clipboard = getSystemService(ClipboardManager::class.java)

        clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.ops_label_reservation_id), id))
        Snackbar.make(rootView, R.string.ops_copied_id, Snackbar.LENGTH_SHORT).setAnchorView(actionBar).show()
    }
}
