package com.example.microgridsystem.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DateTimeUtils {

    private val isoFormats = arrayOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm"
    )

    fun parseIsoToDate(isoString: String?): Date? {
        if (isoString.isNullOrBlank()) return null
        for (pattern in isoFormats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val date = sdf.parse(isoString)
                if (date != null) return date
            } catch (ignored: Exception) {
            }
        }
        return null
    }

    fun formatIsoToDisplay(isoString: String?): String {
        if (isoString.isNullOrBlank()) return "N/A"
        val date = parseIsoToDate(isoString) ?: return isoString
        val displayFormat = SimpleDateFormat("MMM dd, yyyy  hh:mm a", Locale.getDefault())
        displayFormat.timeZone = TimeZone.getDefault()
        return displayFormat.format(date)
    }

    fun formatIsoToDateOnly(isoString: String?): String {
        if (isoString.isNullOrBlank()) return "N/A"
        val date = parseIsoToDate(isoString) ?: return isoString
        val displayFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        return displayFormat.format(date)
    }

    fun formatIsoToTimeOnly(isoString: String?): String {
        if (isoString.isNullOrBlank()) return "N/A"
        val date = parseIsoToDate(isoString) ?: return isoString
        val displayFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return displayFormat.format(date)
    }

    fun formatCalendarToIso(calendar: Calendar): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(calendar.time)
    }

    fun formatDateToIso(date: Date): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(date)
    }

    /**
     * Soft check: Returns true if the slot start time is less than 12 hours from now.
     * Note: Server-side validation is still the authoritative check.
     */
    fun isLessThan12HoursAway(slotStartTimeIso: String?): Boolean {
        val date = parseIsoToDate(slotStartTimeIso) ?: return false
        val now = Date()
        val diffMs = date.time - now.time
        val twelveHoursMs = 12 * 60 * 60 * 1000L
        return diffMs < twelveHoursMs
    }

    /**
     * Soft check: Returns hours remaining until slot start time.
     */
    fun getHoursUntilSlot(slotStartTimeIso: String?): Long {
        val date = parseIsoToDate(slotStartTimeIso) ?: return 0L
        val diffMs = date.time - Date().time
        return diffMs / (60 * 60 * 1000L)
    }
}
