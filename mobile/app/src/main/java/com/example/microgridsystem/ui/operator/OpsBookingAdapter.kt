// File:        OpsBookingAdapter.kt
// Component:   Booking Views & Grid Operator Verification
// Description: RecyclerView adapter for the operator console's "Today's bookings" and
//              "Pending approvals" lists (rows from GET /api/operator/dashboard).
// Author:      Gunathilaka K.K.N.M.

package com.example.microgridsystem.ui.operator

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.microgridsystem.R
import com.example.microgridsystem.models.OperatorBooking

class OpsBookingAdapter(
    private val mode: Mode,
    private val onClick: (OperatorBooking) -> Unit
) : RecyclerView.Adapter<OpsBookingAdapter.BookingHolder>() {

    // TODAY shows the time, a countdown badge and QR status; PENDING shows the date and status.
    enum class Mode { TODAY, PENDING }

    private var items: List<OperatorBooking> = emptyList()
    private var stationNames: Map<String, String> = emptyMap()

    class BookingHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvNic: TextView = view.findViewById(R.id.tvNic)
        val tvBadge: TextView = view.findViewById(R.id.tvBadge)
        val ivWhen: ImageView = view.findViewById(R.id.ivWhen)
        val tvWhen: TextView = view.findViewById(R.id.tvWhen)
        val tvQr: TextView = view.findViewById(R.id.tvQr)
        val tvStation: TextView = view.findViewById(R.id.tvStation)
        val tvEnergy: TextView = view.findViewById(R.id.tvEnergy)
    }

    // Replaces the rows. stationNames fills in older bookings saved without a station name.
    fun submit(bookings: List<OperatorBooking>, stationNames: Map<String, String>) {
        items = bookings
        this.stationNames = stationNames
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookingHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ops_booking, parent, false)
        return BookingHolder(view)
    }

    // Binds one booking card.
    override fun onBindViewHolder(holder: BookingHolder, position: Int) {
        val booking = items[position]
        val context = holder.itemView.context

        val name = displayName(context, booking)
        val nic = booking.nic.orEmpty()
        val hasRealName = realName(booking) != null

        holder.tvAvatar.text = if (hasRealName) OpsFormat.initials(name) else "?"
        holder.tvName.text = name
        holder.tvNic.isVisible = hasRealName && nic.isNotBlank()
        holder.tvNic.text = context.getString(R.string.ops_nic_value, nic)

        val start = OpsFormat.parse(booking.slotStartTime)
        val end = OpsFormat.parse(booking.slotEndTime)
        val range = OpsFormat.slotRange(context, start, end)

        if (mode == Mode.TODAY) {
            holder.ivWhen.setImageResource(R.drawable.ic_clock)
            holder.tvWhen.text = range
            OpsUi.phaseBadge(holder.tvBadge, OpsFormat.phase(context, start, end))

            holder.tvQr.isVisible = true
            if (booking.qrIssued == true) {
                OpsUi.badge(holder.tvQr, context.getString(R.string.ops_qr_issued), OpsTone.COMPLETED)
            } else {
                OpsUi.badge(holder.tvQr, context.getString(R.string.ops_qr_not_issued), OpsTone.NEUTRAL)
            }
        } else {
            holder.ivWhen.setImageResource(R.drawable.ic_calendar)
            holder.tvWhen.text = if (start != null) {
                context.getString(R.string.ops_date_and_range, OpsFormat.shortDate(start), range)
            } else {
                range
            }
            holder.tvBadge.isVisible = true
            OpsUi.statusBadge(holder.tvBadge, booking.status)
            holder.tvQr.isVisible = false
        }

        holder.tvStation.text = stationName(booking) ?: context.getString(R.string.ops_unknown_station)
        holder.tvEnergy.text = OpsFormat.energy(context, booking.energyKWh)

        holder.itemView.setOnClickListener { onClick(booking) }
    }

    // The prosumer's name, or null when the API only knows the NIC
    // (it sends the NIC as the name when no user account is found).
    private fun realName(booking: OperatorBooking): String? {
        return booking.prosumerName?.takeIf { it.isNotBlank() && it != booking.nic }
    }

    // Name to show: the prosumer's name, else their NIC.
    fun displayName(context: Context, booking: OperatorBooking): String {
        return realName(booking)
            ?: booking.nic?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.ops_unknown_prosumer)
    }

    fun stationName(booking: OperatorBooking): String? {
        return booking.stationName?.takeIf { it.isNotBlank() }
            ?: booking.nodeId?.let { stationNames[it] }
    }
}
