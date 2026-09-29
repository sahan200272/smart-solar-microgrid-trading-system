// File:        MyBookingsActivity.kt
// Component:   Booking Views & Grid Operator Verification
// Description: Prosumer booking views. Shows approved and pending counts, and three live
//              views read from the API: Current (approved bookings still ahead), Pending
//              (awaiting approval) and History (every booking, newest first), with search,
//              status and date filters. Tapping a booking opens its details screen.
// Author:      Gunathilaka K.K.N.M.

package com.example.microgridsystem.ui.bookings

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.microgridsystem.AccountStatusActivity
import com.example.microgridsystem.R
import com.example.microgridsystem.models.ReservationDashboardResponse
import com.example.microgridsystem.models.ReservationResponse
import com.example.microgridsystem.network.RetrofitClient
import com.example.microgridsystem.ui.operator.OpsApiError
import com.example.microgridsystem.ui.operator.OpsFailure
import com.example.microgridsystem.ui.operator.OpsFormat
import com.example.microgridsystem.ui.operator.OpsStateView
import com.example.microgridsystem.ui.operator.OpsTone
import com.example.microgridsystem.ui.operator.OpsUi
import com.example.microgridsystem.ui.reservations.NewReservationActivity
import com.example.microgridsystem.ui.reservations.ReservationDetailsActivity
import com.example.microgridsystem.util.SessionManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Date
import java.util.EnumMap
import java.util.Locale

class MyBookingsActivity : AppCompatActivity() {

    companion object {
        private const val SEARCH_DELAY_MS = 300L
        private const val DATE_PICKER_TAG = "ops_booking_date_range"
    }

    private enum class BookingTab { CURRENT, PENDING, HISTORY }

    // Loaded rows and request state for one tab.
    private class TabData {
        var items: List<ReservationResponse> = emptyList()
        var loaded = false
        var loading = false
        var error: OpsFailure? = null
        var call: Call<List<ReservationResponse>>? = null
    }

    private lateinit var session: SessionManager

    private lateinit var rootView: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var contentView: View
    private lateinit var tvSumApproved: TextView
    private lateinit var tvSumPending: TextView
    private lateinit var tvSumNext: TextView
    private lateinit var etSearch: TextInputEditText
    private lateinit var tabs: TabLayout
    private lateinit var historyFilters: View
    private lateinit var chipsStatus: ChipGroup
    private lateinit var chipDateRange: Chip
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var rvBookings: RecyclerView
    private lateinit var skBookings: View
    private lateinit var stateScroll: NestedScrollView
    private lateinit var stateBookings: OpsStateView
    private lateinit var stateRestricted: OpsStateView
    private lateinit var fabNewBooking: ExtendedFloatingActionButton

    private lateinit var adapter: BookingViewAdapter

    private val tabData = EnumMap<BookingTab, TabData>(BookingTab::class.java).apply {
        BookingTab.values().forEach { put(it, TabData()) }
    }
    private var activeTab = BookingTab.CURRENT

    private var query = ""
    private var historyStatus: String? = null
    // Selected date range as UTC-midnight millis, as returned by MaterialDatePicker
    private var dateRange: Pair<Long, Long>? = null

    private var approvedCount: Long? = null
    private var pendingCount: Long? = null
    private var countsCall: Call<ReservationDashboardResponse>? = null

    private var isReady = false
    private val searchHandler = Handler(Looper.getMainLooper())
    private val applySearch = Runnable {
        query = etSearch.text?.toString()?.trim().orEmpty()
        render()
    }

    // Sets up the tabs, search and filters; data loads in onResume.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpsUi.enableEdgeToEdge(this)
        setContentView(R.layout.activity_my_bookings)

        session = SessionManager(this)
        if (!session.isLoggedIn()) {
            OpsUi.goToLogin(this)
            return
        }

        bindViews()
        OpsUi.applySystemBarPadding(rootView)
        setupToolbar()

        // Booking views belong to active prosumer accounts
        if (!OpsUi.isProsumer(session.getRole())) {
            showRestricted(R.string.ops_bookings_restricted_role, R.string.ops_action_go_back) { finish() }
            return
        }

        if (!session.isActive()) {
            showRestricted(R.string.ops_bookings_restricted_inactive, R.string.ops_action_view_account_status) {
                startActivity(Intent(this, AccountStatusActivity::class.java))
            }
            return
        }

