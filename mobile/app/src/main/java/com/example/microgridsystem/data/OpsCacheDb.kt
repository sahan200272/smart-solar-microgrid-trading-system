// File:        OpsCacheDb.kt
// Component:   Booking Views & Grid Operator Verification
// Description: Local SQLite store for the Grid Operator screens. Caches the station list
//              (reference data from GET /api/operator/stations) so the node picker works
//              instantly, and keeps a log of QR verifications made on this device.
//              It never decides anything: the API remains the source of truth.
// Author:      Gunathilaka K.K.N.M.

package com.example.microgridsystem.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.microgridsystem.models.OperatorStation

// One row of the on-device verification log.
data class VerificationLogEntry(
    val reservationId: String?,
    val prosumerName: String?,
    val stationName: String?,
    val outcome: String,
    val reason: String?,
    val deliveredKWh: Double?,
    val createdAt: Long
)

class OpsCacheDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        // Separate database file, so it never clashes with other components' local storage.
        private const val DATABASE_NAME = "ops_cache.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_STATIONS = "stations"
        private const val TABLE_LOG = "verification_log"

        // Keeps the log small; older rows are removed after each insert.
        private const val MAX_LOG_ROWS = 50

        const val OUTCOME_COMPLETED = "COMPLETED"
        const val OUTCOME_REJECTED = "REJECTED"
    }

    // Creates the station cache and verification log tables.
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_STATIONS (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                latitude REAL,
                longitude REAL,
                available_slots INTEGER,
                total_slots INTEGER,
                cached_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_LOG (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                operator_nic TEXT NOT NULL,
                reservation_id TEXT,
                prosumer_name TEXT,
                station_name TEXT,
                outcome TEXT NOT NULL,
                reason TEXT,
                delivered_kwh REAL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    // Both tables only hold cached or device-local data, so an upgrade simply rebuilds them.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_STATIONS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LOG")
        onCreate(db)
    }

    // Replaces the cached station list with the latest one from the API.
    fun replaceStations(stations: List<OperatorStation>) {
        val db = writableDatabase
        val now = System.currentTimeMillis()

        db.beginTransaction()
        try {
            db.delete(TABLE_STATIONS, null, null)

            stations.forEach { station ->
                val id = station.id ?: return@forEach
                val values = ContentValues().apply {
                    put("id", id)
                    put("name", station.stationName ?: id)
                    put("latitude", station.latitude)
                    put("longitude", station.longitude)
                    put("available_slots", station.availableBatterySlots)
                    put("total_slots", station.totalBatterySlots)
                    put("cached_at", now)
                }
                db.insertWithOnConflict(TABLE_STATIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // Returns the cached stations sorted by name, or an empty list before the first sync.
    fun getStations(): List<OperatorStation> {
        val stations = mutableListOf<OperatorStation>()

        readableDatabase.query(
            TABLE_STATIONS, null, null, null, null, null, "name COLLATE NOCASE ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                stations.add(
                    OperatorStation(
                        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                        stationName = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        latitude = cursor.doubleOrNull("latitude"),
                        longitude = cursor.doubleOrNull("longitude"),
                        availableBatterySlots = cursor.intOrNull("available_slots"),
                        totalBatterySlots = cursor.intOrNull("total_slots")
                    )
                )
            }
        }

        return stations
    }

    // Records the outcome of a verification made by this operator on this device.
    fun logVerification(
        operatorNic: String,
        reservationId: String?,
        prosumerName: String?,
        stationName: String?,
        outcome: String,
        reason: String?,
        deliveredKWh: Double?
    ) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("operator_nic", operatorNic)
            put("reservation_id", reservationId)
            put("prosumer_name", prosumerName)
            put("station_name", stationName)
            put("outcome", outcome)
            put("reason", reason)
            put("delivered_kwh", deliveredKWh)
            put("created_at", System.currentTimeMillis())
        }

        db.insert(TABLE_LOG, null, values)
        db.execSQL(
            "DELETE FROM $TABLE_LOG WHERE id NOT IN " +
                "(SELECT id FROM $TABLE_LOG ORDER BY id DESC LIMIT $MAX_LOG_ROWS)"
        )
    }

    // Returns this operator's most recent verifications, newest first.
    fun recentVerifications(operatorNic: String, limit: Int): List<VerificationLogEntry> {
        val entries = mutableListOf<VerificationLogEntry>()

        readableDatabase.query(
            TABLE_LOG, null, "operator_nic = ?", arrayOf(operatorNic),
            null, null, "id DESC", limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries.add(
                    VerificationLogEntry(
                        reservationId = cursor.stringOrNull("reservation_id"),
                        prosumerName = cursor.stringOrNull("prosumer_name"),
                        stationName = cursor.stringOrNull("station_name"),
                        outcome = cursor.getString(cursor.getColumnIndexOrThrow("outcome")),
                        reason = cursor.stringOrNull("reason"),
                        deliveredKWh = cursor.doubleOrNull("delivered_kwh"),
                        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }

        return entries
    }

    // Reads a nullable text column.
    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    // Reads a nullable real column.
    private fun Cursor.doubleOrNull(column: String): Double? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getDouble(index)
    }

    // Reads a nullable integer column.
    private fun Cursor.intOrNull(column: String): Int? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index)
    }
}
