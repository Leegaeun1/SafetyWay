package com.example.safetyway

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class FakeCallSettingActivity : AppCompatActivity() {

    companion object {
        const val PREF_NAME        = "fake_call_prefs"
        const val KEY_CALLER_NAME  = "caller_name"
        const val KEY_HAS_AUDIO    = "has_audio"
        const val KEY_NUMBERS      = "numbers"        // "|" 구분자로 이어붙여 저장
        const val KEY_SMS_BODY     = "sms_body"
        const val AUDIO_FILENAME   = "fake_call_voice.m4a"
        private const val REQ_RECORD_AUDIO = 201
    }

    // 뷰
    private lateinit var etCallerName       : EditText
    private lateinit var tvRecordStatus     : TextView
    private lateinit var tvRecordTimer      : TextView
    private lateinit var btnRecord          : Button
    private lateinit var btnPlay            : Button
    private lateinit var btnDeleteAudio     : ImageButton
    private lateinit var layoutAudioControls: LinearLayout
    private lateinit var llNumberList       : LinearLayout   // 번호 행들이 들어갈 컨테이너
    private lateinit var etNewNumber        : EditText
    private lateinit var btnAddNumber       : ImageButton
    private lateinit var etSmsBody          : EditText
    private lateinit var btnSave            : Button

    // 번호 목록
    private val numbers = mutableListOf<String>()

    // 녹음
    private var recorder    : MediaRecorder? = null
    private var player      : MediaPlayer?   = null
    private var isRecording = false
    private var isPlaying   = false

    private val handler = Handler(Looper.getMainLooper())
    private var recordSeconds = 0
    private var timerRunnable: Runnable? = null

    private val audioFile: File get() = File(filesDir, AUDIO_FILENAME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fake_call_setting)

        bindViews()
        loadSavedPrefs()
        refreshAudioUi()
        renderNumberList()
        findViewById<Button>(R.id.btn_data_source_in_setting).setOnClickListener {
            val dialogView = layoutInflater.inflate(R.layout.dialog_data_source, null)
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(dialogView)
                .create()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialogView.findViewById<Button>(R.id.btn_dialog_confirms)
                .setOnClickListener { dialog.dismiss() }
            dialog.show()
        }
        btnRecord.setOnClickListener      { onRecordClicked() }
        btnPlay.setOnClickListener        { onPlayClicked()   }
        btnDeleteAudio.setOnClickListener { onDeleteAudio()   }
        btnAddNumber.setOnClickListener   { onAddNumber()     }
        btnSave.setOnClickListener        { onSave()          }
    }

    // 뷰 바인딩
    private fun bindViews() {
        etCallerName        = findViewById(R.id.et_caller_name)
        tvRecordStatus      = findViewById(R.id.tv_record_status)
        tvRecordTimer       = findViewById(R.id.tv_record_timer)
        btnRecord           = findViewById(R.id.btn_record)
        btnPlay             = findViewById(R.id.btn_play_preview)
        btnDeleteAudio      = findViewById(R.id.btn_delete_audio)
        layoutAudioControls = findViewById(R.id.layout_audio_controls)
        llNumberList        = findViewById(R.id.ll_number_list)
        etNewNumber         = findViewById(R.id.et_new_number)
        btnAddNumber        = findViewById(R.id.btn_add_number)
        etSmsBody           = findViewById(R.id.et_sms_body)
        btnSave             = findViewById(R.id.btn_save_setting)
    }

    // 저장된 설정 불러오기
    private fun loadSavedPrefs() {
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        etCallerName.setText(prefs.getString(KEY_CALLER_NAME, "아빠") ?: "아빠")
        etSmsBody.setText(prefs.getString(KEY_SMS_BODY, "지금 귀가 중이야. 곧 도착해!") ?: "")
        val saved = prefs.getString(KEY_NUMBERS, "") ?: ""
        numbers.clear()
        if (saved.isNotEmpty()) numbers.addAll(saved.split("|").filter { it.isNotBlank() })
    }

    // 번호 목록 렌더링
    private fun renderNumberList() {
        llNumberList.removeAllViews() // 전체 다시 그리도록 초기화
        numbers.forEachIndexed { index, number ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
            } // 번호 한줄 = 한개의 가로 LinearLayout.

            // 순서 태그 (첫 번째면 "문자발송" 표시)
            val tvTag = TextView(this).apply {
                text = if (index == 0) "📲" else "  "
                textSize = 14f
                setPadding(0, 0, 8, 0)
            }

            val tvNumber = TextView(this).apply {
                text = number
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            // 위로 버튼 (첫 번째 아니면 표시)
            val btnUp = ImageButton(this).apply {
                setImageResource(android.R.drawable.arrow_up_float)
                background = null
                layoutParams = LinearLayout.LayoutParams(72, 72)
                visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
                setOnClickListener {
                    numbers.removeAt(index)
                    numbers.add(index - 1, number)
                    renderNumberList()
                }
            }

            // 삭제 버튼
            val btnDel = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                background = null
                layoutParams = LinearLayout.LayoutParams(72, 72)
                setOnClickListener {
                    numbers.removeAt(index)
                    renderNumberList()
                }
            }

            row.addView(tvTag)
            row.addView(tvNumber)
            row.addView(btnUp)
            row.addView(btnDel)
            llNumberList.addView(row)

            // 구분선
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.setMargins(0, 4, 0, 4) }
                setBackgroundColor(0xFF2a3040.toInt())
            }
            llNumberList.addView(divider)
        }
    }

    // 번호 추가
    private fun onAddNumber() {
        val num = etNewNumber.text.toString().trim()
        if (num.isEmpty()) {
            Toast.makeText(this, "번호를 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }
        numbers.add(num) // 번호 추가
        etNewNumber.text.clear() // 칸 비우기
        renderNumberList() // 갱신
    }

    // 녹음 파일 유무에 따라 UI 갱신
    private fun refreshAudioUi() {
        val hasAudio = audioFile.exists() && audioFile.length() > 0
        layoutAudioControls.visibility = if (hasAudio) View.VISIBLE else View.GONE
        if (hasAudio) {
            tvRecordStatus.text = "✅ 녹음 완료 — 받기 누르면 자동 재생돼요"
            btnRecord.text      = "🔄 다시 녹음"
        } else {
            tvRecordStatus.text = "아직 녹음된 목소리가 없어요"
            btnRecord.text      = "🎙 녹음 시작"
        }
    }

    // 녹음 버튼
    private fun onRecordClicked() {
        if (isRecording) { // 녹음중에 다시 누르면 stop
            stopRecording()
        } else { // 녹음X
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) // 허용
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO
                )
                return
            }
            startRecording()
        }
    }

    private fun startRecording() { // 녹음시작!
        stopPlayer()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            recorder = MediaRecorder(this).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
        }
        isRecording = true
        btnRecord.text      = "⏹ 녹음 중단"
        tvRecordStatus.text = "🔴 녹음 중..."
        tvRecordTimer.visibility = View.VISIBLE
        recordSeconds = 0
        startRecordTimer()
    }

    private fun stopRecording() { // 녹음 중단!
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        isRecording = false
        stopRecordTimer()
        tvRecordTimer.visibility = View.GONE
        refreshAudioUi()
    }

    private fun startRecordTimer() { // 타이머 실행
        timerRunnable = object : Runnable {
            override fun run() {
                recordSeconds++
                val m = recordSeconds / 60
                val s = recordSeconds % 60
                tvRecordTimer.text = "%02d:%02d".format(m, s)
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(timerRunnable!!, 1000)
    }

    private fun stopRecordTimer() { // 타이머 중단
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
    }

    // 미리 듣기
    private fun onPlayClicked() {
        if (isPlaying) { stopPlayer(); return } // 다시누르면 멈춤
        if (!audioFile.exists()) return
        player = MediaPlayer().apply {
            setDataSource(audioFile.absolutePath)
            prepare(); start()
            setOnCompletionListener { stopPlayer() }
        }
        isPlaying = true
        btnPlay.text = "⏹ 중단"
    }

    private fun stopPlayer() {
        runCatching { player?.stop() }
        player?.release(); player = null
        isPlaying = false
        btnPlay.text = "▶ 미리 듣기"
    }

    // 녹음 삭제
    private fun onDeleteAudio() {
        stopPlayer() // 일단 멈춤
        if (audioFile.exists()) audioFile.delete()
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_HAS_AUDIO, false).apply()
        refreshAudioUi()
        Toast.makeText(this, "녹음이 삭제됐어요", Toast.LENGTH_SHORT).show()
    }

    // 저장
    private fun onSave() {
        val name = etCallerName.text.toString().trim().ifEmpty { "아빠" }
        val hasAudio = audioFile.exists() && audioFile.length() > 0
        val smsBody = etSmsBody.text.toString().trim()
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
            .putString(KEY_CALLER_NAME, name)
            .putBoolean(KEY_HAS_AUDIO, hasAudio)
            .putString(KEY_NUMBERS, numbers.joinToString("|"))
            .putString(KEY_SMS_BODY, smsBody)
            .apply()
        Toast.makeText(this, "저장됐어요 ✔", Toast.LENGTH_SHORT).show()
        finish()
    }

    // 권한 콜백
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) startRecording()
        else Toast.makeText(this, "마이크 권한이 필요해요", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) stopRecording()
        stopPlayer()
        stopRecordTimer()
    }
}