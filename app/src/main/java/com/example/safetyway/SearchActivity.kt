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

    companion object {
        const val EXTRA_CUR_LAT = "cur_lat"
        const val EXTRA_CUR_LNG = "cur_lng"
        const val EXTRA_CITY = "city"
        const val EXTRA_DONG = "dong"
        const val RESULT_NAME = "result_name"
        const val RESULT_LAT  = "result_lat"
        const val RESULT_LNG  = "result_lng"
        const val EXTRA_TARGET = "target"
    }

    private lateinit var searchApi: NaverSearchApi
    private var currentLat = 0.0
    private var currentLng = 0.0
    private var city: String? = null
    private var dong: String? = null
    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient

    private var results: List<SearchItem> = emptyList()
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        searchApi = RetrofitClient.createSearchApi(this)
        currentLat = intent.getDoubleExtra(EXTRA_CUR_LAT, 0.0)
        currentLng = intent.getDoubleExtra(EXTRA_CUR_LNG, 0.0)
        city = intent.getStringExtra(EXTRA_CITY)
        dong = intent.getStringExtra(EXTRA_DONG)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // 위치/지역명 유무에 관계없이 항상 자체 획득 시도
        fetchLocationAndRegion()

        val input  = findViewById<EditText>(R.id.search_input)
        val btnBack  = findViewById<ImageButton>(R.id.btn_back)
        val btnClear = findViewById<ImageButton>(R.id.btn_clear)
        val list   = findViewById<ListView>(R.id.result_list)

        // 힌트 텍스트: 출발지/목적지 구분
        val target = intent.getStringExtra(EXTRA_TARGET)
        input.hint = if (target == "start") "출발지 검색" else "목적지 검색"

        btnBack.setOnClickListener { finish() }
        btnClear.setOnClickListener { input.setText("") }

        input.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                btnClear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                searchJob?.cancel()
                if (q.length < 2) { results = emptyList(); list.adapter = null; return }
                searchJob = lifecycleScope.launch { delay(300); fetchSuggestions(q, list) }
            }
        })

        list.setOnItemClickListener { _, _, position, _ ->
            val item = results[position]
            val name = item.title.replace(Regex("<[^>]*>"), "")
            val lat = item.mapy.toDouble() / 1e7
            val lng = item.mapx.toDouble() / 1e7
            val data = Intent().apply {
                putExtra(RESULT_NAME, name)
                putExtra(RESULT_LAT, lat)
                putExtra(RESULT_LNG, lng)
            }
            setResult(Activity.RESULT_OK, data)
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
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

        fusedLocationClient.lastLocation
            .addOnSuccessListener { loc ->
                if (loc == null) { requestCurrentLocation(); return@addOnSuccessListener }
                onLocationObtained(loc.latitude, loc.longitude)
            }
            .addOnFailureListener { e ->
                requestCurrentLocation()
            }
    }
    @SuppressLint("MissingPermission")
    private fun requestCurrentLocation() {
        val hasPerm = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            android.util.Log.d("SafetyWay", "SearchActivity: 위치 권한 없음")
            return
        }

        val req = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, 1000L
        ).setMaxUpdates(1).build()

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
    private fun onLocationObtained(lat: Double, lng: Double) {
        currentLat = lat
        currentLng = lng
        if (city != null) {
            reorderCurrentResults()
            return
        }

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
        if (results.isEmpty() || currentLat == 0.0) return
        val list = findViewById<ListView>(R.id.result_list)
        results = results.sortedBy {
            distanceBetween(currentLat, currentLng, it.mapy.toDouble() / 1e7, it.mapx.toDouble() / 1e7)
        }
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

    private suspend fun fetchSuggestions(query: String, list: ListView) {
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
                    val cleanName = item.title.replace(Regex("<[^>]*>"), "")
                    val address = item.roadAddress.ifEmpty { item.address }
                    v.findViewById<TextView>(R.id.suggestion_name).text = cleanName
                    v.findViewById<TextView>(R.id.suggestion_address).text = address
                    return v
                }
            }
            list.adapter = adapter
        } catch (_: Exception) {}
    }

    private fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 6371000 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}