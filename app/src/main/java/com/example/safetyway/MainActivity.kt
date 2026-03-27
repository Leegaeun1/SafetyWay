package com.example.safetyway

import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.LocationTrackingMode
import com.naver.maps.map.MapFragment
import com.naver.maps.map.NaverMap
import com.naver.maps.map.OnMapReadyCallback
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage
import com.naver.maps.map.util.FusedLocationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var locationSource: FusedLocationSource
    private lateinit var naverMap: NaverMap
    // 마커 상태 관리
    private var isCctvVisible = false
    private val activeCctvMarkers = mutableListOf<Marker>()
    // 마커가 보이기 시작할 최소 줌 레벨
    private val MIN_ZOOM_LEVEL = 14.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val fm = supportFragmentManager
        val mapFragment = fm.findFragmentById(R.id.map_fragment) as MapFragment?
            ?: MapFragment.newInstance().also {
                fm.beginTransaction().add(R.id.map_fragment, it).commit()
            }

        mapFragment.getMapAsync(this)
        locationSource = FusedLocationSource(this, LOCATION_PERMISSION_REQUEST_CODE)
    }

    override fun onMapReady(naverMap: NaverMap) {
        this.naverMap = naverMap
        naverMap.locationSource = locationSource
        naverMap.uiSettings.isLocationButtonEnabled = true
        naverMap.locationTrackingMode = LocationTrackingMode.Follow

        // 지도가 준비된 후 버튼 리스너 설정
        setupButtonListeners()
        // 카메라 이동이 멈췄을 때 실행되는 리스너 추가
        naverMap.addOnCameraIdleListener {
            if (isCctvVisible) { // 보이는 상태
                if (naverMap.cameraPosition.zoom >= MIN_ZOOM_LEVEL) {
                    showCctvsInCurrentBounds() // 충분히 확대되었으면 마커 표시
                } else {
                    clearCctvMarkers() // 너무 축소되었으면 마커 지우기
                }
            }
        }
    }

    private fun setupButtonListeners() {
        val cctvBtn = findViewById<ImageButton>(R.id.cctv_btn)
        val streetlightBtn = findViewById<ImageButton>(R.id.streetlight_btn)

        // CCTV 버튼 클릭 이벤트
        cctvBtn.setOnClickListener {
            isCctvVisible = !isCctvVisible // 상태 반전 (on/off)

            if (isCctvVisible) {
                cctvBtn.setImageResource(R.drawable.cctv_no_see) // 이미지 변경
                showCctvsInCurrentBounds() // 보여줌
            } else {
                cctvBtn.setImageResource(R.drawable.cctv_see) // 이미지 변경
                clearCctvMarkers() // 끔
            }
        }
        // 보안등 버튼 클릭 이벤트 (임시)
        streetlightBtn.setOnClickListener {
            Toast.makeText(this, "보안등 데이터는 아직 준비되지 않았습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // 3. 현재 화면 영역의 CCTV를 DB에서 불러와 마커로 찍는 함수
    private fun showCctvsInCurrentBounds() {
        // 줌 레벨이 너무 낮으면 그리지 않음
        if (naverMap.cameraPosition.zoom < MIN_ZOOM_LEVEL) {
            clearCctvMarkers()
            return
        }
        // 기존 마커 지우기
        clearCctvMarkers()

        // 현재 지도의 화면 경계(Bounds) 가져오기
        val bounds = naverMap.contentBounds
        val minLat = bounds.southWest.latitude
        val maxLat = bounds.northEast.latitude
        val minLng = bounds.southWest.longitude
        val maxLng = bounds.northEast.longitude

        // 코루틴으로 DB 조회 및 UI 업데이트
        lifecycleScope.launch { // 액티비티가 실행중일 때만 실행됨
            // IO 스레드에서 DB 접근
            val cctvList = withContext(Dispatchers.IO) { // 무거운 작업은 IO에서 진행
                // AppDatabase에 있는 싱글톤 불러옴
                val db = AppDatabase.getDatabase(applicationContext)
                // 화면 안에 있는거 가져오도록
                db.cctvDao().getCctvsInBounds(minLat, maxLat, minLng, maxLng)
            }

            // Main 스레드에서 마커 그리기
            withContext(Dispatchers.Main) {
                for (cctv in cctvList) {
                    val marker = Marker().apply {
                        position = LatLng(cctv.latitude, cctv.longitude)
                        map = naverMap
                        // cctv 아이콘 지정
                        icon = OverlayImage.fromResource(R.drawable.cctv)

                        // 아이콘 크기 지정
                        width = 60
                        height = 60
                    }
                    activeCctvMarkers.add(marker)
                }
            }
        }
    }

    // 화면에 그려진 CCTV 마커들을 모두 지우는 함수
    private fun clearCctvMarkers() {
        for (marker in activeCctvMarkers) {
            marker.map = null // 마커를 지우기
        }
        activeCctvMarkers.clear()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (locationSource.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            if (!locationSource.isActivated) {
                naverMap.locationTrackingMode = LocationTrackingMode.None
            }
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1000
    }
}