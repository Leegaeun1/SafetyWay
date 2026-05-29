package com.example.safetyway

import android.app.Activity // 다른 화면에서 결과 받아올 때 필요
import android.content.Intent // 화면 전환시 필요
import android.graphics.Color
import android.os.Bundle // 화면 생성될 때 이전 상태 데이터 넘겨받는 묶음
import android.view.View // UI요소들
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope // Activity 생명주기에 묶인 코루틴 스코프. 화면이 꺼지면 자동으로 코루틴도 취소됨
import com.naver.maps.geometry.LatLng // 네이버지도의 위경도 좌표 클래스.
import com.naver.maps.map.LocationTrackingMode
import com.naver.maps.map.MapFragment
import com.naver.maps.map.NaverMap
import com.naver.maps.map.OnMapReadyCallback
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage
import com.naver.maps.map.overlay.PolylineOverlay
import com.naver.maps.map.util.FusedLocationSource // 더 정확한 위치를 뽑아주는 위치 소스!
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var locationSource: FusedLocationSource
    private lateinit var naverMap: NaverMap
    private var isCctvVisible = false // CCTV가 보이는 상태인가
    private var isLightVisible = false // 보안등이 보이는 상태인가
    private val activeCctvMarkers = mutableListOf<Marker>() // 현재 지도에 표시중인 CCTV 마커들 담아두는 목록임. 나중에 한번에 지우기위함
    private val activeLightMarkers = mutableListOf<Marker>() // 위와 같이 보안등 담아두는 목록.
    private val MIN_ZOOM_LEVEL = 14.0 // 줌 레벨이 이것보다 낮으면 마커를 표시하지 X

    private lateinit var searchApi: NaverSearchApi // 장소 검색 api
    private lateinit var mapApi: NaverMapApi // 지도 api
    private lateinit var safeRouteManager: SafeRouteManager // 안전경로 계산용

    private val polylines = mutableListOf<PolylineOverlay>() // 지도에 그린 경로 선들을 보관하는 목록. 경로 초기화할 때 전부 지울 수 있도록
    private var goalLatLng: LatLng? = null // 목적지 좌표. null이면 아직 선택안한것.
    private var startLatLng: LatLng? = null  // 출발지 좌표. null = 현재위치 사용
    private var selectedRouteIndex = 0 // 3개 경로 중 몇 번째가 선택되었는지?
    private var routeResults = listOf<RouteResult>() // 경로 결과 목록.

    private var lastKnownLocation: LatLng? = null // 마지막으로 알고있는 GPS위치.
    private var cachedCity: String? = null // 역 지오코딩으로 얻은 시 이름
    private var cachedDong: String? = null // 역 지오코딩으로 얻은 동 이름
    private var pendingTarget: String = "goal" // 검색창을 열었을 때 출발지인지 목적지 검색인지 기억!
    private lateinit var fusedClient: com.google.android.gms.location.FusedLocationProviderClient // GPS 업데이트를 요청/취소하는 클라이언트
    private var locationCallback: com.google.android.gms.location.LocationCallback? = null // 위치 업데이트 콜백 객체
    data class RouteResult( // 경로 하나를 표현하는 데이터 클래스. 
        val path: List<List<Double>>, // 좌표 목록
        val distanceM: Int, // 거리(미터)
        val durationSec: Int, // 시간(초)
        val cctvCount: Int, // cctv 수
        val lightCount: Int, // 보안등 수
        val safetyScore: Int // 안전점수
    )

    /**앱이 시작됐을 때 네이버 지도, 검색 API, 경로 매니저 세팅
     * startLocationUpdates()로 GPS위치를 즉시 가져옴.*/
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        locationSource = FusedLocationSource(this, LOCATION_PERMISSION_REQUEST_CODE) // 위치 소스 초기화.
        searchApi = RetrofitClient.createSearchApi(this) // retrofit으로 네이버 검색 api 인스턴스 생성
        mapApi    = RetrofitClient.createMapApi(this) // retrofit으로 네이버 지도 api 인스턴트 생성
        safeRouteManager = SafeRouteManager( // 로컬 DB와 지도 API를 주입해서 SafeRouteManager 생성!!
            safetyDao = AppDatabase.getDatabase(this).safetyDao(),
            mapApi    = mapApi
        )
        fusedClient = com.google.android.gms.location.LocationServices // 초기화. GPS 업데이트 요청에 씀
            .getFusedLocationProviderClient(this)

        val fm = supportFragmentManager
        val mapFragment = fm.findFragmentById(R.id.map_fragment) as MapFragment? // 화면에서 지도 Fragment를 찾거나 없으면 새로 만들어 붙임.
            ?: MapFragment.newInstance().also {
                fm.beginTransaction().add(R.id.map_fragment, it).commit()
            }
        mapFragment.getMapAsync(this) // 지도가 준비되면 onMapReady를 호출함.
        startLocationUpdates() // GPS 업데이트 시작
        setupMainSearchCard() // 메인 검색 카드 설정
        setupRouteInputCard() // 경로 입력 카드 설정
        setupDataSourceButton() // 데이터 출처 설정
    }
    @android.annotation.SuppressLint("MissingPermission") // 경고 무시
    private fun startLocationUpdates() { // GPS 업데이트.
        val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission( // 위치 권한 없으면 함수 종료!
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return

        val req = com.google.android.gms.location.LocationRequest.Builder( // 5초마다 위치요청, 최대 10번만 받음!
            com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5000L
        ).setMaxUpdates(10).build()

        locationCallback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(
                result: com.google.android.gms.location.LocationResult
            ) {
                val loc = result.lastLocation ?: return
                lastKnownLocation = LatLng(loc.latitude, loc.longitude) // 위치 업데이트될때마다 갱신됨!

                if (cachedCity != null) return  // 이미 지역명 있으면 스킵

                lifecycleScope.launch(Dispatchers.IO) { // 지역 없으면 역 지오코딩해서 저장! IO 스레드에서 API 호출 후  Main 스레드에 저장!
                    runCatching {
                        val rg = mapApi.reverseGeocode("${loc.longitude},${loc.latitude}")
                        val region = rg.results?.firstOrNull()?.region
                        withContext(Dispatchers.Main) {
                            cachedCity = region?.area2?.name // 시 이름
                            cachedDong = region?.area3?.name // 동 이름
                        }
                    }
                }
            }
        }

        fusedClient.requestLocationUpdates(req, locationCallback!!, mainLooper) // 실제로 GPS 업데이트 시작! 메인스레드에서 받음.
    }
    // 뒤로가기: route_input_card 보이면 -> main_search_card로, 아니면 기본 종료
    override fun onBackPressed() {
        val routeCard = findViewById<CardView>(R.id.route_input_card)
        if (routeCard.visibility == View.VISIBLE) {
            resetToMainSearch()
        } else {
            super.onBackPressed()
        }
    }
    private fun setupDataSourceButton() {
        findViewById<ImageButton>(R.id.btn_data_source).setOnClickListener {
            val dialogView = layoutInflater.inflate(R.layout.dialog_data_source, null)

            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(dialogView)
                .create()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            dialogView.findViewById<android.widget.Button>(R.id.btn_dialog_confirms)
                .setOnClickListener { dialog.dismiss() }
            dialog.show()
        }
    }

    private fun resetToMainSearch() {
        // 카드 전환
        findViewById<CardView>(R.id.route_input_card).visibility = View.GONE
        findViewById<CardView>(R.id.main_search_card).visibility = View.VISIBLE

        // 경로/마커/상태 초기화
        clearPolylines()
        goalLatLng = null
        startLatLng = null
        routeResults = listOf()
        selectedRouteIndex = 0

        // 경로 결과 카드 & 네비 버튼 숨김
        findViewById<android.widget.HorizontalScrollView>(R.id.route_result_scroll).visibility = View.GONE
        findViewById<android.widget.Button>(R.id.btn_start_navi).visibility = View.GONE

        // 입력창 초기화
        findViewById<android.widget.EditText>(R.id.start_input).setText("")
        findViewById<android.widget.EditText>(R.id.goal_input).setText("")
    }

    override fun onDestroy() { // 화면 종료
        super.onDestroy()
        locationCallback?.let { fusedClient.removeLocationUpdates(it) } // GPS 업데이트 구독해제. 안하면 메모리 누수 + 배터리 낭비!
    }
    // SearchActivity 결과 처리
    private val searchLauncher = registerForActivityResult( // SearchActivity를 열고 결과를 돌려받음.
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult // 검색을 취소하거나 실패하면 무시함(아무것도 선택X)
        val data = result.data ?: return@registerForActivityResult // return@register~ 이건 이 람다를 빠져나가라는 뜻임.
        val name = data.getStringExtra(SearchActivity.RESULT_NAME) ?: return@registerForActivityResult // 각각 SearchActivity가 넘겨준 장소명, 위도, 경도 꺼냄
        val lat  = data.getDoubleExtra(SearchActivity.RESULT_LAT, 0.0)
        val lng  = data.getDoubleExtra(SearchActivity.RESULT_LNG, 0.0)
        if (lat == 0.0) return@registerForActivityResult

        // route_input_card 표시 (검색 카드 숨김)
        showRouteInputCard()

        when (pendingTarget) {
            "goal" -> { // 목적지 검색임
                findViewById<EditText>(R.id.goal_input).setText(name)
                goalLatLng = LatLng(lat, lng)
                // 출발지도 이미 설정되어 있으면 바로 경로탐색
                if (startLatLng != null || lastKnownLocation != null) findRoutes()
            }
            "start" -> { // 출발지 검색임
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
    private fun findRoutes() { // 경로 탐색
        val goal = goalLatLng ?: return

        // 출발지: 직접 선택했으면 그것, 아니면 현재위치
        val start = startLatLng
            ?: lastKnownLocation // GPS 위치
            ?: naverMap.locationOverlay.position.takeIf { it.latitude != 0.0 } // 지도 위치 오버레이.

        if (start == null || start.latitude == 0.0) {
            Toast.makeText(this, "현재 위치를 확인 중입니다", Toast.LENGTH_SHORT).show()
            return
        }
        clearPolylines() // 그려진거 초기화

        lifecycleScope.launch {
            try {
                val scored = withContext(Dispatchers.IO) {
                    safeRouteManager.findThreeRoutes(start, goal) // 3개 경로를 안전점수와 함께 계산함.
                }
                if (scored.isEmpty()) { // 안전점수가 없을때
                    Toast.makeText(this@MainActivity, "경로를 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val results = scored.map { sr -> // 결과를 MainActivity의 RouteResult형식으로 반환.
                    RouteResult(
                        path = sr.path,
                        distanceM = sr.distanceM,
                        durationSec = sr.durationMs / 1000,
                        cctvCount = sr.cctvCount,
                        lightCount = sr.lightCount,
                        safetyScore = sr.safetyScore
                    )
                }

                results.forEachIndexed { i, route -> // 첫번째 경로만 초록으로 굵게, 나머지는 회색 가늘게 그림.
                    drawPolyline(
                        path  = route.path,
                        color = if (i == 0) ROUTE_COLORS[0] else ROUTE_GRAY,
                        width = if (i == 0) 15 else 8
                    )
                }
                showRouteCards(results) // 경로 카드를 보여줌.
            } catch (e: Exception) {
                //Toast.makeText(this@MainActivity, "경로 탐색 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val ROUTE_COLORS = listOf( // 각각 다른 색상
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
            cardIds.forEachIndexed { i, cardId ->// 선택된 카드는 컬러배경
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

                if (i < polylines.size) { // 지도에서 굵은선.
                    polylines[i].width = if (isSelected) 15 else 8
                    polylines[i].color = if (isSelected) routeColor else ROUTE_GRAY
                }
            }
        }

        routes.forEachIndexed { i, route ->
            if (i >= 3) return@forEachIndexed
            val walkingMinutes = (route.distanceM / 65.0).toInt().coerceAtLeast(1) // 거리/65m = 도보시간.
            val steps = (route.distanceM * 1.4).toInt() // 거리 x1.4 = 걸음 수
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

    private fun startNaverMapNavi() { // 네비게이션 시작
        val goal = goalLatLng ?: return
        val selectedRoute = routeResults.getOrNull(selectedRouteIndex) ?: return

        val lats = selectedRoute.path.map { it[1] }.toDoubleArray() // 위도 배열
        val lngs = selectedRoute.path.map { it[0] }.toDoubleArray() // 경도 배열 분리!

        val intent = Intent(this, NavigationActivity::class.java).apply {
            putExtra(NavigationActivity.EXTRA_PATH_LAT, lats)
            putExtra(NavigationActivity.EXTRA_PATH_LNG, lngs)
            putExtra(NavigationActivity.EXTRA_GOAL_LAT, goal.latitude)
            putExtra(NavigationActivity.EXTRA_GOAL_LNG, goal.longitude)
            putExtra(NavigationActivity.EXTRA_GOAL_NAME,
                findViewById<EditText>(R.id.goal_input).text.toString())
            putExtra(NavigationActivity.EXTRA_TOTAL_DISTANCE, selectedRoute.distanceM.toDouble())
        }// NavigationActivity로 경로 데이터를 Intent에 담아 전달하고 화면 전환.
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
        naverMap.locationSource = locationSource // 위치 연결
        naverMap.uiSettings.isLocationButtonEnabled = true // 내 위치 버튼 표시
        naverMap.uiSettings.isZoomControlEnabled = false
        naverMap.locationTrackingMode = LocationTrackingMode.Follow // 카메라가 내 위치를 따라다니는 모드 설정
        setupButtonListeners()

        naverMap.addOnCameraIdleListener { // 지도 카메라 이동이 멈출 때마다 현재 화면 범위에 맞게 마커 업데이트
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

    private fun setupButtonListeners() { // CCTV와 보안등 보기, 통화 설정 버튼
        findViewById<ImageButton>(R.id.btn_fake_call_setting).setOnClickListener {
            startActivity(Intent(this, FakeCallSettingActivity::class.java))
        }
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

    private fun updateMarkers(type: String) { // 마커 업데이트
        val isVisible     = if (type == "CCTV") isCctvVisible else isLightVisible
        val activeMarkers = if (type == "CCTV") activeCctvMarkers else activeLightMarkers
        activeMarkers.forEach { it.map = null }
        activeMarkers.clear() // 기존 마커 전부 지도에서 제거 + 목록 비움.
        if (!isVisible || naverMap.cameraPosition.zoom < MIN_ZOOM_LEVEL) return // 숨김 상태/ 줌이 너무 작으면 여기서 종료함.
        val bounds = naverMap.contentBounds  // 현재 지도 화면의 경계 좌표
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
    // 사용자가 위치 권한 허용/거부했을때 처리. 거부하면 위치 추적모드를 None으로함.
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (locationSource.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            if (!locationSource.isActivated) naverMap.locationTrackingMode = LocationTrackingMode.None
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    companion object { // 위치 권한 요청
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1000
    }
}