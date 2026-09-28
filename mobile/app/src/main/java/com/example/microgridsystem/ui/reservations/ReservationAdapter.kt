package com.example.microgridsystem.ui.reservations

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.microgridsystem.R
import com.example.microgridsystem.models.ReservationResponse
import com.example.microgridsystem.utils.DateTimeUtils
import com.google.android.material.button.MaterialButton

class ReservationAdapter(
    private var reservations: List<ReservationResponse>,
    private val onItemClick: (ReservationResponse) -> Unit,
    private val onQrClick: (ReservationResponse) -> Unit,
    private val onModifyClick: (ReservationResponse) -> Unit,
    private val onCancelClick: (ReservationResponse) -> Unit
) : RecyclerView.Adapter<ReservationAdapter.ReservationViewHolder>() {

    fun updateData(newReservations: List<ReservationResponse>) {
        this.reservations = newReservations
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReservationViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_reservation, parent, false)
        return ReservationViewHolder(view)
    }

    override fun onBindViewHolder(holder: ReservationViewHolder, position: Int) {
        val item = reservations[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = reservations.size

    inner class ReservationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvStationName: TextView = itemView.findViewById(R.id.tvStationName)
        private val tvReservationId: TextView = itemView.findViewById(R.id.tvReservationId)
        private val tvStatusBadge: TextView = itemView.findViewById(R.id.tvStatusBadge)
        private val tvEnergyKWh: TextView = itemView.findViewById(R.id.tvEnergyKWh)
        private val tvSlotTime: TextView = itemView.findViewById(R.id.tvSlotTime)
        private val tvUrgentNotice: TextView = itemView.findViewById(R.id.tvUrgentNotice)
        private val layoutActions: View = itemView.findViewById(R.id.layoutActions)
        private val btnQrPass: MaterialButton = itemView.findViewById(R.id.btnQrPass)
        private val btnModify: MaterialButton = itemView.findViewById(R.id.btnModify)
        private val btnCancel: MaterialButton = itemView.findViewById(R.id.btnCancel)

        fun bind(item: ReservationResponse) {
            val context = itemView.context
            tvStationName.text = if (item.stationName.isNotBlank()) item.stationName else "Station (${item.nodeId.take(6)})"
            tvReservationId.text = if (item.id.isNotBlank()) "ID: #${item.id.take(8)}" else "ID: N/A"
            tvEnergyKWh.text = String.format(java.util.Locale.US, "%.1f kWh", item.energyKWh)

            // Format slot window
            val startStr = DateTimeUtils.formatIsoToDisplay(item.slotStartTime)
            val endStr = DateTimeUtils.formatIsoToTimeOnly(item.slotEndTime)
            tvSlotTime.text = "$startStr - $endStr"

            // Status Badge styling matching Web App Scheme
            val status = item.status.trim()
            tvStatusBadge.text = status

            when (status.lowercase(java.util.Locale.US)) {
                "approved" -> {
                    tvStatusBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_badge_approved)
                    tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_approved_text))
                    btnQrPass.visibility = View.VISIBLE
                    btnModify.visibility = View.VISIBLE
                    btnCancel.visibility = View.VISIBLE
                    layoutActions.visibility = View.VISIBLE
                }
                "pending" -> {
                    tvStatusBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_badge_pending)
                    tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_pending_text))
                    btnQrPass.visibility = View.GONE
                    btnModify.visibility = View.VISIBLE
                    btnCancel.visibility = View.VISIBLE
                    layoutActions.visibility = View.VISIBLE
                }
                "completed" -> {
                    tvStatusBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_badge_completed)
                    tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_completed_text))
                    btnQrPass.visibility = View.GONE
                    btnModify.visibility = View.GONE
                    btnCancel.visibility = View.GONE
                    layoutActions.visibility = View.GONE
                }
                "cancelled" -> {
                    tvStatusBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_badge_cancelled)
                    tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_cancelled_text))
                    btnQrPass.visibility = View.GONE
                    btnModify.visibility = View.GONE
                    btnCancel.visibility = View.GONE
                    layoutActions.visibility = View.GONE
                }
                "rejected" -> {
                    tvStatusBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_badge_rejected)
                    tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_rejected_text))
                    btnQrPass.visibility = View.GONE
                    btnModify.visibility = View.GONE
                    btnCancel.visibility = View.GONE
                    layoutActions.visibility = View.GONE
                }
                else -> {
                    tvStatusBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_badge_pending)
                    tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_pending_text))
                    btnQrPass.visibility = View.GONE
                    layoutActions.visibility = View.VISIBLE
                }
            }

            // 12-hour soft notice
            val isUrgent = DateTimeUtils.isLessThan12HoursAway(item.slotStartTime)
            val isOpen = status.equals("Pending", ignoreCase = true) || status.equals("Approved", ignoreCase = true)
            if (isUrgent && isOpen) {
                tvUrgentNotice.visibility = View.VISIBLE
                tvUrgentNotice.text = "⚠️ <12h to slot"
            } else {
                tvUrgentNotice.visibility = View.GONE
            }

            // Click listeners
            itemView.setOnClickListener { onItemClick(item) }
            btnQrPass.setOnClickListener { onQrClick(item) }
            btnModify.setOnClickListener { onModifyClick(item) }
            btnCancel.setOnClickListener { onCancelClick(item) }
        }
    }
}
