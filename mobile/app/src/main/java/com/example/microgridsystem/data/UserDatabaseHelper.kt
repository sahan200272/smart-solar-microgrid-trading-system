// File:        UserDatabaseHelper.kt
// Component:   User & Access Management
// Description: Local SQLite store for User & Access Management. Handles native SQLite
//              persistence and caching for authenticated user sessions, user profiles,
//              account status, local registrations, and the Grid Operator's prosumer directory.
//              Strictly uses Android SQLiteOpenHelper and native SQLite APIs (no Room/Firebase).
//              The C# Web API remains the central source of truth for business logic and MongoDB data.
// Author:      Rashipaba Gimhani

package com.example.microgridsystem.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.microgridsystem.models.ProsumerProfileResponse

/**
 * Data class representing a locally stored user session/profile in SQLite.
 */
data class LocalUserSession(
    val nic: String,
    val token: String?,
    val fullName: String?,
    val email: String?,
    val phone: String?,
    val address: String?,
    val role: String?,
    val status: String?,
    val isLoggedIn: Boolean,
    val createdAt: String?,
    val updatedAt: Long
)

class UserDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        // Separate database file for User & Access Management, preventing clashes with other components
        private const val DATABASE_NAME = "user_access.db"
        private const val DATABASE_VERSION = 1

        // Table 1: Logged-in user and local user profiles
        private const val TABLE_USERS = "users"
        private const val COL_NIC = "nic"
        private const val COL_TOKEN = "token"
        private const val COL_FULL_NAME = "full_name"
        private const val COL_EMAIL = "email"
        private const val COL_PHONE = "phone"
        private const val COL_ADDRESS = "address"
        private const val COL_ROLE = "role"
        private const val COL_STATUS = "status"
        private const val COL_IS_LOGGED_IN = "is_logged_in"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_UPDATED_AT = "updated_at"

        // Table 2: Prosumer Directory Cache (for Grid Operator)
        private const val TABLE_PROSUMERS_CACHE = "cached_prosumers"
        private const val COL_CACHE_NIC = "nic"
        private const val COL_CACHE_FULL_NAME = "full_name"
        private const val COL_CACHE_EMAIL = "email"
        private const val COL_CACHE_PHONE = "phone"
        private const val COL_CACHE_ADDRESS = "address"
        private const val COL_CACHE_STATUS = "status"
        private const val COL_CACHE_CREATED_AT = "created_at"
        private const val COL_CACHE_TIMESTAMP = "cached_at"

        @Volatile
        private var INSTANCE: UserDatabaseHelper? = null

        fun getInstance(context: Context): UserDatabaseHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserDatabaseHelper(context).also { INSTANCE = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Create user session and profile table
        db.execSQL(
            """
            CREATE TABLE $TABLE_USERS (
                $COL_NIC TEXT PRIMARY KEY,
                $COL_TOKEN TEXT,
                $COL_FULL_NAME TEXT,
                $COL_EMAIL TEXT,
                $COL_PHONE TEXT,
                $COL_ADDRESS TEXT,
                $COL_ROLE TEXT,
                $COL_STATUS TEXT,
                $COL_IS_LOGGED_IN INTEGER DEFAULT 0,
                $COL_CREATED_AT TEXT,
                $COL_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // Create cached prosumers directory table for Grid Operator
        db.execSQL(
            """
            CREATE TABLE $TABLE_PROSUMERS_CACHE (
                $COL_CACHE_NIC TEXT PRIMARY KEY,
                $COL_CACHE_FULL_NAME TEXT,
                $COL_CACHE_EMAIL TEXT,
                $COL_CACHE_PHONE TEXT,
                $COL_CACHE_ADDRESS TEXT,
                $COL_CACHE_STATUS TEXT,
                $COL_CACHE_CREATED_AT TEXT,
                $COL_CACHE_TIMESTAMP INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_USERS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PROSUMERS_CACHE")
        onCreate(db)
    }

    // =========================================================================
    // USER SESSION & PROFILE OPERATIONS
    // =========================================================================

    /**
     * Saves or updates the login session for the user upon successful login.
     * Sets any prior logged-in session to 0 to ensure one active user at a time.
     */
    fun saveLoginSession(
        nic: String,
        token: String,
        fullName: String,
        role: String,
        status: String
    ) {
        val db = writableDatabase
        val now = System.currentTimeMillis()

        db.beginTransaction()
        try {
            // Reset active login flag on any existing accounts
            val resetValues = ContentValues().apply {
                put(COL_IS_LOGGED_IN, 0)
            }
            db.update(TABLE_USERS, resetValues, null, null)

            val existing = getUserByNic(nic)
            val values = ContentValues().apply {
                put(COL_NIC, nic)
                put(COL_TOKEN, token)
                put(COL_FULL_NAME, fullName)
                put(COL_ROLE, role)
                put(COL_STATUS, status)
                put(COL_IS_LOGGED_IN, 1)
                put(COL_UPDATED_AT, now)
                if (existing != null) {
                    if (!existing.email.isNullOrBlank()) put(COL_EMAIL, existing.email)
                    if (!existing.phone.isNullOrBlank()) put(COL_PHONE, existing.phone)
                    if (!existing.address.isNullOrBlank()) put(COL_ADDRESS, existing.address)
                    if (!existing.createdAt.isNullOrBlank()) put(COL_CREATED_AT, existing.createdAt)
                }
            }

            db.insertWithOnConflict(TABLE_USERS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Records a newly registered prosumer in SQLite with Pending status.
     */
    fun saveRegistration(
        nic: String,
        fullName: String,
        email: String,
        phone: String,
        address: String,
        status: String = "Pending"
    ) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_NIC, nic)
            put(COL_FULL_NAME, fullName)
            put(COL_EMAIL, email)
            put(COL_PHONE, phone)
            put(COL_ADDRESS, address)
            put(COL_ROLE, "Prosumer")
            put(COL_STATUS, status)
            put(COL_IS_LOGGED_IN, 0)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_USERS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Updates full profile information from the API response (GET /api/prosumers/{nic}).
     */
    fun saveOrUpdateFullProfile(profile: ProsumerProfileResponse) {
        val nic = profile.nic ?: return
        val db = writableDatabase
        val now = System.currentTimeMillis()

        val existing = getUserByNic(nic)
        val values = ContentValues().apply {
            put(COL_NIC, nic)
            put(COL_FULL_NAME, profile.fullName ?: existing?.fullName)
            put(COL_EMAIL, profile.email ?: existing?.email)
            put(COL_PHONE, profile.phone ?: existing?.phone)
            put(COL_ADDRESS, profile.address ?: existing?.address)
            put(COL_STATUS, profile.status ?: existing?.status)
            put(COL_CREATED_AT, profile.createdAt ?: existing?.createdAt)
            put(COL_UPDATED_AT, now)
            if (existing != null) {
                put(COL_TOKEN, existing.token)
                put(COL_ROLE, existing.role)
                put(COL_IS_LOGGED_IN, if (existing.isLoggedIn) 1 else 0)
            }
        }
        db.insertWithOnConflict(TABLE_USERS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Updates editable profile fields (full name, email, phone, address).
     */
    fun updateProfile(
        nic: String,
        fullName: String,
        email: String,
        phone: String,
        address: String
    ) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_FULL_NAME, fullName)
            put(COL_EMAIL, email)
            put(COL_PHONE, phone)
            put(COL_ADDRESS, address)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        db.update(TABLE_USERS, values, "$COL_NIC = ?", arrayOf(nic))
    }

    /**
     * Updates account status (e.g. Active, Pending, Deactivated).
     */
    fun updateAccountStatus(nic: String, status: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_STATUS, status)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        db.update(TABLE_USERS, values, "$COL_NIC = ?", arrayOf(nic))
    }

    /**
     * Updates user full name.
     */
    fun updateFullName(nic: String, fullName: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_FULL_NAME, fullName)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        db.update(TABLE_USERS, values, "$COL_NIC = ?", arrayOf(nic))
    }

    /**
     * Returns the currently active logged-in user session, or null if none.
     */
    fun getLoggedInUser(): LocalUserSession? {
        val db = readableDatabase
        db.query(
            TABLE_USERS, null, "$COL_IS_LOGGED_IN = 1", null,
            null, null, null, "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.toUserSession()
            }
        }
        return null
    }

    /**
     * Returns user session/profile by NIC.
     */
    fun getUserByNic(nic: String): LocalUserSession? {
        val db = readableDatabase
        db.query(
            TABLE_USERS, null, "$COL_NIC = ?", arrayOf(nic),
            null, null, null, "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.toUserSession()
            }
        }
        return null
    }

    /**
     * Clears active logged-in session (sets is_logged_in = 0).
     */
    fun clearLoggedInSession() {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_IS_LOGGED_IN, 0)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        db.update(TABLE_USERS, values, null, null)
    }

    // =========================================================================
    // PROSUMER DIRECTORY CACHE (GRID OPERATOR)
    // =========================================================================

    /**
     * Caches the prosumer list from GET /api/prosumers.
     */
    fun replaceCachedProsumers(prosumers: List<ProsumerProfileResponse>) {
        val db = writableDatabase
        val now = System.currentTimeMillis()

        db.beginTransaction()
        try {
            db.delete(TABLE_PROSUMERS_CACHE, null, null)

            prosumers.forEach { p ->
                val nic = p.nic ?: return@forEach
                val values = ContentValues().apply {
                    put(COL_CACHE_NIC, nic)
                    put(COL_CACHE_FULL_NAME, p.fullName)
                    put(COL_CACHE_EMAIL, p.email)
                    put(COL_CACHE_PHONE, p.phone)
                    put(COL_CACHE_ADDRESS, p.address)
                    put(COL_CACHE_STATUS, p.status)
                    put(COL_CACHE_CREATED_AT, p.createdAt)
                    put(COL_CACHE_TIMESTAMP, now)
                }
                db.insertWithOnConflict(
                    TABLE_PROSUMERS_CACHE,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Retrieves all cached prosumers from SQLite.
     */
    fun getAllCachedProsumers(): List<ProsumerProfileResponse> {
        val list = mutableListOf<ProsumerProfileResponse>()
        readableDatabase.query(
            TABLE_PROSUMERS_CACHE, null, null, null, null, null,
            "$COL_CACHE_FULL_NAME COLLATE NOCASE ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.toProsumerProfileResponse())
            }
        }
        return list
    }

    /**
     * Retrieves a single cached prosumer by NIC.
     */
    fun getCachedProsumer(nic: String): ProsumerProfileResponse? {
        readableDatabase.query(
            TABLE_PROSUMERS_CACHE, null, "$COL_CACHE_NIC = ?", arrayOf(nic),
            null, null, null, "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.toProsumerProfileResponse()
            }
        }
        return null
    }

    /**
     * Saves or updates a single prosumer in the directory cache.
     */
    fun saveOrUpdateCachedProsumer(p: ProsumerProfileResponse) {
        val nic = p.nic ?: return
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_CACHE_NIC, nic)
            put(COL_CACHE_FULL_NAME, p.fullName)
            put(COL_CACHE_EMAIL, p.email)
            put(COL_CACHE_PHONE, p.phone)
            put(COL_CACHE_ADDRESS, p.address)
            put(COL_CACHE_STATUS, p.status)
            put(COL_CACHE_CREATED_AT, p.createdAt)
            put(COL_CACHE_TIMESTAMP, System.currentTimeMillis())
        }
        db.insertWithOnConflict(
            TABLE_PROSUMERS_CACHE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // =========================================================================
    // CURSOR CONVERSION HELPERS
    // =========================================================================

    private fun Cursor.toUserSession(): LocalUserSession {
        return LocalUserSession(
            nic = getString(getColumnIndexOrThrow(COL_NIC)),
            token = stringOrNull(COL_TOKEN),
            fullName = stringOrNull(COL_FULL_NAME),
            email = stringOrNull(COL_EMAIL),
            phone = stringOrNull(COL_PHONE),
            address = stringOrNull(COL_ADDRESS),
            role = stringOrNull(COL_ROLE),
            status = stringOrNull(COL_STATUS),
            isLoggedIn = getInt(getColumnIndexOrThrow(COL_IS_LOGGED_IN)) == 1,
            createdAt = stringOrNull(COL_CREATED_AT),
            updatedAt = getLong(getColumnIndexOrThrow(COL_UPDATED_AT))
        )
    }

    private fun Cursor.toProsumerProfileResponse(): ProsumerProfileResponse {
        return ProsumerProfileResponse(
            id = null,
            nic = stringOrNull(COL_CACHE_NIC),
            fullName = stringOrNull(COL_CACHE_FULL_NAME),
            email = stringOrNull(COL_CACHE_EMAIL),
            phone = stringOrNull(COL_CACHE_PHONE),
            address = stringOrNull(COL_CACHE_ADDRESS),
            status = stringOrNull(COL_CACHE_STATUS),
            createdAt = stringOrNull(COL_CACHE_CREATED_AT)
        )
    }

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }
}
