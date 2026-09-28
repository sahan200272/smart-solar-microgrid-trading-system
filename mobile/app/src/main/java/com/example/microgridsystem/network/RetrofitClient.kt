package com.example.microgridsystem.network

import android.content.Context
import com.example.microgridsystem.util.SessionManager
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    // 10.0.2.2 for Android emulator localhost; 192.168.8.153 for physical device on LAN
    // Must end with a trailing slash!
    const val DEFAULT_BASE_URL = "http://192.168.8.153:5098/"

    @Volatile
    private var currentBaseUrl: String = DEFAULT_BASE_URL

    @Volatile
    private var retrofit: Retrofit? = null

    @Volatile
    private var apiService: ApiService? = null

    // Retain compatibility with existing code calling RetrofitClient.instance
    val instance: ApiService
        get() = getService()

    fun getService(context: Context? = null): ApiService {
        val targetUrl = if (context != null) {
            SessionManager(context).getBaseUrl()
        } else {
            currentBaseUrl
        }
        val sanitized = if (!targetUrl.endsWith("/")) "$targetUrl/" else targetUrl

        if (apiService == null || currentBaseUrl != sanitized) {
            synchronized(this) {
                if (apiService == null || currentBaseUrl != sanitized) {
                    currentBaseUrl = sanitized
                    retrofit = Retrofit.Builder()
                        .baseUrl(currentBaseUrl)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()
                    apiService = retrofit!!.create(ApiService::class.java)
                }
            }
        }
        return apiService!!
    }

    fun getService(baseUrl: String): ApiService {
        updateBaseUrl(baseUrl)
        return apiService!!
    }

    fun updateBaseUrl(url: String) {
        val sanitized = if (!url.endsWith("/")) "$url/" else url
        synchronized(this) {
            currentBaseUrl = sanitized
            retrofit = Retrofit.Builder()
                .baseUrl(currentBaseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            apiService = retrofit!!.create(ApiService::class.java)
        }
    }

    fun setBaseUrl(newUrl: String) {
        updateBaseUrl(newUrl)
    }
}