package com.example.microgridsystem.models

import com.google.gson.annotations.SerializedName

// Request payload for POST /api/auth/login
data class LoginRequest(
    @SerializedName("NIC") val nic: String,
    @SerializedName("Password") val password: String
)

// Response from POST /api/auth/login
data class LoginResponse(
    val message: String?,
    val token: String?,
    val user: UserInfo?
)

data class UserInfo(
    val id: String?,
    val nic: String?,
    val fullName: String?,
    val role: String?,
    val status: String?
)

// Request payload for POST /api/auth/register/prosumer
data class RegisterProsumerRequest(
    @SerializedName("NIC") val nic: String,
    @SerializedName("FullName") val fullName: String,
    @SerializedName("Email") val email: String,
    @SerializedName("Phone") val phone: String,
    @SerializedName("Address") val address: String,
    @SerializedName("Password") val password: String
)

// Response from POST /api/auth/register/prosumer
data class RegisterProsumerResponse(
    val message: String?,
    val nic: String?,
    val status: String?
)

// Response from GET /api/prosumers/{nic}
data class ProsumerProfileResponse(
    val id: String?,
    val nic: String?,
    val fullName: String?,
    val email: String?,
    val phone: String?,
    val address: String?,
    val status: String?,
    val createdAt: String?
)

// Request payload for PUT /api/prosumers/{nic}
data class UpdateProsumerRequest(
    @SerializedName("FullName") val fullName: String?,
    @SerializedName("Email") val email: String?,
    @SerializedName("Phone") val phone: String?,
    @SerializedName("Address") val address: String?
)

// Generic API message response
data class ApiResponseMessage(
    val message: String?
)
