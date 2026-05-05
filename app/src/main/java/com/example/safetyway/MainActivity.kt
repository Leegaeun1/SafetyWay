package com.example.safetyway

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.LocationTrackingMode
import com.naver.maps.map.MapFragment
import com.naver.maps.map.NaverMap
import com.naver.maps.map.OnMapReadyCallback
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage
import com.naver.maps.map.overlay.PolylineOverlay
import com.naver.maps.map.util.FusedLocationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var locationSource: FusedLocationSource
    private lateinit var naverMap: NaverMap
    private var isCctvVisible = false
    private var isLightVisible = false
    private val activeCctvMarkers = mutableListOf<Marker>()
    private val activeLightMarkers = mutableListOf<Marker>()
    private val MIN_ZOOM_LEVEL = 14.0

    private lateinit var searchApi: NaverSearchApi
    private lateinit var mapApi: NaverMapApi
    private lateinit var safeRouteManager: SafeRouteManager

    private val polylines = mutableListOf<PolylineOverlay>()
    private var goalLatLng: LatLng? = null
    private var searchResults = listOf<SearchItem>()
    private var activeInput: EditText? = null

    private var selectedRouteIndex = 0  // 선택된 경로 인덱스
    private var routeResults = listOf<RouteResult>()  // 경로 결과 저장
    // 경로 결과 데이터
    data class RouteResult(
        val path: List<List<Double>>,
        val distanceM: Int,
        val durationSec: Int,
        val cctvCount: Int,
        val lightCount: Int,
        val safetyScore: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        searchApi = RetrofitClient.createSearchApi(this)
        mapApi = RetrofitClient.createMapApi(this)
        safeRouteManager = SafeRouteManager(
            safetyDao = AppDatabase.getDatabase(this).safetyDao(),
            mapApi = mapApi
        )

        val fm = supportFragmentManager
        val mapFragment = fm.findFragmentById(R.id.map_fragment) as MapFragment?
            ?: MapFragment.newInstance().also {
                fm.beginTransaction().add(R.id.map_fragment, it).commit()
            }
        mapFragment.getMapAsync(this)
        locationSource = FusedLocationSource(this, LOCATION_PERMISSION_REQUEST_CODE)

        setupMainSearchCard()
        setupRouteInputCard()
    }

    // "어디로 갈까요?" 클릭 → 경로탐색 카드로 전환
    private fun setupMainSearchCard() {
        findViewById<CardView>(R.id.main_search_card).setOnClickListener {
            it.visibility = View.GONE
            findViewById<CardView>(R.id.route_input_card).visibility = View.VISIBLE

            // 출발지 기본값 "현재 위치" (null = 현재위치 사용)
            startLatLng = null
            val startInput = findViewById<EditText>(R.id.start_input)
            startInput.setText("현재 위치")

            // 목적지로 포커스
            val goalInput = findViewById<EditText>(R.id.goal_input)
            goalInput.requestFocus()
        }
    }

    // 출발지 LatLng (null이면 현재위치 사용)
    private var startLatLng: LatLng? = null

    private fun setupRouteInputCard() {
        val startInput = findViewById<EditText>(R.id.start_input)
        val goalInput = findViewById<EditText>(R.id.goal_input)
        val suggestionCard = findViewById<CardView>(R.id.suggestion_card)
        val suggestionList = findViewById<ListView>(R.id.suggestion_list)

        fun setupInputWatcher(input: EditText) {
            var searchJob: Job? = null
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchJob?.cancel()
                    val query = s.toString()

                    // 텍스트 바뀌면 해당 입력의 LatLng 초기화
                    if (input == goalInput) goalLatLng = null
                    if (input == startInput) startLatLng = null

                    // "현재 위치" 입력이면 null(현재위치 사용)로 유지하고 자동완성 안 띄움
                    if (input == startInput && query == "현재 위치") {
                        startLatLng = null
                        suggestionCard.visibility = View.GONE
                        return
                    }

                    if (query.length < 2) {
                        suggestionCard.visibility = View.GONE
                        return
                    }
                    activeInput = input
                    searchJob = lifecycleScope.launch {
                        delay(300)
                        searchSuggestions(query)
                    }
                }
            })
        }

        setupInputWatcher(startInput)
        setupInputWatcher(goalInput)

        // 자동완성 클릭
        suggestionList.setOnItemClickListener { _, _, position, _ ->
            val item = searchResults[position]
            val cleanName = item.title.replace(Regex("<[^>]*>"), "")
            val lat = item.mapy.toDouble() / 1e7
            val lng = item.mapx.toDouble() / 1e7

            activeInput?.setText(cleanName)
            suggestionCard.visibility = View.GONE

            if (activeInput == startInput) {
                startLatLng = LatLng(lat, lng)
                // 출발지와 목적지 모두 선택됐으면 경로 탐색
                if (goalLatLng != null) findRoutes()
            } else if (activeInput == goalInput) {
                goalLatLng = LatLng(lat, lng)
                findRoutes()
            }
        }
    }

    // 검색 자동완성 - Reverse Geocoding으로 현재위치의 시/구 이름을 쿼리에 붙여 검색
    private suspend fun searchSuggestions(query: String) {
        try {
            val currentLoc = locationSource.lastLocation

            // 현재위치가 있으면 reverse geocoding으로 행정구역명 가져오기
            val localQuery = if (currentLoc != null) {
                try {
                    val coords = "${currentLoc.longitude},${currentLoc.latitude}"
                    val rgResult = withContext(Dispatchers.IO) {
                        mapApi.reverseGeocode(coords = coords)
                    }
                    // area2 = 구/군, area3 = 동/읍/면
                    val area2 = rgResult.results?.firstOrNull()?.region?.area2?.name ?: ""
                    val area3 = rgResult.results?.firstOrNull()?.region?.area3?.name ?: ""
                    // 검색어에 현재 구/동 추가
                    val locationHint = listOf(area2, area3).filter { it.isNotEmpty() }.joinToString(" ")
                    if (locationHint.isNotEmpty()) "$query $locationHint" else query
                } catch (_: Exception) {
                    query  // reverse geocoding 실패 시 원래 쿼리 사용
                }
            } else query

            val result = withContext(Dispatchers.IO) {
                searchApi.searchPlace(query = localQuery, display = 10)
            }
            searchResults = result.items

            // 현재위치 기준 거리순 정렬
            val sorted = if (currentLoc != null) {
                searchResults.sortedBy { item ->
                    val lat = item.mapy.toDouble() / 1e7
                    val lng = item.mapx.toDouble() / 1e7
                    distanceBetween(currentLoc.latitude, currentLoc.longitude, lat, lng)
                }
            } else searchResults

            searchResults = sorted

            val names = sorted.take(5).map { item ->
                val cleanName = item.title.replace(Regex("<[^>]*>"), "")
                "$cleanName | ${item.roadAddress.ifEmpty { item.address }}"
            }

            val suggestionCard = findViewById<CardView>(R.id.suggestion_card)
            val suggestionList = findViewById<ListView>(R.id.suggestion_list)

            if (names.isEmpty()) { suggestionCard.visibility = View.GONE; return }

            suggestionList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
            suggestionCard.visibility = View.VISIBLE

        } catch (e: Exception) { /* 무시 */ }
    }
    private fun startNaverMapNavi() {
        val goal = goalLatLng ?: return
        val selectedRoute = routeResults.getOrNull(selectedRouteIndex) ?: return

        val lats = selectedRoute.path.map { it[1] }.toDoubleArray()
        val lngs = selectedRoute.path.map { it[0] }.toDoubleArray()

        val intent = android.content.Intent(this, NavigationActivity::class.java).apply {
            putExtra(NavigationActivity.EXTRA_PATH_LAT, lats)
            putExtra(NavigationActivity.EXTRA_PATH_LNG, lngs)
            putExtra(NavigationActivity.EXTRA_GOAL_LAT, goal.latitude)
            putExtra(NavigationActivity.EXTRA_GOAL_LNG, goal.longitude)
            putExtra(NavigationActivity.EXTRA_GOAL_NAME,
                findViewById<EditText>(R.id.goal_input).text.toString())
            putExtra(NavigationActivity.EXTRA_TOTAL_DISTANCE, selectedRoute.distanceM.toDouble())
        }
        startActivity(intent)
    }
    // 경로 3개 탐색
    private fun findRoutes() {
        val goal = goalLatLng ?: return
        val start = startLatLng ?: naverMap.locationOverlay.position
        clearPolylines()

        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    // trafast = 가장 빠른 경로, traoptimal = 최적(거리+시간 균형)
                    // 두 경로를 받아서 안전 경로(safe route)를 별도 계산
                    val fastResponse = mapApi.getRoute(
                        start = "${start.longitude},${start.latitude}",
                        goal = "${goal.longitude},${goal.latitude}",
                        option = "trafast"
                    )
                    val optimalResponse = mapApi.getRoute(
                        start = "${start.longitude},${start.latitude}",
                        goal = "${goal.longitude},${goal.latitude}",
                        option = "traoptimal"
                    )

                    val fastRoute = fastResponse.route.trafast?.get(0)
                    val optimalRoute = optimalResponse.route.traoptimal?.get(0)
                        ?: optimalResponse.route.trafast?.get(0)

                    val routeList = mutableListOf<RouteResult>()

                    // 최단 경로 분석 (trafast)
                    if (fastRoute != null) {
                        val path = fastRoute.path
                        val distance = fastRoute.summary.distance
                        val walkMin = (distance / 80.0).toInt()
                        val cctvCount = countNearby(path, "CCTV")
                        val lightCount = countNearby(path, "LIGHT")
                        val safetyScore = calcSafetyScore(path, cctvCount, lightCount, distance)
                        routeList.add(RouteResult(path, distance, walkMin * 60, cctvCount, lightCount, safetyScore))
                    }

                    // 최적 경로 분석 (traoptimal, 최단과 다를 경우만)
                    if (optimalRoute != null) {
                        val path = optimalRoute.path
                        val distance = optimalRoute.summary.distance
                        val walkMin = (distance / 80.0).toInt()
                        val cctvCount = countNearby(path, "CCTV")
                        val lightCount = countNearby(path, "LIGHT")
                        val safetyScore = calcSafetyScore(path, cctvCount, lightCount, distance)
                        routeList.add(RouteResult(path, distance, walkMin * 60, cctvCount, lightCount, safetyScore))
                    }

                    // 안전 경로 탐색 (SafeRouteManager 사용)
                    try {
                        val safePath = safeRouteManager.findSafeRoute(start, goal)
                        if (safePath.isNotEmpty()) {
                            var safeDist = 0.0
                            for (i in 0 until safePath.size - 1) {
                                val a = safePath[i]; val b = safePath[i+1]
                                safeDist += distanceBetween(a[1], a[0], b[1], b[0])
                            }
                            val safeDistInt = safeDist.toInt()
                            val walkMin = (safeDist / 80.0).toInt()
                            val cctvCount = countNearby(safePath, "CCTV")
                            val lightCount = countNearby(safePath, "LIGHT")
                            val safetyScore = calcSafetyScore(safePath, cctvCount, lightCount, safeDistInt)
                            routeList.add(RouteResult(safePath, safeDistInt, walkMin * 60, cctvCount, lightCount, safetyScore))
                        }
                    } catch (_: Exception) { }

                    // 중복 경로 제거 (거리 차이 50m 이하면 같은 경로로 판단)
                    val deduplicated = routeList
                        .sortedByDescending { it.safetyScore }
                        .distinctBy { (it.distanceM / 50) }  // 50m 단위로 중복 제거

                    // 3개 부족하면 기존 경로에서 가장 차이나는 것 채움
                    val final3 = deduplicated.take(3).toMutableList()
                    while (final3.size < minOf(3, routeList.size)) {
                        val candidate = routeList.firstOrNull { c -> final3.none { it === c } }
                        if (candidate != null) final3.add(candidate) else break
                    }

                    // 정렬: 1=안전점수 최고, 2=안전+거리 균형, 3=최단거리
                    val sorted = final3.sortedWith(compareByDescending<RouteResult> { it.safetyScore })
                    // 추천3은 무조건 최단거리로
                    if (sorted.size == 3) {
                        val reordered = mutableListOf(sorted[0], sorted[1], sorted.minByOrNull { it.distanceM }!!)
                        reordered
                    } else sorted
                }

                withContext(Dispatchers.Main) {
                    val colors = listOf(
                        Color.parseColor("#3D6BF5"),
                        Color.parseColor("#AAAAAA"),
                        Color.parseColor("#CCCCCC")
                    )
                    results.forEachIndexed { i, route ->
                        drawPolyline(route.path, colors[i], if (i == 0) 15 else 10)
                    }
                    showRouteCards(results)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "경로 탐색 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 안전점수 계산 (0~100)
     * - CCTV와 보안등 밀도(개/km)를 기준으로 점수화
     * - 밀도 5개/km 이상이면 만점 구간
     */
    private fun calcSafetyScore(
        path: List<List<Double>>,
        cctvCount: Int,
        lightCount: Int,
        distanceM: Int
    ): Int {
        if (distanceM <= 0) return 0
        val distKm = distanceM / 1000.0
        // 밀도 계산 (개/km)
        val cctvDensity = cctvCount / distKm
        val lightDensity = lightCount / distKm
        // 밀도 5개/km를 만점 기준으로 정규화 (비율 기반)
        val cctvScore = minOf(cctvDensity / 5.0, 1.0) * 50   // CCTV 최대 50점
        val lightScore = minOf(lightDensity / 5.0, 1.0) * 50 // 보안등 최대 50점
        return (cctvScore + lightScore).toInt().coerceIn(0, 100)
    }

    // 경로 주변 CCTV/보안등 개수
    private suspend fun countNearby(path: List<List<Double>>, type: String): Int {
        var count = 0
        path.filterIndexed { i, _ -> i % 10 == 0 }.forEach { point ->
            val lat = point[1]; val lng = point[0]
            val items = AppDatabase.getDatabase(applicationContext).safetyDao()
                .getSafetyInBounds(lat - 0.0005, lat + 0.0005, lng - 0.0005, lng + 0.0005, type)
            count += items.sumOf { it.count }
        }
        return count
    }

    // 하단 카드 3개 업데이트
    private fun showRouteCards(routes: List<RouteResult>) {
        routeResults = routes
        selectedRouteIndex = 0

        val scroll = findViewById<HorizontalScrollView>(R.id.route_result_scroll)
        val naviBtn = findViewById<Button>(R.id.btn_start_navi)
        scroll.visibility = View.VISIBLE
        naviBtn.visibility = View.VISIBLE

        val cardIds    = listOf(R.id.route_card_1, R.id.route_card_2, R.id.route_card_3)
        val labelIds   = listOf(R.id.label_1, R.id.label_2, R.id.label_3)
        val timeIds    = listOf(R.id.time_1, R.id.time_2, R.id.time_3)
        val distIds    = listOf(R.id.dist_1, R.id.dist_2, R.id.dist_3)
        val cctvIds    = listOf(R.id.cctv_1, R.id.cctv_2, R.id.cctv_3)
        val lightIds   = listOf(R.id.light_1, R.id.light_2, R.id.light_3)
        val scoreIds   = listOf(R.id.score_1, R.id.score_2, R.id.score_3)

        fun applySelection(selected: Int) {
            cardIds.forEachIndexed { i, cardId ->
                val isSelected = i == selected
                val bgColor = if (isSelected) Color.parseColor("#3D6BF5") else Color.WHITE
                val mainTextColor = if (isSelected) Color.WHITE else Color.BLACK
                val subTextColor = if (isSelected) Color.parseColor("#CCDDFF") else Color.parseColor("#888888")

                findViewById<CardView>(cardId).setCardBackgroundColor(bgColor)
                findViewById<TextView>(labelIds[i]).setTextColor(mainTextColor)
                findViewById<TextView>(timeIds[i]).setTextColor(mainTextColor)
                findViewById<TextView>(distIds[i]).setTextColor(subTextColor)
                findViewById<TextView>(cctvIds[i]).setTextColor(subTextColor)
                findViewById<TextView>(lightIds[i]).setTextColor(subTextColor)
                findViewById<TextView>(scoreIds[i]).setTextColor(mainTextColor)

                // 폴리라인 강조
                if (i < polylines.size) {
                    polylines[i].width = if (isSelected) 15 else 8
                    polylines[i].color = if (isSelected) Color.parseColor("#3D6BF5")
                    else Color.parseColor("#CCCCCC")
                }
            }
        }

        routes.forEachIndexed { i, route ->
            if (i >= 3) return@forEachIndexed
            val minutes = maxOf(1, route.durationSec / 60)
            val km = "%.1f".format(route.distanceM / 1000.0)
            val steps = (route.distanceM * 1.3).toInt()

            val label = when (i) {
                0 -> "🛡 안전 추천"
                1 -> "⚖ 안전+거리"
                else -> "⚡ 최단거리"
            }

            findViewById<TextView>(labelIds[i]).text  = label
            findViewById<TextView>(timeIds[i]).text   = "${minutes}분"
            findViewById<TextView>(distIds[i]).text   = "${km}km · ${steps}걸음"
            findViewById<TextView>(cctvIds[i]).text   = "CCTV ${route.cctvCount}개"
            findViewById<TextView>(lightIds[i]).text  = "보안등 ${route.lightCount}개"
            findViewById<TextView>(scoreIds[i]).text  = "안전점수 ${route.safetyScore}점"

            findViewById<CardView>(cardIds[i]).setOnClickListener {
                selectedRouteIndex = i
                applySelection(i)
            }
        }

        // 기본 선택
        applySelection(0)

        // 안심 귀가 버튼 → 네이버 지도 앱 연동
        naviBtn.setOnClickListener {
            startNaverMapNavi()
        }
    }

    private fun drawPolyline(path: List<List<Double>>, color: Int, width: Int) {
        if (path.size < 2) return
        val coords = path.map { LatLng(it[1], it[0]) }
        val polyline = PolylineOverlay().apply {
            this.coords = coords
            this.color = color
            this.width = width
            map = naverMap
        }
        polylines.add(polyline)
    }

    private fun clearPolylines() {
        polylines.forEach { it.map = null }
        polylines.clear()
    }

    private fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng/2) * Math.sin(dLng/2)
        return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))
    }

    override fun onMapReady(naverMap: NaverMap) {
        this.naverMap = naverMap
        naverMap.locationSource = locationSource
        naverMap.uiSettings.isLocationButtonEnabled = true
        naverMap.locationTrackingMode = LocationTrackingMode.Follow
        setupButtonListeners()
        naverMap.addOnCameraIdleListener {
            updateMarkers("CCTV")
            updateMarkers("LIGHT")
        }
    }

    private fun setupButtonListeners() {
        val cctvBtn = findViewById<ImageButton>(R.id.cctv_btn)
        val streetlightBtn = findViewById<ImageButton>(R.id.streetlight_btn)
        cctvBtn.setOnClickListener {
            isCctvVisible = !isCctvVisible
            updateMarkers("CCTV")
            cctvBtn.setImageResource(if (isCctvVisible) R.drawable.cctv_no_see else R.drawable.cctv_see)
        }
        streetlightBtn.setOnClickListener {
            isLightVisible = !isLightVisible
            updateMarkers("LIGHT")
            streetlightBtn.setImageResource(if (isLightVisible) R.drawable.streetlight_no_see else R.drawable.streetlight_see)
        }
    }

    private fun updateMarkers(type: String) {
        val isVisible = if (type == "CCTV") isCctvVisible else isLightVisible
        val activeMarkers = if (type == "CCTV") activeCctvMarkers else activeLightMarkers
        activeMarkers.forEach { it.map = null }
        activeMarkers.clear()
        if (!isVisible || naverMap.cameraPosition.zoom < MIN_ZOOM_LEVEL) return
        val bounds = naverMap.contentBounds
        lifecycleScope.launch {
            val dataList = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(applicationContext).safetyDao()
                    .getSafetyInBounds(bounds.southWest.latitude, bounds.northEast.latitude,
                        bounds.southWest.longitude, bounds.northEast.longitude, type)
            }
            withContext(Dispatchers.Main) {
                for (item in dataList) {
                    val marker = Marker().apply {
                        position = LatLng(item.latitude, item.longitude)
                        map = naverMap
                        icon = OverlayImage.fromResource(if (type == "CCTV") R.drawable.cctv else R.drawable.streetlight)
                        width = 60; height = 60
                    }
                    activeMarkers.add(marker)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (locationSource.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            if (!locationSource.isActivated) naverMap.locationTrackingMode = LocationTrackingMode.None
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1000
    }
}