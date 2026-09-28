package com.example.microgridsystem.ui.reservations

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.microgridsystem.R
import com.example.microgridsystem.models.CreateReservationRequest
import com.example.microgridsystem.models.NodeResponse
import com.example.microgridsystem.models.ReservationResponse
import com.example.microgridsystem.network.ApiErrorParser
import com.example.microgridsystem.network.RetrofitClient
import com.example.microgridsystem.storage.SessionManager
import com.example.microgridsystem.utils.DateTimeUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Calendar

class NewReservationActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    private lateinit var toolbar: MaterialToolbar
    private lateinit var etProsumerNic: TextInputEditText
    private lateinit var actvStation: AutoCompleteTextView
    private lateinit var etEnergyKWh: TextInputEditText
    private lateinit var btnPickStartDate: MaterialButton
    private lateinit var btnPickStartTime: MaterialButton
    private lateinit var tvSelectedStartDisplay: TextView
    private lateinit var btnPickEndDate: MaterialButton
    private lateinit var btnPickEndTime: MaterialButton
    private lateinit var tvSelectedEndDisplay: TextView
    private lateinit var tvSanityError: TextView
    private lateinit var btnSubmitReservation: MaterialButton
    private lateinit var pbSubmit: ProgressBar

    private var availableNodes: List<NodeResponse> = emptyList()
    private var selectedNode: NodeResponse? = null

    private var startCalendar: Calendar = Calendar.getInstance().apply {
        add(Calendar.HOUR_OF_DAY, 2)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
    }

    private var endCalendar: Calendar = Calendar.getInstance().apply {
        add(Calendar.HOUR_OF_DAY, 4)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_reservation)

        sessionManager = SessionManager.getInstance(this)

        initViews()
        setupToolbar()
        setupDateTimePickers()
        fetchNodes()
        setupSubmitButton()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        etProsumerNic = findViewById(R.id.etProsumerNic)
        actvStation = findViewById(R.id.actvStation)
        etEnergyKWh = findViewById(R.id.etEnergyKWh)
        btnPickStartDate = findViewById(R.id.btnPickStartDate)
        btnPickStartTime = findViewById(R.id.btnPickStartTime)
        tvSelectedStartDisplay = findViewById(R.id.tvSelectedStartDisplay)
        btnPickEndDate = findViewById(R.id.btnPickEndDate)
        btnPickEndTime = findViewById(R.id.btnPickEndTime)
        tvSelectedEndDisplay = findViewById(R.id.tvSelectedEndDisplay)
        tvSanityError = findViewById(R.id.tvSanityError)
        btnSubmitReservation = findViewById(R.id.btnSubmitReservation)
        pbSubmit = findViewById(R.id.pbSubmit)

        // Pre-fill prosumer NIC from session
        val currentNic = sessionManager.getProsumerNic()
        if (currentNic.isNotBlank()) {
            etProsumerNic.setText(currentNic)
        }
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupDateTimePickers() {
        updateStartDisplay()
        updateEndDisplay()

        btnPickStartDate.setOnClickListener {
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

        btnPickStartTime.setOnClickListener {
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

        btnPickEndDate.setOnClickListener {
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

        btnPickEndTime.setOnClickListener {
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
        tvSelectedStartDisplay.text = "Start: ${DateTimeUtils.formatCalendarToIso(startCalendar)}"
        validateSanityTimes()
    }

    private fun updateEndDisplay() {
        tvSelectedEndDisplay.text = "End: ${DateTimeUtils.formatCalendarToIso(endCalendar)}"
        validateSanityTimes()
    }

    private fun validateSanityTimes(): Boolean {
        if (endCalendar.timeInMillis <= startCalendar.timeInMillis) {
            tvSanityError.text = "⚠️ Slot end time must be after slot start time."
            tvSanityError.visibility = View.VISIBLE
            return false
        } else {
            tvSanityError.visibility = View.GONE
            return true
        }
    }

    /**
     * Requirement 2: Fetch station/node list from API (GET /api/nodes)
     */
    private fun fetchNodes() {
        val token = sessionManager.getBearerToken()
        RetrofitClient.instance.getAllNodes(token).enqueue(object : Callback<List<NodeResponse>> {
            override fun onResponse(
                call: Call<List<NodeResponse>>,
                response: Response<List<NodeResponse>>
            ) {
                if (response.isSuccessful) {
                    availableNodes = response.body() ?: emptyList()
                    val stationLabels = availableNodes.map { node ->
                        "${node.stationName} (${node.availableBatterySlots}/${node.totalBatterySlots} slots)"
                    }
                    val dropdownAdapter = ArrayAdapter(
                        this@NewReservationActivity,
                        android.R.layout.simple_dropdown_item_1line,
                        stationLabels
                    )
                    actvStation.setAdapter(dropdownAdapter)

                    actvStation.setOnItemClickListener { _, _, position, _ ->
                        if (position in availableNodes.indices) {
                            selectedNode = availableNodes[position]
                        }
                    }

                    if (availableNodes.isNotEmpty()) {
                        selectedNode = availableNodes.first()
                        actvStation.setText(stationLabels.first(), false)
                    }
                } else {
                    Toast.makeText(
                        this@NewReservationActivity,
                        "Failed to load station list from API.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onFailure(call: Call<List<NodeResponse>>, t: Throwable) {
                Toast.makeText(
                    this@NewReservationActivity,
                    "Network error loading station nodes.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }

    private fun setupSubmitButton() {
        btnSubmitReservation.setOnClickListener {
            val nic = etProsumerNic.text.toString().trim()
            val energyStr = etEnergyKWh.text.toString().trim()

            if (nic.isBlank()) {
                etProsumerNic.error = "Prosumer NIC is required"
                return@setOnClickListener
            }

            if (selectedNode == null) {
                actvStation.error = "Please select a microgrid station"
                return@setOnClickListener
            }

            val energy = energyStr.toDoubleOrNull()
            if (energy == null || energy <= 0) {
                etEnergyKWh.error = "Enter a valid positive energy volume (kWh)"
                return@setOnClickListener
            }

            if (!validateSanityTimes()) {
                return@setOnClickListener
            }

            createReservation(nic, selectedNode!!.id, selectedNode!!.stationName, energy)
        }
    }

    private fun createReservation(nic: String, nodeId: String, stationName: String, energyKWh: Double) {
        setLoading(true)

        val startIso = DateTimeUtils.formatCalendarToIso(startCalendar)
        val endIso = DateTimeUtils.formatCalendarToIso(endCalendar)

        val request = CreateReservationRequest(
            prosumerNic = nic,
            nodeId = nodeId,
            energyKWh = energyKWh,
            slotStartTime = startIso,
            slotEndTime = endIso,
            stationName = stationName
        )

        val token = sessionManager.getBearerToken()
        RetrofitClient.instance.createReservation(token, request)
            .enqueue(object : Callback<ReservationResponse> {
                override fun onResponse(
                    call: Call<ReservationResponse>,
                    response: Response<ReservationResponse>
                ) {
                    setLoading(false)
                    if (response.isSuccessful && response.body() != null) {
                        val created = response.body()!!
                        Toast.makeText(
                            this@NewReservationActivity,
                            "Reservation created successfully!",
                            Toast.LENGTH_LONG
                        ).show()

                        // Open Confirmation / Details Screen
                        val intent = Intent(this@NewReservationActivity, ReservationDetailsActivity::class.java).apply {
                            putExtra("EXTRA_RESERVATION", created)
                            putExtra("EXTRA_IS_NEW", true)
                        }
                        startActivity(intent)
                        finish()
                    } else {
                        val errorMessage = ApiErrorParser.parseError(response)
                        tvSanityError.text = "Server Error: $errorMessage"
                        tvSanityError.visibility = View.VISIBLE
                        Toast.makeText(this@NewReservationActivity, errorMessage, Toast.LENGTH_LONG).show()
                    }
                }

                override fun onFailure(call: Call<ReservationResponse>, t: Throwable) {
                    setLoading(false)
                    tvSanityError.text = "Connection failed. Could not communicate with server."
                    tvSanityError.visibility = View.VISIBLE
                    Toast.makeText(this@NewReservationActivity, "Network request failed.", Toast.LENGTH_LONG).show()
                }
            })
    }

    private fun setLoading(loading: Boolean) {
        if (loading) {
            btnSubmitReservation.visibility = View.INVISIBLE
            pbSubmit.visibility = View.VISIBLE
        } else {
            btnSubmitReservation.visibility = View.VISIBLE
            pbSubmit.visibility = View.GONE
        }
    }
}
