package com.example.safetyway

import retrofit2.http.GET
import retrofit2.http.Query

interface NaverSearchApi {
    @GET("v1/search/local.json")
    suspend fun searchPlace(
        @Query("query") query: String,
        @Query("display") display: Int = 5,
        @Query("sort") sort: String = "random"  // "random"=관련도순 (기본값)
    ): NaverSearchResponse
}