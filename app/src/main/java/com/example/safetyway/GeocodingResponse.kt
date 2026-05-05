package com.example.safetyway

data class GeocodingResponse(
    val status: String,
    val addresses: List<GeocodingAddress>
)

data class GeocodingAddress(
    val roadAddress: String,
    val jibunAddress: String,
    val x: String,   // 경도
    val y: String    // 위도
)