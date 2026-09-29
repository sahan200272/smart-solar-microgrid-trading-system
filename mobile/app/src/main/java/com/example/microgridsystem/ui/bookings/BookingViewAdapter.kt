// File:        BookingViewAdapter.kt
// Component:   Booking Views & Grid Operator Verification
// Description: RecyclerView adapter for the prosumer's booking views. Shows booking cards
//              and, in the history view, month headings between them.
// Author:      Gunathilaka K.K.N.M.

package com.example.microgridsystem.ui.bookings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.microgridsystem.R
import com.example.microgridsystem.models.ReservationResponse
import com.example.microgridsystem.ui.operator.OpsFormat
import com.example.microgridsystem.ui.operator.OpsTone
import com.example.microgridsystem.ui.operator.OpsUi

class BookingViewAdapter(
    private val onClick: (ReservationResponse) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // A list row: either a month heading or a booking (with or without its countdown badge).
    sealed class Row {
        data class Header(val label: String) : Row()
        data class Booking(val booking: ReservationResponse, val showPhase: Boolean) : Row()
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_BOOKING = 1
    }

    private var rows: List<Row> = emptyList()

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLabel: TextView = view.findViewById(R.id.tvMonthHeader)
    }

    class BookingHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDay: TextView = view.findViewById(R.id.tvDay)
        val tvMonth: TextView = view.findViewById(R.id.tvMonth)
        val tvYear: TextView = view.findViewById(R.id.tvYear)
        val tvStation: TextView = view.findViewById(R.id.tvStation)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvEnergyId: TextView = view.findViewById(R.id.tvEnergyId)
        val tvPhase: TextView = view.findViewById(R.id.tvPhase)
    }

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    // Used by the grid layout so headings span every column.
    fun isHeader(position: Int): Boolean = rows.getOrNull(position) is Row.Header

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int {
        return if (rows[position] is Row.Header) TYPE_HEADER else TYPE_BOOKING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_booking_month_header, parent, false))
        } else {
            BookingHolder(inflater.inflate(R.layout.item_booking_view, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).tvLabel.text = row.label
            is Row.Booking -> bindBooking(holder as BookingHolder, row)
        }
    }

    // Binds one booking card: date block, station, status, slot time, energy and reference.
    private fun bindBooking(holder: BookingHolder, row: Row.Booking) {
        val booking = row.booking
        val context = holder.itemView.context
        val placeholder = context.getString(R.string.ops_placeholder)

        val start = OpsFormat.parse(booking.slotStartTime)
        val end = OpsFormat.parse(booking.slotEndTime)

        holder.tvDay.text = start?.let { OpsFormat.dayOfMonth(it) } ?: placeholder
        holder.tvMonth.text = start?.let { OpsFormat.monthShort(it) }.orEmpty()
        holder.tvYear.text = start?.let { OpsFormat.year(it) }.orEmpty()

        holder.tvStation.text = booking.stationName?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.ops_unknown_station)
        OpsUi.statusBadge(holder.tvStatus, booking.status)
        holder.tvTime.text = OpsFormat.slotRange(context, start, end)

        val reference = OpsFormat.shortId(booking.id)
        val energy = OpsFormat.energy(context, booking.energyKWh)
        holder.tvEnergyId.text = if (reference.isEmpty()) {
            energy
        } else {
            context.getString(R.string.ops_energy_and_id, energy, reference)
        }

        // Current bookings show a countdown; cancelled ones show when they were cancelled
        val cancelledAt = OpsFormat.parse(booking.cancelledAt)
        when {
            row.showPhase -> OpsUi.phaseBadge(holder.tvPhase, OpsFormat.phase(context, start, end))
            booking.status.equals("Cancelled", ignoreCase = true) && cancelledAt != null -> {
                holder.tvPhase.isVisible = true
                OpsUi.badge(
                    holder.tvPhase,
                    context.getString(R.string.ops_cancelled_on, OpsFormat.shortDate(cancelledAt)),
                    OpsTone.NEUTRAL
                )
            }
            else -> holder.tvPhase.isVisible = false
        }

        holder.itemView.contentDescription = context.getString(
            R.string.ops_booking_description,
            holder.tvStation.text,
            OpsFormat.statusLabel(booking.status),
            start?.let { OpsFormat.dateTime(it) } ?: placeholder
        )
        holder.itemView.setOnClickListener { onClick(booking) }
    }
}
