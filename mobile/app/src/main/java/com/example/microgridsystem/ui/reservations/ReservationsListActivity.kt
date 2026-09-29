package com.example.microgridsystem.ui.reservations

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.microgridsystem.R
import com.example.microgridsystem.models.ApiGenericResponse
import com.example.microgridsystem.models.ReservationDashboardResponse
import com.example.microgridsystem.models.ReservationResponse
import com.example.microgridsystem.network.ApiErrorParser
import com.example.microgridsystem.network.RetrofitClient
import com.example.microgridsystem.storage.SessionManager
import com.example.microgridsystem.utils.DateTimeUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ReservationsListActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var adapter: ReservationAdapter

    private lateinit var tvProsumerNicLabel: TextView
    private lateinit var tvTotalCount: TextView
    private lateinit var tvActiveCount: TextView
    private lateinit var tvPendingCount: TextView
    private lateinit var etSearchQuery: TextInputEditText
    private lateinit var chipGroupStatus: ChipGroup
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var rvReservations: RecyclerView
    private lateinit var layoutLoading: LinearLayout
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var layoutError: LinearLayout
    private lateinit var tvErrorMessage: TextView
    private lateinit var btnRetry: MaterialButton
    private lateinit var btnEmptyBookNow: MaterialButton
    private lateinit var fabNewReservation: ExtendedFloatingActionButton
    private lateinit var btnHeaderRefresh: MaterialButton
    private lateinit var btnConfigSession: MaterialButton

    private var allReservations: List<ReservationResponse> = emptyList()
    private var currentFilterStatus: String? = null // null means "All"
    private var currentSearchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reservations_list)

        sessionManager = SessionManager.getInstance(this)

        initViews()
        setupListeners()
        setupRecyclerView()
    }

    override fun onResume() {
        super.onResume()
        updateHeaderNic()
        loadData()
    }

    private fun initViews() {
        tvProsumerNicLabel = findViewById(R.id.tvProsumerNicLabel)
        tvTotalCount = findViewById(R.id.tvTotalCount)
        tvActiveCount = findViewById(R.id.tvActiveCount)
        tvPendingCount = findViewById(R.id.tvPendingCount)
        etSearchQuery = findViewById(R.id.etSearchQuery)
        chipGroupStatus = findViewById(R.id.chipGroupStatus)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        rvReservations = findViewById(R.id.rvReservations)
        layoutLoading = findViewById(R.id.layoutLoading)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        layoutError = findViewById(R.id.layoutError)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)
        btnRetry = findViewById(R.id.btnRetry)
        btnEmptyBookNow = findViewById(R.id.btnEmptyBookNow)
        fabNewReservation = findViewById(R.id.fabNewReservation)
        btnHeaderRefresh = findViewById(R.id.btnHeaderRefresh)
    }

    private fun updateHeaderNic() {
        val nic = sessionManager.getProsumerNic()
        if (nic.isNotBlank()) {
            tvProsumerNicLabel.text = "Prosumer: $nic"
        } else {
            tvProsumerNicLabel.text = "Prosumer: Not Set"
        }
    }

    private fun setupRecyclerView() {
        adapter = ReservationAdapter(
            reservations = emptyList(),
            onItemClick = { reservation ->
                val intent = Intent(this, ReservationDetailsActivity::class.java).apply {
                    putExtra("EXTRA_RESERVATION", reservation)
                }
                startActivity(intent)
            },
            onQrClick = { reservation ->
                val intent = Intent(this, ReservationQrActivity::class.java).apply {
                    putExtra("EXTRA_RESERVATION_ID", reservation.id)
                    putExtra("EXTRA_RESERVATION", reservation)
                }
                startActivity(intent)
            },
            onModifyClick = { reservation ->
                val intent = Intent(this, ModifyReservationActivity::class.java).apply {
                    putExtra("EXTRA_RESERVATION", reservation)
                }
                startActivity(intent)
            },
            onCancelClick = { reservation ->
                showCancelConfirmationDialog(reservation)
            }
        )

        rvReservations.layoutManager = LinearLayoutManager(this)
        rvReservations.adapter = adapter
    }

    private fun setupListeners() {
        swipeRefreshLayout.setOnRefreshListener {
            loadData()
        }

        btnHeaderRefresh.setOnClickListener {
            loadData()
        }

        btnRetry.setOnClickListener {
            loadData()
        }

        btnEmptyBookNow.setOnClickListener {
            startActivity(Intent(this, NewReservationActivity::class.java))
        }

        fabNewReservation.setOnClickListener {
            startActivity(Intent(this, NewReservationActivity::class.java))
        }

        // Search text watcher
        etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                applyLocalSearchFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Filter chips listener
        chipGroupStatus.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            currentFilterStatus = when (checkedIds.first()) {
                R.id.chipPending -> "Pending"
                R.id.chipApproved -> "Approved"
                R.id.chipCompleted -> "Completed"
                R.id.chipCancelled -> "Cancelled"
                else -> null
            }
            fetchReservationsList()
        }
    }

    private fun loadData() {
        fetchDashboardCounts()
        fetchReservationsList()
    }

    /**
     * Requirement 1: Fetch GET /api/reservations/dashboard/{nic} for counts
     */
    private fun fetchDashboardCounts() {
        val nic = sessionManager.getProsumerNic()
        if (nic.isBlank()) {
            tvTotalCount.text = "0"
            tvActiveCount.text = "0"
            tvPendingCount.text = "0"
            return
        }

        val token = sessionManager.getBearerToken()
        RetrofitClient.instance.getReservationDashboard(token, nic)
            .enqueue(object : Callback<ReservationDashboardResponse> {
                override fun onResponse(
                    call: Call<ReservationDashboardResponse>,
                    response: Response<ReservationDashboardResponse>
                ) {
                    if (response.isSuccessful) {
                        val dashboard = response.body()
                        if (dashboard != null) {
                            tvActiveCount.text = dashboard.activeCount.toString()
                            tvPendingCount.text = dashboard.pendingCount.toString()
                        }
                    }
                }

                override fun onFailure(call: Call<ReservationDashboardResponse>, t: Throwable) {
                    // Soft failure, counts will remain as is
                }
            })
    }

    /**
     * Requirement 1: Fetch GET /api/reservations?prosumerNic={nic}&status={status}
     */
    private fun fetchReservationsList() {
        val token = sessionManager.getBearerToken()
        val nic = sessionManager.getProsumerNic().ifBlank { null }

        showLoading()

        RetrofitClient.instance.getReservations(
            token = token,
            prosumerNic = nic,
            status = currentFilterStatus
        ).enqueue(object : Callback<List<ReservationResponse>> {
            override fun onResponse(
                call: Call<List<ReservationResponse>>,
                response: Response<List<ReservationResponse>>
            ) {
                swipeRefreshLayout.isRefreshing = false

                if (response.isSuccessful) {
                    allReservations = response.body() ?: emptyList()
                    tvTotalCount.text = allReservations.size.toString()
                    applyLocalSearchFilter()
                } else {
                    val errorMsg = ApiErrorParser.parseError(response)
                    showError(errorMsg)
                }
            }

            override fun onFailure(call: Call<List<ReservationResponse>>, t: Throwable) {
                swipeRefreshLayout.isRefreshing = false
                showError("Network connection failed. Please verify API is running and check network.")
            }
        })
    }

    private fun applyLocalSearchFilter() {
        val filtered = if (currentSearchQuery.isBlank()) {
            allReservations
        } else {
            allReservations.filter { r ->
                r.stationName.contains(currentSearchQuery, ignoreCase = true) ||
                        r.nodeId.contains(currentSearchQuery, ignoreCase = true) ||
                        r.id.contains(currentSearchQuery, ignoreCase = true) ||
                        r.slotStartTime.contains(currentSearchQuery, ignoreCase = true)
            }
        }

        if (filtered.isEmpty()) {
            showEmpty()
        } else {
            showContent()
            adapter.updateData(filtered)
        }
    }

    private fun showCancelConfirmationDialog(reservation: ReservationResponse) {
        val isLessThan12h = DateTimeUtils.isLessThan12HoursAway(reservation.slotStartTime)
        val warningExtra = if (isLessThan12h) {
            "\n\n⚠️ WARNING: This slot is scheduled in less than 12 hours. Microgrid rules may reject this cancellation."
        } else ""

        MaterialAlertDialogBuilder(this)
            .setTitle("Cancel Reservation?")
            .setMessage("Are you sure you want to cancel the reservation for ${reservation.stationName} (${reservation.energyKWh} kWh)?$warningExtra")
            .setPositiveButton("Confirm Cancel") { _, _ ->
                performCancel(reservation.id)
            }
            .setNegativeButton("Keep Booking", null)
            .show()
    }

    private fun performCancel(id: String) {
        val token = sessionManager.getBearerToken()
        showLoading()

        RetrofitClient.instance.cancelReservation(token, id)
            .enqueue(object : Callback<ApiGenericResponse> {
                override fun onResponse(
                    call: Call<ApiGenericResponse>,
                    response: Response<ApiGenericResponse>
                ) {
                    if (response.isSuccessful) {
                        Toast.makeText(
                            this@ReservationsListActivity,
                            "Reservation cancelled successfully.",
                            Toast.LENGTH_LONG
                        ).show()
                        loadData()
                    } else {
                        val errMsg = ApiErrorParser.parseError(response)
                        showError(errMsg)
                        Toast.makeText(this@ReservationsListActivity, errMsg, Toast.LENGTH_LONG).show()
                    }
                }

                override fun onFailure(call: Call<ApiGenericResponse>, t: Throwable) {
                    showError("Network failure while cancelling reservation.")
                }
            })
    }

    private fun showLoading() {
        layoutLoading.visibility = View.VISIBLE
        layoutEmpty.visibility = View.GONE
        layoutError.visibility = View.GONE
        rvReservations.visibility = View.GONE
    }

    private fun showContent() {
        layoutLoading.visibility = View.GONE
        layoutEmpty.visibility = View.GONE
        layoutError.visibility = View.GONE
        rvReservations.visibility = View.VISIBLE
    }

    private fun showEmpty() {
        layoutLoading.visibility = View.GONE
        layoutEmpty.visibility = View.VISIBLE
        layoutError.visibility = View.GONE
        rvReservations.visibility = View.GONE
    }

    private fun showError(message: String) {
        layoutLoading.visibility = View.GONE
        layoutEmpty.visibility = View.GONE
        layoutError.visibility = View.VISIBLE
        rvReservations.visibility = View.GONE
        tvErrorMessage.text = message
    }
}
