package com.example.microgridsystem.network

import com.example.microgridsystem.models.ApiResponseMessage
import com.example.microgridsystem.models.LoginRequest
import com.example.microgridsystem.models.LoginResponse
import com.example.microgridsystem.models.NodeResponse
import com.example.microgridsystem.models.ProsumerProfileResponse
import com.example.microgridsystem.models.RegisterProsumerRequest
import com.example.microgridsystem.models.RegisterProsumerResponse
import com.example.microgridsystem.models.UpdateProsumerRequest
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    // Component: User & Access Management
    // 1. Authenticate user and get JWT
    @POST("api/auth/login")
    fun login(
        @Body request: LoginRequest
    ): Call<LoginResponse>

    // 2. Register new Prosumer account
    @POST("api/auth/register/prosumer")
    fun registerProsumer(
        @Body request: RegisterProsumerRequest
    ): Call<RegisterProsumerResponse>

    // 3. Get Prosumer profile by NIC
    @GET("api/prosumers/{nic}")
    fun getProsumerProfile(
        @Header("Authorization") token: String,
        @Path("nic") nic: String
    ): Call<ProsumerProfileResponse>

    // 4. Update Prosumer profile
    @PUT("api/prosumers/{nic}")
    fun updateProsumerProfile(
        @Header("Authorization") token: String,
        @Path("nic") nic: String,
        @Body request: UpdateProsumerRequest
    ): Call<ApiResponseMessage>

    // 5. Request account deactivation
    @PUT("api/prosumers/{nic}/deactivate")
    fun deactivateProsumer(
        @Header("Authorization") token: String,
        @Path("nic") nic: String
    ): Call<ApiResponseMessage>

    // Microgrid Node Component
    @GET("api/nodes/nearby")
    fun getNearbyNodes(
        @Header("Authorization") token: String,
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("radiusKm") radiusKm: Double = 20.0
    ): Call<List<NodeResponse>>
}