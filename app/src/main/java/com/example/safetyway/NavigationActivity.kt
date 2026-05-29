package com.example.safetyway

import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class NavigationActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var naverMap: NaverMap
    private lateinit var locationSource: FusedLocationSource
    private val polylines = mutableListOf<PolylineOverlay>()
    private lateinit var goalMarker: Marker

    // 경로 데이터 (MainActivity에서 넘겨받음)
    private var fullPath = listOf<LatLng>()
    private var remainingPath = mutableListOf<LatLng>()
    private var goalLat = 0.0
    private var goalLng = 0.0
    private var goalName = ""
    private var totalDistanceM = 0.0   // 전체 경로 거리(m) — 시간 계산 기준

    // 현재 상태
    private var currentPolyline: PolylineOverlay? = null
    private var passedPolyline: PolylineOverlay? = null
    private var lastLocation: LatLng? = null

    private var isNaviCctvVisible = false
    private var isNaviLightVisible = false
    private val naviCctvMarkers = mutableListOf<Marker>()
    private val naviLightMarkers = mutableListOf<Marker>()
    private val MIN_ZOOM_LEVEL = 14.0

    companion object {
        const val EXTRA_PATH_LAT = "path_lat"
        const val EXTRA_PATH_LNG = "path_lng"
        const val EXTRA_GOAL_LAT = "goal_lat"
        const val EXTRA_GOAL_LNG = "goal_lng"
        const val EXTRA_GOAL_NAME = "goal_name"
        const val EXTRA_TOTAL_DISTANCE = "total_distance"   // 전체 거리(m)
        private const val LOCATION_PERMISSION_REQUEST_CODE = 2000
        private const val ARRIVED_DISTANCE = 20.0
        private const val OFF_ROUTE_DISTANCE = 50.0
        private const val WALK_SPEED_M_PER_MIN = 80.0  // 도보 약 4.8km/h (실제 체감 속도)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        // MainActivity에서 경로 데이터 받기
        val lats = intent.getDoubleArrayExtra(EXTRA_PATH_LAT) ?: return
        val lngs = intent.getDoubleArrayExtra(EXTRA_PATH_LNG) ?: return
        goalLat = intent.getDoubleExtra(EXTRA_GOAL_LAT, 0.0)
        goalLng = intent.getDoubleExtra(EXTRA_GOAL_LNG, 0.0)
        goalName = intent.getStringExtra(EXTRA_GOAL_NAME) ?: "목적지"

        fullPath = lats.zip(lngs.toList()).map { (lat, lng) -> LatLng(lat, lng) }
        remainingPath = fullPath.toMutableList()
        totalDistanceM = intent.getDoubleExtra(EXTRA_TOTAL_DISTANCE, 0.0)
        // totalDistanceM이 0이면 경로 좌표에서 직접 계산
        if (totalDistanceM == 0.0 && fullPath.size >= 2) {
            for (i in 0 until fullPath.size - 1) {
                totalDistanceM += distanceBetween(
                    fullPath[i].latitude, fullPath[i].longitude,
                    fullPath[i+1].latitude, fullPath[i+1].longitude
                )
            }
        }

        locationSource = FusedLocationSource(this, LOCATION_PERMISSION_REQUEST_CODE)

        val fm = supportFragmentManager
        val mapFragment = fm.findFragmentById(R.id.navi_map_fragment) as MapFragment?
            ?: MapFragment.newInstance().also {
                fm.beginTransaction().add(R.id.navi_map_fragment, it).commit()
            }
        mapFragment.getMapAsync(this)

        // 뒤로가기 버튼
        findViewById<ImageButton>(R.id.btn_close_navi).setOnClickListener {
            finish()
        }
    }

    override fun onMapReady(naverMap: NaverMap) {
        this.naverMap = naverMap
        naverMap.locationSource = locationSource
        naverMap.locationTrackingMode = LocationTrackingMode.Face

        // 위치 버튼 활성화 (동그란 버튼)
        naverMap.uiSettings.isLocationButtonEnabled = true

        // 목적지 마커
        goalMarker = Marker().apply {
            position = LatLng(goalLat, goalLng)
            map = naverMap
            captionText = goalName
        }

        drawFullRoute()
        startLocationTracking()
        setupNaviButtons()

        // 카메라 이동 완료 시 마커 업데이트 (디바운스 적용)
        var markerUpdateJob: kotlinx.coroutines.Job? = null
        naverMap.addOnCameraIdleListener {
            markerUpdateJob?.cancel()
            markerUpdateJob = lifecycleScope.launch {
                delay(300)  // 0.3초 후 업데이트 (회전 중 과도한 갱신 방지)
                updateNaviMarkers("CCTV")
                updateNaviMarkers("LIGHT")
            }
        }
    }
    private fun setupNaviButtons() {
        val cctvBtn = findViewById<ImageButton>(R.id.navi_cctv_btn)
        val lightBtn = findViewById<ImageButton>(R.id.navi_streetlight_btn)

        cctvBtn.setOnClickListener {
            isNaviCctvVisible = !isNaviCctvVisible
            updateNaviMarkers("CCTV")
            cctvBtn.setImageResource(
                if (isNaviCctvVisible) R.drawable.cctv_no_see else R.drawable.cctv_see
            )
        }

        lightBtn.setOnClickListener {
            isNaviLightVisible = !isNaviLightVisible
            updateNaviMarkers("LIGHT")
            lightBtn.setImageResource(
                if (isNaviLightVisible) R.drawable.streetlight_no_see else R.drawable.streetlight_see
            )
        }
    }

    private fun updateNaviMarkers(type: String) {
        val isVisible = if (type == "CCTV") isNaviCctvVisible else isNaviLightVisible
        val activeMarkers = if (type == "CCTV") naviCctvMarkers else naviLightMarkers

        activeMarkers.forEach { it.map = null }
        activeMarkers.clear()

        if (!isVisible || naverMap.cameraPosition.zoom < MIN_ZOOM_LEVEL) return

        val bounds = naverMap.contentBounds
        lifecycleScope.launch {
            val dataList = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(applicationContext).safetyDao()
                    .getSafetyInBounds(
                        bounds.southWest.latitude, bounds.northEast.latitude,
                        bounds.southWest.longitude, bounds.northEast.longitude,
                        type
                    )
            }
            withContext(Dispatchers.Main) {
                for (item in dataList) {
                    val marker = Marker().apply {
                        position = LatLng(item.latitude, item.longitude)
                        map = naverMap
                        icon = OverlayImage.fromResource(
                            if (type == "CCTV") R.drawable.cctv else R.drawable.streetlight
                        )
                        width = 60
                        height = 60
                    }
                    activeMarkers.add(marker)
                }
            }
        }
    }
    private fun drawFullRoute() {
        currentPolyline?.map = null
        currentPolyline = PolylineOverlay().apply {
            coords = fullPath
            color = Color.parseColor("#3D6BF5")
            width = 15
            map = naverMap
        }
    }

    private fun startLocationTracking() {
        naverMap.addOnLocationChangeListener { location ->
            val current = LatLng(location.latitude, location.longitude)
            onLocationUpdate(current, location)
        }
    }

    private fun onLocationUpdate(current: LatLng, location: Location) {
        // 1. 도착 여부 확인
        val distToGoal = distanceBetween(current.latitude, current.longitude, goalLat, goalLng)
        if (distToGoal < ARRIVED_DISTANCE) {
            onArrived()
            return
        }

        // 2. 경로에서 현재 위치와 가장 가까운 지점 찾기
        val closestIndex = findClosestPointIndex(current)

        // 3. 지나간 경로(회색) / 남은 경로(파랑) 분리
        updateRouteDisplay(current, closestIndex)

        // 4. 다음 안내 정보 업데이트
        updateNavigationInfo(current, closestIndex)

        // 5. 경로 이탈 감지
        val distToRoute = distanceBetween(
            current.latitude, current.longitude,
            fullPath[closestIndex].latitude, fullPath[closestIndex].longitude
        )
        if (distToRoute > OFF_ROUTE_DISTANCE) {
            onOffRoute(current)
        }

        lastLocation = current
    }

    private fun findClosestPointIndex(current: LatLng): Int {
        return fullPath.indices.minByOrNull { i ->
            distanceBetween(
                current.latitude, current.longitude,
                fullPath[i].latitude, fullPath[i].longitude
            )
        } ?: 0
    }

    private fun updateRouteDisplay(current: LatLng, closestIndex: Int) {
        // 지나간 경로 (회색)
        passedPolyline?.map = null
        if (closestIndex > 0) {
            passedPolyline = PolylineOverlay().apply {
                coords = fullPath.subList(0, closestIndex + 1)
                color = Color.parseColor("#AAAAAA")
                width = 10
                map = naverMap
            }
        }

        // 남은 경로 (파랑)
        currentPolyline?.map = null
        if (closestIndex < fullPath.size - 1) {
            currentPolyline = PolylineOverlay().apply {
                coords = fullPath.subList(closestIndex, fullPath.size)
                color = Color.parseColor("#3D6BF5")
                width = 15
                map = naverMap
            }
        }
    }

    private fun updateNavigationInfo(current: LatLng, closestIndex: Int) {
        // 남은 거리: closestIndex 이후 좌표들의 누적 거리
        var remainingDist = 0.0
        for (i in closestIndex until fullPath.size - 1) {
            remainingDist += distanceBetween(
                fullPath[i].latitude, fullPath[i].longitude,
                fullPath[i+1].latitude, fullPath[i+1].longitude
            )
        }
        // 현재 위치 -> closestIndex 좌표까지의 잔여 거리도 포함
        if (closestIndex < fullPath.size) {
            remainingDist += distanceBetween(
                current.latitude, current.longitude,
                fullPath[closestIndex].latitude, fullPath[closestIndex].longitude
            )
        }

        val remainingMin = (remainingDist / 65.0).toInt().coerceAtLeast(1)  // 65m/분
        val remainingKm = "%.1f".format(remainingDist / 1000.0)
        val remainingSteps = (remainingDist * 1.4).toInt()  // 1m = 1.4걸음

        val direction = if (closestIndex < fullPath.size - 1) {
            getDirectionText(current, fullPath[closestIndex + 1])
        } else "목적지 근처"

        findViewById<TextView>(R.id.tv_direction).text = direction
        findViewById<TextView>(R.id.tv_remaining_time).text = "${remainingMin}분"
        findViewById<TextView>(R.id.tv_remaining_dist).text = "${remainingKm}km"
        findViewById<TextView>(R.id.tv_remaining_steps).text = "${remainingSteps}걸음"
    }

    // 방향 텍스트 계산
    private fun getDirectionText(from: LatLng, to: LatLng): String {
        val dLng = to.longitude - from.longitude
        val dLat = to.latitude - from.latitude
        val angle = Math.toDegrees(atan2(dLng, dLat))

        return when {
            angle < -135 || angle > 135 -> "↓ 직진"
            angle < -45 -> "← 좌회전"
            angle > 45 -> "→ 우회전"
            else -> "↑ 직진"
        }
    }

    private fun onArrived() {
        findViewById<TextView>(R.id.tv_direction).text = "🎉 목적지 도착!"
        currentPolyline?.map = null
        passedPolyline?.map = null
    }

    private fun onOffRoute(current: LatLng) {
        // 경로 이탈 시 토스트
        android.widget.Toast.makeText(this, "경로를 벗어났습니다. 재탐색 중...", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat/2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng/2).pow(2)
        return 6371000 * 2 * atan2(sqrt(a), sqrt(1-a))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (locationSource.onRequestPermissionsResult(requestCode, permissions, grantResults)) return
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}