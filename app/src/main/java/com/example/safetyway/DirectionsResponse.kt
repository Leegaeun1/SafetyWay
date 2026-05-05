package com.example.safetyway

data class DirectionsResponse(
    val code: Int,
    val message: String,
    val route: Route
)

data class Route(
    val trafast: List<TraRoute>?,
    val tracomfort: List<TraRoute>?,
    val traoptimal: List<TraRoute>?
)

data class TraRoute(
    val summary: RouteSummary,
    val path: List<List<Double>>     // [[경도,위도], [경도,위도], ...]
)

data class RouteSummary(
    val distance: Int,   // 거리 (미터)
    val duration: Int    // 시간 (밀리초)
)
