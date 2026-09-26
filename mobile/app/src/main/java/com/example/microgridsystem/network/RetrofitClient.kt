package com.example.microgridsystem.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    // 10.0.2.2 is the Android emulator's special alias for your computer's
    // localhost - it does NOT work on a physical device on a different network.
    private const val BASE_URL = "http://192.168.1.8:5098"

    val instance: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}