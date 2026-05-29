package com.example.safetyway

import com.naver.maps.geometry.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class SafeRouteManager(
    private val safetyDao: SafetyDao,
    private val mapApi: NaverMapApi
) {
    companion object {
        // 점수 계산 파라미터
        private const val BUFFER_M = 80.0           // 검사 반경 50->80m (골목길 커버)
        private const val SAMPLE_INTERVAL_M = 30.0  // 샘플링 간격
        private const val CCTV_WEIGHT = 1.5
        private const val LIGHT_WEIGHT = 1.0

        // waypoint 선택 파라미터
        private const val MIN_ROUTE_M_FOR_WP = 500.0
    }

    data class ScoredRoute(
        val path: List<List<Double>>,
        val distanceM: Int,
        val durationMs: Int,
        val cctvCount: Int,
        val lightCount: Int,
        val safetyScore: Int
    )

    /**
     * 3개 후보 경로를 동시에 생성하고 안전점수까지 계산해서 반환.
     *   [0] 안전추천 — waypoint 4개, 우회 250m 허용
     *   [1] 균형    — waypoint 2개, 우회 120m
     *   [2] 최단    — waypoint 없음
     */
    suspend fun findThreeRoutes(start: LatLng, goal: LatLng): List<ScoredRoute> {
        val results = mutableListOf<ScoredRoute>()

        val safeWp = findSafeWaypoints(start, goal, segments = 5, maxDetourM = 250.0).take(4)
        val r0 = callDirections(start, goal, safeWp, "trafast")
            ?: callDirections(start, goal, emptyList(), "tracomfort")
        r0?.let { results.add(scoreRoute(it)) }

        val balancedWp = findSafeWaypoints(start, goal, segments = 4, maxDetourM = 120.0).take(2)
        val r1 = callDirections(start, goal, balancedWp, "trafast")
            ?: callDirections(start, goal, emptyList(), "traoptimal")
        r1?.let { results.add(scoreRoute(it)) }

        callDirections(start, goal, emptyList(), "trafast")
            ?.let { results.add(scoreRoute(it)) }

        return results
    }

    // 안전 점수 계산
    /**
     * 개선된 점수 계산:
     *
     * 1. BUFFER_M(80m) 내 안전시설 개수를 거리 감쇠로 가중합 -> weightedSum
     * 2. 샘플 수로 나누지 않고 "경로 km당 밀도"로 정규화
     *    → 경로 길이가 달라도 공정한 비교 가능
     * 3. log 스케일 곡선으로 점수 계산:
     *    - 밀도 0      → 10점 (완전히 0이면 너무 낮아 사용자 혼란)
     *    - 밀도 1/km   → 약 40점
     *    - 밀도 5/km   → 약 70점
     *    - 밀도 15/km  → 약 90점
     *    - 밀도 30+/km → 최대 100점
     * 4. cctvCount/lightCount: 실제로 감지된 위치 수(중복 제거)를 반환
     */
    private data class PathMetrics(val score: Int, val cctv: Int, val light: Int)

    private suspend fun calculatePathMetrics(path: List<List<Double>>): PathMetrics {
        if (path.size < 2) return PathMetrics(10, 0, 0)

        // 경로 전체 bbox + 버퍼로 한 번에 안전시설 조회
        val degBuffer = (BUFFER_M + 30.0) / 111_000.0
        val lats = path.map { it[1] }
        val lngs = path.map { it[0] }
        val allItems = safetyDao.getAllSafetyInBounds(
            lats.min() - degBuffer, lats.max() + degBuffer,
            lngs.min() - degBuffer, lngs.max() + degBuffer
        )

        // 시설이 전혀 없으면 10점
        if (allItems.isEmpty()) return PathMetrics(10, 0, 0)

        val samples = samplePath(path, SAMPLE_INTERVAL_M)

        // 샘플 포인트별 가중합 + 위치별 감지 여부 추적 (중복 제거)
        val cctvHit  = mutableSetOf<Int>()
        val lightHit = mutableSetOf<Int>()

        for ((sLat, sLng) in samples) {
            for (item in allItems) {
                val d = distanceBetween(sLat, sLng, item.latitude, item.longitude)
                if (d > BUFFER_M) continue
                if (item.type == "CCTV") cctvHit.add(item.id)
                else lightHit.add(item.id)
            }
        }

        // 경로 총 길이(km) 계산
        var totalDistM = 0.0
        for (i in 0 until path.size - 1) {
            totalDistM += distanceBetween(
                path[i][1], path[i][0],
                path[i + 1][1], path[i + 1][0]
            )
        }
        val totalDistKm = (totalDistM / 1000.0).coerceAtLeast(0.01)

        // km당 가중 밀도
        val densityPerKm = (cctvHit.size * CCTV_WEIGHT + lightHit.size * LIGHT_WEIGHT) / totalDistKm


        // log 스케일 점수 곡선
        // score = 10 + 90 * log(1 + density/k) / log(1 + 30/k)
        // k=3: density=0->10, 1->33, 5->62, 15->85, 30->100
        val k = 3.0
        val score = (10.0 + 90.0 * ln(1.0 + densityPerKm / k) / ln(1.0 + 30.0 / k))
            .toInt().coerceIn(10, 100)

        return PathMetrics(score, cctvHit.size, lightHit.size)
    }

    /** 경로를 일정 거리 간격으로 보간 샘플링 */
    private fun samplePath(path: List<List<Double>>, intervalM: Double): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        out += path[0][1] to path[0][0]
        var carry = 0.0

        for (i in 0 until path.size - 1) {
            val lat1 = path[i][1]; val lng1 = path[i][0]
            val lat2 = path[i+1][1]; val lng2 = path[i+1][0]
            val segLen = distanceBetween(lat1, lng1, lat2, lng2)
            if (segLen == 0.0) continue

            var traveled = -carry
            while (traveled + intervalM <= segLen) {
                traveled += intervalM
                val t = traveled / segLen
                out += (lat1 + (lat2 - lat1) * t) to (lng1 + (lng2 - lng1) * t)
            }
            carry = segLen - traveled
        }
        // 끝점 추가
        out += path.last()[1] to path.last()[0]
        return out
    }

    // waypoint 선택
    private suspend fun findSafeWaypoints(
        start: LatLng, goal: LatLng,
        segments: Int, maxDetourM: Double
    ): List<LatLng> {
        val totalDist = distanceBetween(
            start.latitude, start.longitude, goal.latitude, goal.longitude
        )
        if (totalDist < MIN_ROUTE_M_FOR_WP) return emptyList()

        val searchRadiusM = (totalDist * 0.10).coerceIn(100.0, 400.0)
        val degRadius = searchRadiusM / 111_000.0

        val result = mutableListOf<LatLng>()
        for (seg in 1 until segments) {
            val ratio = seg.toDouble() / segments
            val midLat = start.latitude + (goal.latitude - start.latitude) * ratio
            val midLng = start.longitude + (goal.longitude - start.longitude) * ratio

            val candidates = safetyDao.getHighDensityPoints(
                midLat - degRadius, midLat + degRadius,
                midLng - degRadius, midLng + degRadius
            )
            if (candidates.isEmpty()) continue

            val best = candidates.maxByOrNull {
                val detour = distanceBetween(midLat, midLng, it.latitude, it.longitude)
                it.totalCount / (1.0 + detour / 50.0)
            } ?: continue

            val detour = distanceBetween(midLat, midLng, best.latitude, best.longitude)
            if (detour > maxDetourM) continue

            if (result.isNotEmpty()) {
                val last = result.last()
                val gap = distanceBetween(
                    last.latitude, last.longitude, best.latitude, best.longitude
                )
                if (gap < 100.0) continue
            }
            result.add(LatLng(best.latitude, best.longitude))
        }
        return result
    }

    // Directions API 호출
    private suspend fun callDirections(
        start: LatLng, goal: LatLng, waypoints: List<LatLng>, option: String
    ): Triple<List<List<Double>>, Int, Int>? = try {
        val wpStr = waypoints.joinToString(":") { "${it.longitude},${it.latitude}" }
        val resp = mapApi.getRoute(
            start     = "${start.longitude},${start.latitude}",
            goal      = "${goal.longitude},${goal.latitude}",
            waypoints = wpStr,
            option    = option
        )
        val tra = when (option) {
            "tracomfort" -> resp.route.tracomfort?.firstOrNull()
            "traoptimal" -> resp.route.traoptimal?.firstOrNull()
            else         -> resp.route.trafast?.firstOrNull()
        } ?: resp.route.trafast?.firstOrNull()
        tra?.let { Triple(it.path, it.summary.distance, it.summary.duration) }
    } catch (e: Exception) {
        null
    }

    private suspend fun scoreRoute(
        triple: Triple<List<List<Double>>, Int, Int>
    ): ScoredRoute {
        val (path, dist, dur) = triple
        val m = calculatePathMetrics(path)
        return ScoredRoute(path, dist, dur, m.cctv, m.light, m.score)
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