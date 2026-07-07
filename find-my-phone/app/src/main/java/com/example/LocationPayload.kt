package com.example

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LocationPayload(
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
    val deviceId: String
)
