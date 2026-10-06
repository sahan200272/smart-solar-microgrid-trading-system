package com.example.microgridsystem

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.microgridsystem.data.UserDatabaseHelper
import com.example.microgridsystem.models.ProsumerProfileResponse
import com.example.microgridsystem.network.RetrofitClient
import com.example.microgridsystem.util.ApiErrorUtils
import com.example.microgridsystem.util.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Prosumer List Screen for Grid Operator
 *
 * Screen 2A for User & Access Management:
 * - Lists prosumers retrieved from the API (GET /api/prosumers).
 * - Real-time search by NIC and Name.
 * - Filters by account status (All, Active, Pending, Deactivated).
 * - Displays account status with color-coded badges.
 * - Click on an individual prosumer opens ProsumerDetailsActivity.
 * - Strictly read-only for Grid Operators.
 */
class ProsumerListActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var userDb: UserDatabaseHelper
    private lateinit var adapter: ProsumerAdapter

    private lateinit var btnBack: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var etSearch: TextInputEditText
    private lateinit var chipGroupStatus: ChipGroup
    private lateinit var tvCountSummary: TextView

    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var rvProsumers: RecyclerView
    private lateinit var layoutLoading: LinearLayout
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var layoutError: LinearLayout
    private lateinit var tvErrorMessage: TextView
    private lateinit var btnRetry: MaterialButton

    private var allProsumers: List<ProsumerProfileResponse> = emptyList()
    private var currentFilterStatus: String? = null // null means "All"
    private var currentSearchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_prosumer_list)

        userDb = UserDatabaseHelper.getInstance(this)
        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            redirectToLogin()
            return
        }

        initViews()
        setupRecyclerView()
        setupListeners()
        loadProsumers()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        btnRefresh = findViewById(R.id.btnRefresh)
        etSearch = findViewById(R.id.etSearch)
        chipGroupStatus = findViewById(R.id.chipGroupStatus)
        tvCountSummary = findViewById(R.id.tvCountSummary)

        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        rvProsumers = findViewById(R.id.rvProsumers)
        layoutLoading = findViewById(R.id.layoutLoading)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        layoutError = findViewById(R.id.layoutError)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)
        btnRetry = findViewById(R.id.btnRetry)
    }

    private fun setupRecyclerView() {
        adapter = ProsumerAdapter(emptyList()) { selectedProsumer ->
            val intent = Intent(this, ProsumerDetailsActivity::class.java).apply {
                putExtra(ProsumerDetailsActivity.EXTRA_PROSUMER_NIC, selectedProsumer.nic)
                putExtra(ProsumerDetailsActivity.EXTRA_PRELOADED_NAME, selectedProsumer.fullName)
                putExtra(ProsumerDetailsActivity.EXTRA_PRELOADED_STATUS, selectedProsumer.status)
            }
            startActivity(intent)
        }
        rvProsumers.layoutManager = LinearLayoutManager(this)
        rvProsumers.adapter = adapter
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        btnRefresh.setOnClickListener { loadProsumers() }

        swipeRefreshLayout.setOnRefreshListener { loadProsumers() }

        btnRetry.setOnClickListener { loadProsumers() }

        // Real-time search by NIC and Name
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim().orEmpty()
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Status Filter Chips
        chipGroupStatus.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilterStatus = when {
                checkedIds.contains(R.id.chipActive) -> "Active"
                checkedIds.contains(R.id.chipPending) -> "Pending"
                checkedIds.contains(R.id.chipDeactivated) -> "Deactivated"
                else -> null // All
            }
            applyFilters()
        }
    }

    /**
     * Requirement: Call GET /api/prosumers and synchronize with SQLite
     */
    private fun loadProsumers() {
        // 1. Immediately load cached prosumers from native SQLite database
        val cached = userDb.getAllCachedProsumers()
        if (cached.isNotEmpty()) {
            allProsumers = cached
            applyFilters()
        }

        if (!swipeRefreshLayout.isRefreshing && allProsumers.isEmpty()) {
            showLoading(true)
        }
        hideError()

        val token = sessionManager.getAuthHeader()
        val apiService = RetrofitClient.getService(this)

        apiService.getAllProsumers(token).enqueue(object : Callback<List<ProsumerProfileResponse>> {
            override fun onResponse(
                call: Call<List<ProsumerProfileResponse>>,
                response: Response<List<ProsumerProfileResponse>>
            ) {
                showLoading(false)
                swipeRefreshLayout.isRefreshing = false

                if (response.isSuccessful) {
                    val prosumers = response.body().orEmpty()
                    allProsumers = prosumers
                    // Persist prosumers to SQLite database cache
                    userDb.replaceCachedProsumers(prosumers)
                    applyFilters()
                } else {
                    val errorMsg = ApiErrorUtils.parseErrorMessage(response, "Failed to load prosumers from API.")
                    if (allProsumers.isEmpty()) {
                        showError(errorMsg)
                    } else {
                        Toast.makeText(this@ProsumerListActivity, "Showing cached prosumer directory", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onFailure(call: Call<List<ProsumerProfileResponse>>, t: Throwable) {
                showLoading(false)
                swipeRefreshLayout.isRefreshing = false
                if (allProsumers.isEmpty()) {
                    showError("Network connection error: ${t.localizedMessage}. Check server IP.")
                } else {
                    Toast.makeText(this@ProsumerListActivity, "Offline: showing cached prosumer directory", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    /**
     * Filters list in-memory by search query (NIC and Name) and status filter.
     */
    private fun applyFilters() {
        var filtered = allProsumers

        // Search by NIC or Name
        if (currentSearchQuery.isNotEmpty()) {
            filtered = filtered.filter { item ->
                val nicMatch = item.nic?.contains(currentSearchQuery, ignoreCase = true) == true
                val nameMatch = item.fullName?.contains(currentSearchQuery, ignoreCase = true) == true
                nicMatch || nameMatch
            }
        }

        // Filter by Status
        if (!currentFilterStatus.isNullOrBlank()) {
            filtered = filtered.filter { item ->
                item.status.equals(currentFilterStatus, ignoreCase = true)
            }
        }

        adapter.updateData(filtered)

        // Update count summary
        val total = allProsumers.size
        val showing = filtered.size
        tvCountSummary.text = if (currentSearchQuery.isEmpty() && currentFilterStatus == null) {
            "Total Prosumers: $total"
        } else {
            "Showing $showing of $total prosumers"
        }

        // Show/hide empty state
        if (filtered.isEmpty()) {
            layoutEmpty.visibility = View.VISIBLE
            rvProsumers.visibility = View.GONE
        } else {
            layoutEmpty.visibility = View.GONE
            rvProsumers.visibility = View.VISIBLE
        }
    }

    private fun showLoading(loading: Boolean) {
        layoutLoading.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) {
            rvProsumers.visibility = View.GONE
            layoutEmpty.visibility = View.GONE
            layoutError.visibility = View.GONE
        }
    }

    private fun showError(message: String) {
        tvErrorMessage.text = message
        layoutError.visibility = View.VISIBLE
        rvProsumers.visibility = View.GONE
        layoutEmpty.visibility = View.GONE
        layoutLoading.visibility = View.GONE
        tvCountSummary.text = "Error loading data"
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
