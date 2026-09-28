package com.example.microgridsystem.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    // 10.0.2.2 for Android emulator localhost; 192.168.1.8 for physical device on LAN
    const val DEFAULT_BASE_URL = "http://192.168.1.8:5098"

    @Volatile
    private var currentBaseUrl: String = DEFAULT_BASE_URL

    @Volatile
    private var apiService: ApiService? = null

    val instance: ApiService
        get() = getService(currentBaseUrl)

    fun getService(baseUrl: String = DEFAULT_BASE_URL): ApiService {
        val sanitized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        if (apiService == null || currentBaseUrl != sanitized) {
            synchronized(this) {
                if (apiService == null || currentBaseUrl != sanitized) {
                    currentBaseUrl = sanitized
                    apiService = Retrofit.Builder()
                        .baseUrl(sanitized)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()
                        .create(ApiService::class.java)
                }
            }
        }
        return apiService!!
    }

    fun setBaseUrl(newUrl: String) {
        getService(newUrl)
    }
}