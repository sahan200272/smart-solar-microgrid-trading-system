// File:        OperatorConsoleActivity.kt
// Component:   Booking Views & Grid Operator Verification
// Description: Grid Operator console on mobile. Shows live booking counts (pending approval
//              and approved future reservations first), today's bookings and pending
//              approvals from GET /api/operator/dashboard, filtered by microgrid node, and
//              opens the QR scanner.
// Author:      Gunathilaka K.K.N.M.

package com.example.microgridsystem.ui.operator

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.microgridsystem.R
import com.example.microgridsystem.data.OpsCacheDb
import com.example.microgridsystem.data.OpsPrefs
import com.example.microgridsystem.models.OperatorBooking
import com.example.microgridsystem.models.OperatorDashboardResponse
import com.example.microgridsystem.models.OperatorStation
import com.example.microgridsystem.network.RetrofitClient
import com.example.microgridsystem.util.SessionManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Date

class OperatorConsoleActivity : AppCompatActivity() {

    companion object {
        // Number of on-device verifications listed under "Recent on this device".
        private const val RECENT_LIMIT = 5
    }

    private lateinit var session: SessionManager
    private lateinit var cacheDb: OpsCacheDb

    private lateinit var rootView: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var scrollView: NestedScrollView
    private lateinit var actNode: MaterialAutoCompleteTextView
    private lateinit var bannerError: MaterialCardView
    private lateinit var tvBannerTitle: TextView
    private lateinit var tvBannerMessage: TextView

    private lateinit var tvKpiPending: TextView
    private lateinit var tvKpiUpcoming: TextView
    private lateinit var skKpiPending: View
    private lateinit var skKpiUpcoming: View
    private lateinit var tvKpiToday: TextView
    private lateinit var tvKpiCompleted: TextView
    private lateinit var tvKpiCancelled: TextView

    private lateinit var tvTodayCount: TextView
    private lateinit var rvToday: RecyclerView
    private lateinit var skToday: View
    private lateinit var cardTodayState: MaterialCardView
    private lateinit var stateToday: OpsStateView
    private lateinit var tvTodayFooter: TextView

    private lateinit var headerPending: View
    private lateinit var tvPendingCount: TextView
    private lateinit var rvPending: RecyclerView
    private lateinit var skPending: View
    private lateinit var cardPendingState: MaterialCardView
    private lateinit var statePending: OpsStateView
    private lateinit var tvPendingFooter: TextView

    private lateinit var sectionRecent: View
    private lateinit var recentRows: LinearLayout
    private lateinit var stateRestricted: OpsStateView
    private lateinit var fabScan: ExtendedFloatingActionButton

    private lateinit var todayAdapter: OpsBookingAdapter
    private lateinit var pendingAdapter: OpsBookingAdapter

    private var stations: List<OperatorStation> = emptyList()
    private var selectedNodeId: String? = null
    private var isReady = false
    private var isLoading = false
    private var hasLoadedOnce = false
    private var lastUpdated: Date? = null
    private var dashboardCall: Call<OperatorDashboardResponse>? = null
    private var stationsCall: Call<List<OperatorStation>>? = null

    // Sets up the console; data loads in onResume so it refreshes after every scan.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpsUi.enableEdgeToEdge(this)
        setContentView(R.layout.activity_operator_console)

        session = SessionManager(this)
        if (!session.isLoggedIn()) {
            OpsUi.goToLogin(this)
            return
        }

        cacheDb = OpsCacheDb(this)
        bindViews()
        OpsUi.applySystemBarPadding(rootView)
        setupToolbar()

        val role = session.getRole()
        if (!OpsUi.canViewConsole(role)) {
            showRestricted()
            return
        }

        // Backoffice can view the console, but only Grid Operators verify QR codes
        fabScan.isVisible = OpsUi.isGridOperator(role)
        fabScan.setOnClickListener { startActivity(Intent(this, ScanQrActivity::class.java)) }

        setupLists()
        setupInteractions()

