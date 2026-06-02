package com.example.safetyway

import com.google.gson.JsonElement
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

// TMAP에 보낼 요청 Body 데이터 구조
data class TmapRouteRequest(
    val startX: Double,
    val startY: Double,
    val endX: Double,
    val endY: Double,
    val startName: String = "Start",
    val endName: String = "End",
    val reqCoordType: String = "WGS84GEO",
    val resCoordType: String = "WGS84GEO",
    val searchOption: String = "0", // 0 : 추천(기본값), 4 : 추천 + 대로우선, 10 : 최단
    val passList: String? = null // 경유지 좌표 (형식: "경도,위도")
)

interface TmapSearchApi {
    @POST("tmap/routes/pedestrian")
    suspend fun getPedestrianRoute(
        @Header("appKey") appKey: String,
        @Query("version") version: Int = 1,
        @Body body: TmapRouteRequest
    ): JsonElement // GeoJSON은 구조가 유동적이므로 JsonElement로 받아 파싱하는 게 가장 안전
}