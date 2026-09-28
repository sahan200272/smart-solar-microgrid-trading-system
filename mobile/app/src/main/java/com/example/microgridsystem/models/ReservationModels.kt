package com.example.microgridsystem.models

import com.google.gson.annotations.SerializedName
import java.io.Serializable

enum class ReservationStatus {
    Pending,
    Approved,
    Cancelled,
    Completed,
    Rejected
}

data class ReservationResponse(
    @SerializedName("id")
    val id: String = "",
    
    @SerializedName("prosumerNic")
    val prosumerNic: String = "",
    
    @SerializedName("nodeId")
    val nodeId: String = "",
    
    @SerializedName("stationName")
    val stationName: String = "",
    
    @SerializedName("energyKWh")
    val energyKWh: Double = 0.0,
    
    @SerializedName("slotStartTime")
    val slotStartTime: String = "",
    
    @SerializedName("slotEndTime")
    val slotEndTime: String = "",
    
    @SerializedName("status")
    val status: String = "Pending",
    
    @SerializedName("createdAt")
    val createdAt: String? = null,
    
    @SerializedName("updatedAt")
    val updatedAt: String? = null,
    
    @SerializedName("cancelledAt")
    val cancelledAt: String? = null,
    
    @SerializedName("qrToken")
    val qrToken: String? = null,
    
    @SerializedName("qrExpiresAt")
    val qrExpiresAt: String? = null,
    
    @SerializedName("qrUsedAt")
    val qrUsedAt: String? = null
) : Serializable

data class CreateReservationRequest(
    @SerializedName("prosumerNic")
    val prosumerNic: String,
    
    @SerializedName("nodeId")
    val nodeId: String,
    
    @SerializedName("energyKWh")
    val energyKWh: Double,
    
    @SerializedName("slotStartTime")
    val slotStartTime: String,
    
    @SerializedName("slotEndTime")
    val slotEndTime: String,
    
    @SerializedName("stationName")
    val stationName: String? = null
)

data class UpdateReservationRequest(
    @SerializedName("slotStartTime")
    val slotStartTime: String?,
    
    @SerializedName("slotEndTime")
    val slotEndTime: String?
)

data class ReservationDashboardResponse(
    @SerializedName("activeCount")
    val activeCount: Long = 0,
    
    @SerializedName("pendingCount")
    val pendingCount: Long = 0
)

data class QrGenerationResponse(
    @SerializedName("reservationId")
    val reservationId: String = "",
    
    @SerializedName("qrToken")
    val qrToken: String = "",
    
    @SerializedName("qrPayload")
    val qrPayload: String = "",
    
    @SerializedName("expiresAt")
    val expiresAt: String = "",
    
    @SerializedName("slotStartTime")
    val slotStartTime: String? = null,
    
    @SerializedName("slotEndTime")
    val slotEndTime: String? = null,
    
    @SerializedName("stationName")
    val stationName: String? = null,
    
    @SerializedName("energyKWh")
    val energyKWh: Double? = null,
    
    @SerializedName("status")
    val status: String? = null
)

data class ApiGenericResponse(
    @SerializedName("message")
    val message: String? = null,
    
    @SerializedName("reason")
    val reason: String? = null,
    
    @SerializedName("reservation")
    val reservation: ReservationResponse? = null
)
