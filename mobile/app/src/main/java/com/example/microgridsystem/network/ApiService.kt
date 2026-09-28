package com.example.microgridsystem.network

import com.example.microgridsystem.models.ApiGenericResponse
import com.example.microgridsystem.models.CreateReservationRequest
import com.example.microgridsystem.models.NodeResponse
import com.example.microgridsystem.models.QrGenerationResponse
import com.example.microgridsystem.models.ReservationDashboardResponse
import com.example.microgridsystem.models.ReservationResponse
import com.example.microgridsystem.models.UpdateReservationRequest
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    // Nodes / Stations
    @GET("api/nodes")
    fun getAllNodes(
        @Header("Authorization") token: String
    ): Call<List<NodeResponse>>

    @GET("api/nodes/nearby")
    fun getNearbyNodes(
        @Header("Authorization") token: String,
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("radiusKm") radiusKm: Double = 20.0
    ): Call<List<NodeResponse>>

    // Reservations Dashboard
    @GET("api/reservations/dashboard/{nic}")
    fun getReservationDashboard(
        @Header("Authorization") token: String,
        @Path("nic") nic: String
    ): Call<ReservationDashboardResponse>

    // Reservations List & Filter
    @GET("api/reservations")
    fun getReservations(
        @Header("Authorization") token: String,
        @Query("prosumerNic") prosumerNic: String? = null,
        @Query("nodeId") nodeId: String? = null,
        @Query("status") status: String? = null
    ): Call<List<ReservationResponse>>

    @GET("api/reservations/{id}")
    fun getReservationById(
        @Header("Authorization") token: String,
        @Path("id") id: String
    ): Call<ReservationResponse>

    // Create Reservation
    @POST("api/reservations")
    fun createReservation(
        @Header("Authorization") token: String,
        @Body request: CreateReservationRequest
    ): Call<ReservationResponse>

    // Update / Modify Reservation
    @PUT("api/reservations/{id}")
    fun updateReservation(
        @Header("Authorization") token: String,
        @Path("id") id: String,
        @Body request: UpdateReservationRequest
    ): Call<ReservationResponse>

    // Cancel Reservation
    @PUT("api/reservations/{id}/cancel")
    fun cancelReservation(
        @Header("Authorization") token: String,
        @Path("id") id: String
    ): Call<ApiGenericResponse>

    // QR Code Generation / Retrieval
    @POST("api/reservations/{id}/qr")
    fun generateQr(
        @Header("Authorization") token: String,
        @Path("id") id: String
    ): Call<QrGenerationResponse>
}