package com.example.safetyway

data class NaverSearchResponse(
    val lastBuildDate: String,
    val total: Int,
    val start: Int,
    val display: Int,
    val items: List<SearchItem>
)

data class SearchItem(
    val title: String,          // 장소명 (HTML 태그 포함될 수 있음)
    val link: String,
    val category: String,
    val description: String,
    val telephone: String,
    val address: String,        // 지번 주소
    val roadAddress: String,    // 도로명 주소
    val mapx: String,           // 경도 (문자열로 옴!)
    val mapy: String            // 위도 (문자열로 옴!)
)