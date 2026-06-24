package com.example.safetyway

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var locationSource: FusedLocationSource
    private lateinit var naverMap: NaverMap
    private var isCctvVisible = false // CCTV가 보이는 상태인가
    private var isLightVisible = false // 보안등이 보이는 상태인가
    private val activeCctvMarkers = mutableListOf<Marker>() // 현재 지도에 표시중인 CCTV 마커들 담아두는 목록임. 나중에 한번에 지우기위함
    private val activeLightMarkers = mutableListOf<Marker>() // 위와 같이 보안등 담아두는 목록.
    private val activePoliceMarkers = mutableListOf<Marker>() // 파출소 마커 리스트
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
    private var sirenPlayer: android.media.MediaPlayer? = null
    private var isSirenOn = false
    private lateinit var audioManager: android.media.AudioManager
    private var savedVolume = 0
    private val userReportedCctvs = mutableListOf<LatLng>()
    private val userReportedLights = mutableListOf<LatLng>()
    private var isInfraVisible = false          // 인프라 탭 토글
    private val activeInfraMarkers = mutableListOf<Marker>()  // 인프라 마커 목록
    private var _previewCard:  androidx.cardview.widget.CardView? = null
    private var _previewImage: android.widget.ImageView? = null
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
    private val client = OkHttpClient()
    val serverIp = BuildConfig.MY_IP_KEY
    private val FLASK_SERVER_URL = "http://$serverIp/get_safe_waypoint"
    // assets 폴더의 a.csv를 읽어서 Room DB에 넣는 함수
    private fun loadPoliceDataOnce() {
        val prefs = getSharedPreferences("safety_prefs", MODE_PRIVATE)
        val isLoaded = prefs.getBoolean("is_police_loaded", false)

        if (isLoaded) return // 이미 저장되어 있으면 스킵!

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(applicationContext).safetyDao()
                val inputStream = applicationContext.assets.open("a.csv")
                val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream))

                reader.readLine() // 첫 줄(헤더) 건너뛰기
                var line = reader.readLine()

                while (line != null) {
                    val tokens = line.split(",")
                    if (tokens.size >= 3) {
                        // 맨 뒤에서 첫 번째가 위도(Y), 두 번째가 경도(X)
                        val latStr = tokens.last().trim()
                        val lngStr = tokens[tokens.size - 2].trim()

                        val latitude = latStr.toDoubleOrNull()
                        val longitude = lngStr.toDoubleOrNull()

                        if (latitude != null && longitude != null) {
                            db.insert(SafetyEntity(
                                latitude = latitude,
                                longitude = longitude,
                                type = "POLICE",
                                count = 1
                            ))
                        }
                    }
                    line = reader.readLine()
                }
                reader.close()

                // 완료 후 다신 실행 안 되게 저장
                prefs.edit().putBoolean("is_police_loaded", true).apply()
                android.util.Log.d("SafetyWay", "파출소 데이터 CSV -> DB 저장 완료!")

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
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
        audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager // 오디오 설정
        searchApi = RetrofitClient.createSearchApi() // retrofit으로 네이버 검색 api 인스턴스 생성
        mapApi    = RetrofitClient.createMapApi() // retrofit으로 네이버 지도 api 인스턴트 생성
        safeRouteManager = SafeRouteManager( // 로컬 DB와 지도 API를 주입해서 SafeRouteManager 생성!!
            safetyDao = AppDatabase.getDatabase(this).safetyDao(),
            mapApi    = mapApi
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2000)
            }
        }
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
        loadPoliceDataOnce()
        loadUserReports()
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
    // 승인된 사용자 제보를 가져와서 메모리에 저장하는 함수
    private fun loadUserReports() {
        FirebaseFirestore.getInstance().collection("reports")
            .whereEqualTo("status", "APPROVED")
            .get()
            .addOnSuccessListener { documents ->
                userReportedCctvs.clear()
                userReportedLights.clear()

                for (document in documents) {
                    val lat = document.getDouble("latitude") ?: continue
                    val lng = document.getDouble("longitude") ?: continue
                    val type = document.getString("type") ?: continue

                    if (type == "CCTV") {
                        userReportedCctvs.add(LatLng(lat, lng))
                    } else if (type == "보안등") {
                        userReportedLights.add(LatLng(lat, lng))
                    }
                }
                android.util.Log.d("SafetyWay", "사용자 제보 로드 완료: CCTV ${userReportedCctvs.size}개, 보안등 ${userReportedLights.size}개")
            }
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
    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = photoUri
            if (uri != null && _previewImage != null && _previewCard != null) {
                // Glide 없이 ContentResolver로 비트맵 로드
                try {
                    val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        android.graphics.ImageDecoder.decodeBitmap(
                            android.graphics.ImageDecoder.createSource(contentResolver, uri)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    }
                    _previewImage!!.setImageBitmap(bitmap)
                    _previewCard!!.visibility = View.VISIBLE
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            android.widget.Toast.makeText(this, "사진이 촬영되었습니다!", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            photoUri  = null
            photoFile = null
        }
    }
    private fun resetToMainSearch() {
        // 카드 전환
        findViewById<CardView>(R.id.route_input_card).visibility = View.GONE
        findViewById<CardView>(R.id.main_search_card).visibility = View.VISIBLE
        // 필터 탭을 top_search_row 아래로 복원
        val filterScroll = findViewById<android.widget.HorizontalScrollView>(R.id.filter_tab_scroll)
        val params = filterScroll.layoutParams as android.widget.RelativeLayout.LayoutParams
        params.removeRule(android.widget.RelativeLayout.BELOW)
        params.addRule(android.widget.RelativeLayout.BELOW, R.id.top_search_row)
        filterScroll.layoutParams = params

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
        stopSiren()
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
        // 검색 카드 클릭
        findViewById<androidx.cardview.widget.CardView>(R.id.main_search_card).setOnClickListener {
            openSearchFor("goal")
        }
    }

    // 경로입력 카드 표시/숨김
    private fun showRouteInputCard() {
        findViewById<CardView>(R.id.main_search_card).visibility = View.GONE
        findViewById<CardView>(R.id.route_input_card).visibility = View.VISIBLE
        val filterScroll = findViewById<android.widget.HorizontalScrollView>(R.id.filter_tab_scroll)
        val params = filterScroll.layoutParams as android.widget.RelativeLayout.LayoutParams
        params.removeRule(android.widget.RelativeLayout.BELOW)
        params.addRule(android.widget.RelativeLayout.BELOW, R.id.route_input_card)
        filterScroll.layoutParams = params
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
        val start = startLatLng ?: lastKnownLocation
        val goal = goalLatLng
        val loc = lastKnownLocation

        if (start == null || goal == null || loc == null) {
            android.widget.Toast.makeText(this, "위치를 확인할 수 없습니다.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // 목적지까지의 직선 거리 계산
        val straightDistM = FloatArray(1).apply {
            Location.distanceBetween(start.latitude, start.longitude, goal.latitude, goal.longitude, this)
        }[0].toDouble()
        val offset = 0.001 // 점수 부여용 반경

        lifecycleScope.launch {
            var detourRoute: RouteResult? = null // 우회 경로 결과
            val baseResults = mutableListOf<RouteResult>() // 기본 경로

            try {
                // 1. 파이썬 서버에서 안전 경유지 '리스트 전체'를 받아옴
                val aiWaypoints = getAiWaypointsFromServer(
                    start.latitude, start.longitude, goal.latitude, goal.longitude
                )

                // 2. 5개씩 쪼개서 길을 그리고 하나로 합침
                if (aiWaypoints != null && aiWaypoints.isNotEmpty()) {
                    val aiResponse = fetchChunkedAiRoute(start, goal, aiWaypoints)

                    if (aiResponse != null) {
                        detourRoute = calculateRouteScore(this@MainActivity, aiResponse, offset, isDetour = true)
                        detourRoute?.label = "🛡 안전 추천"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 3. TMAP 기본 경로 호출 (추천, 최단, 편안한 길)
            for (option in listOf("10", "0", "4")) {
                val tmapResult = safeRouteManager.fetchTmapPedestrianRoute(this@MainActivity, start, goal, option, null)
                if (tmapResult != null) {
                    val baseRoute = calculateRouteScore(this@MainActivity, tmapResult, offset, isDetour = false)
                    baseResults.add(baseRoute)
                }
            }

            if (baseResults.isEmpty() && detourRoute == null) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@MainActivity, "경로를 찾을 수 없습니다.", android.widget.Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // --- 경로 비교, 중복 제거 로직 ---
            val candidates = mutableListOf<RouteResult>()
            if (detourRoute != null) {
                // detourRoute가 shortest보다 2배 이상 길면 버림
                val shortestDist = baseResults.minOfOrNull { it.distanceM } ?: Int.MAX_VALUE
                if (detourRoute.distanceM <= shortestDist * 2.0) {
                    candidates.add(detourRoute)
                }
            }
            candidates.addAll(baseResults)

            // 2. 중복 제거 (경로 좌표 리스트를 Key로 사용)
            val uniqueRoutes = mutableMapOf<List<List<Double>>, RouteResult>()
            for (route in candidates) {
                val existing = uniqueRoutes[route.path]
                if (existing == null || route.safetyScore > existing.safetyScore) {
                    uniqueRoutes[route.path] = route
                }
            }
            val allUnique = uniqueRoutes.values.toList()

            // 💡 3. 명확한 목적에 따른 3가지 경로 추출 (로직 초강화 버전)
            val safest = allUnique.maxByOrNull { it.safetyScore }
            val shortest = allUnique.minByOrNull { it.distanceM }

            // 🔥 핵심: balanced(안전+최단)는 반드시 '안전추천'보다는 짧고, '최단거리'보다는 안전해야 함!
            val balanced = allUnique.filter {
                it != safest && it != shortest &&
                        (safest == null || it.distanceM < safest.distanceM) && // 안전 추천보단 무조건 짧을 것!
                        (shortest == null || it.safetyScore > shortest.safetyScore) // 최단 거리보단 무조건 안전할 것!
            }.maxByOrNull { it.safetyScore }

            val finalResults = mutableListOf<RouteResult>()

            // 1순위 카드: 안전 추천
            if (safest != null) {
                safest.label = "🛡 안전 추천"
                finalResults.add(safest)
            }

            // 2순위 카드: 안전+최단 (조건에 맞는 놈이 없으면 아예 안 띄웁니다!)
            if (balanced != null) {
                balanced.label = "⚖️ 안전+최단"
                finalResults.add(balanced)
            }

            // 3순위 카드: 최단 거리
            if (shortest != null && shortest != safest && shortest != balanced) {
                shortest.label = "⚡ 최단 거리"
                finalResults.add(shortest)
            }

            val results = finalResults.take(3)

            withContext(Dispatchers.Main) {
                polylines.forEach { it.map = null }
                polylines.clear()

                results.forEachIndexed { i, route ->
                    val polyline = com.naver.maps.map.overlay.PolylineOverlay().apply {
                        coords = route.path.map { LatLng(it[1], it[0]) }
                        color = if (i == 0) ROUTE_COLORS[0] else ROUTE_GRAY
                        width = if (i == 0) 15 else 10
                        map = naverMap
                        zIndex = if (i == 0) 100 else 50
                    }
                    polylines.add(polyline)
                }
                showRouteCards(results)

                if (results.isNotEmpty()) {
                    val firstRoute = results[0].path
                    val bounds = com.naver.maps.geometry.LatLngBounds.Builder()
                    firstRoute.forEach { bounds.include(LatLng(it[1], it[0])) }
                    naverMap.moveCamera(com.naver.maps.map.CameraUpdate.fitBounds(bounds.build(), 150))
                }
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
            // 2. 사용자 제보 데이터 (파이어베이스) 반경 검사 추가
            for (report in userReportedCctvs) {
                // 경로 상의 점과 제보 위치 사이의 거리가 50m(오차범위) 이내라면 점수 획득!
                if (distanceBetween(lat, lng, report.latitude, report.longitude) <= 50.0) {
                    detectedCctvs.add(Pair(report.latitude, report.longitude))
                }
            }
            for (report in userReportedLights) {
                if (distanceBetween(lat, lng, report.latitude, report.longitude) <= 50.0) {
                    detectedLights.add(Pair(report.latitude, report.longitude))
                }
            }
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
        naverMap.addOnCameraIdleListener {
            updateMarkers("CCTV")
            updateMarkers("LIGHT")
            updateMarkers("INFRA") // 수정: 지도 이동 후 갱신할 때
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
        // 지도 짧게 터치 이벤트 감지 (출발지/목적지 설정)
        naverMap.setOnMapClickListener { point, latLng ->
            lifecycleScope.launch {
                try {
                    // 역지오코딩 요청 (도로명 주소 포함해서 달라고 API 설정이 되어있어야 함)
                    val rg = withContext(Dispatchers.IO) {
                        mapApi.reverseGeocode("${latLng.longitude},${latLng.latitude}")
                    }

                    val result = rg.results?.firstOrNull()

                    // 1. 도로명 주소(roadaddr)나 지번 주소(addr)에서 정보 빼오기
                    val land = result?.land
                    val roadName = land?.name // 예: "진주대로"
                    val bldNum = land?.number1 // 예: "501"

                    // 2. 동 이름
                    val dongName = result?.region?.area3?.name

                    // 3. 도로명 주소가 있으면 우선 사용하고, 없으면 동 이름을 씁니다.
                    val finalAddress = if (!roadName.isNullOrEmpty() && !bldNum.isNullOrEmpty()) {
                        "$roadName $bldNum"
                    } else {
                        dongName ?: "선택한 위치"
                    }

                    // 사용자에게 팝업 띄우기
                    val options = arrayOf("여기를 출발지로 설정", "여기를 목적지로 설정")
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(finalAddress)
                        .setItems(options) { _, which ->
                            showRouteInputCard()

                            when (which) {
                                0 -> {
                                    findViewById<android.widget.EditText>(R.id.start_input).setText(finalAddress)
                                    startLatLng = latLng
                                }
                                1 -> {
                                    findViewById<android.widget.EditText>(R.id.goal_input).setText(finalAddress)
                                    goalLatLng = latLng
                                }
                            }

                            if (startLatLng != null && goalLatLng != null) {
                                findRoutes()
                            } else if (which == 1 && startLatLng == null && lastKnownLocation != null) {
                                startLatLng = lastKnownLocation
                                findViewById<android.widget.EditText>(R.id.start_input).setText("현재 위치")
                                findRoutes()
                            }
                        }
                        .show()

                } catch (e: Exception) {
                    android.widget.Toast.makeText(this@MainActivity, "주소 정보를 불러올 수 없습니다.", android.widget.Toast.LENGTH_SHORT).show()
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

        bottomSheetDialog.setOnDismissListener {
            tempReportMarker?.map = null
        }

        val tvAddress    = view.findViewById<android.widget.TextView>(R.id.tv_report_address)
        val rgType       = view.findViewById<android.widget.RadioGroup>(R.id.rg_infrastructure_type)
        val btnTakePhoto = view.findViewById<android.widget.Button>(R.id.btn_take_photo)
        val btnSubmit    = view.findViewById<android.widget.Button>(R.id.btn_submit_report)
        val cardPreview  = view.findViewById<androidx.cardview.widget.CardView>(R.id.card_photo_preview)
        val ivPreview    = view.findViewById<android.widget.ImageView>(R.id.iv_photo_preview)
        val btnRetake    = view.findViewById<android.widget.ImageView>(R.id.btn_retake_photo)

        tvAddress.text = "좌표: ${String.format("%.4f", latLng.latitude)}, ${String.format("%.4f", latLng.longitude)}"

        // 사진 촬영
        btnTakePhoto.setOnClickListener {
            dispatchTakePictureIntent()
        }

        // 다시 찍기 (× 버튼)
        btnRetake.setOnClickListener {
            photoUri  = null
            photoFile = null
            cardPreview.visibility = View.GONE
            btnTakePhoto.text = "📷 현장 사진 촬영 (필수)"
        }

        // takePhotoLauncher 결과를 BottomSheet 안 ImageView에도 반영해야 하므로
        // currentBottomSheetViews 에 저장해 공유
        _previewCard  = cardPreview
        _previewImage = ivPreview

        // 제보하기
        btnSubmit.setOnClickListener {
            val selectedType = if (rgType.checkedRadioButtonId == R.id.rb_cctv) "CCTV" else "보안등"
            uploadReport(latLng, selectedType)
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }
    private fun setupButtonListeners() {
        val activeColor  = Color.parseColor("#3D6BF5")
        val sosColor     = Color.parseColor("#E53935")
        val defaultColor = Color.parseColor("#444444")

        // ── 설정 버튼 (검색창 오른쪽) ──
        findViewById<View>(R.id.btn_fake_call_setting).setOnClickListener {
            startActivity(Intent(this, FakeCallSettingActivity::class.java))
        }

        // ── 필터 탭: CCTV ──
        val tabCctv      = findViewById<View>(R.id.tab_cctv)
        val tabCctvIcon  = findViewById<android.widget.ImageView>(R.id.tab_cctv_icon)
        val tabCctvLabel = findViewById<android.widget.TextView>(R.id.tab_cctv_label)
        tabCctv.setOnClickListener {
            isCctvVisible = !isCctvVisible
            updateMarkers("CCTV")
            applyTabState(tabCctv, tabCctvIcon, tabCctvLabel, isCctvVisible)
        }

        // ── 필터 탭: 보안등 ──
        val tabLight      = findViewById<View>(R.id.tab_streetlight)
        val tabLightIcon  = findViewById<android.widget.ImageView>(R.id.tab_streetlight_icon)
        val tabLightLabel = findViewById<android.widget.TextView>(R.id.tab_streetlight_label)
        tabLight.setOnClickListener {
            isLightVisible = !isLightVisible
            updateMarkers("LIGHT")
            applyTabState(tabLight, tabLightIcon, tabLightLabel, isLightVisible)
        }

        // ── 필터 탭: 인프라 ──
        val tabInfra      = findViewById<View>(R.id.tab_infra)
        val tabInfraIcon  = findViewById<android.widget.ImageView>(R.id.tab_infra_icon)
        val tabInfraLabel = findViewById<android.widget.TextView>(R.id.tab_infra_label)
        tabInfra.setOnClickListener {
            isInfraVisible = !isInfraVisible
            updateMarkers("INFRA")   // 현재 POLICE 타입이 인프라(경찰서 등). 추후 편의점/소방서 확장 시 분기
            applyTabState(tabInfra, tabInfraIcon, tabInfraLabel, isInfraVisible)
        }

        // ── 비상벨 ──
        val sosItem  = findViewById<View>(R.id.btn_sos_item)
        val sosIcon  = findViewById<android.widget.ImageView>(R.id.sos_btn)
        val sosLabel = findViewById<android.widget.TextView>(R.id.sos_label)
        sosItem.setOnClickListener {
            if (isSirenOn) stopSiren() else startSiren()
            val c = if (isSirenOn) sosColor else defaultColor
            sosIcon.setColorFilter(c)
            sosLabel.setTextColor(c)
        }

        // ── 가짜전화 ──
        findViewById<View>(R.id.call_btn).setOnClickListener {
            startActivity(Intent(this, FakeCallActivity::class.java))
        }

        // ── 사진촬영 버튼 (UI만, 기능은 추후 구현) ──
        findViewById<View>(R.id.btn_photo_item).setOnClickListener {
            android.widget.Toast.makeText(this, "사진 촬영 기능은 준비 중입니다.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    private fun applyTabState(
        tab: View,
        icon: android.widget.ImageView,
        label: android.widget.TextView,
        isActive: Boolean
    ) {
        if (isActive) {
            tab.setBackgroundResource(R.drawable.bg_filter_tab_active)
            icon.setColorFilter(Color.WHITE)
            label.setTextColor(Color.WHITE)
        } else {
            tab.setBackgroundResource(R.drawable.bg_filter_tab_default)
            icon.setColorFilter(Color.parseColor("#666666"))
            label.setTextColor(Color.parseColor("#666666"))
        }
    }
    private fun updateMarkers(type: String) {
        // 파출소는 버튼 없이 항상 보이게 true로 고정
        val isVisible = when (type) {
            "CCTV" -> isCctvVisible
            "LIGHT" -> isLightVisible
            "INFRA" -> isInfraVisible
            else -> false
        }

        val activeMarkers = when (type) {
            "CCTV" -> activeCctvMarkers
            "LIGHT" -> activeLightMarkers
            "INFRA" -> activeInfraMarkers
            else -> return
        }

        activeMarkers.forEach { it.map = null }
        activeMarkers.clear() // 기존 마커 싹 지우기

        if (!isVisible || naverMap.cameraPosition.zoom < MIN_ZOOM_LEVEL) return

        val bounds = naverMap.contentBounds

        lifecycleScope.launch {
            // 1. 기존 로컬(Room DB) 데이터 불러오기
            val localDataList = withContext(Dispatchers.IO) {
                val dao = AppDatabase.getDatabase(applicationContext).safetyDao()

                if (type == "INFRA") {
                    val policeList = dao.getSafetyInBounds(bounds.southWest.latitude, bounds.northEast.latitude, bounds.southWest.longitude, bounds.northEast.longitude, "POLICE")
                    val fireList = dao.getSafetyInBounds(bounds.southWest.latitude, bounds.northEast.latitude, bounds.southWest.longitude, bounds.northEast.longitude, "FIRE")
                    val storeList = dao.getSafetyInBounds(bounds.southWest.latitude, bounds.northEast.latitude, bounds.southWest.longitude, bounds.northEast.longitude, "STORE")
                    policeList + fireList + storeList
                } else {
                    // CCTV나 LIGHT일 경우
                    dao.getSafetyInBounds(bounds.southWest.latitude, bounds.northEast.latitude, bounds.southWest.longitude, bounds.northEast.longitude, type)
                }
            }

            withContext(Dispatchers.Main) {
                // 로컬 DB 마커 먼저 지도에 그리기
                for (item in localDataList) {
                    val marker = Marker().apply {
                        position = LatLng(item.latitude, item.longitude)
                        map = naverMap
                        // DB에 저장된 item.type을 기준으로 아이콘을 다르게 설정합니다.
                        icon = OverlayImage.fromResource(
                            when (item.type) {
                                "CCTV" -> R.drawable.cctv
                                "LIGHT" -> R.drawable.streetlight
                                "POLICE" -> R.drawable.police
                                "FIRE" -> R.drawable.fire    // 소방서 아이콘
                                "STORE" -> R.drawable.store  // 편의점 아이콘
                                else -> R.drawable.cctv
                            }
                        )
                        width = 60; height = 60
                    }
                    activeMarkers.add(marker)
                }

                // 인프라는 파이어베이스 제보 데이터가 없으므로 여기서 바로 함수 종료
                if (type == "INFRA") return@withContext

                // 2. Firebase에서 승인된 사용자 제보 데이터 불러오기 (CCTV, LIGHT 전용)
                val firestoreType = if (type == "CCTV") "CCTV" else "보안등"

                FirebaseFirestore.getInstance().collection("reports")
                    .whereEqualTo("status", "APPROVED")
                    .whereEqualTo("type", firestoreType)
                    .get()
                    .addOnSuccessListener { documents ->
                        for (document in documents) {
                            val lat = document.getDouble("latitude") ?: continue
                            val lng = document.getDouble("longitude") ?: continue

                            if (lat in bounds.southWest.latitude..bounds.northEast.latitude &&
                                lng in bounds.southWest.longitude..bounds.northEast.longitude) {

                                val marker = Marker().apply {
                                    position = LatLng(lat, lng)
                                    map = naverMap
                                    icon = OverlayImage.fromResource(if (type == "CCTV") R.drawable.cctv else R.drawable.streetlight)
                                    width = 60; height = 60

                                    captionText = "사용자 제보"
                                    captionTextSize = 10f
                                    captionColor = Color.parseColor("#3D6BF5")
                                    captionMinZoom = 15.0
                                }
                                activeMarkers.add(marker)
                            }
                        }
                    }
            }
        }
    }
    private fun startSiren() {
        isSirenOn = true

        val audioDevices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val isHeadsetConnected = audioDevices.any { device ->
            device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

        val streamType = if (isHeadsetConnected) {
            android.media.AudioManager.STREAM_MUSIC
        } else {
            android.media.AudioManager.STREAM_ALARM
        }

        val maxVolume = audioManager.getStreamMaxVolume(streamType)
        savedVolume = audioManager.getStreamVolume(streamType)
        val targetVolume = (maxVolume * 0.6f).toInt()
        audioManager.setStreamVolume(streamType, targetVolume, 0)

        try {
            sirenPlayer = android.media.MediaPlayer.create(applicationContext, R.raw.scream).apply {
                isLooping = true
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isSirenOn = false
        }
    }

    private fun stopSiren() {
        if (!isSirenOn) return
        isSirenOn = false

        sirenPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        sirenPlayer = null

        val audioDevices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val isHeadsetConnected = audioDevices.any { device ->
            device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        val streamType = if (isHeadsetConnected) android.media.AudioManager.STREAM_MUSIC else android.media.AudioManager.STREAM_ALARM
        audioManager.setStreamVolume(streamType, savedVolume, 0)
    }
    // 사용자가 위치 권한 허용/거부했을때 처리. 거부하면 위치 추적모드를 None으로함.
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (locationSource.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            if (!locationSource.isActivated) naverMap.locationTrackingMode = LocationTrackingMode.None
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    // 1. 파이썬 서버에서 안전 경유지 리스트(List) 전체를 받아오는 함수
    private suspend fun getAiWaypointsFromServer(startLat: Double, startLng: Double, endLat: Double, endLng: Double): List<LatLng>? {
        return suspendCancellableCoroutine { continuation ->
            val url = "$FLASK_SERVER_URL?startLat=$startLat&startLng=$startLng&endLat=$endLat&endLng=$endLng"
            val request = Request.Builder().url(url).build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = JSONObject(body)
                            if (json.getString("status") == "success") {
                                val arr = json.getJSONArray("waypoints")
                                val list = mutableListOf<LatLng>()
                                for (i in 0 until arr.length()) {
                                    val obj = arr.getJSONObject(i)
                                    list.add(LatLng(obj.getDouble("lat"), obj.getDouble("lng")))
                                }
                                if (continuation.isActive) continuation.resume(list)
                                return
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }
    }

    // 🔥 2. (핵심 기술) 경유지가 5개를 넘으면 경로를 쪼개서 통신하고 이어붙이는 함수!
    private suspend fun fetchChunkedAiRoute(start: LatLng, goal: LatLng, waypoints: List<LatLng>): TmapRouteResponse? {
        if (waypoints.isEmpty()) {
            return safeRouteManager.fetchTmapPedestrianRoute(this@MainActivity, start, goal, "30", null)
        }

        val chunks = mutableListOf<TmapRouteResponse>()
        var currentStart = start
        var idx = 0

        // 5개씩 쪼개서 TMAP 통신
        while (idx <= waypoints.size) {
            val remainingWps = waypoints.size - idx

            // 이전 청크가 마지막 경유지에서 끝났다면, 마지막 경유지 -> 최종 목적지 경로 1번 더 호출
            if (remainingWps == 0) {
                val response = safeRouteManager.fetchTmapPedestrianRoute(this@MainActivity, currentStart, goal, "30", null)
                if (response != null) chunks.add(response) else return null
                break
            }

            val takeCount = minOf(5, remainingWps)
            val passWps = waypoints.subList(idx, idx + takeCount)

            // 이번 청크의 목적지는 다음 경유지(takeCount 번째)이거나, 더 없으면 최종 목적지(goal)
            val chunkGoal = if (idx + takeCount < waypoints.size) {
                waypoints[idx + takeCount]
            } else {
                goal
            }

            val passListStr = passWps.joinToString("_") { "${it.longitude},${it.latitude}" }

            val response = safeRouteManager.fetchTmapPedestrianRoute(this@MainActivity, currentStart, chunkGoal, "30", passListStr)
            if (response != null) {
                chunks.add(response)
            } else {
                return null // 통신 중 하나라도 실패하면 전체 우회로 탐색 실패 처리
            }

            currentStart = chunkGoal
            idx += takeCount + 1 // 목표지점으로 사용한 경유지는 passList에서 제외하기 위해 +1
        }

        // 🔥 쪼개진 경로들(Chunks)을 하나의 완벽한 경로로 바느질(Merge)
        val mergedPath = mutableListOf<List<Double>>()
        var totalDist = 0
        var totalTime = 0

        chunks.forEach { chunk ->
            if (mergedPath.isNotEmpty() && chunk.path.isNotEmpty()) {
                // 이음새 부분이 겹치지 않도록 첫 번째 좌표는 빼고 붙임
                mergedPath.addAll(chunk.path.drop(1))
            } else {
                mergedPath.addAll(chunk.path)
            }
            totalDist += chunk.distanceM
            totalTime += chunk.durationSec
        }

        return TmapRouteResponse(mergedPath, totalDist, totalTime)
    }
    companion object { // 위치 권한 요청
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1000
    }
}