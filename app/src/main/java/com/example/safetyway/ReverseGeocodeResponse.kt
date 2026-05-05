package com.example.safetyway

data class ReverseGeocodeResponse(
    val status: RgStatus?,
    val results: List<RgResult>?
)

data class RgStatus(
    val code: Int,
    val name: String,
    val message: String
)

data class RgResult(
    val name: String,
    val region: RgRegion?
)

data class RgRegion(
    val area0: RgArea?,  // 국가 (kr)
    val area1: RgArea?,  // 시/도 (경상남도)
    val area2: RgArea?,  // 시/군/구 (진주시)
    val area3: RgArea?,  // 읍/면/동 (가좌동)
    val area4: RgArea?   // 리 (보통 비어있음)
)

data class RgArea(
    val name: String
)