        selectedNodeId = OpsPrefs.getNodeId(this)
        loadStations()
        showLoadingPlaceholders()
        isReady = true
    }

    override fun onResume() {
        super.onResume()

        if (isReady) {
            loadDashboard(manual = false)
            renderRecent()
        }
    }

    override fun onDestroy() {
        dashboardCall?.cancel()
        stationsCall?.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        rootView = findViewById(R.id.consoleRoot)
        toolbar = findViewById(R.id.toolbar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        scrollView = findViewById(R.id.consoleScroll)
        actNode = findViewById(R.id.actNode)
        bannerError = findViewById(R.id.bannerError)
        tvBannerTitle = findViewById(R.id.tvBannerTitle)
        tvBannerMessage = findViewById(R.id.tvBannerMessage)

        tvKpiPending = findViewById(R.id.tvKpiPending)
        tvKpiUpcoming = findViewById(R.id.tvKpiUpcoming)
        skKpiPending = findViewById(R.id.skKpiPending)
        skKpiUpcoming = findViewById(R.id.skKpiUpcoming)
        tvKpiToday = findViewById(R.id.tvKpiToday)
        tvKpiCompleted = findViewById(R.id.tvKpiCompleted)
        tvKpiCancelled = findViewById(R.id.tvKpiCancelled)

        tvTodayCount = findViewById(R.id.tvTodayCount)
        rvToday = findViewById(R.id.rvToday)
        skToday = findViewById(R.id.skToday)
        cardTodayState = findViewById(R.id.cardTodayState)
        stateToday = OpsStateView(findViewById(R.id.stateToday))
        tvTodayFooter = findViewById(R.id.tvTodayFooter)

        headerPending = findViewById(R.id.headerPending)
        tvPendingCount = findViewById(R.id.tvPendingCount)
        rvPending = findViewById(R.id.rvPending)
        skPending = findViewById(R.id.skPending)
        cardPendingState = findViewById(R.id.cardPendingState)
        statePending = OpsStateView(findViewById(R.id.statePending))
        tvPendingFooter = findViewById(R.id.tvPendingFooter)

        sectionRecent = findViewById(R.id.sectionRecent)
        recentRows = findViewById(R.id.recentRows)
        stateRestricted = OpsStateView(findViewById(R.id.stateRestricted))
        fabScan = findViewById(R.id.fabScan)
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_ops_refresh) {
                loadDashboard(manual = true)
                true
            } else {
                false
            }
        }
    }

    // Two columns on tablets, one on phones.
    private fun setupLists() {
        todayAdapter = OpsBookingAdapter(OpsBookingAdapter.Mode.TODAY) { showBookingDetails(it, isToday = true) }
        pendingAdapter = OpsBookingAdapter(OpsBookingAdapter.Mode.PENDING) { showBookingDetails(it, isToday = false) }

        val columns = resources.getInteger(R.integer.ops_list_columns)
        listOf(rvToday to todayAdapter, rvPending to pendingAdapter).forEach { (list, adapter) ->
            list.layoutManager = if (columns > 1) GridLayoutManager(this, columns) else LinearLayoutManager(this)
            list.adapter = adapter
        }
    }

    private fun setupInteractions() {
        swipeRefresh.setColorSchemeResources(R.color.primary)
        swipeRefresh.setOnRefreshListener { loadDashboard(manual = true) }

        findViewById<View>(R.id.btnBannerRetry).setOnClickListener { loadDashboard(manual = true) }

        // The pending count jumps to the list of requests behind it
        findViewById<View>(R.id.cardKpiPending).setOnClickListener {
            scrollView.smoothScrollTo(0, headerPending.top - OpsUi.dp(this, 8f))
        }

        // Keep the Scan QR button compact while scrolling through the lists
        scrollView.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY > oldScrollY + 12) {
                fabScan.shrink()
            } else if (scrollY < oldScrollY - 12) {
                fabScan.extend()
            }
        })

        actNode.setOnItemClickListener { _, _, position, _ ->
            val nodeId = if (position == 0) null else stations.getOrNull(position - 1)?.id
            if (nodeId != selectedNodeId) {
                selectedNodeId = nodeId
                OpsPrefs.setNodeId(this, nodeId)
                reloadForNewNode()
            }
        }
    }

    // ---------- Stations (node filter) ----------

    // Fills the node filter from the SQLite cache, then refreshes it from the API.
    private fun loadStations() {
        stations = cacheDb.getStations()
        bindNodeFilter()

        stationsCall = RetrofitClient.getService(this).getOperatorStations(session.getAuthHeader())
        stationsCall?.enqueue(object : Callback<List<OperatorStation>> {
            override fun onResponse(call: Call<List<OperatorStation>>, response: Response<List<OperatorStation>>) {
                if (isFinishing || isDestroyed) return

                val body = response.body()
                if (!response.isSuccessful || body == null) return

                stations = body.filter { !it.id.isNullOrBlank() }.sortedBy { (it.stationName ?: "").lowercase() }
                cacheDb.replaceStations(stations)

                // A saved node that was deactivated falls back to all nodes
                if (selectedNodeId != null && stations.none { it.id == selectedNodeId }) {
                    selectedNodeId = null
                    OpsPrefs.setNodeId(this@OperatorConsoleActivity, null)
                    reloadForNewNode()
                }

                bindNodeFilter()
            }

            override fun onFailure(call: Call<List<OperatorStation>>, error: Throwable) {
                // The filter keeps the cached list; the dashboard itself reports connection problems
            }
        })
    }

    private fun bindNodeFilter() {
        val labels = listOf(getString(R.string.ops_node_all)) + stations.map { it.stationName ?: it.id.orEmpty() }
        actNode.setSimpleItems(labels.toTypedArray())
        actNode.setText(nodeLabel(), false)
    }

    private fun nodeLabel(): String {
        return stations.firstOrNull { it.id == selectedNodeId }?.stationName ?: getString(R.string.ops_node_all)
    }

    private fun stationNameMap(): Map<String, String> {
        return stations.mapNotNull { station ->
            val id = station.id ?: return@mapNotNull null
            id to (station.stationName ?: id)
        }.toMap()
    }

    // ---------- Dashboard ----------

    // Switching node replaces the figures, so show placeholders instead of stale numbers.
    private fun reloadForNewNode() {
        dashboardCall?.cancel()
        isLoading = false
        hasLoadedOnce = false
        showLoadingPlaceholders()
        loadDashboard(manual = false)
    }

    // Loads counts and preview lists. Existing data stays on screen during a refresh.
    private fun loadDashboard(manual: Boolean) {
        if (isLoading) {
            return
        }

        isLoading = true
        setRefreshEnabled(false)

        if (!hasLoadedOnce) {
            showLoadingPlaceholders()
        } else if (manual) {
            swipeRefresh.isRefreshing = true
        }

        dashboardCall = RetrofitClient.getService(this).getOperatorDashboard(session.getAuthHeader(), selectedNodeId)
        dashboardCall?.enqueue(object : Callback<OperatorDashboardResponse> {
            override fun onResponse(call: Call<OperatorDashboardResponse>, response: Response<OperatorDashboardResponse>) {
                if (call.isCanceled || isFinishing || isDestroyed) return
                finishLoading()

                val body = response.body()
                if (response.isSuccessful && body != null) {
                    val refreshedExisting = hasLoadedOnce
                    hasLoadedOnce = true
                    lastUpdated = OpsFormat.parse(body.generatedAt) ?: Date()
                    render(body)

                    if (manual && refreshedExisting) {
                        Snackbar.make(rootView, R.string.ops_console_up_to_date, Snackbar.LENGTH_SHORT)
                            .setAnchorView(fabScan.takeIf { it.isVisible })
                            .show()
                    }
                } else {
                    handleLoadError(OpsApiError.fromResponse(this@OperatorConsoleActivity, response))
                }
            }

            override fun onFailure(call: Call<OperatorDashboardResponse>, error: Throwable) {
                if (call.isCanceled || isFinishing || isDestroyed) return
                finishLoading()
                handleLoadError(OpsApiError.fromThrowable(this@OperatorConsoleActivity, error))
            }
        })
    }

    private fun finishLoading() {
        isLoading = false
        swipeRefresh.isRefreshing = false
        setRefreshEnabled(true)
    }

    private fun setRefreshEnabled(enabled: Boolean) {
        toolbar.menu.findItem(R.id.action_ops_refresh)?.isEnabled = enabled
    }

    // Shows the counts and both preview lists.
    private fun render(data: OperatorDashboardResponse) {
        val counts = data.counts
        val today = data.todayBookings.orEmpty()
        val pending = data.pendingReservations.orEmpty()
        val stationNames = stationNameMap()

        bannerError.isVisible = false
        updateSubtitle(stale = false)

        setKpi(tvKpiPending, skKpiPending, counts?.pendingCount)
        setKpi(tvKpiUpcoming, skKpiUpcoming, counts?.approvedFutureCount)
        tvKpiToday.text = OpsFormat.count(counts?.activeTodayCount)
        tvKpiCompleted.text = OpsFormat.count(counts?.completedTodayCount)
        tvKpiCancelled.text = OpsFormat.count(counts?.cancelledTodayCount)

        val todayTotal = counts?.activeTodayCount ?: today.size.toLong()
        val pendingTotal = counts?.pendingCount ?: pending.size.toLong()
        tvTodayCount.text = OpsFormat.count(todayTotal)
        tvPendingCount.text = OpsFormat.count(pendingTotal)

        skToday.isVisible = false
        skPending.isVisible = false

        if (today.isEmpty()) {
            showListState(
                rvToday, cardTodayState, stateToday, R.drawable.ic_calendar, OpsTone.NEUTRAL,
                R.string.ops_today_empty_title, R.string.ops_today_empty_message
            )
        } else {
            hideListState(rvToday, cardTodayState, stateToday)
            todayAdapter.submit(today, stationNames)
        }

        if (pending.isEmpty()) {
            showListState(
                rvPending, cardPendingState, statePending, R.drawable.ic_check_circle, OpsTone.APPROVED,
                R.string.ops_pending_empty_title, R.string.ops_pending_empty_message
            )
        } else {
            hideListState(rvPending, cardPendingState, statePending)
            pendingAdapter.submit(pending, stationNames)
        }

        // The API returns at most 10 rows per list, so say when more exist
        setFooter(tvTodayFooter, today.size, todayTotal, R.string.ops_today_footer)
        setFooter(tvPendingFooter, pending.size, pendingTotal, R.string.ops_pending_footer)
    }

    // Keeps shown data on a failed refresh; only replaces placeholders on a failed first load.
    private fun handleLoadError(failure: OpsFailure) {
        when {
            failure.status == 401 -> OpsUi.showSessionExpired(this)
            failure.status == 403 -> showRestricted()
            hasLoadedOnce -> {
                updateSubtitle(stale = true)
                Snackbar.make(rootView, R.string.ops_console_refresh_failed, Snackbar.LENGTH_LONG)
                    .setAnchorView(fabScan.takeIf { it.isVisible })
                    .setAction(R.string.ops_action_retry) { loadDashboard(manual = true) }
                    .show()
            }
            else -> {
                tvBannerTitle.setText(R.string.ops_console_load_failed)
                tvBannerMessage.text = failure.message
                bannerError.isVisible = true

                setKpi(tvKpiPending, skKpiPending, null)
                setKpi(tvKpiUpcoming, skKpiUpcoming, null)
                tvKpiToday.text = getString(R.string.ops_placeholder)
                tvKpiCompleted.text = getString(R.string.ops_placeholder)
                tvKpiCancelled.text = getString(R.string.ops_placeholder)
                tvTodayCount.text = getString(R.string.ops_placeholder)
                tvPendingCount.text = getString(R.string.ops_placeholder)

                skToday.isVisible = false
                skPending.isVisible = false
                showListState(
                    rvToday, cardTodayState, stateToday, R.drawable.ic_ops_cloud_off, OpsTone.DANGER,
                    R.string.ops_data_unavailable_title, R.string.ops_data_unavailable_message
                )
                showListState(
                    rvPending, cardPendingState, statePending, R.drawable.ic_ops_cloud_off, OpsTone.DANGER,
                    R.string.ops_data_unavailable_title, R.string.ops_data_unavailable_message
                )
                tvTodayFooter.isVisible = false
                tvPendingFooter.isVisible = false
                toolbar.subtitle = getString(R.string.ops_console_subtitle_failed)
            }
        }
    }

    // Skeleton placeholders for the first load (or after switching node).
    private fun showLoadingPlaceholders() {
        bannerError.isVisible = false
        toolbar.subtitle = getString(R.string.ops_console_subtitle_loading)

        listOf(tvKpiPending to skKpiPending, tvKpiUpcoming to skKpiUpcoming).forEach { (value, skeleton) ->
            value.visibility = View.INVISIBLE
            skeleton.isVisible = true
        }

        tvKpiToday.text = getString(R.string.ops_placeholder)
        tvKpiCompleted.text = getString(R.string.ops_placeholder)
        tvKpiCancelled.text = getString(R.string.ops_placeholder)
        tvTodayCount.text = getString(R.string.ops_placeholder)
        tvPendingCount.text = getString(R.string.ops_placeholder)

        hideListState(rvToday, cardTodayState, stateToday)
        hideListState(rvPending, cardPendingState, statePending)
        rvToday.isVisible = false
        rvPending.isVisible = false
        skToday.isVisible = true
        skPending.isVisible = true
        tvTodayFooter.isVisible = false
        tvPendingFooter.isVisible = false
    }

    private fun setKpi(value: TextView, skeleton: View, count: Long?) {
        skeleton.isVisible = false
        value.visibility = View.VISIBLE
        value.text = OpsFormat.count(count)
    }

    private fun showListState(
        list: RecyclerView,
        card: MaterialCardView,
        state: OpsStateView,
        icon: Int,
        tone: OpsTone,
        title: Int,
        message: Int
    ) {
        list.isVisible = false
        card.isVisible = true
        state.show(icon, tone, getString(title), getString(message))
    }

    private fun hideListState(list: RecyclerView, card: MaterialCardView, state: OpsStateView) {
        state.hide()
        card.isVisible = false
        list.isVisible = true
    }

    private fun setFooter(footer: TextView, shown: Int, total: Long, textRes: Int) {
        footer.isVisible = total > shown
        footer.text = getString(textRes, shown, OpsFormat.count(total))
    }

    // "Updated 10:42 AM · All nodes", or a stale notice when a refresh failed.
    private fun updateSubtitle(stale: Boolean) {
        val time = lastUpdated?.let { OpsFormat.time(it) } ?: getString(R.string.ops_placeholder)

        toolbar.subtitle = if (stale) {
            getString(R.string.ops_console_subtitle_stale, time)
        } else {
            getString(R.string.ops_console_subtitle, time, nodeLabel())
        }
    }

    // Replaces the console with a message for accounts that can't use it.
    private fun showRestricted() {
        swipeRefresh.isVisible = false
        fabScan.isVisible = false
        toolbar.menu.findItem(R.id.action_ops_refresh)?.isVisible = false
        toolbar.subtitle = null

        stateRestricted.show(
            R.drawable.ic_lock, OpsTone.NEUTRAL,
            getString(R.string.ops_console_restricted_title), getString(R.string.ops_console_restricted_message),
            getString(R.string.ops_action_go_back)
        ) { finish() }
    }

    // ---------- Details and device log ----------

    // Bottom sheet with every detail of the tapped booking.
    private fun showBookingDetails(booking: OperatorBooking, isToday: Boolean) {
        val dialog = BottomSheetDialog(this)
        val sheet = LayoutInflater.from(this).inflate(R.layout.sheet_ops_booking_details, null)
        val adapter = if (isToday) todayAdapter else pendingAdapter
        val placeholder = getString(R.string.ops_placeholder)

        val name = adapter.displayName(this, booking)
        sheet.findViewById<TextView>(R.id.tvSheetAvatar).text = OpsFormat.initials(name)
        sheet.findViewById<TextView>(R.id.tvSheetName).text = name
        sheet.findViewById<TextView>(R.id.tvSheetNic).text = getString(R.string.ops_nic_value, booking.nic ?: placeholder)
        OpsUi.statusBadge(sheet.findViewById(R.id.tvSheetStatus), booking.status)

        val start = OpsFormat.parse(booking.slotStartTime)
        val end = OpsFormat.parse(booking.slotEndTime)
        val rows = sheet.findViewById<LinearLayout>(R.id.sheetRows)

        OpsUi.addDetailRow(rows, getString(R.string.ops_label_station), adapter.stationName(booking) ?: getString(R.string.ops_unknown_station))
        OpsUi.addDetailRow(rows, getString(R.string.ops_label_slot_date), start?.let { OpsFormat.date(it) } ?: getString(R.string.ops_not_scheduled))
        OpsUi.addDetailRow(rows, getString(R.string.ops_label_slot_time), OpsFormat.slotRange(this, start, end))
        OpsUi.addDetailRow(rows, getString(R.string.ops_label_reserved_energy), OpsFormat.energy(this, booking.energyKWh))

        if (isToday) {
            val qrText = if (booking.qrIssued == true) R.string.ops_qr_issued_long else R.string.ops_qr_not_issued_long
            OpsUi.addDetailRow(rows, getString(R.string.ops_label_qr), getString(qrText))
        }

        OpsUi.addDetailRow(rows, getString(R.string.ops_label_reservation_id), booking.id ?: placeholder, monospace = true)

        // Offer a scan shortcut for today's approved bookings
        val canScan = isToday && booking.status.equals("Approved", ignoreCase = true) &&
            OpsUi.isGridOperator(session.getRole())
        sheet.findViewById<MaterialButton>(R.id.btnSheetScan).apply {
            isVisible = canScan
            setOnClickListener {
                dialog.dismiss()
                startActivity(Intent(this@OperatorConsoleActivity, ScanQrActivity::class.java))
            }
        }
        sheet.findViewById<MaterialButton>(R.id.btnSheetClose).setOnClickListener { dialog.dismiss() }

        dialog.setContentView(sheet)
        dialog.show()
    }

    // Lists this operator's latest verifications stored in SQLite on this phone.
    private fun renderRecent() {
        val entries = cacheDb.recentVerifications(session.getNic().orEmpty(), RECENT_LIMIT)

        sectionRecent.isVisible = entries.isNotEmpty()
        recentRows.removeAllViews()

        entries.forEach { entry ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_ops_recent, recentRows, false)
            val completed = entry.outcome == OpsCacheDb.OUTCOME_COMPLETED
            val tone = if (completed) OpsTone.APPROVED else OpsTone.DANGER

            row.findViewById<FrameLayout>(R.id.recentIconFrame).backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, tone.background))
            row.findViewById<ImageView>(R.id.ivRecentIcon).apply {
                setImageResource(if (completed) R.drawable.ic_check_circle else R.drawable.ic_ops_error)
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, tone.foreground))
            }

            val who = listOfNotNull(entry.prosumerName, entry.stationName).joinToString(" · ")
            row.findViewById<TextView>(R.id.tvRecentTitle).text =
                who.ifBlank { getString(R.string.ops_recent_unknown_booking) }

            row.findViewById<TextView>(R.id.tvRecentSubtitle).text = when {
                completed && entry.deliveredKWh != null ->
                    getString(R.string.ops_recent_completed_delivered, OpsFormat.number(entry.deliveredKWh))
                completed -> getString(R.string.ops_recent_completed)
                else -> getString(R.string.ops_recent_rejected, recentReasonLabel(entry.reason))
            }

            row.findViewById<TextView>(R.id.tvRecentTime).text = OpsFormat.time(Date(entry.createdAt))
            recentRows.addView(row)
        }
    }

    private fun recentReasonLabel(reason: String?): String {
        val details = OpsApiError.describe(OpsFailure(409, reason, ""))
        return getString(details.title)
    }
}
