package com.example.microgridsystem.ui.reservations

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.microgridsystem.R
import com.example.microgridsystem.models.ReservationResponse
import com.example.microgridsystem.models.UpdateReservationRequest
import com.example.microgridsystem.network.ApiErrorParser
import com.example.microgridsystem.network.RetrofitClient
import com.example.microgridsystem.storage.SessionManager
import com.example.microgridsystem.utils.DateTimeUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Calendar

class ModifyReservationActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private var reservation: ReservationResponse? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var layoutTwelveHourWarning: LinearLayout
    private lateinit var tvTwelveHourWarningText: TextView
    private lateinit var tvCurrentStation: TextView
    private lateinit var tvCurrentEnergy: TextView
    private lateinit var tvCurrentSlotTime: TextView
    private lateinit var btnPickModifyStartDate: MaterialButton
    private lateinit var btnPickModifyStartTime: MaterialButton
    private lateinit var tvModifySelectedStart: TextView
    private lateinit var btnPickModifyEndDate: MaterialButton
    private lateinit var btnPickModifyEndTime: MaterialButton
    private lateinit var tvModifySelectedEnd: TextView
    private lateinit var tvModifySlotCapacityHint: TextView
    private lateinit var tvModifyError: TextView
    private lateinit var btnSubmitUpdate: MaterialButton
    private lateinit var pbUpdate: ProgressBar

    private var startCalendar: Calendar = Calendar.getInstance()
    private var endCalendar: Calendar = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_modify_reservation)

        sessionManager = SessionManager.getInstance(this)
        @Suppress("DEPRECATION")
        reservation = intent.getSerializableExtra("EXTRA_RESERVATION") as? ReservationResponse

        if (reservation == null) {
            Toast.makeText(this, "Reservation not found.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupToolbar()
        populateCurrentDetails()
        setupDateTimePickers()
        setupSubmitButton()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        layoutTwelveHourWarning = findViewById(R.id.layoutTwelveHourWarning)
        tvTwelveHourWarningText = findViewById(R.id.tvTwelveHourWarningText)
        tvCurrentStation = findViewById(R.id.tvCurrentStation)
        tvCurrentEnergy = findViewById(R.id.tvCurrentEnergy)
        tvCurrentSlotTime = findViewById(R.id.tvCurrentSlotTime)
        btnPickModifyStartDate = findViewById(R.id.btnPickModifyStartDate)
        btnPickModifyStartTime = findViewById(R.id.btnPickModifyStartTime)
        tvModifySelectedStart = findViewById(R.id.tvModifySelectedStart)
        btnPickModifyEndDate = findViewById(R.id.btnPickModifyEndDate)
        btnPickModifyEndTime = findViewById(R.id.btnPickModifyEndTime)
        tvModifySelectedEnd = findViewById(R.id.tvModifySelectedEnd)
        tvModifySlotCapacityHint = findViewById(R.id.tvModifySlotCapacityHint)
        tvModifyError = findViewById(R.id.tvModifyError)
        btnSubmitUpdate = findViewById(R.id.btnSubmitUpdate)
        pbUpdate = findViewById(R.id.pbUpdate)
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun populateCurrentDetails() {
        val r = reservation ?: return
        tvCurrentStation.text = "Station: ${if (r.stationName.isNotBlank()) r.stationName else r.nodeId}"
        tvCurrentEnergy.text = String.format(java.util.Locale.US, "Volume: %.1f kWh", r.energyKWh)

        val startStr = DateTimeUtils.formatIsoToDisplay(r.slotStartTime)
        val endStr = DateTimeUtils.formatIsoToTimeOnly(r.slotEndTime)
        tvCurrentSlotTime.text = "Slot: $startStr - $endStr"

        // Initialize picker calendars with existing slot time or tomorrow
        val startDate = DateTimeUtils.parseIsoToDate(r.slotStartTime)
        val endDate = DateTimeUtils.parseIsoToDate(r.slotEndTime)

        if (startDate != null) {
            startCalendar.time = startDate
        } else {
            startCalendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        if (endDate != null) {
            endCalendar.time = endDate
        } else {
            endCalendar.time = startCalendar.time
            endCalendar.add(Calendar.HOUR_OF_DAY, 2)
        }

        // Soft client check for 12-hour notice
        val isLessThan12h = DateTimeUtils.isLessThan12HoursAway(r.slotStartTime)
        if (isLessThan12h) {
            layoutTwelveHourWarning.visibility = View.VISIBLE
            val hours = DateTimeUtils.getHoursUntilSlot(r.slotStartTime)
            tvTwelveHourWarningText.text = "⚠️ Notice: Slot begins in ~${hours}h (<12h notice rule). Server policy may reject modifications."
        } else {
            layoutTwelveHourWarning.visibility = View.GONE
        }
    }

    private fun setupDateTimePickers() {
        updateStartDisplay()
        updateEndDisplay()

        btnPickModifyStartDate.setOnClickListener {
            val datePicker = DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    startCalendar.set(Calendar.YEAR, year)
                    startCalendar.set(Calendar.MONTH, month)
                    startCalendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    updateStartDisplay()
                },
                startCalendar.get(Calendar.YEAR),
                startCalendar.get(Calendar.MONTH),
                startCalendar.get(Calendar.DAY_OF_MONTH)
            )
            datePicker.show()
        }

        btnPickModifyStartTime.setOnClickListener {
            val timePicker = TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    startCalendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    startCalendar.set(Calendar.MINUTE, minute)
                    updateStartDisplay()
                },
                startCalendar.get(Calendar.HOUR_OF_DAY),
                startCalendar.get(Calendar.MINUTE),
                false
            )
            timePicker.show()
        }

        btnPickModifyEndDate.setOnClickListener {
            val datePicker = DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    endCalendar.set(Calendar.YEAR, year)
                    endCalendar.set(Calendar.MONTH, month)
                    endCalendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    updateEndDisplay()
                },
                endCalendar.get(Calendar.YEAR),
                endCalendar.get(Calendar.MONTH),
                endCalendar.get(Calendar.DAY_OF_MONTH)
            )
            datePicker.show()
        }

        btnPickModifyEndTime.setOnClickListener {
            val timePicker = TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    endCalendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    endCalendar.set(Calendar.MINUTE, minute)
                    updateEndDisplay()
                },
                endCalendar.get(Calendar.HOUR_OF_DAY),
                endCalendar.get(Calendar.MINUTE),
                false
            )
            timePicker.show()
        }
    }

    private fun updateStartDisplay() {
        tvModifySelectedStart.text = "New Start: ${DateTimeUtils.formatCalendarToIso(startCalendar)}"
        validateSanity()
        fetchSlotCapacity()
    }

    private fun updateEndDisplay() {
        tvModifySelectedEnd.text = "New End: ${DateTimeUtils.formatCalendarToIso(endCalendar)}"
        validateSanity()
        fetchSlotCapacity()
    }

    private fun validateSanity(): Boolean {
        if (endCalendar.timeInMillis <= startCalendar.timeInMillis) {
            tvModifyError.text = "⚠️ Slot end time must be after slot start time."
            tvModifyError.visibility = View.VISIBLE
            return false
        } else {
            tvModifyError.visibility = View.GONE
            return true
        }
    }

    private fun fetchSlotCapacity() {
        val r = reservation ?: return
        val token = sessionManager.getBearerToken()
        if (token.isBlank() || r.nodeId.isBlank()) return

        val startIso = DateTimeUtils.formatCalendarToIso(startCalendar)
        val endIso = DateTimeUtils.formatCalendarToIso(endCalendar)

        RetrofitClient.instance.getSlotsByNode(token, r.nodeId)
            .enqueue(object : Callback<List<com.example.microgridsystem.models.SlotResponse>> {
                override fun onResponse(
                    call: Call<List<com.example.microgridsystem.models.SlotResponse>>,
                    response: Response<List<com.example.microgridsystem.models.SlotResponse>>
                ) {
                    if (response.isSuccessful) {
                        val slots = response.body() ?: emptyList()
                        val matching = slots.find { slot ->
                            slot.slotStartTime == startIso && slot.slotEndTime == endIso
                        }
                        if (matching != null) {
                            tvModifySlotCapacityHint.text = "⚡ Target Slot Capacity: %.1f kWh (%d of %d slots free)".format(
                                java.util.Locale.US,
                                matching.availableCapacityKWh,
                                matching.availableSlotCount,
                                matching.totalSlotCount
                            )
                            tvModifySlotCapacityHint.visibility = View.VISIBLE
                        } else {
                            tvModifySlotCapacityHint.visibility = View.GONE
                        }
                    }
                }

                override fun onFailure(call: Call<List<com.example.microgridsystem.models.SlotResponse>>, t: Throwable) {
                    // Non-blocking: silently skip
                }
            })
    }

    private fun setupSubmitButton() {
        btnSubmitUpdate.setOnClickListener {
            val r = reservation ?: return@setOnClickListener
            if (!validateSanity()) return@setOnClickListener

            val newStartIso = DateTimeUtils.formatCalendarToIso(startCalendar)
            val newEndIso = DateTimeUtils.formatCalendarToIso(endCalendar)

            performUpdate(r.id, newStartIso, newEndIso)
        }
    }

    private fun performUpdate(id: String, startIso: String, endIso: String) {
        setLoading(true)

        val request = UpdateReservationRequest(
            slotStartTime = startIso,
            slotEndTime = endIso
        )

        val token = sessionManager.getBearerToken()
        RetrofitClient.instance.updateReservation(token, id, request)
            .enqueue(object : Callback<ReservationResponse> {
                override fun onResponse(
                    call: Call<ReservationResponse>,
                    response: Response<ReservationResponse>
                ) {
                    setLoading(false)
                    if (response.isSuccessful && response.body() != null) {
                        val updated = response.body()!!
                        Toast.makeText(
                            this@ModifyReservationActivity,
                            "Slot updated successfully!",
                            Toast.LENGTH_LONG
                        ).show()

                        val intent = Intent(this@ModifyReservationActivity, ReservationDetailsActivity::class.java).apply {
                            putExtra("EXTRA_RESERVATION", updated)
                            putExtra("EXTRA_IS_MODIFIED", true)
                        }
                        startActivity(intent)
                        finish()
                    } else {
                        val errorMsg = ApiErrorParser.parseError(response)
                        tvModifyError.text = "Server Error: $errorMsg"
                        tvModifyError.visibility = View.VISIBLE
                        Toast.makeText(this@ModifyReservationActivity, errorMsg, Toast.LENGTH_LONG).show()
                    }
                }

                override fun onFailure(call: Call<ReservationResponse>, t: Throwable) {
                    setLoading(false)
                    val errorMsg = "Network request failed. Could not reach server."
                    tvModifyError.text = errorMsg
                    tvModifyError.visibility = View.VISIBLE
                    Toast.makeText(this@ModifyReservationActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            })
    }

    private fun setLoading(loading: Boolean) {
        if (loading) {
            btnSubmitUpdate.visibility = View.INVISIBLE
            pbUpdate.visibility = View.VISIBLE
        } else {
            btnSubmitUpdate.visibility = View.VISIBLE
            pbUpdate.visibility = View.GONE
        }
    }
}
