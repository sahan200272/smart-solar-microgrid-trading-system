package com.example.microgridsystem.network

import android.content.Context
import com.example.microgridsystem.util.SessionManager
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    // Default URL. For Android emulator, use 10.0.2.2.
    // For physical phone on Wi-Fi, use your PC's LAN IP e.g. http://192.168.1.8:5098/
    // Must end with a trailing slash!
    private var currentBaseUrl = "http://192.168.8.153:5098/"
    private var retrofit: Retrofit? = null
    private var service: ApiService? = null

    fun getService(context: Context? = null): ApiService {
        val targetUrl = if (context != null) {
            SessionManager(context).getBaseUrl()
        } else {
            currentBaseUrl
        }

        if (service == null || targetUrl != currentBaseUrl) {
            currentBaseUrl = targetUrl
            retrofit = Retrofit.Builder()
                .baseUrl(currentBaseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            service = retrofit!!.create(ApiService::class.java)
        }
        return service!!
    }

    // Retain compatibility with existing code calling RetrofitClient.instance
    val instance: ApiService
        get() = getService()

    fun updateBaseUrl(url: String) {
        currentBaseUrl = if (!url.endsWith("/")) "$url/" else url
        retrofit = Retrofit.Builder()
            .baseUrl(currentBaseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        service = retrofit!!.create(ApiService::class.java)
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