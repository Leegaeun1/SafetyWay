package com.example.safetyway

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "safety_info", // 파이썬에서 정한 테이블명
    indices = [Index(value = ["latitude", "longitude", "type"], name = "idx_location_type")]
)
data class SafetyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val type: String,      // "CCTV" 또는 "LIGHT"
    val latitude: Double,
    val longitude: Double,
    val count: Int = 1         // 카메라 대수 또는 보안등 개수
)