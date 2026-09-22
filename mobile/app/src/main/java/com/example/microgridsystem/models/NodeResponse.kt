package com.example.microgridsystem.models

// Matches the JSON shape returned by GET /api/nodes/nearby
data class NodeResponse(
    val id: String,
    val stationName: String,
    val latitude: Double,
    val longitude: Double,
    val capacityKWh: Double,
    val totalBatterySlots: Int,
    val availableBatterySlots: Int,
    val operatingSchedule: String,
    val isActive: Boolean
)