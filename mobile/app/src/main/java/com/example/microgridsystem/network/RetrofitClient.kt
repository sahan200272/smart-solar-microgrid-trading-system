package com.example.microgridsystem.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    // IMPORTANT: Android emulator can't reach "localhost" - it refers to
    // the emulator itself, not your computer. Use 10.0.2.2 instead,
    // which is the emulator's special alias for your machine's localhost.
    private const val BASE_URL = "http://10.0.2.2:5098/"

    val instance: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}