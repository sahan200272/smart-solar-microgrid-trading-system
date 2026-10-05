package com.example.microgridsystem

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.microgridsystem.models.ProsumerProfileResponse

class ProsumerAdapter(
    private var prosumers: List<ProsumerProfileResponse>,
    private val onItemClick: (ProsumerProfileResponse) -> Unit
) : RecyclerView.Adapter<ProsumerAdapter.ProsumerViewHolder>() {

    fun updateData(newProsumers: List<ProsumerProfileResponse>) {
        this.prosumers = newProsumers
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProsumerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_prosumer, parent, false)
        return ProsumerViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProsumerViewHolder, position: Int) {
        val item = prosumers[position]
        holder.bind(item, onItemClick)
    }

    override fun getItemCount(): Int = prosumers.size

    class ProsumerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvProsumerName: TextView = itemView.findViewById(R.id.tvProsumerName)
        private val tvProsumerNic: TextView = itemView.findViewById(R.id.tvProsumerNic)
        private val tvProsumerPhone: TextView = itemView.findViewById(R.id.tvProsumerPhone)
        private val tvProsumerEmail: TextView = itemView.findViewById(R.id.tvProsumerEmail)
        private val tvProsumerStatus: TextView = itemView.findViewById(R.id.tvProsumerStatus)

        fun bind(prosumer: ProsumerProfileResponse, onItemClick: (ProsumerProfileResponse) -> Unit) {
            val context = itemView.context

            tvProsumerName.text = if (!prosumer.fullName.isNullOrBlank()) prosumer.fullName else "Unknown Name"
            tvProsumerNic.text = "NIC: ${prosumer.nic ?: "N/A"}"
            tvProsumerPhone.text = if (!prosumer.phone.isNullOrBlank()) prosumer.phone else "Phone not provided"
            tvProsumerEmail.text = if (!prosumer.email.isNullOrBlank()) prosumer.email else "Email not provided"

            val status = prosumer.status ?: "Pending"
            when {
                status.equals("Active", ignoreCase = true) -> {
                    tvProsumerStatus.text = "● Active"
                    tvProsumerStatus.setBackgroundResource(R.drawable.bg_status_active)
                    tvProsumerStatus.setTextColor(ContextCompat.getColor(context, R.color.status_active))
                }
                status.equals("Pending", ignoreCase = true) -> {
                    tvProsumerStatus.text = "⏳ Pending"
                    tvProsumerStatus.setBackgroundResource(R.drawable.bg_status_pending)
                    tvProsumerStatus.setTextColor(ContextCompat.getColor(context, R.color.status_pending))
                }
                else -> {
                    tvProsumerStatus.text = "✖ Deactivated"
                    tvProsumerStatus.setBackgroundResource(R.drawable.bg_status_deactivated)
                    tvProsumerStatus.setTextColor(ContextCompat.getColor(context, R.color.status_deactivated))
                }
            }

            itemView.setOnClickListener {
                onItemClick(prosumer)
            }
        }
    }
}
