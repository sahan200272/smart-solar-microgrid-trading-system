package com.example.microgridsystem.network

import com.example.microgridsystem.models.NodeResponse
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface ApiService {
    @GET("api/nodes/nearby")
    fun getNearbyNodes(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("radiusKm") radiusKm: Double = 20.0
    ): Call<List<NodeResponse>>
}