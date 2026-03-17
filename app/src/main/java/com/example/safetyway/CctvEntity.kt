package com.example.safetyway

import androidx.room.Entity
import androidx.room.PrimaryKey

// 1. 이 클래스가 데이터베이스의 테이블임을 선언합니다.
@Entity(tableName = "cctv_table")
data class CctvEntity(
    // 2. 각 데이터마다 고유한 번호(ID)를 자동으로 생성해 부여합니다.
    @PrimaryKey(autoGenerate = true) val id: Int = 0,

    val purpose: String,      // 목적 (방범, 어린이보호구역 등)
    val dong: String,         // 읍면동
    val address: String,      // 설치장소
    val latitude: Double,     // 위도
    val longitude: Double,    // 경도
    val year: Int,            // 설치연도
    val count: Int            // 설치대수
)