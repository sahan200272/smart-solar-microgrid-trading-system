// File:        OpsFormat.kt
// Component:   Booking Views & Grid Operator Verification
// Description: Date, time, energy and name formatting for the booking view and Grid
//              Operator screens. Times are shown in Sri Lankan time, matching how the
//              API defines "today" on the operator dashboard.
// Author:      Gunathilaka K.K.N.M.

package com.example.microgridsystem.ui.operator

import android.content.Context
import com.example.microgridsystem.R
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

object OpsFormat {

    val TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Colombo")

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    private const val MINUTES_PER_DAY = 24 * 60
    private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

    // Date, time, optional seconds, optional fraction (any length) and optional offset.
    private val ISO_TIMESTAMP = Regex(
        """^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})(?::(\d{2}))?(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?$"""
    )

    enum class PhaseKey { UPCOMING, LIVE, ENDED }

    data class Phase(val key: PhaseKey, val label: String)

    // Parses an API timestamp such as "2026-09-29T04:30:00.1234567Z".
    // .NET sends up to 7 fraction digits, which SimpleDateFormat would read as milliseconds
    // (about 20 minutes off), so the fraction is cut to 3 digits first. No offset means UTC.
    fun parse(value: String?): Date? {
        if (value.isNullOrBlank()) {
            return null
        }

        val match = ISO_TIMESTAMP.matchEntire(value.trim()) ?: return null
        val (date, hourMinute, seconds, fraction, zone) = match.destructured

        val millis = fraction.padEnd(3, '0').take(3)
        val offset = when {
            zone.isEmpty() || zone == "Z" -> "+00:00"
            zone.length == 5 -> zone.substring(0, 3) + ":" + zone.substring(3)
            else -> zone
        }
        val normalised = "${date}T$hourMinute:${seconds.ifEmpty { "00" }}.$millis$offset"

        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).parse(normalised)
        } catch (e: ParseException) {
            null
        }
    }

    // Formats a date in Sri Lankan time using the given pattern.
    private fun format(pattern: String, value: Date, timeZone: TimeZone = TIME_ZONE): String {
        return SimpleDateFormat(pattern, Locale.ENGLISH).apply { this.timeZone = timeZone }.format(value)
    }

    // "29 Sep 2026"
    fun date(value: Date): String = format("d MMM yyyy", value)

    // "29 Sep"
    fun shortDate(value: Date): String = format("d MMM", value)

    // "9:00 AM"
    fun time(value: Date): String = format("h:mm a", value)

    // "29 Sep 2026, 9:00 AM"
    fun dateTime(value: Date): String = "${date(value)}, ${time(value)}"

    // Date block parts for booking cards: "29", "SEP", "2026".
    fun dayOfMonth(value: Date): String = format("d", value)

    fun monthShort(value: Date): String = format("MMM", value).uppercase(Locale.ENGLISH)

    fun year(value: Date): String = format("yyyy", value)

    // Month section header in the booking history: "September 2026".
    fun monthYear(value: Date): String = format("MMMM yyyy", value)

    // Calendar-day key used to compare days: "2026-09-29".
    fun dayKey(value: Date): String = format("yyyy-MM-dd", value)

    // Day key for a date picked in MaterialDatePicker, which returns UTC midnight.
    fun pickerDayKey(utcMillis: Long): String = format("yyyy-MM-dd", Date(utcMillis), UTC)

    // Label for a date picked in MaterialDatePicker: "29 Sep".
    fun pickerShortDate(utcMillis: Long): String = format("d MMM", Date(utcMillis), UTC)

    // Words that make a booking findable by date, e.g. "29 sep 2026 september tue".
    fun searchableDate(value: Date): String {
        return listOf(date(value), shortDate(value), monthYear(value), format("EEEE EEE", value), time(value))
            .joinToString(" ")
    }

    // Slot window text: "9:00 AM – 10:00 AM", or "9:00 AM – 30 Sep, 1:00 AM" across midnight.
    fun slotRange(context: Context, start: Date?, end: Date?): String {
        if (start == null) {
            return context.getString(R.string.ops_not_scheduled)
        }

        if (end == null) {
            return context.getString(R.string.ops_slot_from, time(start))
        }

        val endText = if (dayKey(start) == dayKey(end)) {
            time(end)
        } else {
            "${shortDate(end)}, ${time(end)}"
        }

        return context.getString(R.string.ops_slot_range, time(start), endText)
    }

    // Where "now" sits relative to a slot: upcoming (with a countdown), in progress or ended.
    fun phase(context: Context, start: Date?, end: Date?, now: Date = Date()): Phase? {
        if (start == null) {
            return null
        }

        if (now.before(start)) {
            val minutes = ((start.time - now.time) / 60000.0).roundToInt().coerceAtLeast(1)
            val hours = minutes / 60
            val remainder = minutes % 60

            val label = when {
                minutes < 60 -> context.getString(R.string.ops_phase_minutes, minutes)
                minutes < MINUTES_PER_DAY && remainder == 0 -> context.getString(R.string.ops_phase_hours, hours)
                minutes < MINUTES_PER_DAY -> context.getString(R.string.ops_phase_hours_minutes, hours, remainder)
                else -> {
                    val days = minutes / MINUTES_PER_DAY
                    context.resources.getQuantityString(R.plurals.ops_phase_days, days, days)
                }
            }

            return Phase(PhaseKey.UPCOMING, label)
        }

        if (end == null || now.before(end)) {
            return Phase(PhaseKey.LIVE, context.getString(R.string.ops_phase_live))
        }

        return Phase(PhaseKey.ENDED, context.getString(R.string.ops_phase_ended))
    }

    // Friendly day plus time: "Today, 9:00 AM", "Tomorrow, 9:00 AM" or "Thu 2 Oct, 9:00 AM".
    fun relativeDayTime(context: Context, value: Date, now: Date = Date()): String {
        val day = when (dayKey(value)) {
            dayKey(now) -> context.getString(R.string.ops_day_today)
            dayKey(Date(now.time + MILLIS_PER_DAY)) -> context.getString(R.string.ops_day_tomorrow)
            else -> format("EEE d MMM", value)
        }

        return context.getString(R.string.ops_day_time, day, time(value))
    }

    // "1,250.5" — up to two decimals, no trailing zeros.
    fun number(value: Double): String {
        return DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US)).format(value)
    }

    // Energy amount such as "25 kWh". Older bookings saved 0 when no amount was recorded.
    fun energy(context: Context, value: Double?): String {
        return if (value != null && value > 0) {
            context.getString(R.string.ops_energy_value, number(value))
        } else {
            context.getString(R.string.ops_placeholder)
        }
    }

    // Count with thousands separators, or a dash when unknown.
    fun count(value: Long?): String {
        return value?.let { NumberFormat.getIntegerInstance(Locale.US).format(it) } ?: "—"
    }

    // Two-letter initials for avatars: "Nimal Perera" -> "NP".
    fun initials(name: String?): String {
        val parts = name.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(2).uppercase(Locale.ENGLISH)
            else -> "${parts.first().first()}${parts.last().first()}".uppercase(Locale.ENGLISH)
        }
    }

    // Short booking reference for cards. The end of a MongoDB id is the most distinctive part.
    fun shortId(id: String?): String {
        return if (id.isNullOrBlank()) "" else "#${id.takeLast(6)}"
    }

    // Status label with a capital first letter: "approved" -> "Approved".
    fun statusLabel(status: String?): String {
        return status?.trim()?.lowercase(Locale.ENGLISH)
            ?.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
            ?.takeIf { it.isNotEmpty() }
            ?: "Unknown"
    }
}
