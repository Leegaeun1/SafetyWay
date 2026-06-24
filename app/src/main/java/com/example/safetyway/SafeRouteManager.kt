package com.example.safetyway

import android.widget.Toast
import com.naver.maps.geometry.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
data class TmapRouteResponse(
    val path: List<List<Double>>, // [경도, 위도] 좌표들의 모음
    val distanceM: Int,           // 총 거리 (미터)
    val durationSec: Int          // 총 소요 시간 (초)
)
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

    suspend fun fetchTmapPedestrianRoute(
        context: android.content.Context,
        start: LatLng,
        goal: LatLng,
        searchOption: String,
        passList: String? = null // 경유지 인자
    ): TmapRouteResponse? {
        val tmapApi = RetrofitClient.createTmapApi()
        val apiKey = BuildConfig.TMAP_APP_KEY

        val requestBody = TmapRouteRequest(
            startX = start.longitude,
            startY = start.latitude,
            endX = goal.longitude,
            endY = goal.latitude,
            searchOption = searchOption, // 탐색 옵션
            passList = passList // 티맵에 경유지 세팅
        )

        try {
            val response: com.google.gson.JsonElement = tmapApi.getPedestrianRoute(appKey = apiKey, body = requestBody)
            val jsonObject: com.google.gson.JsonObject = response.asJsonObject
            val features: com.google.gson.JsonArray = jsonObject.getAsJsonArray("features") ?: return null

            val firstFeature: com.google.gson.JsonObject = features.get(0).asJsonObject
            val firstProps: com.google.gson.JsonObject = firstFeature.getAsJsonObject("properties")
            val totalDistance = firstProps.get("totalDistance")?.asInt ?: 0
            val totalTime = firstProps.get("totalTime")?.asInt ?: 0

            val pathPoints = mutableListOf<List<Double>>()
            for (i in 0 until features.size()) {
                val feature: com.google.gson.JsonObject = features.get(i).asJsonObject
                val geometry: com.google.gson.JsonObject = feature.getAsJsonObject("geometry")
                val type = geometry.get("type").asString

                if (type == "LineString") { // 선형 경로인것만 찾음.
                    val coordinates: com.google.gson.JsonArray = geometry.getAsJsonArray("coordinates")
                    for (j in 0 until coordinates.size()) {
                        val coord: com.google.gson.JsonArray = coordinates.get(j).asJsonArray
                        val lng = coord.get(0).asDouble
                        val lat = coord.get(1).asDouble
                        pathPoints.add(listOf(lng, lat)) // 경도, 위도
                    }
                }
            }
            return TmapRouteResponse(pathPoints, totalDistance, totalTime)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "가까운 거리로 다시 테스트해보세요!", Toast.LENGTH_LONG).show()
            }
            e.printStackTrace()
            return null
        }
    }
    private suspend fun calculatePathMetrics(path: List<List<Double>>): PathMetrics {
        if (path.size < 2) return PathMetrics(10, 0, 0)

        val degBuffer = (BUFFER_M + 30.0) / 111_000.0
        val lats = path.map { it[1] }
        val lngs = path.map { it[0] }
        val allItems = safetyDao.getAllSafetyInBounds(
            lats.min() - degBuffer, lats.max() + degBuffer,
            lngs.min() - degBuffer, lngs.max() + degBuffer
        )

        if (allItems.isEmpty()) return PathMetrics(10, 0, 0)

        val samples = samplePath(path, SAMPLE_INTERVAL_M)

        // 각 시설별로 중복 제거를 위한 Set 생성
        val cctvHit = mutableSetOf<Int>()
        val lightHit = mutableSetOf<Int>()
        val storeHit = mutableSetOf<Int>()
        val policeHit = mutableSetOf<Int>()
        val fireHit = mutableSetOf<Int>()

        for ((sLat, sLng) in samples) {
            for (item in allItems) {
                val d = distanceBetween(sLat, sLng, item.latitude, item.longitude)
                if (d > BUFFER_M) continue
                when (item.type) {
                    "CCTV" -> repeat(item.count.coerceAtMost(5)) { cctvHit.add(item.id) }
                    "LIGHT" -> lightHit.add(item.id)
                    "STORE" -> storeHit.add(item.id)
                    "POLICE" -> policeHit.add(item.id)
                    "FIRE" -> fireHit.add(item.id)
                }
            }
        }

        var totalDistM = 0.0
        for (i in 0 until path.size - 1) {
            totalDistM += distanceBetween(path[i][1], path[i][0], path[i + 1][1], path[i + 1][0])
        }
        val totalDistKm = (totalDistM / 1000.0).coerceAtLeast(0.01)

        // 각 시설의 중요도에 따라 가중치를 곱해 밀도 계산
        val weightedSum = (cctvHit.size * 1.5) +
                (lightHit.size * 1.0) +
                (storeHit.size * 1.2) +  // 편의점은 1.2점
                (policeHit.size * 3.0) + // 경찰서는 3.0점
                (fireHit.size * 2.0)     // 소방서는 2.0점

        val densityPerKm = weightedSum / totalDistKm

        val k = 3.0
        val score = (10.0 + 90.0 * ln(1.0 + densityPerKm / k) / ln(1.0 + 30.0 / k))
            .toInt().coerceIn(10, 100)

        // 안전시설 총 카운트 반환
        val totalSafetyCount = lightHit.size + storeHit.size + policeHit.size + fireHit.size
        return PathMetrics(score, cctvHit.size, totalSafetyCount)
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