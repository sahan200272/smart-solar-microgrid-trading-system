// File:        ReservationCacheDb.kt
// Component:   Energy Slot Reservation (Prosumer Reservation Module)
// Description: Local SQLite database for non-authoritative caching and draft form persistence.
//              Used strictly as a short-lived cache (e.g. instant UI rendering while fresh API
//              calls are in flight, offline viewing) and to hold in-progress draft form values.
//              Central source of truth is MongoDB via the C# Web API (ReservationsController / SlotsController).
//              No business decisions (such as the 12-hour rule, 7-day advance booking, or slot capacity)
//              are ever decided locally from this cache.

package com.example.microgridsystem.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.microgridsystem.models.ReservationDashboardResponse
import com.example.microgridsystem.models.ReservationResponse

/**
 * Data class representing an in-progress draft of a new reservation form.
 * Enables restoring unsubmitted form inputs across screen rotations or app restarts.
 */
data class DraftReservation(
    val prosumerNic: String,
    val nodeId: String? = null,
    val stationName: String? = null,
    val energyKWh: Double? = null,
    val startTimeMillis: Long? = null,
    val endTimeMillis: Long? = null
)

class ReservationCacheDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "reservation_cache.db"
        private const val DATABASE_VERSION = 1

        // Table 1: Cached Reservations List
        private const val TABLE_RESERVATIONS = "cached_reservations"
        private const val COL_ID = "id"
        private const val COL_PROSUMER_NIC = "prosumer_nic"
        private const val COL_NODE_ID = "node_id"
        private const val COL_STATION_NAME = "station_name"
        private const val COL_ENERGY_KWH = "energy_kwh"
        private const val COL_SLOT_START_TIME = "slot_start_time"
        private const val COL_SLOT_END_TIME = "slot_end_time"
        private const val COL_STATUS = "status"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_UPDATED_AT = "updated_at"
        private const val COL_CANCELLED_AT = "cancelled_at"
        private const val COL_CACHED_AT = "cached_at"

        // Table 2: Cached Dashboard Counts
        private const val TABLE_DASHBOARD = "cached_dashboard_summary"
        private const val COL_DASH_NIC = "prosumer_nic"
        private const val COL_ACTIVE_COUNT = "active_count"
        private const val COL_PENDING_COUNT = "pending_count"
        private const val COL_DASH_CACHED_AT = "cached_at"

        // Table 3: Draft Reservation Form Inputs
        private const val TABLE_DRAFT = "draft_new_reservation"
        private const val COL_DRAFT_NIC = "prosumer_nic"
        private const val COL_DRAFT_NODE_ID = "node_id"
        private const val COL_DRAFT_STATION_NAME = "station_name"
        private const val COL_DRAFT_ENERGY_KWH = "energy_kwh"
        private const val COL_DRAFT_START_MILLIS = "start_time_millis"
        private const val COL_DRAFT_END_MILLIS = "end_time_millis"
        private const val COL_DRAFT_SAVED_AT = "saved_at"

        @Volatile
        private var INSTANCE: ReservationCacheDb? = null

        fun getInstance(context: Context): ReservationCacheDb {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ReservationCacheDb(context).also { INSTANCE = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Table 1: Cached reservations
        db.execSQL(
            """
            CREATE TABLE $TABLE_RESERVATIONS (
                $COL_ID TEXT PRIMARY KEY,
                $COL_PROSUMER_NIC TEXT NOT NULL,
                $COL_NODE_ID TEXT,
                $COL_STATION_NAME TEXT,
                $COL_ENERGY_KWH REAL,
                $COL_SLOT_START_TIME TEXT,
                $COL_SLOT_END_TIME TEXT,
                $COL_STATUS TEXT,
                $COL_CREATED_AT TEXT,
                $COL_UPDATED_AT TEXT,
                $COL_CANCELLED_AT TEXT,
                $COL_CACHED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // Table 2: Cached dashboard summary counts
        db.execSQL(
            """
            CREATE TABLE $TABLE_DASHBOARD (
                $COL_DASH_NIC TEXT PRIMARY KEY,
                $COL_ACTIVE_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_PENDING_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_DASH_CACHED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // Table 3: Draft new reservation
        db.execSQL(
            """
            CREATE TABLE $TABLE_DRAFT (
                $COL_DRAFT_NIC TEXT PRIMARY KEY,
                $COL_DRAFT_NODE_ID TEXT,
                $COL_DRAFT_STATION_NAME TEXT,
                $COL_DRAFT_ENERGY_KWH REAL,
                $COL_DRAFT_START_MILLIS INTEGER,
                $COL_DRAFT_END_MILLIS INTEGER,
                $COL_DRAFT_SAVED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_RESERVATIONS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_DASHBOARD")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_DRAFT")
        onCreate(db)
    }

    // =========================================================================
    // RESERVATIONS LIST CACHING (Non-authoritative, for fast display / offline)
    // =========================================================================

    /**
     * Atomically replaces the cached reservations for a given prosumer with fresh data from the API.
     */
    fun replaceCachedReservations(prosumerNic: String, reservations: List<ReservationResponse>) {
        val db = writableDatabase
        val now = System.currentTimeMillis()

        db.beginTransaction()
        try {
            if (prosumerNic.isNotBlank()) {
                db.delete(TABLE_RESERVATIONS, "$COL_PROSUMER_NIC = ?", arrayOf(prosumerNic))
            } else {
                db.delete(TABLE_RESERVATIONS, null, null)
            }

            reservations.forEach { r ->
                val values = ContentValues().apply {
                    put(COL_ID, r.id)
                    put(COL_PROSUMER_NIC, if (r.prosumerNic.isNotBlank()) r.prosumerNic else prosumerNic)
                    put(COL_NODE_ID, r.nodeId)
                    put(COL_STATION_NAME, r.stationName)
                    put(COL_ENERGY_KWH, r.energyKWh)
                    put(COL_SLOT_START_TIME, r.slotStartTime)
                    put(COL_SLOT_END_TIME, r.slotEndTime)
                    put(COL_STATUS, r.status)
                    put(COL_CREATED_AT, r.createdAt)
                    put(COL_UPDATED_AT, r.updatedAt)
                    put(COL_CANCELLED_AT, r.cancelledAt)
                    put(COL_CACHED_AT, now)
                }
                db.insertWithOnConflict(TABLE_RESERVATIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Retrieves the cached reservations for fast initial display before fresh network fetch returns.
     */
    fun getCachedReservations(prosumerNic: String, statusFilter: String? = null): List<ReservationResponse> {
        val list = mutableListOf<ReservationResponse>()
        val selectionBuilder = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        if (prosumerNic.isNotBlank()) {
            selectionBuilder.add("$COL_PROSUMER_NIC = ?")
            selectionArgs.add(prosumerNic)
        }

        if (!statusFilter.isNullOrBlank()) {
            selectionBuilder.add("$COL_STATUS = ? COLLATE NOCASE")
            selectionArgs.add(statusFilter)
        }

        val selection = if (selectionBuilder.isNotEmpty()) selectionBuilder.joinToString(" AND ") else null
        val args = if (selectionArgs.isNotEmpty()) selectionArgs.toTypedArray() else null

        readableDatabase.query(
            TABLE_RESERVATIONS,
            null,
            selection,
            args,
            null,
            null,
            "$COL_SLOT_START_TIME DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.toReservationResponse())
            }
        }
        return list
    }

    /**
     * Clears cached reservations for the specified prosumer or all.
     */
    fun clearCachedReservations(prosumerNic: String? = null) {
        val db = writableDatabase
        if (!prosumerNic.isNullOrBlank()) {
            db.delete(TABLE_RESERVATIONS, "$COL_PROSUMER_NIC = ?", arrayOf(prosumerNic))
        } else {
            db.delete(TABLE_RESERVATIONS, null, null)
        }
    }

    // =========================================================================
    // DASHBOARD COUNTS CACHING
    // =========================================================================

    /**
     * Saves last-fetched active and pending reservation counts for fast dashboard display.
     */
    fun saveCachedDashboard(prosumerNic: String, activeCount: Long, pendingCount: Long) {
        if (prosumerNic.isBlank()) return
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_DASH_NIC, prosumerNic)
            put(COL_ACTIVE_COUNT, activeCount)
            put(COL_PENDING_COUNT, pendingCount)
            put(COL_DASH_CACHED_AT, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_DASHBOARD, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Reads last cached dashboard counts.
     */
    fun getCachedDashboard(prosumerNic: String): ReservationDashboardResponse? {
        if (prosumerNic.isBlank()) return null
        readableDatabase.query(
            TABLE_DASHBOARD,
            null,
            "$COL_DASH_NIC = ?",
            arrayOf(prosumerNic),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val active = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ACTIVE_COUNT))
                val pending = cursor.getLong(cursor.getColumnIndexOrThrow(COL_PENDING_COUNT))
                return ReservationDashboardResponse(activeCount = active, pendingCount = pending)
            }
        }
        return null
    }

    // =========================================================================
    // DRAFT FORM PERSISTENCE (survives rotation / app restarts before submit)
    // =========================================================================

    /**
     * Saves in-progress form inputs so the user doesn't lose typed data.
     */
    fun saveDraft(draft: DraftReservation) {
        if (draft.prosumerNic.isBlank()) return
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_DRAFT_NIC, draft.prosumerNic)
            put(COL_DRAFT_NODE_ID, draft.nodeId)
            put(COL_DRAFT_STATION_NAME, draft.stationName)
            put(COL_DRAFT_ENERGY_KWH, draft.energyKWh)
            put(COL_DRAFT_START_MILLIS, draft.startTimeMillis)
            put(COL_DRAFT_END_MILLIS, draft.endTimeMillis)
            put(COL_DRAFT_SAVED_AT, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_DRAFT, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Retrieves saved draft form data if available.
     */
    fun getDraft(prosumerNic: String): DraftReservation? {
        if (prosumerNic.isBlank()) return null
        readableDatabase.query(
            TABLE_DRAFT,
            null,
            "$COL_DRAFT_NIC = ?",
            arrayOf(prosumerNic),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return DraftReservation(
                    prosumerNic = cursor.getString(cursor.getColumnIndexOrThrow(COL_DRAFT_NIC)),
                    nodeId = cursor.stringOrNull(COL_DRAFT_NODE_ID),
                    stationName = cursor.stringOrNull(COL_DRAFT_STATION_NAME),
                    energyKWh = cursor.doubleOrNull(COL_DRAFT_ENERGY_KWH),
                    startTimeMillis = cursor.longOrNull(COL_DRAFT_START_MILLIS),
                    endTimeMillis = cursor.longOrNull(COL_DRAFT_END_MILLIS)
                )
            }
        }
        return null
    }

    /**
     * Clears draft form data after a reservation is successfully created.
     */
    fun clearDraft(prosumerNic: String) {
        if (prosumerNic.isBlank()) return
        val db = writableDatabase
        db.delete(TABLE_DRAFT, "$COL_DRAFT_NIC = ?", arrayOf(prosumerNic))
    }

    // =========================================================================
    // CURSOR CONVERSION HELPERS
    // =========================================================================

    private fun Cursor.toReservationResponse(): ReservationResponse {
        return ReservationResponse(
            id = getString(getColumnIndexOrThrow(COL_ID)),
            prosumerNic = getString(getColumnIndexOrThrow(COL_PROSUMER_NIC)),
            nodeId = getString(getColumnIndexOrThrow(COL_NODE_ID)) ?: "",
            stationName = getString(getColumnIndexOrThrow(COL_STATION_NAME)) ?: "",
            energyKWh = getDouble(getColumnIndexOrThrow(COL_ENERGY_KWH)),
            slotStartTime = getString(getColumnIndexOrThrow(COL_SLOT_START_TIME)) ?: "",
            slotEndTime = getString(getColumnIndexOrThrow(COL_SLOT_END_TIME)) ?: "",
            status = getString(getColumnIndexOrThrow(COL_STATUS)) ?: "Pending",
            createdAt = stringOrNull(COL_CREATED_AT),
            updatedAt = stringOrNull(COL_UPDATED_AT),
            cancelledAt = stringOrNull(COL_CANCELLED_AT),
            slotId = null,
            qrToken = null,
            qrExpiresAt = null,
            qrUsedAt = null
        )
    }

    private fun Cursor.stringOrNull(column: String): String? {
        val idx = getColumnIndexOrThrow(column)
        return if (isNull(idx)) null else getString(idx)
    }

    private fun Cursor.doubleOrNull(column: String): Double? {
        val idx = getColumnIndexOrThrow(column)
        return if (isNull(idx)) null else getDouble(idx)
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val idx = getColumnIndexOrThrow(column)
        return if (isNull(idx)) null else getLong(idx)
    }
}
