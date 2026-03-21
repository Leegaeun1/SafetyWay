package com.example.safetyway



import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.naver.maps.map.MapFragment
import com.naver.maps.map.NaverMap
import com.naver.maps.map.OnMapReadyCallback
import java.security.MessageDigest


class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        try {
            val packageName = packageName
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }

            // 버전별로 다른 서명 정보를 안전하게 가져옵니다
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            // null 체크(?.)를 해서 'Non-nullable' 에러를 방지합니다
            signatures?.forEach { signature ->
                val md = MessageDigest.getInstance("SHA-1")
                md.update(signature.toByteArray())
                val bytes = md.digest()
                val sha1 = bytes.joinToString(":") { String.format("%02X", it) }

                // Logcat에서 이 태그로 검색하세요!
                Log.d("FINAL_SHA1", "가은님의 SHA-1: $sha1")
            }
        } catch (e: Exception) {
            Log.e("FINAL_SHA1", "에러 발생: ${e.message}")
        }
        // 1. 레이아웃에서 지도 프래그먼트를 찾아옵니다.
        val fm = supportFragmentManager
        val mapFragment = fm.findFragmentById(R.id.map_fragment) as MapFragment?
            ?: MapFragment.newInstance().also {
                fm.beginTransaction().add(R.id.map_fragment, it).commit()
            }

        // 2. 지도가 준비되면 onMapReady를 호출하도록 설정합니다.
        mapFragment.getMapAsync(this)
    }

    // 3. 지도가 준비되었을 때 실행되는 함수입니다.
    override fun onMapReady(naverMap: NaverMap) {
        // 여기서부터 지도 위에 마커를 찍거나 경로를 그리는 코드를 작성합니다!
        // 예: 지도 유형을 위성 지도로 바꾸기
        // naverMap.mapType = NaverMap.MapType.Satellite
    }
}