package com.example.safetyway



import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.naver.maps.map.LocationTrackingMode
import com.naver.maps.map.MapFragment
import com.naver.maps.map.NaverMap
import com.naver.maps.map.OnMapReadyCallback
import com.naver.maps.map.util.FusedLocationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var locationSource: FusedLocationSource
    private lateinit var naverMap: NaverMap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // 해당 xml을 보여줌

        // 1. 레이아웃에서 지도 프래그먼트를 찾아옵니다.
        val fm = supportFragmentManager
        val mapFragment = fm.findFragmentById(R.id.map_fragment) as MapFragment?
            ?: MapFragment.newInstance().also {
                fm.beginTransaction().add(R.id.map_fragment, it).commit()
            }

        // 2. 지도가 준비되면 onMapReady를 호출하도록 설정합니다.
        mapFragment.getMapAsync(this)
        locationSource = FusedLocationSource(
            this,
            LOCATION_PERMISSION_REQUEST_CODE
        )
        // 코루틴을 사용하여 백그라운드에서 DB 읽기
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(applicationContext)
                val dao = db.cctvDao()

                // 테스트: 데이터가 총 몇 개인지, 첫 번째 데이터는 무엇인지 로그로 확인
                val allData = dao.getAllCctvs()
                Log.d("DB_TEST", "총 CCTV 개수: ${allData.size}")

                if (allData.isNotEmpty()) {
                    Log.d("DB_TEST", "첫번째 CCTV 정보 - 위도: ${allData[0].latitude}, 경도: ${allData[0].longitude}, 대수: ${allData[0].cameraCount}")
                }
            }
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (
            locationSource.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
            )
        ) {
            if (!locationSource.isActivated) { // 권한 거부됨
                naverMap.locationTrackingMode = LocationTrackingMode.None
            }
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    // 3. 지도가 준비되었을 때 실행되는 함수입니다.
    override fun onMapReady(naverMap: NaverMap) {
        this.naverMap = naverMap
        naverMap.locationSource = locationSource
        val uiSettings = naverMap.uiSettings
        uiSettings.isLocationButtonEnabled = true
        naverMap.locationTrackingMode = LocationTrackingMode.Follow

    }
    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1000
    }
}