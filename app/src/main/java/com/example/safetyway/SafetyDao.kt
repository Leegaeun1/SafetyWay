package com.example.safetyway

import androidx.room.Dao
import androidx.room.Query

@Dao
interface SafetyDao {
    @Query("SELECT * FROM safety_info WHERE type = :type")
    suspend fun getItemsByType(type: String): List<SafetyEntity>

    @Query("""
        SELECT * FROM safety_info 
        WHERE (latitude BETWEEN :minLat AND :maxLat) 
        AND (longitude BETWEEN :minLng AND :maxLng) 
        AND type = :type
    """)
    suspend fun getSafetyInBounds(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double, type: String): List<SafetyEntity>

    // 범위 내 CCTV+보안등 밀집 지점 조회
    @Query("""
        SELECT latitude, longitude, SUM(count) as totalCount
        FROM safety_info
        WHERE latitude BETWEEN :minLat AND :maxLat
        AND longitude BETWEEN :minLng AND :maxLng
        GROUP BY ROUND(latitude, 3), ROUND(longitude, 3)
        HAVING totalCount >= :minCount
        ORDER BY totalCount DESC
    """)
    suspend fun getHighDensityPoints(
        minLat: Double, maxLat: Double,
        minLng: Double, maxLng: Double,
        minCount: Int = 2
    ): List<SafetyPoint>
}

// DAO 파일 아래에 같이 추가
data class SafetyPoint(
    val latitude: Double,
    val longitude: Double,
    val totalCount: Int
)