        setupTabs()
        setupList()
        setupSearchAndFilters()
        isReady = true
    }

    // Reloads every time the screen returns, so changes made on the details screen show at once.
    override fun onResume() {
        super.onResume()

        if (isReady) {
            refreshAll()
        }
    }

    override fun onDestroy() {
        searchHandler.removeCallbacks(applySearch)
        countsCall?.cancel()
        tabData.values.forEach { it.call?.cancel() }
        super.onDestroy()
    }

    private fun bindViews() {
        rootView = findViewById(R.id.bookingsRoot)
        toolbar = findViewById(R.id.toolbar)
        contentView = findViewById(R.id.bookingsContent)
        tvSumApproved = findViewById(R.id.tvSumApproved)
        tvSumPending = findViewById(R.id.tvSumPending)
        tvSumNext = findViewById(R.id.tvSumNext)
        etSearch = findViewById(R.id.etSearch)
        tabs = findViewById(R.id.tabs)
        historyFilters = findViewById(R.id.historyFilters)
        chipsStatus = findViewById(R.id.chipsStatus)
        chipDateRange = findViewById(R.id.chipDateRange)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        rvBookings = findViewById(R.id.rvBookings)
        skBookings = findViewById(R.id.skBookings)
        stateScroll = findViewById(R.id.stateScroll)
        stateBookings = OpsStateView(findViewById(R.id.stateBookings))
        stateRestricted = OpsStateView(findViewById(R.id.stateRestricted))
        fabNewBooking = findViewById(R.id.fabNewBooking)
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_ops_refresh) {
                refreshAll()
                true
            } else {
                false
            }
        }
    }

    private fun setupTabs() {
        BookingTab.values().forEach { tabs.addTab(tabs.newTab().setText(tabLabel(it))) }

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                activeTab = BookingTab.values()[tab.position]
                val data = tabData.getValue(activeTab)

                if (!data.loaded && !data.loading) {
                    loadTab(activeTab)
                }
                render()
                rvBookings.scrollToPosition(0)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {
                rvBookings.smoothScrollToPosition(0)
            }
        })
    }

    // Grid on tablets (month headings span the full width), single column on phones.
    private fun setupList() {
        adapter = BookingViewAdapter { booking ->
            startActivity(
                Intent(this, ReservationDetailsActivity::class.java).putExtra("EXTRA_RESERVATION", booking)
            )
        }

        val columns = resources.getInteger(R.integer.ops_list_columns)
        rvBookings.layoutManager = if (columns > 1) {
            GridLayoutManager(this, columns).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int = if (adapter.isHeader(position)) columns else 1
                }
            }
        } else {
            LinearLayoutManager(this)
        }
        rvBookings.adapter = adapter

        rvBookings.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 8) fabNewBooking.shrink() else if (dy < -8) fabNewBooking.extend()
            }
        })

        swipeRefresh.setColorSchemeResources(R.color.primary)
        swipeRefresh.setOnRefreshListener { refreshAll() }
        // The list sits inside a FrameLayout, so tell the refresh layout when it can still scroll up
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            when {
                rvBookings.isVisible -> rvBookings.canScrollVertically(-1)
                stateScroll.isVisible -> stateScroll.canScrollVertically(-1)
                else -> false
            }
        }

        fabNewBooking.setOnClickListener { openNewBooking() }
    }

    private fun setupSearchAndFilters() {
        etSearch.doAfterTextChanged {
            searchHandler.removeCallbacks(applySearch)
            searchHandler.postDelayed(applySearch, SEARCH_DELAY_MS)
        }
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchHandler.removeCallbacks(applySearch)
                applySearch.run()
                hideKeyboard()
                true
            } else {
                false
            }
        }

        chipsStatus.setOnCheckedStateChangeListener { _, checkedIds ->
            historyStatus = when (checkedIds.firstOrNull()) {
                R.id.chipCompleted -> "Completed"
                R.id.chipCancelled -> "Cancelled"
                R.id.chipApproved -> "Approved"
                R.id.chipPending -> "Pending"
                else -> null
            }
            render()
        }

        chipDateRange.setOnClickListener { showDateRangePicker() }
        chipDateRange.setOnCloseIconClickListener {
            dateRange = null
            updateDateChip()
            render()
        }
    }

    // ---------- Loading ----------

    // Reloads the counts, the Current tab (it feeds "Next slot") and the tab on screen.
    private fun refreshAll() {
        loadCounts()
        loadTab(BookingTab.CURRENT)

        if (activeTab != BookingTab.CURRENT) {
            loadTab(activeTab)
        }

        // Other tabs reload the next time they are opened
        BookingTab.values()
            .filter { it != BookingTab.CURRENT && it != activeTab }
            .forEach { tabData.getValue(it).loaded = false }
    }

    // GET /api/reservations/dashboard/{nic}: approved (active) and pending counts.
    private fun loadCounts() {
        val nic = session.getNic()
        if (nic.isNullOrBlank()) {
            OpsUi.showSessionExpired(this)
            return
        }

        countsCall?.cancel()
        countsCall = RetrofitClient.getService(this).getReservationDashboard(session.getAuthHeader(), nic)
        countsCall?.enqueue(object : Callback<ReservationDashboardResponse> {
            override fun onResponse(
                call: Call<ReservationDashboardResponse>,
                response: Response<ReservationDashboardResponse>
            ) {
                if (call.isCanceled || isFinishing || isDestroyed) return

                val body = response.body()
                if (response.isSuccessful && body != null) {
                    approvedCount = body.activeCount
                    pendingCount = body.pendingCount
                    renderSummary()
                    updateTabLabels()
                } else if (response.code() == 401) {
                    OpsUi.showSessionExpired(this@MyBookingsActivity)
                }
            }

            override fun onFailure(call: Call<ReservationDashboardResponse>, error: Throwable) {
                // The list shows connection problems; the counts simply keep their last value
            }
        })
    }

    // Loads one tab from its own endpoint.
    private fun loadTab(tab: BookingTab) {
        val nic = session.getNic()
        if (nic.isNullOrBlank()) {
            OpsUi.showSessionExpired(this)
            return
        }

        val data = tabData.getValue(tab)
        data.call?.cancel()
        data.loading = true

        if (tab == activeTab) {
            render()
        }

        val api = RetrofitClient.getService(this)
        val auth = session.getAuthHeader()
        val call = when (tab) {
            BookingTab.CURRENT -> api.getReservations(token = auth, status = "Approved")
            BookingTab.PENDING -> api.getPendingReservations(auth)
            BookingTab.HISTORY -> api.getReservationHistory(auth, nic)
        }
        data.call = call

        call.enqueue(object : Callback<List<ReservationResponse>> {
            override fun onResponse(call: Call<List<ReservationResponse>>, response: Response<List<ReservationResponse>>) {
                if (call.isCanceled || isFinishing || isDestroyed) return

                data.loading = false
                data.call = null

                val body = response.body()
                if (response.isSuccessful && body != null) {
                    data.items = prepare(tab, body)
                    data.loaded = true
                    data.error = null
                } else {
                    val failure = OpsApiError.fromResponse(this@MyBookingsActivity, response)
                    if (failure.status == 401) {
                        OpsUi.showSessionExpired(this@MyBookingsActivity)
                        return
                    }
                    onTabFailed(tab, data, failure)
                }

                onTabChanged(tab)
            }

            override fun onFailure(call: Call<List<ReservationResponse>>, error: Throwable) {
                if (call.isCanceled || isFinishing || isDestroyed) return

                data.loading = false
                data.call = null
                onTabFailed(tab, data, OpsApiError.fromThrowable(this@MyBookingsActivity, error))
                onTabChanged(tab)
            }
        })
    }

    // Keeps already shown rows on a failed refresh and offers a retry.
    private fun onTabFailed(tab: BookingTab, data: TabData, failure: OpsFailure) {
        data.error = failure

        if (data.loaded && tab == activeTab) {
            Snackbar.make(rootView, R.string.ops_bookings_refresh_failed, Snackbar.LENGTH_LONG)
                .setAnchorView(fabNewBooking)
                .setAction(R.string.ops_action_retry) { loadTab(tab) }
                .show()
        }
    }

    // Orders each view for reading. Current keeps only approved slots that haven't ended yet.
    private fun prepare(tab: BookingTab, items: List<ReservationResponse>): List<ReservationResponse> {
        val now = Date()

        return when (tab) {
            BookingTab.CURRENT -> items
                .filter { booking ->
                    val finish = OpsFormat.parse(booking.slotEndTime) ?: OpsFormat.parse(booking.slotStartTime)
                    finish != null && finish.after(now)
                }
                .sortedBy { OpsFormat.parse(it.slotStartTime)?.time ?: Long.MAX_VALUE }
            BookingTab.PENDING -> items.sortedBy { OpsFormat.parse(it.slotStartTime)?.time ?: Long.MAX_VALUE }
            BookingTab.HISTORY -> items
        }
    }

    private fun onTabChanged(tab: BookingTab) {
        updateTabLabels()

        if (tab == BookingTab.CURRENT) {
            renderSummary()
        }

        if (tab == activeTab) {
            render()
        }
    }

    // ---------- Rendering ----------

    // Shows the active tab: skeleton, error, empty, "no matches" or the filtered list.
    private fun render() {
        val data = tabData.getValue(activeTab)

        historyFilters.isVisible = activeTab == BookingTab.HISTORY
        swipeRefresh.isRefreshing = data.loading && data.loaded

        if (!data.loaded) {
            val error = data.error
            if (error != null && !data.loading) {
                showState(
                    R.drawable.ic_ops_cloud_off, OpsTone.DANGER,
                    getString(R.string.ops_bookings_error_title), error.message,
                    getString(R.string.ops_action_try_again)
                ) { loadTab(activeTab) }
            } else {
                showSkeleton()
            }
            return
        }

        val filtered = applyFilters(data.items)

        when {
            filtered.isNotEmpty() -> showList(filtered)
            data.items.isEmpty() -> showEmptyTab()
            else -> showState(
                R.drawable.ic_search, OpsTone.NEUTRAL,
                getString(R.string.ops_bookings_no_match_title),
                if (query.isNotEmpty()) getString(R.string.ops_bookings_no_match_query, query)
                else getString(R.string.ops_bookings_no_match_filters),
                getString(R.string.ops_action_clear_filters)
            ) { clearFilters() }
        }
    }

    private fun showList(bookings: List<ReservationResponse>) {
        skBookings.isVisible = false
        stateScroll.isVisible = false
        stateBookings.hide()
        rvBookings.isVisible = true
        adapter.submit(buildRows(bookings))
    }

    private fun showSkeleton() {
        rvBookings.isVisible = false
        stateScroll.isVisible = false
        stateBookings.hide()
        skBookings.isVisible = true
    }

    private fun showState(
        icon: Int,
        tone: OpsTone,
        title: String,
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        rvBookings.isVisible = false
        skBookings.isVisible = false
        stateScroll.isVisible = true
        stateBookings.show(icon, tone, title, message, actionLabel, onAction)
    }

    private fun showEmptyTab() {
        when (activeTab) {
            BookingTab.CURRENT -> showState(
                R.drawable.ic_calendar, OpsTone.NEUTRAL,
                getString(R.string.ops_bookings_empty_current_title),
                getString(R.string.ops_bookings_empty_current_message),
                getString(R.string.ops_action_book_slot)
            ) { openNewBooking() }
            BookingTab.PENDING -> showState(
                R.drawable.ic_hourglass, OpsTone.NEUTRAL,
                getString(R.string.ops_bookings_empty_pending_title),
                getString(R.string.ops_bookings_empty_pending_message)
            )
            BookingTab.HISTORY -> showState(
                R.drawable.ic_ops_history, OpsTone.NEUTRAL,
                getString(R.string.ops_bookings_empty_history_title),
                getString(R.string.ops_bookings_empty_history_message),
                getString(R.string.ops_action_book_slot)
            ) { openNewBooking() }
        }
    }

    // History is grouped under month headings; the other views are plain lists.
    private fun buildRows(bookings: List<ReservationResponse>): List<BookingViewAdapter.Row> {
        if (activeTab != BookingTab.HISTORY) {
            val showPhase = activeTab == BookingTab.CURRENT
            return bookings.map { BookingViewAdapter.Row.Booking(it, showPhase) }
        }

        val rows = mutableListOf<BookingViewAdapter.Row>()
        var currentMonth: String? = null

        bookings.forEach { booking ->
            val start = OpsFormat.parse(booking.slotStartTime)
            val month = start?.let { OpsFormat.monthYear(it) } ?: getString(R.string.ops_not_scheduled)

            if (month != currentMonth) {
                rows.add(BookingViewAdapter.Row.Header(month))
                currentMonth = month
            }
            rows.add(BookingViewAdapter.Row.Booking(booking, showPhase = false))
        }

        return rows
    }

    // Applies the History status and date filters, then the search text (to every tab).
    private fun applyFilters(items: List<ReservationResponse>): List<ReservationResponse> {
        var result = items

        if (activeTab == BookingTab.HISTORY) {
            historyStatus?.let { status ->
                result = result.filter { it.status.equals(status, ignoreCase = true) }
            }

            dateRange?.let { (from, to) ->
                val range = OpsFormat.pickerDayKey(from)..OpsFormat.pickerDayKey(to)
                result = result.filter { booking ->
                    val start = OpsFormat.parse(booking.slotStartTime)
                    start != null && OpsFormat.dayKey(start) in range
                }
            }
        }

        if (query.isNotEmpty()) {
            val terms = query.lowercase(Locale.ENGLISH).split(Regex("\\s+")).filter { it.isNotEmpty() }
            result = result.filter { booking ->
                val text = searchText(booking)
                terms.all { text.contains(it) }
            }
        }

        return result
    }

    // Everything a booking can be found by: station, reference, status, dates, time and energy.
    private fun searchText(booking: ReservationResponse): String {
        val start = OpsFormat.parse(booking.slotStartTime)

        return listOfNotNull(
            booking.stationName,
            booking.id,
            booking.status,
            start?.let { OpsFormat.searchableDate(it) },
            OpsFormat.energy(this, booking.energyKWh)
        ).joinToString(" ").lowercase(Locale.ENGLISH)
    }

    private fun renderSummary() {
        tvSumApproved.text = OpsFormat.count(approvedCount)
        tvSumPending.text = OpsFormat.count(pendingCount)

        val current = tabData.getValue(BookingTab.CURRENT)
        val next = current.items.firstOrNull()?.let { OpsFormat.parse(it.slotStartTime) }

        tvSumNext.text = when {
            !current.loaded -> getString(R.string.ops_placeholder)
            next == null -> getString(R.string.ops_bookings_next_none)
            else -> OpsFormat.relativeDayTime(this, next)
        }
    }

    private fun updateTabLabels() {
        BookingTab.values().forEachIndexed { index, tab ->
            tabs.getTabAt(index)?.text = tabLabel(tab)
        }
    }

    // "Current · 3". Pending uses the server count once known.
    private fun tabLabel(tab: BookingTab): String {
        val data = tabData.getValue(tab)
        val count: Long? = when (tab) {
            BookingTab.PENDING -> pendingCount ?: data.items.size.toLong().takeIf { data.loaded }
            else -> data.items.size.toLong().takeIf { data.loaded }
        }

        val name = getString(
            when (tab) {
                BookingTab.CURRENT -> R.string.ops_tab_current
                BookingTab.PENDING -> R.string.ops_tab_pending
                BookingTab.HISTORY -> R.string.ops_tab_history
            }
        )

        return if (count == null) name else getString(R.string.ops_tab_with_count, name, OpsFormat.count(count))
    }

    // ---------- Filters ----------

    // MaterialDatePicker returns the chosen days as UTC midnight.
    private fun showDateRangePicker() {
        if (supportFragmentManager.findFragmentByTag(DATE_PICKER_TAG) != null) return

        val builder = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.ops_bookings_date_range_title)

        dateRange?.let { (from, to) -> builder.setSelection(androidx.core.util.Pair(from, to)) }

        val picker = builder.build()
        picker.addOnPositiveButtonClickListener { selection ->
            val from = selection.first
            val to = selection.second

            if (from != null && to != null) {
                dateRange = from to to
                updateDateChip()
                render()
            }
        }
        picker.show(supportFragmentManager, DATE_PICKER_TAG)
    }

    private fun updateDateChip() {
        val range = dateRange

        if (range == null) {
            chipDateRange.setText(R.string.ops_filter_date_range)
            chipDateRange.isCloseIconVisible = false
        } else {
            chipDateRange.text = getString(
                R.string.ops_filter_date_range_value,
                OpsFormat.pickerShortDate(range.first),
                OpsFormat.pickerShortDate(range.second)
            )
            chipDateRange.isCloseIconVisible = true
        }
    }

    private fun clearFilters() {
        searchHandler.removeCallbacks(applySearch)
        etSearch.setText("")
        query = ""
        dateRange = null
        updateDateChip()
        chipsStatus.check(R.id.chipAll)
        historyStatus = null
        render()
    }

    // ---------- Navigation ----------

    private fun openNewBooking() {
        startActivity(Intent(this, NewReservationActivity::class.java))
    }

    private fun showRestricted(messageRes: Int, actionRes: Int, onAction: () -> Unit) {
        contentView.isVisible = false
        toolbar.menu.findItem(R.id.action_ops_refresh)?.isVisible = false

        stateRestricted.show(
            R.drawable.ic_lock, OpsTone.NEUTRAL,
            getString(R.string.ops_bookings_restricted_title), getString(messageRes),
            getString(actionRes), onAction
        )
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
        etSearch.clearFocus()
    }
}
