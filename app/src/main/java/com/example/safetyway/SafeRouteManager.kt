package com.example.safetyway

import com.naver.maps.geometry.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class SafeRouteManager(
    private val safetyDao: SafetyDao,
    private val mapApi: NaverMapApi
) {
    suspend fun calculateSafetyScore(path: List<List<Double>>): Int {
        var score = 0
        path.filterIndexed { index, _ -> index % 10 == 0 }.forEach { point ->
            val lat = point[1]
            val lng = point[0]
            val points = safetyDao.getHighDensityPoints(
                lat - 0.0005, lat + 0.0005,
                lng - 0.0005, lng + 0.0005
            )
            score += points.sumOf { it.totalCount }
        }
        return minOf(score, 100)
    }

    suspend fun findSafeRoute(start: LatLng, goal: LatLng): List<List<Double>> {
        val safeWaypoints = findSafeWaypoints(start, goal)

        // 경유지 없으면 일반 경로 반환
        if (safeWaypoints.isEmpty()) {
            val response = mapApi.getRoute(
                start = "${start.longitude},${start.latitude}",
                goal = "${goal.longitude},${goal.latitude}"
            )
            return response.route.trafast?.get(0)?.path ?: emptyList()
        }

        // 경유지 최대 4개, API 1번만 호출
        val waypointStr = safeWaypoints
            .take(4)
            .joinToString(":") { "${it.longitude},${it.latitude}" }

        val response = mapApi.getRoute(
            start = "${start.longitude},${start.latitude}",
            goal = "${goal.longitude},${goal.latitude}",
            waypoints = waypointStr
        )
        return response.route.trafast?.get(0)?.path ?: emptyList()
    }

    /**
     * 출발→목적지 직선을 최대 5구간으로 분할,
     * 각 구간 중간 지점 근처에서 CCTV+보안등 밀도가 가장 높은 지점 1개씩 선택.
     * 이렇게 하면 경유지가 항상 목적지 방향으로 정렬되고 뱅글뱅글 안 돎.
     */
    private suspend fun findSafeWaypoints(start: LatLng, goal: LatLng): List<LatLng> {
        val result = mutableListOf<LatLng>()
        val segments = 5  // 5구간 → 내부 중간점 4개 → 경유지 최대 4개

        for (seg in 1 until segments) {
            val ratio = seg.toDouble() / segments
            // 직선 보간으로 구간 중간 좌표 계산
            val midLat = start.latitude + (goal.latitude - start.latitude) * ratio
            val midLng = start.longitude + (goal.longitude - start.longitude) * ratio

            // 중간 지점 반경 300m(약 +- 0.003도) 내에서 안전 거점 탐색
            val radius = 0.003
            val candidates = safetyDao.getHighDensityPoints(
                midLat - radius, midLat + radius,
                midLng - radius, midLng + radius
            )

            if (candidates.isEmpty()) continue

            // 후보 중 가장 밀도(totalCount) 높은 지점 선택
            val best = candidates.maxByOrNull { it.totalCount } ?: continue

            // 중간 지점에서 너무 멀면(150m 초과) 우회가 심해지므로 제외
            val detour = distanceBetween(midLat, midLng, best.latitude, best.longitude)
            if (detour > 150) continue

            result.add(LatLng(best.latitude, best.longitude))
        }

        return result
    }

    private fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}