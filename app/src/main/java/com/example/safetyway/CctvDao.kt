package com.example.safetyway
// 구성한 데이터베이스 테이블에 접근하기 위한 인터페이스.
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CctvDao {
    // 데이터를 DB에 저장하는 명령 (이미 있으면 덮어쓰기)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cctvList: List<CctvEntity>)

    // 모든 CCTV 데이터를 가져오는 명령
    @Query("SELECT * FROM cctv_table")
    suspend fun getAllCctv(): List<CctvEntity>
}