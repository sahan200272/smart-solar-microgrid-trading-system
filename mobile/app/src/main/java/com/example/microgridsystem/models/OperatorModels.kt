// File:        OperatorModels.kt
// Component:   Booking Views & Grid Operator Verification
// Description: Request and response models for the Grid Operator console and the QR
//              verification workflow (GET /api/operator/dashboard, GET /api/operator/stations,
//              POST /api/reservations/verify-qr, PUT /api/reservations/{id}/finalize).
// Author:      Gunathilaka K.K.N.M.

package com.example.microgridsystem.models

import com.google.gson.annotations.SerializedName
import java.io.Serializable

// Response fields are nullable because Gson does not run Kotlin constructors: a field
// missing from the JSON arrives as null even when the Kotlin type says it can't be.

// GET /api/operator/dashboard
data class OperatorDashboardResponse(
    @SerializedName("generatedAt") val generatedAt: String? = null,
    @SerializedName("nodeId") val nodeId: String? = null,
    @SerializedName("counts") val counts: OperatorCounts? = null,
    @SerializedName("pendingReservations") val pendingReservations: List<OperatorBooking>? = null,
    @SerializedName("todayBookings") val todayBookings: List<OperatorBooking>? = null
)

data class OperatorCounts(
    @SerializedName("pendingCount") val pendingCount: Long? = null,
    @SerializedName("approvedFutureCount") val approvedFutureCount: Long? = null,
    @SerializedName("activeTodayCount") val activeTodayCount: Long? = null,
    @SerializedName("completedTodayCount") val completedTodayCount: Long? = null,
    @SerializedName("cancelledTodayCount") val cancelledTodayCount: Long? = null
)

// A booking row in the dashboard preview lists. qrIssued is only sent for today's bookings.
data class OperatorBooking(
    @SerializedName("id") val id: String? = null,
    @SerializedName("nic") val nic: String? = null,
    @SerializedName("prosumerName") val prosumerName: String? = null,
    @SerializedName("nodeId") val nodeId: String? = null,
    @SerializedName("stationName") val stationName: String? = null,
    @SerializedName("slotStartTime") val slotStartTime: String? = null,
    @SerializedName("slotEndTime") val slotEndTime: String? = null,
    @SerializedName("energyKWh") val energyKWh: Double? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("qrIssued") val qrIssued: Boolean? = null
) : Serializable

// GET /api/operator/stations
data class OperatorStation(
    @SerializedName("id") val id: String? = null,
    @SerializedName("stationName") val stationName: String? = null,
    @SerializedName("latitude") val latitude: Double? = null,
    @SerializedName("longitude") val longitude: Double? = null,
    @SerializedName("availableBatterySlots") val availableBatterySlots: Int? = null,
    @SerializedName("totalBatterySlots") val totalBatterySlots: Int? = null
)

// POST /api/reservations/verify-qr. qrToken can be the full "SSMG:1:" payload or the bare token.
data class VerifyQrRequest(
    @SerializedName("qrToken") val qrToken: String,
    @SerializedName("nodeId") val nodeId: String?
)

data class VerifyQrResponse(
    @SerializedName("valid") val valid: Boolean? = null,
    @SerializedName("reservationId") val reservationId: String? = null,
    @SerializedName("prosumer") val prosumer: VerifiedProsumer? = null,
    @SerializedName("station") val station: VerifiedStation? = null,
    @SerializedName("slotStartTime") val slotStartTime: String? = null,
    @SerializedName("slotEndTime") val slotEndTime: String? = null,
    @SerializedName("energyKWh") val energyKWh: Double? = null,
    @SerializedName("status") val status: String? = null
) : Serializable

data class VerifiedProsumer(
    @SerializedName("nic") val nic: String? = null,
    @SerializedName("fullName") val fullName: String? = null,
    @SerializedName("phone") val phone: String? = null
) : Serializable

data class VerifiedStation(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null
) : Serializable

// PUT /api/reservations/{id}/finalize. Null fields are left out of the JSON by Gson.
data class FinalizeReservationRequest(
    @SerializedName("qrToken") val qrToken: String,
    @SerializedName("nodeId") val nodeId: String?,
    @SerializedName("deliveredKWh") val deliveredKWh: Double?
)

data class FinalizeReservationResponse(
    @SerializedName("message") val message: String? = null,
    @SerializedName("reservation") val reservation: FinalizedReservation? = null
) : Serializable

data class FinalizedReservation(
    @SerializedName("id") val id: String? = null,
    @SerializedName("nic") val nic: String? = null,
    @SerializedName("stationName") val stationName: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("slotStartTime") val slotStartTime: String? = null,
    @SerializedName("slotEndTime") val slotEndTime: String? = null,
    @SerializedName("energyKWh") val energyKWh: Double? = null,
    @SerializedName("deliveredKWh") val deliveredKWh: Double? = null,
    @SerializedName("completedAt") val completedAt: String? = null,
    @SerializedName("completedBy") val completedBy: String? = null,
    @SerializedName("completedByNic") val completedByNic: String? = null
) : Serializable

// Error body returned by the QR endpoints, e.g. { valid: false, reason: "EXPIRED", message: "..." }.
// "title" covers ASP.NET's default validation problem responses.
data class OpsFailureBody(
    @SerializedName("valid") val valid: Boolean? = null,
    @SerializedName("reason") val reason: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("title") val title: String? = null
)
