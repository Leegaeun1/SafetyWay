package com.example.safetyway
// 구성한 데이터베이스 테이블에 접근하기 위한 인터페이스.
import androidx.room.Dao
import androidx.room.Query

@Dao
interface CctvDao {
    // 모든 CCTV 데이터 가져오기 (테스트용)
    @Query("SELECT * FROM cctv_info")
    suspend fun getAllCctvs(): List<CctvEntity> // suspend는 비동기로 실행되는 것입니다.
    // 모든 cctv정보를 리스트로 불러옵니다.

    // 특정 화면(위도/경도 범위) 내에 있는 CCTV만 가져오기 (실제 지도 표시용)
    // minLat, maxLat, minLng, maxLng 값을 던져주면 그 안의 데이터만 빠르게 찾습니다.
    @Query("SELECT * FROM cctv_info WHERE latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLng AND :maxLng")
    suspend fun getCctvsInBounds(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<CctvEntity>
}