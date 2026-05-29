package com.example.safetyway

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class SearchActivity : AppCompatActivity() {

    companion object { // Intent로 주고받을때를 위해 상수로 미리 정의해둠.
        const val EXTRA_CUR_LAT = "cur_lat"
        const val EXTRA_CUR_LNG = "cur_lng"
        const val EXTRA_CITY = "city"
        const val EXTRA_DONG = "dong"
        const val RESULT_NAME = "result_name"
        const val RESULT_LAT  = "result_lat"
        const val RESULT_LNG  = "result_lng"
        const val EXTRA_TARGET = "target"
    }

    private lateinit var searchApi: NaverSearchApi // 장소 검색 API
    private var currentLat = 0.0 // 현재 위치 좌표. 0.0은 아직 위치 모름
    private var currentLng = 0.0
    private var city: String? = null
    private var dong: String? = null
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient // GPS 위치 가져옴

    private var results: List<SearchItem> = emptyList() // 현재 검색 결과 목록.
    private var searchJob: Job? = null // 실행중인 검색 코루틴. 새 검색이 시작되면 이전 걸 취소하기 위해 보관함.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        // MainActivity에서 Intent로 넘겨준 현재 위치, 지역명 꺼냄.
        searchApi = RetrofitClient.createSearchApi(this)
        currentLat = intent.getDoubleExtra(EXTRA_CUR_LAT, 0.0)
        currentLng = intent.getDoubleExtra(EXTRA_CUR_LNG, 0.0)
        city = intent.getStringExtra(EXTRA_CITY)
        dong = intent.getStringExtra(EXTRA_DONG)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this) // GPS 클라이언트 초기화

        // 위치/지역명 유무에 관계없이 항상 자체 획득 시도
        fetchLocationAndRegion()

        val input  = findViewById<EditText>(R.id.search_input)
        val btnBack  = findViewById<ImageButton>(R.id.btn_back)
        val btnClear = findViewById<ImageButton>(R.id.btn_clear)
        val list   = findViewById<ListView>(R.id.result_list)

        // 힌트 텍스트: 출발지/목적지 구분
        val target = intent.getStringExtra(EXTRA_TARGET)
        input.hint = if (target == "start") "출발지 검색" else "목적지 검색"

        btnBack.setOnClickListener { finish() } // 뒤로가기버튼 = 화면 닫기
        btnClear.setOnClickListener { input.setText("") } // X버튼 = 검색창 비우기

        input.requestFocus() // 화면 열리자마자 검색창에 커서 위치 + 키보드 자동으로 올라옴.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        // 실시간 검색.
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { // 변경 후
                val q = s?.toString().orEmpty()
                btnClear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE // 입력값이 비어있으면 X 버튼 숨김.
                searchJob?.cancel() // 이전 검색 취소
                if (q.length < 2) { results = emptyList(); list.adapter = null; return } // 두글자 미만이면 결과 비우고 종료
                searchJob = lifecycleScope.launch { delay(300); fetchSuggestions(q, list) } // 300ms 후 검색 실행
            }
        })

        // 리스트 아이템 클릭
        list.setOnItemClickListener { _, _, position, _ ->
            val item = results[position]
            val name = item.title.replace(Regex("<[^>]*>"), "") // 정규식으로 태그 제거(HTML태그 포함해서 줘서)
            val lat = item.mapy.toDouble() / 1e7 // 네이버 검색 API는 좌표를 정수로줘서 나눠줘야함.
            val lng = item.mapx.toDouble() / 1e7
            val data = Intent().apply { // 빈 Intent를 만들어서 결과 데이터를 담고 RESULT_OK와 함께 MainActivity로 돌려줌.
                putExtra(RESULT_NAME, name)
                putExtra(RESULT_LAT, lat)
                putExtra(RESULT_LNG, lng)
            }
            setResult(Activity.RESULT_OK, data)
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager) // 키보드를 내리고 화면 닫기.
                .hideSoftInputFromWindow(currentFocus?.windowToken, 0)
            finish()
        }
    }
    // 위치 + 지역명 자체 획득
    @SuppressLint("MissingPermission")
    private fun fetchLocationAndRegion() {
        val hasPerm = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return

        fusedLocationClient.lastLocation // 기기가 캐시해둔 마지막 위치를 즉시 반환.
            .addOnSuccessListener { loc ->
                if (loc == null) { requestCurrentLocation(); return@addOnSuccessListener }
                onLocationObtained(loc.latitude, loc.longitude)
            }
            .addOnFailureListener { e ->
                requestCurrentLocation()
            }
    }
    @SuppressLint("MissingPermission")
    private fun requestCurrentLocation() { // 실시간 위치 요청
        val hasPerm = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            android.util.Log.d("SafetyWay", "SearchActivity: 위치 권한 없음")
            return
        }
        // 1초 간격으로 딱 1번만 받겠다.
        val req = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, 1000L
        ).setMaxUpdates(1).build()
        
        // 위치 받으면 즉시 구독 해제 
        val cb = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(
                result: com.google.android.gms.location.LocationResult
            ) {
                fusedLocationClient.removeLocationUpdates(this)
                val loc = result.lastLocation
                if (loc != null) onLocationObtained(loc.latitude, loc.longitude)
            }
        }
        fusedLocationClient.requestLocationUpdates(req, cb, mainLooper)
    }
    // 위치 획득 완료
    private fun onLocationObtained(lat: Double, lng: Double) {
        currentLat = lat
        currentLng = lng
        if (city != null) {
            reorderCurrentResults() // 지역명 이미 알면 결과만 재정렬.(역 지오코딩 X)
            return
        }
        // 역지오코딩. 
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val mapApi = RetrofitClient.createMapApi(this@SearchActivity)
                val rg = mapApi.reverseGeocode("$lng,$lat")
                val region = rg.results?.firstOrNull()?.region
                withContext(Dispatchers.Main) {
                    city = region?.area2?.name
                    dong = region?.area3?.name
                    val q = findViewById<EditText>(R.id.search_input).text.toString()
                    if (q.length >= 2) triggerSearch(q) else reorderCurrentResults()
                }
            }.onFailure { e ->
                android.util.Log.e("SafetyWay", "reverseGeocode 실패: $e")
            }
        }
    }

    // 현재 결과 리스트를 거리순으로 재정렬만 함
    private fun reorderCurrentResults() {
        if (results.isEmpty() || currentLat == 0.0) return // 결과가 없거나 위치 모르면 종료
        val list = findViewById<ListView>(R.id.result_list)
        results = results.sortedBy {
            distanceBetween(currentLat, currentLng, it.mapy.toDouble() / 1e7, it.mapx.toDouble() / 1e7)
        } // 현재 위치 기준 가까운 순서로 정렬
        list.adapter?.let {
            // 어댑터 그대로 notifyDataSetChanged로 순서만 갱신
            @Suppress("UNCHECKED_CAST")
            (it as? ArrayAdapter<SearchItem>)?.notifyDataSetChanged()
        } ?: run {
            // 어댑터가 없으면 (결과 있지만 아직 세팅 안 된 경우) 재세팅
            triggerSearch(findViewById<EditText>(R.id.search_input).text.toString())
        }
    }
    private fun triggerSearch(query: String) {
        val list = findViewById<ListView>(R.id.result_list)
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            fetchSuggestions(query, list)
        }
    }

    private suspend fun fetchSuggestions(query: String, list: ListView) { // 실제 검색
        try {
            // 지역 prefix 결정
            // 우선순위: 동 이름 -> 시 이름 -> prefix 없음
            // query에 이미 시/구 이름이 들어있으면 prefix 제거 (중복 방지)
            val localCity = city
            val localDong = dong
            val prefix = when {
                localCity == null -> ""
                query.contains(localCity) -> ""
                !localDong.isNullOrEmpty() -> "$localCity $localDong "
                else -> "$localCity "
            }

            val merged = withContext(Dispatchers.IO) {
                if (prefix.isNotEmpty()) {
                    // 지역 prefix 붙인 검색(8개) + 전국 검색(3개) 병합
                    val local  = searchApi.searchPlace("$prefix$query", display = 8)
                    val global = runCatching {
                        searchApi.searchPlace(query, display = 3)
                    }.getOrNull()
                    (local.items + (global?.items ?: emptyList()))
                        .distinctBy { "${it.mapx},${it.mapy}" }  // 좌표 기준 중복 제거
                } else {
                    searchApi.searchPlace(query, display = 10).items
                }
            }

            // 현재위치 기준 거리순 정렬
            val sorted = if (currentLat != 0.0 && currentLng != 0.0) {
                merged.sortedBy {
                    val la = it.mapy.toDouble() / 1e7
                    val ln = it.mapx.toDouble() / 1e7
                    distanceBetween(currentLat, currentLng, la, ln)
                }
            } else merged

            results = sorted

            val adapter = object : ArrayAdapter<SearchItem>(
                this@SearchActivity, R.layout.item_suggestion, sorted
            ) {
                override fun getView(
                    position: Int, convertView: View?, parent: android.view.ViewGroup
                ): View {
                    val v = convertView
                        ?: layoutInflater.inflate(R.layout.item_suggestion, parent, false)
                    val item = sorted[position]
                    val cleanName = item.title.replace(Regex("<[^>]*>"), "") // 장소명
                    val address = item.roadAddress.ifEmpty { item.address } // 주소
                    v.findViewById<TextView>(R.id.suggestion_name).text = cleanName
                    v.findViewById<TextView>(R.id.suggestion_address).text = address
                    return v
                }
            }
            list.adapter = adapter
        } catch (_: Exception) {}
    }
    // 거리계산. 하버사인 공식. 6371000은 지구 반지름(미터)
    private fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 6371000 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}