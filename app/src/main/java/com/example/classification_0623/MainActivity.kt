package com.example.classification_0623

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var resultTextView: TextView
    private var allowPredictionUpdates: Boolean = true

    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SERVICE_STATUS -> {
                    val running = intent.getBooleanExtra("running", false)
                    if (!running) {
                        // 서비스 종료 알림만 왔을 때
                        if (::resultTextView.isInitialized && resultTextView.text.isNullOrBlank()) {
                            resultTextView.text = "완료"
                        }
                    }
                }
                ACTION_BENCH_DONE -> {
                    val err = intent.getStringExtra("error")
                    val path = intent.getStringExtra("path")
                    resultTextView.text = when {
                        err != null -> "벤치 실패: $err"
                        path != null -> "벤치 완료\n$path"
                        else -> "벤치 완료(상세 없음)"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        resultTextView = findViewById(R.id.resultTextView)

        // 짧게 누르면: 기존 실시간 모드 시작
        findViewById<Button>(R.id.startButton).setOnClickListener {
            allowPredictionUpdates = true
            val intent = Intent(this, SensorForegroundService::class.java).apply {
                putExtra("mode", "realtime")
            }
            ContextCompat.startForegroundService(this, intent)
            Log.d(TAG, "ForegroundService 시작 (realtime)")
            resultTextView.text = "실시간 측정 중…"
        }

        // 길게 누르면: 벤치마크 모드
        findViewById<Button>(R.id.startButton).setOnLongClickListener {
            allowPredictionUpdates = false
            val intent = Intent(this, SensorForegroundService::class.java).apply {
                putExtra("mode", "benchmark")
            }
            ContextCompat.startForegroundService(this, intent)
            Log.d(TAG, "Benchmark 시작")
            resultTextView.text = "벤치마크 중… (수십 초)"
            true
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            allowPredictionUpdates = false
            val intent = Intent(this, SensorForegroundService::class.java)
            stopService(intent)
            Log.d(TAG, "ForegroundService 중지")
            resultTextView.text = "측정대기"
        }
    }

    override fun onResume() {
        super.onResume()
        // 브로드캐스트 등록
        val filter = IntentFilter().apply {
            addAction(ACTION_SERVICE_STATUS)
            addAction(ACTION_BENCH_DONE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(serviceStatusReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(serviceStatusReceiver, filter)
        }

        // ★ 최근 벤치마크 결과 자동 표시
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val latest: File? = dir
            ?.listFiles { f -> f.name.startsWith("tflite_bench") && f.name.endsWith(".csv") }
            ?.maxByOrNull { it.lastModified() }
        latest?.let {
            // 이미 "벤치 완료" 등으로 채워져 있으면 덮어쓰지 않음
            if (resultTextView.text.isBlank() || resultTextView.text.startsWith("실시간")) {
                resultTextView.text = "최근 벤치 결과\n${it.absolutePath}"
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(serviceStatusReceiver)
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "MainActivity"
        const val ACTION_SERVICE_STATUS = "com.example.classification_0623.SERVICE_STATUS"
        const val ACTION_BENCH_DONE = "com.example.classification_0623.BENCH_DONE"
    }
}
