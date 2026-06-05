package com.example.safetyway

import retrofit2.http.GET
import retrofit2.http.Query

interface NaverMapApi {

    // Geocoding
    @GET("map-geocode/v2/geocode")
    suspend fun getCoordinates(
        @Query("query") address: String
    ): GeocodingResponse
    // Directions
    @GET("map-direction/v1/driving")
    suspend fun getRoute(
        @Query("start") start: String,
        @Query("goal") goal: String,
        @Query("waypoints") waypoints: String = "",
        @Query("option") option: String = "trafast"
    ): DirectionsResponse

    // Reverse Geocoding - 좌표 -> 행정구역명 (현재위치 기반 검색에 사용)
    @GET("map-reversegeocode/v2/gc")
    suspend fun reverseGeocode(
        @Query("coords") coords: String,           // "경도,위도" 형식
        @Query("orders") orders: String = "roadaddr,addr,admcode",  // admcode = 행정구역
        @Query("output") output: String = "json"
    ): ReverseGeocodeResponse
}