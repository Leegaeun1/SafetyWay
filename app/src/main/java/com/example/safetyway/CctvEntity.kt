package com.example.safetyway

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
// 테이블 구조
// 파이썬에서 만든 인덱스(idx_location) 정보를 Room에 알려줍니다.
@Entity(
    tableName = "cctv_info",
    indices = [Index(value = ["latitude", "longitude"], name = "idx_location")]
)
data class CctvEntity(
    // 파이썬에서 만든 명시적인 id를 PrimaryKey로 사용합니다.
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "camera_count") // 실제 컬럼 명은 camera_count 입니다.
    val cameraCount: Int
)