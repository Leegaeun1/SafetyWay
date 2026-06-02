package com.example.safetyway

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.LocationTrackingMode
import com.naver.maps.map.MapFragment
import com.naver.maps.map.NaverMap
import com.naver.maps.map.OnMapReadyCallback
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage
import com.naver.maps.map.overlay.PolylineOverlay
import com.naver.maps.map.util.FusedLocationSource
import com.naver.maps.map.util.MarkerIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    // 길게 눌렀을 때 나타날 임시 마커
    private var tempReportMarker: Marker? = null
    private var photoUri: Uri? = null
    private var photoFile: File? = null
    private lateinit var auth: FirebaseAuth // 추가
    data class RouteResult(
        val path: List<List<Double>>,
        val distanceM: Int,
        val durationSec: Int,
        val cctvCount: Int,
        val lightCount: Int,
        val safetyScore: Int,
        val isDetour: Boolean = false,
        var label: String = "" //️ 라벨을 자체적으로 기억하도록 변수 추가!
    )

    /**앱이 시작됐을 때 네이버 지도, 검색 API, 경로 매니저 세팅
     * startLocationUpdates()로 GPS위치를 즉시 가져옴.*/
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Auth 인스턴스 초기화
        auth = FirebaseAuth.getInstance()
        android.util.Log.d("KeyTest", "네이버 맵 ID: ${BuildConfig.NAVER_MAP_CLIENT_ID}")
        android.util.Log.d("KeyTest", "네이버 맵 시크릿: ${BuildConfig.NAVER_MAP_CLIENT_SECRET}")
        // 앱이 시작될 때 익명 로그인 실행
        signInAnonymously()
        locationSource = FusedLocationSource(this, LOCATION_PERMISSION_REQUEST_CODE) // 위치 소스 초기화.
        searchApi = RetrofitClient.createSearchApi() // retrofit으로 네이버 검색 api 인스턴스 생성
        mapApi    = RetrofitClient.createMapApi() // retrofit으로 네이버 지도 api 인스턴트 생성
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
    private fun signInAnonymously() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            auth.signInAnonymously()
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        android.util.Log.d("SafetyWay", "파이어베이스 익명 로그인 성공")
                    } else {
                        android.util.Log.e("SafetyWay", "로그인 실패: ${task.exception}")
                        Toast.makeText(this, "서버 연결에 실패했습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
        }
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
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
        return File.createTempFile("REPORT_${timeStamp}_", ".jpg", storageDir)
    }

    // 카메라 앱 호출 함수
    private fun dispatchTakePictureIntent() {
        val file = try {
            createImageFile()
        } catch (ex: Exception) {
            null
        }

        file?.also {
            photoFile = it
            // Android 7.0 이상부터는 FileProvider를 통해 보안 URI를 제공해야 함
            photoUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                it
            )
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            }
            takePhotoLauncher.launch(intent)
        }
    }
    private fun uploadReport(latLng: LatLng, type: String) {
        val currentUri = photoUri
        if (currentUri == null) {
            Toast.makeText(this, "현장 사진 촬영이 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "제보를 업로드 중입니다...", Toast.LENGTH_SHORT).show()

        val storageRef = FirebaseStorage.getInstance().reference
        val firestore = FirebaseFirestore.getInstance()

        // 1. 파일명 생성 (예: reports/17123456789.jpg)
        val fileName = "reports/${System.currentTimeMillis()}.jpg"
        val imageRef = storageRef.child(fileName)

        // 2. Firebase Storage에 이미지 업로드
        imageRef.putFile(currentUri)
            .addOnSuccessListener {
                // 업로드 성공 시 이미지의 웹 다운로드 URL 주소 가져오기
                imageRef.downloadUrl.addOnSuccessListener { downloadUrl ->

                    // 3. Firestore에 저장할 데이터 패키징
                    val reportData = hashMapOf(
                        "latitude" to latLng.latitude,
                        "longitude" to latLng.longitude,
                        "type" to type,
                        "imageUrl" to downloadUrl.toString(),
                        "status" to "PENDING", // 최초 상태는 대기 중
                        "timestamp" to com.google.firebase.Timestamp.now()
                    )

                    // 4. Firestore 'reports' 컬렉션에 등록
                    firestore.collection("reports")
                        .add(reportData)
                        .addOnSuccessListener {
                            Toast.makeText(this, "안전 인프라 제보가 접수되었습니다. 검토 후 반영됩니다!", Toast.LENGTH_LONG).show()
                            // 업로드 완료 후 임시 데이터 비우기
                            photoUri = null
                            photoFile = null
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(this, "데이터베이스 등록 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "사진 업로드 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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
    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // 사진 촬영 성공 시, photoUri에 이미지 데이터가 담겨 있음
            Toast.makeText(this, "사진이 촬영되었습니다!", Toast.LENGTH_SHORT).show()
        } else {
            photoUri = null
            photoFile = null
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
        val start = startLatLng ?: lastKnownLocation ?: naverMap.locationOverlay.position.takeIf { it.latitude != 0.0 }

        if (start == null || start.latitude == 0.0) {
            Toast.makeText(this, "현재 위치를 확인 중입니다", Toast.LENGTH_SHORT).show()
            return
        }
        clearPolylines() // 기존 선 초기화

        lifecycleScope.launch {
            try {
                val offset = 50.0 / 111000.0 // 50m 반경 오차 범위

                var detourRoute: RouteResult? = null // 우회 경로 결과
                val baseResults = mutableListOf<RouteResult>() // 기본 경로 

                // 출발지와 목적지 사이의 직선거리를 미터 단위로 계산
                val straightDistM = distanceBetween(start.latitude, start.longitude, goal.latitude, goal.longitude)

                // 거리가 200m 이하로 너무 짧으면 우회로 탐색 자체를 스킵 (바로 최단거리로 유도)
                if (straightDistM > 200.0) {
                    val midLat = (start.latitude + goal.latitude) / 2.0 // 출발지와 목적지 가운데
                    val midLng = (start.longitude + goal.longitude) / 2.0

                    // 원래 300m 고정이었던 반경을 전체 거리의 30% 수준으로 제한 (최대 300m)
                    val dynamicSearchM = (straightDistM * 0.3).coerceAtMost(300.0)
                    val searchOffset = dynamicSearchM / 111000.0 // 계산된 탐색 반경을 다시 위경도 좌표계 수치로 변환

                    val nearbySafetyHubs = withContext(Dispatchers.IO) {
                        AppDatabase.getDatabase(applicationContext).safetyDao()
                            .getSafetyInBounds(midLat - searchOffset, midLat + searchOffset, midLng - searchOffset, midLng + searchOffset, "CCTV")
                    }

                    val safetyWaypoint = nearbySafetyHubs.firstOrNull()
                    if (safetyWaypoint != null) {
                        val passListStr = "${safetyWaypoint.longitude},${safetyWaypoint.latitude}"
                        val detourTmap = withContext(Dispatchers.IO) {
                            safeRouteManager.fetchTmapPedestrianRoute(this@MainActivity, start, goal, "0", passListStr)
                        }
                        if (detourTmap != null && detourTmap.path.isNotEmpty()) {
                            detourRoute = calculateRouteScore(this@MainActivity, detourTmap, offset, isDetour = true)
                        }
                    }
                }

                // 기본 TMAP 탐색
                val baseOptions = listOf("0", "10")
                for (option in baseOptions) {
                    val tmapResult = withContext(Dispatchers.IO) {
                        safeRouteManager.fetchTmapPedestrianRoute(this@MainActivity, start, goal, option, null)
                    }
                    if (tmapResult != null && tmapResult.path.isNotEmpty()) {
                        val baseRoute = calculateRouteScore(this@MainActivity, tmapResult, offset, isDetour = false)
                        baseResults.add(baseRoute)
                    }
                }

                if (baseResults.isEmpty() && detourRoute == null) {
                    Toast.makeText(this@MainActivity, "도보 경로를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val safest = baseResults.maxByOrNull { it.safetyScore }
                val shortest = baseResults.minByOrNull { it.distanceM }
                val distSorted  = baseResults.sortedBy { it.distanceM }
                val scoreSorted = baseResults.sortedByDescending { it.safetyScore }
                val balanced = baseResults.minByOrNull { r -> distSorted.indexOf(r) + scoreSorted.indexOf(r) }

                // 거리 대비 비효율적인 우회로 쳐내기
                val validDetour = if (detourRoute != null && safest != null && shortest != null) {
                    val isLessSafe = detourRoute.safetyScore <= safest.safetyScore
                    // 최단 거리보다 1.5배 이상 멀면 아무리 안전해도 기각 (예: 100m -> 150m까지만 허용)
                    val isTooFar = detourRoute.distanceM > shortest.distanceM * 1.5

                    if (isLessSafe || isTooFar) null else detourRoute
                } else {
                    detourRoute
                }

                // .copy()를 사용해 서로의 이름표가 꼬이지 않게 독립적인 객체로 묶어줌
                val candidates = listOfNotNull(
                    validDetour?.copy(label = "🛡 안전 우회 경로"),
                    safest?.copy(label = "🛡 안전 추천"),
                    balanced?.copy(label = "⚖ 안전+거리"),
                    shortest?.copy(label = "⚡ 최단 거리")
                )

                // 기하학적으로 완전히 겹치는 경로의 라벨 진화 로직
                val uniqueRoutes = mutableMapOf<List<List<Double>>, RouteResult>()

                for (route in candidates) {
                    val existing = uniqueRoutes[route.path]
                    if (existing == null) {
                        uniqueRoutes[route.path] = route
                    } else {
                        // 중복 경로인데, 하나는 제일 안전하고 하나는 제일 짧았다면 라벨을 합침
                        if (existing.label == "🛡 안전 추천" && route.label == "⚡ 최단 거리") {
                            existing.label = "🛡 최적 경로 (안전+최단)"
                        }
                    }
                }

                // 점수가 가장 높은 순서대로 최대 3개까지만 자르기
                val results = uniqueRoutes.values.toList().sortedByDescending { it.safetyScore }.take(3)

                // 지도에 선 그리기
                results.forEachIndexed { i, route ->
                    drawPolyline(
                        path  = route.path,
                        color = if (i == 0) ROUTE_COLORS[0] else ROUTE_GRAY,
                        width = if (i == 0) 15 else 8
                    )
                }

                // 하단 결과 카드 UI 업데이트
                showRouteCards(results)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 코루틴 비동기 처리를 위해 suspend를 붙이고, 내부에서 DB를 직접 열어 타입 에러를 방지
    private suspend fun calculateRouteScore(
        context: android.content.Context,
        tmapResult: TmapRouteResponse,
        offset: Double,
        isDetour: Boolean
    ): RouteResult = withContext(Dispatchers.IO) {
        val localDb = AppDatabase.getDatabase(context).safetyDao()
        val detectedCctvs = mutableSetOf<Pair<Double, Double>>()
        val detectedLights = mutableSetOf<Pair<Double, Double>>()

        for (coord in tmapResult.path) {
            val lng = coord[0]
            val lat = coord[1]

            val nearbyCctv = localDb.getSafetyInBounds(lat - offset, lat + offset, lng - offset, lng + offset, "CCTV")
            val nearbyLight = localDb.getSafetyInBounds(lat - offset, lat + offset, lng - offset, lng + offset, "LIGHT")

            nearbyCctv.forEach { detectedCctvs.add(Pair(it.latitude, it.longitude)) }
            nearbyLight.forEach { detectedLights.add(Pair(it.latitude, it.longitude)) }
        }

        val cctvCount = detectedCctvs.size
        val lightCount = detectedLights.size

        //  1. 원본 점수 계산
        val rawScore = (cctvCount * 5) + (lightCount * 2)

        //  2. 로그(log10)를 활용한 100점 만점 압축
        // (예: rawScore가 10이면 41점, 50이면 68점, 300이면 99점, 그 이상은 100점으로 고정)
        val safetyScore = if (rawScore > 0) {
            (kotlin.math.log10(rawScore.toDouble() + 1.0) * 40).toInt().coerceIn(0, 100)
        } else {
            0
        }

        RouteResult(
            path = tmapResult.path,
            distanceM = tmapResult.distanceM,
            durationSec = tmapResult.durationSec,
            cctvCount = cctvCount,
            lightCount = lightCount,
            safetyScore = safetyScore,
            isDetour = isDetour
        )
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

        // 먼저 모든 카드를 숨김 처리
        cardIds.forEach { findViewById<CardView>(it).visibility = View.GONE }

        fun applySelection(selected: Int) {
            routes.forEachIndexed { i, route ->
                if (i >= 3) return@forEachIndexed
                val isSelected = i == selected
                val routeColor = ROUTE_COLORS.getOrElse(i) { Color.parseColor("#3D6BF5") }
                val bgColor   = if (isSelected) routeColor else Color.WHITE
                val mainColor = if (isSelected) Color.WHITE else Color.BLACK
                val subColor  = if (isSelected) Color.argb(200, 255, 255, 255) else Color.parseColor("#888888")

                findViewById<CardView>(cardIds[i]).setCardBackgroundColor(bgColor)
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

            findViewById<CardView>(cardIds[i]).visibility = View.VISIBLE

            val walkingMinutes = (route.distanceM / 65.0).toInt().coerceAtLeast(1)
            val steps = (route.distanceM * 1.4).toInt()
            val km    = "%.1f".format(route.distanceM / 1000.0)

            val label = route.label

            findViewById<TextView>(labelIds[i]).text  = label
            findViewById<TextView>(timeIds[i]).text   = "${walkingMinutes}분"
            findViewById<TextView>(distIds[i]).text   = "${km}km · ${steps}걸음"
            findViewById<TextView>(cctvIds[i]).text   = "CCTV ${route.cctvCount}개"
            findViewById<TextView>(lightIds[i]).text  = "보안등 ${route.lightCount}개"
            findViewById<TextView>(scoreIds[i]).text  = "안전점수 ${route.safetyScore}점" // 로그가 적용된 100점 만점 점수!

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
        // 지도 길게 누르기 이벤트 감지
        naverMap.setOnMapLongClickListener { point, latLng ->

            // 1. 내 현재 GPS 위치 가져오기
            val currentLoc = lastKnownLocation
            if (currentLoc == null) {
                Toast.makeText(this, "현재 위치를 확인 중입니다. 잠시 후 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnMapLongClickListener
            }

            // 2. 내 위치와 내가 길게 터치한 곳 사이의 거리 계산 (단위: 미터)
            val distance = distanceBetween(
                currentLoc.latitude, currentLoc.longitude,
                latLng.latitude, latLng.longitude
            )

            // 3. 거리가 100m를 초과하면 제보 차단!
            if (distance > 100.0) {
                Toast.makeText(this, "현장에서만 제보할 수 있습니다.\n(현재 위치에서 ${distance.toInt()}m 떨어져 있음)", Toast.LENGTH_LONG).show()
                return@setOnMapLongClickListener // 여기서 함수를 끝내버려서 팝업이 안 뜨게 함
            }

            // 4. 거리가 50m 이내라면 정상적으로 기존 로직 실행 (마커 찍고 팝업 띄우기)
            tempReportMarker?.map = null
            tempReportMarker = Marker().apply {
                position = latLng
                map = naverMap
                icon = MarkerIcons.BLACK // 기본 마커 아이콘 색상 변경
                iconTintColor = Color.parseColor("#FF6600") // 주황색으로 강조
                captionText = "제보 위치"
            }

            showReportBottomSheet(latLng)
        }
    }
    private fun showReportBottomSheet(latLng: LatLng) {
        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_report, null)
        bottomSheetDialog.setContentView(view)

        // 다이얼로그가 닫힐 때 임시 마커도 같이 지도에서 지워주기
        bottomSheetDialog.setOnDismissListener {
            tempReportMarker?.map = null
        }

        // 위치 텍스트 뷰 업데이트 (역 지오코딩으로 주소를 가져올 수도 있지만, 일단 위경도로 표시)
        val tvAddress = view.findViewById<TextView>(R.id.tv_report_address)
        tvAddress.text = "좌표: ${String.format("%.4f", latLng.latitude)}, ${String.format("%.4f", latLng.longitude)}"

        // 뷰 내부의 버튼들 가져오기
        val rgType = view.findViewById<android.widget.RadioGroup>(R.id.rg_infrastructure_type)
        val btnTakePhoto = view.findViewById<Button>(R.id.btn_take_photo)
        val btnSubmit = view.findViewById<Button>(R.id.btn_submit_report)

        // 카메라 버튼 클릭 이벤트
        btnTakePhoto.setOnClickListener {
            dispatchTakePictureIntent() // 카메라 켜기
        }

        // 제보하기 버튼 클릭 이벤트
        btnSubmit.setOnClickListener {
            val selectedType = if (rgType.checkedRadioButtonId == R.id.rb_cctv) "CCTV" else "보안등"

            // 파이어베이스 업로드 실행
            uploadReport(latLng, selectedType)

            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
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

    private fun updateMarkers(type: String) {
        val isVisible = if (type == "CCTV") isCctvVisible else isLightVisible
        val activeMarkers = if (type == "CCTV") activeCctvMarkers else activeLightMarkers

        activeMarkers.forEach { it.map = null }
        activeMarkers.clear() // 기존 마커 싹 지우기

        if (!isVisible || naverMap.cameraPosition.zoom < MIN_ZOOM_LEVEL) return

        val bounds = naverMap.contentBounds

        lifecycleScope.launch {
            // 1. 기존 로컬(Room DB) 데이터 불러오기 (공공데이터)
            val localDataList = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(applicationContext).safetyDao()
                    .getSafetyInBounds(bounds.southWest.latitude, bounds.northEast.latitude,
                        bounds.southWest.longitude, bounds.northEast.longitude, type)
            }

            withContext(Dispatchers.Main) {
                // 로컬 DB 마커 먼저 지도에 그리기
                for (item in localDataList) {
                    val marker = Marker().apply {
                        position = LatLng(item.latitude, item.longitude)
                        map = naverMap
                        icon = OverlayImage.fromResource(if (type == "CCTV") R.drawable.cctv else R.drawable.streetlight)
                        width = 60; height = 60
                    }
                    activeMarkers.add(marker)
                }

                // 2. Firebase에서 승인된(APPROVED) 사용자 제보 데이터 불러오기
                // 앱의 type은 "LIGHT"지만, 파이어베이스에는 "보안등"으로 저장했으므로 매핑.
                val firestoreType = if (type == "CCTV") "CCTV" else "보안등"

                FirebaseFirestore.getInstance().collection("reports")
                    .whereEqualTo("status", "APPROVED") // 상태가 APPROVED인 것만!
                    .whereEqualTo("type", firestoreType)
                    .get()
                    .addOnSuccessListener { documents ->
                        for (document in documents) {
                            val lat = document.getDouble("latitude") ?: continue
                            val lng = document.getDouble("longitude") ?: continue

                            // 현재 폰 화면(bounds) 안에 있는 제보 데이터만 마커로 찍기
                            if (lat in bounds.southWest.latitude..bounds.northEast.latitude &&
                                lng in bounds.southWest.longitude..bounds.northEast.longitude) {

                                val marker = Marker().apply {
                                    position = LatLng(lat, lng)
                                    map = naverMap
                                    icon = OverlayImage.fromResource(if (type == "CCTV") R.drawable.cctv else R.drawable.streetlight)
                                    width = 60; height = 60

                                    // 사용자 제보 데이터라는 걸 티내기 위해 작은 글씨 추가
                                    captionText = "사용자 제보"
                                    captionTextSize = 10f
                                    captionColor = Color.parseColor("#3D6BF5")
                                    captionMinZoom = 15.0 // 지도를 좀 확대했을 때만 글씨 보이기
                                }
                                activeMarkers.add(marker)
                            }
                        }
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