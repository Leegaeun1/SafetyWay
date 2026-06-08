package com.example.safetyway

import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.FirebaseFirestore
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
    // 사각지대 경고 상태
    private var isBlindSpotWarningShown = false
    private val CCTV_BLIND_SPOT_RADIUS = 150.0  // CCTV 없는 구간으로 판단할 반경(m)
    private val naviCctvMarkers = mutableListOf<Marker>()
    private val naviLightMarkers = mutableListOf<Marker>()
    private val MIN_ZOOM_LEVEL = 14.0

    // 비상 사이렌
    private var sirenPlayer: MediaPlayer? = null
    private var isSirenOn = false
    private lateinit var audioManager: AudioManager
    private var savedVolume = 0  // 사이렌 종료 후 원래 볼륨으로 복원하기 위해 저장
    private var lastPassedIndex = 0
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
    // TMAP 경로를 5m 간격으로 잘라주는 함수
    private fun makeDensePath(path: List<LatLng>, intervalM: Double): List<LatLng> {
        if (path.size < 2) return path
        val densePath = mutableListOf<LatLng>()
        densePath.add(path[0])
        for (i in 0 until path.size - 1) {
            val p1 = path[i]
            val p2 = path[i+1]
            val dist = distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude)

            if (dist > intervalM) {
                val steps = (dist / intervalM).toInt()
                for (j in 1..steps) {
                    val fraction = j.toDouble() / (steps + 1.0)
                    val lat = p1.latitude + (p2.latitude - p1.latitude) * fraction
                    val lng = p1.longitude + (p2.longitude - p1.longitude) * fraction
                    densePath.add(LatLng(lat, lng))
                }
            }
            densePath.add(p2)
        }
        return densePath
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // MainActivity에서 경로 데이터 받기
        val lats = intent.getDoubleArrayExtra(EXTRA_PATH_LAT) ?: return
        val lngs = intent.getDoubleArrayExtra(EXTRA_PATH_LNG) ?: return
        goalLat = intent.getDoubleExtra(EXTRA_GOAL_LAT, 0.0)
        goalLng = intent.getDoubleExtra(EXTRA_GOAL_LNG, 0.0)
        goalName = intent.getStringExtra(EXTRA_GOAL_NAME) ?: "목적지"

        val rawPath = lats.zip(lngs.toList()).map { (lat, lng) -> LatLng(lat, lng) }
        fullPath = makeDensePath(rawPath, 5.0)
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
        // 화면이 켜지자마자 보여줄 '초기 남은 시간/거리'를 계산
        val initialMin = (totalDistanceM / 65.0).toInt().coerceAtLeast(1)
        val initialKm = "%.1f".format(totalDistanceM / 1000.0)
        val initialSteps = (totalDistanceM * 1.4).toInt()

        findViewById<TextView>(R.id.tv_remaining_time).text = "${initialMin}분"
        findViewById<TextView>(R.id.tv_remaining_dist).text = "${initialKm}km"
        findViewById<TextView>(R.id.tv_remaining_steps).text = "${initialSteps}걸음"

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
        setupSirenButton()
        findViewById<ImageButton>(R.id.call_btn).apply {
            // 가짜 통화 바로 실행
            setOnClickListener {
                startActivity(Intent(this@NavigationActivity, FakeCallActivity::class.java))
            }
        }
        // 1. 포그라운드 서비스(NaviService) 실행
        val serviceIntent = Intent(this, NaviService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
    // 비상 사이렌 버튼 설정
    private fun setupSirenButton() {
        val sosBtn = findViewById<ImageButton>(R.id.sos_btn)
        sosBtn.setOnClickListener {
            if (isSirenOn) stopSiren() else startSiren()
            // 아이콘 토글 (활성/비활성 상태 구분)
            sosBtn.setImageResource(
                if (isSirenOn) R.drawable.is_siren_off else R.drawable.is_siren_on
            )
        }
    }
    private fun startSiren() {
        isSirenOn = true

        // 이어폰 연결 여부 확인 (API 버전 무관하게 안전한 방식)
        val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val isHeadsetConnected = audioDevices.any { device ->
            device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

        // 이어폰 있으면 STREAM_MUSIC(이어폰), 없으면 STREAM_ALARM(스피커) - 테스트 하기위해 이어폰을 추가해두었음.
        val streamType = if (isHeadsetConnected) {
            AudioManager.STREAM_MUSIC
        } else {
            AudioManager.STREAM_ALARM
        }

        // 볼륨 저장 후 조절
        // TODO: 실제 배포 시 0.6f -> 1.0f 로 변경!
        val maxVolume = audioManager.getStreamMaxVolume(streamType) // 최대 볼륨
        savedVolume = audioManager.getStreamVolume(streamType)
        val targetVolume = (maxVolume * 0.6f).toInt()
        audioManager.setStreamVolume(streamType, targetVolume, 0) // 볼륨 설정

        try {
            sirenPlayer = MediaPlayer.create(applicationContext, R.raw.scream).apply {
                isLooping = true
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isSirenOn = false
            //android.widget.Toast.makeText(this, "사이렌 오류: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopSiren() {
        // 만약 사이렌이 켜진 상태가 아니었다면, 아무것도 하지 않고 함수를 끝냅니다! (볼륨 0 되는 문제 해결 핵심)
        if (!isSirenOn) return
        isSirenOn = false

        sirenPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        sirenPlayer = null

        // 사용한 스트림 타입에 맞게 볼륨 복원
        val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val isHeadsetConnected = audioDevices.any { device ->
            device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        val streamType = if (isHeadsetConnected) AudioManager.STREAM_MUSIC else AudioManager.STREAM_ALARM
        // 이전에 저장해둔 볼륨으로 안전하게 원상복구
        audioManager.setStreamVolume(streamType, savedVolume, 0)
    }
    override fun onMapReady(naverMap: NaverMap) {
        this.naverMap = naverMap
        naverMap.locationSource = locationSource
        naverMap.locationTrackingMode = LocationTrackingMode.Face
        naverMap.uiSettings.isZoomControlEnabled = false
        // 위치 버튼 활성화 (동그란 버튼)
        naverMap.uiSettings.isLocationButtonEnabled = true
        naverMap.setContentPadding(0, 0, 0, 250)  // 하단 카드 높이만큼 패딩

        // 목적지 마커
        goalMarker = Marker().apply {
            position = LatLng(goalLat, goalLng)
            map = naverMap
            captionText = goalName
        }
        naverMap.addOnLocationChangeListener { location ->
            val current = LatLng(location.latitude, location.longitude)
            onLocationUpdate(current, location)
        }
        drawFullRoute()
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
            // 1. 공공데이터 불러오기
            val localDataList = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(applicationContext).safetyDao()
                    .getSafetyInBounds(
                        bounds.southWest.latitude, bounds.northEast.latitude,
                        bounds.southWest.longitude, bounds.northEast.longitude,
                        type
                    )
            }

            withContext(Dispatchers.Main) {
                // 로컬 마커 그리기
                for (item in localDataList) {
                    val marker = Marker().apply {
                        position = LatLng(item.latitude, item.longitude)
                        map = naverMap
                        icon = OverlayImage.fromResource(
                            if (type == "CCTV") R.drawable.cctv else R.drawable.streetlight
                        )
                        width = 60; height = 60
                    }
                    activeMarkers.add(marker)
                }

                // 2. 파이어베이스 승인 제보 데이터 불러오기
                val firestoreType = if (type == "CCTV") "CCTV" else "보안등"

                FirebaseFirestore.getInstance().collection("reports")
                    .whereEqualTo("status", "APPROVED")
                    .whereEqualTo("type", firestoreType)
                    .get()
                    .addOnSuccessListener { documents ->
                        for (document in documents) {
                            val lat = document.getDouble("latitude") ?: continue
                            val lng = document.getDouble("longitude") ?: continue

                            // 화면 범위 필터링
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
    private fun drawFullRoute() {
        currentPolyline?.map = null
        currentPolyline = PolylineOverlay().apply {
            coords = fullPath
            color = Color.parseColor("#3D6BF5")
            width = 15
            map = naverMap
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

        // 6. CCTV 사각지대 감지
        checkBlindSpot(current)

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
        // 1. 사용자에게 안내 메시지 띄우기
        android.widget.Toast.makeText(this, "목적지에 도착하여 안내를 종료합니다.", android.widget.Toast.LENGTH_LONG).show()

        // 2. 현재 네비게이션 화면 종료 (자동으로 MainActivity로 돌아감)
        finish()
    }

    private fun onOffRoute(current: LatLng) {
        // 경로 이탈 시 토스트
        android.widget.Toast.makeText(this, "경로를 벗어났습니다. 재탐색 중...", android.widget.Toast.LENGTH_SHORT).show()
    }

    // 액티비티 종료 시 사이렌 반드시 정리
    override fun onDestroy() {
        super.onDestroy()
        stopSiren()
        stopService(Intent(this, NaviService::class.java)) // 포그라운드 알림 끄기
    }
    /**
     * 현재 위치 반경 CCTV_BLIND_SPOT_RADIUS(m) 안에 CCTV가 없으면 경고 카드 표시
     * DB 조회는 IO 스레드에서, UI 업데이트는 Main 스레드에서 처리
     */
    private fun checkBlindSpot(current: LatLng) {
        val warningCard = findViewById<CardView>(R.id.blind_spot_warning_card)
        val tvDistance = findViewById<TextView>(R.id.tv_blind_spot_distance)
        val ivIcon = findViewById<ImageView>(R.id.iv_blind_spot_icon)
        ivIcon.setColorFilter(android.graphics.Color.parseColor("#FF6600"), android.graphics.PorterDuff.Mode.SRC_IN)

        // 위도/경도 1도 ==> 111km -> 150m를 도 단위로 변환
        val degreeOffset = CCTV_BLIND_SPOT_RADIUS / 111000.0

        lifecycleScope.launch {
            val nearbyCount = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(applicationContext).safetyDao()
                    .getSafetyInBounds(
                        current.latitude - degreeOffset,
                        current.latitude + degreeOffset,
                        current.longitude - degreeOffset,
                        current.longitude + degreeOffset,
                        "CCTV"
                    ).count { item ->
                        // 실제 거리로 한 번 더 필터링 (사각형 쿼리 -> 원형 보정)
                        distanceBetween(
                            current.latitude, current.longitude,
                            item.latitude, item.longitude
                        ) <= CCTV_BLIND_SPOT_RADIUS
                    }
            }

            withContext(Dispatchers.Main) {
                val isBlindSpot = nearbyCount == 0
                if (isBlindSpot && !isBlindSpotWarningShown) {
                    // 사각지대 진입 -> 카드 표시
                    tvDistance.text = "${CCTV_BLIND_SPOT_RADIUS.toInt()}m 이전까지 CCTV가 없습니다!"
                    warningCard.visibility = View.VISIBLE
                    isBlindSpotWarningShown = true
                } else if (!isBlindSpot && isBlindSpotWarningShown) {
                    // CCTV 범위 재진입 -> 카드 숨김
                    warningCard.visibility = View.GONE
                    isBlindSpotWarningShown = false
                }
            }
        }
    }

    private fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng/2) * Math.sin(dLng/2)
        return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (locationSource.onRequestPermissionsResult(requestCode, permissions, grantResults)) return
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}