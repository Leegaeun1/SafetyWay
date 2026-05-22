package com.example.safetyway

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
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
    private var startLatLng: LatLng? = null  // null = 현재위치 사용
    private var selectedRouteIndex = 0
    private var routeResults = listOf<RouteResult>()

    private var lastKnownLocation: LatLng? = null
    private var cachedCity: String? = null
    private var cachedDong: String? = null
    private var pendingTarget: String = "goal"
    private lateinit var fusedClient: com.google.android.gms.location.FusedLocationProviderClient
    private var locationCallback: com.google.android.gms.location.LocationCallback? = null
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

        locationSource = FusedLocationSource(this, LOCATION_PERMISSION_REQUEST_CODE)
        searchApi = RetrofitClient.createSearchApi(this)
        mapApi    = RetrofitClient.createMapApi(this)
        safeRouteManager = SafeRouteManager(
            safetyDao = AppDatabase.getDatabase(this).safetyDao(),
            mapApi    = mapApi
        )
        fusedClient = com.google.android.gms.location.LocationServices
            .getFusedLocationProviderClient(this)

        val fm = supportFragmentManager
        val mapFragment = fm.findFragmentById(R.id.map_fragment) as MapFragment?
            ?: MapFragment.newInstance().also {
                fm.beginTransaction().add(R.id.map_fragment, it).commit()
            }
        mapFragment.getMapAsync(this)

        startLocationUpdates()

        setupMainSearchCard()
        setupRouteInputCard()
    }
    @android.annotation.SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return

        val req = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5000L
        ).setMaxUpdates(10).build()

        locationCallback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(
                result: com.google.android.gms.location.LocationResult
            ) {
                val loc = result.lastLocation ?: return
                lastKnownLocation = LatLng(loc.latitude, loc.longitude)

                if (cachedCity != null) return  // 이미 지역명 있으면 스킵

                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        val rg = mapApi.reverseGeocode("${loc.longitude},${loc.latitude}")
                        val region = rg.results?.firstOrNull()?.region
                        withContext(Dispatchers.Main) {
                            cachedCity = region?.area2?.name
                            cachedDong = region?.area3?.name
                            android.util.Log.d("SafetyWay",
                                "MainActivity city=$cachedCity, dong=$cachedDong")
                        }
                    }
                }
            }
        }

        fusedClient.requestLocationUpdates(req, locationCallback!!, mainLooper)
    }
    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
    }
    // SearchActivity 결과 처리
    private val searchLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val name = data.getStringExtra(SearchActivity.RESULT_NAME) ?: return@registerForActivityResult
        val lat  = data.getDoubleExtra(SearchActivity.RESULT_LAT, 0.0)
        val lng  = data.getDoubleExtra(SearchActivity.RESULT_LNG, 0.0)
        if (lat == 0.0) return@registerForActivityResult

        // route_input_card 표시 (검색 카드 숨김)
        showRouteInputCard()

        when (pendingTarget) {
            "goal" -> {
                findViewById<EditText>(R.id.goal_input).setText(name)
                goalLatLng = LatLng(lat, lng)
                // 출발지도 이미 설정되어 있으면 바로 경로탐색
                if (startLatLng != null || lastKnownLocation != null) findRoutes()
            }
            "start" -> {
                findViewById<EditText>(R.id.start_input).setText(name)
                startLatLng = LatLng(lat, lng)
                // 목적지도 이미 설정되어 있으면 바로 경로탐색
                if (goalLatLng != null) findRoutes()
            }
        }
    }

    private fun openSearchFor(target: String) {
        android.util.Log.d("SafetyWay", "openSearchFor: city=$cachedCity, dong=$cachedDong")

        pendingTarget = target
        val loc = lastKnownLocation
            ?: naverMap.locationOverlay.position.takeIf {
                it.latitude != 0.0 && it.longitude != 0.0
            }
        val intent = Intent(this, SearchActivity::class.java).apply {
            putExtra(SearchActivity.EXTRA_TARGET, target)
            putExtra(SearchActivity.EXTRA_CUR_LAT, loc?.latitude ?: 0.0)
            putExtra(SearchActivity.EXTRA_CUR_LNG, loc?.longitude ?: 0.0)
            putExtra(SearchActivity.EXTRA_CITY, cachedCity)
            putExtra(SearchActivity.EXTRA_DONG, cachedDong)
        }
        searchLauncher.launch(intent)
    }

    // 메인 검색 카드 (어디로 갈까요?)
    private fun setupMainSearchCard() {
        findViewById<CardView>(R.id.main_search_card).setOnClickListener {
            openSearchFor("goal")
        }
    }

    // 경로입력 카드 표시/숨김
    private fun showRouteInputCard() {
        findViewById<CardView>(R.id.main_search_card).visibility = View.GONE
        findViewById<CardView>(R.id.route_input_card).visibility = View.VISIBLE
    }

    // 출발지/목적지 EditText 클릭 -> SearchActivity
    private fun setupRouteInputCard() {
        val startInput = findViewById<EditText>(R.id.start_input)
        val goalInput  = findViewById<EditText>(R.id.goal_input)

        listOf(startInput, goalInput).forEach { et ->
            et.isFocusable = false
            et.isClickable = true
        }
        startInput.setOnClickListener { openSearchFor("start") }
        goalInput.setOnClickListener  { openSearchFor("goal") }

        // 출발지 기본값: "현재 위치" 힌트 -> 클릭 시 검색 또는 그냥 현재위치 사용
        startInput.hint = "출발지 (미입력 시 현재 위치)"
    }

    private fun hideSuggestions() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }

    // 경로 탐색
    private fun findRoutes() {
        val goal = goalLatLng ?: return

        // 출발지: 직접 선택했으면 그것, 아니면 현재위치
        val start = startLatLng
            ?: lastKnownLocation
            ?: naverMap.locationOverlay.position.takeIf { it.latitude != 0.0 }

        if (start == null || start.latitude == 0.0) {
            Toast.makeText(this, "현재 위치를 확인 중입니다", Toast.LENGTH_SHORT).show()
            return
        }
        clearPolylines()

        lifecycleScope.launch {
            try {
                val scored = withContext(Dispatchers.IO) {
                    safeRouteManager.findThreeRoutes(start, goal)
                }
                if (scored.isEmpty()) {
                    Toast.makeText(this@MainActivity, "경로를 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val results = scored.map { sr ->
                    RouteResult(
                        path = sr.path,
                        distanceM = sr.distanceM,
                        durationSec = sr.durationMs / 1000,
                        cctvCount = sr.cctvCount,
                        lightCount = sr.lightCount,
                        safetyScore = sr.safetyScore
                    )
                }

                results.forEachIndexed { i, route ->
                    drawPolyline(
                        path  = route.path,
                        color = if (i == 0) ROUTE_COLORS[0] else ROUTE_GRAY,
                        width = if (i == 0) 15 else 8
                    )
                }
                showRouteCards(results)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "경로 탐색 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val ROUTE_COLORS = listOf(
        Color.parseColor("#2ECC71"),
        Color.parseColor("#F1C40F"),
        Color.parseColor("#3D6BF5")
    )
    private val ROUTE_GRAY = Color.parseColor("#AAAAAA")

    private fun showRouteCards(routes: List<RouteResult>) {
        routeResults = routes
        selectedRouteIndex = 0

        val scroll  = findViewById<HorizontalScrollView>(R.id.route_result_scroll)
        val naviBtn = findViewById<Button>(R.id.btn_start_navi)
        scroll.visibility  = View.VISIBLE
        naviBtn.visibility = View.VISIBLE

        val cardIds  = listOf(R.id.route_card_1, R.id.route_card_2, R.id.route_card_3)
        val labelIds = listOf(R.id.label_1, R.id.label_2, R.id.label_3)
        val timeIds  = listOf(R.id.time_1, R.id.time_2, R.id.time_3)
        val distIds  = listOf(R.id.dist_1, R.id.dist_2, R.id.dist_3)
        val cctvIds  = listOf(R.id.cctv_1, R.id.cctv_2, R.id.cctv_3)
        val lightIds = listOf(R.id.light_1, R.id.light_2, R.id.light_3)
        val scoreIds = listOf(R.id.score_1, R.id.score_2, R.id.score_3)

        fun applySelection(selected: Int) {
            cardIds.forEachIndexed { i, cardId ->
                val isSelected = i == selected
                val routeColor = ROUTE_COLORS.getOrElse(i) { Color.parseColor("#3D6BF5") }
                val bgColor   = if (isSelected) routeColor else Color.WHITE
                val mainColor = if (isSelected) Color.WHITE else Color.BLACK
                val subColor  = if (isSelected) Color.argb(200, 255, 255, 255) else Color.parseColor("#888888")

                findViewById<CardView>(cardId).setCardBackgroundColor(bgColor)
                listOf(labelIds[i], timeIds[i]).forEach {
                    findViewById<TextView>(it).setTextColor(mainColor)
                }
                listOf(distIds[i], cctvIds[i], lightIds[i]).forEach {
                    findViewById<TextView>(it).setTextColor(subColor)
                }
                findViewById<TextView>(scoreIds[i]).setTextColor(mainColor)

                if (i < polylines.size) {
                    polylines[i].width = if (isSelected) 15 else 8
                    polylines[i].color = if (isSelected) routeColor else ROUTE_GRAY
                }
            }
        }

        routes.forEachIndexed { i, route ->
            if (i >= 3) return@forEachIndexed
            val walkingMinutes = (route.distanceM / 65.0).toInt().coerceAtLeast(1)
            val steps = (route.distanceM * 1.4).toInt()
            val km    = "%.1f".format(route.distanceM / 1000.0)
            val label = when (i) {
                0    -> "🛡 안전 추천"
                1    -> "⚖ 안전+거리"
                else -> "⚡ 최단거리"
            }

            findViewById<TextView>(labelIds[i]).text  = label
            findViewById<TextView>(timeIds[i]).text   = "${walkingMinutes}분"
            findViewById<TextView>(distIds[i]).text   = "${km}km · ${steps}걸음"
            findViewById<TextView>(cctvIds[i]).text   = "CCTV ${route.cctvCount}개"
            findViewById<TextView>(lightIds[i]).text  = "보안등 ${route.lightCount}개"
            findViewById<TextView>(scoreIds[i]).text  = "안전점수 ${route.safetyScore}점"

            findViewById<CardView>(cardIds[i]).setOnClickListener {
                selectedRouteIndex = i
                applySelection(i)
            }
        }
        applySelection(0)

        naviBtn.setOnClickListener { startNaverMapNavi() }
    }

    private fun startNaverMapNavi() {
        val goal = goalLatLng ?: return
        val selectedRoute = routeResults.getOrNull(selectedRouteIndex) ?: return

        val lats = selectedRoute.path.map { it[1] }.toDoubleArray()
        val lngs = selectedRoute.path.map { it[0] }.toDoubleArray()

        val intent = Intent(this, NavigationActivity::class.java).apply {
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

    private fun drawPolyline(path: List<List<Double>>, color: Int, width: Int) {
        if (path.size < 2) return
        val coords = path.map { LatLng(it[1], it[0]) }
        val polyline = PolylineOverlay().apply {
            this.coords = coords
            this.color  = color
            this.width  = width
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

    // 지도 준비
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

        // 위치 변경 시 lastKnownLocation 갱신 + 최초 1회 reverseGeocode
        naverMap.addOnLocationChangeListener { loc ->
            lastKnownLocation = LatLng(loc.latitude, loc.longitude)
            if (cachedCity == null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        val rg = mapApi.reverseGeocode("${loc.longitude},${loc.latitude}")
                        val region = rg.results?.firstOrNull()?.region
                        withContext(Dispatchers.Main) {
                            cachedCity = region?.area2?.name
                            cachedDong = region?.area3?.name
                            android.util.Log.d("SafetyWay", "city=$cachedCity, dong=$cachedDong")

                        }
                    }
                }
            }
        }
    }

    private fun setupButtonListeners() {
        val cctvBtn        = findViewById<ImageButton>(R.id.cctv_btn)
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
        val isVisible     = if (type == "CCTV") isCctvVisible else isLightVisible
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
                        map      = naverMap
                        icon     = OverlayImage.fromResource(if (type == "CCTV") R.drawable.cctv else R.drawable.streetlight)
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