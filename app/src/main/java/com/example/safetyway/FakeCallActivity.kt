package com.example.safetyway

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class FakeCallActivity : AppCompatActivity() {

    private lateinit var audioManager: AudioManager
    private var ringtonePlayer: MediaPlayer? = null
    private var voicePlayer: MediaPlayer? = null

    private val handler = Handler(Looper.getMainLooper())
    private var callSeconds = 0
    private var timerRunnable: Runnable? = null

    companion object {
        private const val REQ_SEND_SMS = 301
    }

    // SharedPreferences에서 설정 읽기
    private val prefs by lazy { getSharedPreferences(FakeCallSettingActivity.PREF_NAME, MODE_PRIVATE) }

    private val callerName: String by lazy {
        prefs.getString(FakeCallSettingActivity.KEY_CALLER_NAME, "아빠") ?: "아빠"
    }

    // 번호 목록: "|" 구분자로 저장된 값을 리스트로 분리
    private val numbers: List<String> by lazy {
        val raw = prefs.getString(FakeCallSettingActivity.KEY_NUMBERS, "") ?: ""
        if (raw.isBlank()) emptyList() else raw.split("|").filter { it.isNotBlank() }
    }

    private val smsBody: String by lazy {
        prefs.getString(FakeCallSettingActivity.KEY_SMS_BODY, "") ?: ""
    }

    // 표시할 번호: 등록된 번호 있으면 첫 번째, 없으면 랜덤
    private val displayNumber: String by lazy {
        if (numbers.isNotEmpty()) numbers[0]
        else "010-${(1000..9999).random()}-${(1000..9999).random()}"
    }

    private val audioFile: File get() = File(filesDir, FakeCallSettingActivity.AUDIO_FILENAME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fake_call)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        findViewById<TextView>(R.id.tv_caller_name).text   = callerName
        findViewById<TextView>(R.id.tv_active_name).text   = callerName
        findViewById<TextView>(R.id.tv_caller_number).text = displayNumber
        findViewById<TextView>(R.id.tv_active_number).text = displayNumber

        startRingtone()

        findViewById<ImageButton>(R.id.btn_accept).setOnClickListener {
            stopRingtone()
            showActiveCall()
            playVoiceRecording()
            sendSmsIfNeeded()   // 받기 누르는 순간 문자 발송
        }

        findViewById<ImageButton>(R.id.btn_decline).setOnClickListener {
            stopRingtone()
            finish()
        }

        findViewById<ImageButton>(R.id.btn_hangup).setOnClickListener {
            stopVoicePlayer()
            stopTimer()
            finish()
        }
    }

    // 문자 발송
    private fun sendSmsIfNeeded() {
        // 번호 없거나 문자 내용 없으면 스킵
        if (numbers.isEmpty() || smsBody.isBlank()) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // 권한 없으면 요청 (승인되면 onRequestPermissionsResult에서 재시도)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), REQ_SEND_SMS)
            return
        }

        numbers.forEach { trySendSms(it, smsBody) }
    }

    private fun trySendSms(target: String, body: String) {
        try {
            @Suppress("DEPRECATION")
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(target, null, body, null, null)
            //Toast.makeText(this, "📨 $target 에게 문자 발송", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            //Toast.makeText(this, "문자 발송 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SEND_SMS &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            if (numbers.isNotEmpty() && smsBody.isNotBlank()) trySendSms(numbers[0], smsBody)
        }
    }

    // 벨소리
    private fun startRingtone() {
        val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val isHeadsetConnected = audioDevices.any { device ->
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        // TODO: 배포 시 streamType을 STREAM_RING 으로 고정
        val streamType = if (isHeadsetConnected) AudioManager.STREAM_MUSIC else AudioManager.STREAM_RING
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        try {
            ringtonePlayer = MediaPlayer().apply {
                setDataSource(applicationContext, ringtoneUri)
                @Suppress("DEPRECATION")
                setAudioStreamType(streamType)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun stopRingtone() {
        ringtonePlayer?.apply { if (isPlaying) stop(); release() }
        ringtonePlayer = null
    }

    // 저장된 목소리 재생
    private fun playVoiceRecording() {
        val file = audioFile
        if (!file.exists() || file.length() == 0L) return
        try {
            voicePlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                // STREAM_MUSIC -> 이어폰/블루투스로 출력됨
                @Suppress("DEPRECATION")
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                prepare(); start()
                setOnCompletionListener { stopVoicePlayer() }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun stopVoicePlayer() {
        runCatching { voicePlayer?.stop() }
        voicePlayer?.release(); voicePlayer = null
    }

    // 통화 중 화면 전환
    private fun showActiveCall() {
        findViewById<LinearLayout>(R.id.incoming_layout).visibility = View.GONE
        findViewById<LinearLayout>(R.id.active_layout).visibility   = View.VISIBLE
        startTimer()
    }

    private fun startTimer() {
        val timerTv = findViewById<TextView>(R.id.tv_call_timer)
        timerRunnable = object : Runnable {
            override fun run() {
                callSeconds++
                val min = callSeconds / 60; val sec = callSeconds % 60
                timerTv.text = "%02d:%02d".format(min, sec)
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(timerRunnable!!, 1000)
    }

    private fun stopTimer() {
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRingtone()
        stopVoicePlayer()
        stopTimer()
    }
}