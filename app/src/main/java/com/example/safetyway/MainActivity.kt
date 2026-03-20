package com.example.safetyway

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 기존의 setContent { ... } 부분은 과감히 지우세요!
        // 대신 아래 한 줄을 적어줍니다.
        setContentView(R.layout.activity_main)
    }
}