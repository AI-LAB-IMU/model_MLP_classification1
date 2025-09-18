// ========================= MainActivity.kt =========================
package com.example.classification_0623

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var resultTextView: TextView

    // 예측 브로드캐스트를 UI에 반영할지 여부
    private var allowPredictionUpdates = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.classification_0623.PREDICTION_RESULT" -> {
                    if (!allowPredictionUpdates) return
                    val prediction = intent.getIntExtra("prediction", -1)
                    if (prediction in 0..4) {
                        resultTextView.text = "예측 클래스: ${prediction + 1}"
                    }
                }
                "com.example.classification_0623.SERVICE_STATUS" -> {
                    val running = intent.getBooleanExtra("running", true)
                    if (!running) {
                        allowPredictionUpdates = false
                        resultTextView.text = "측정대기"
                    }
                }
            }
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 필요 시 결과 처리 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        resultTextView = findViewById(R.id.resultTextView)

        val filter = IntentFilter().apply {
            addAction("com.example.classification_0623.PREDICTION_RESULT")
            addAction("com.example.classification_0623.SERVICE_STATUS")
        }
        registerReceiver(receiver, filter)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            allowPredictionUpdates = true
            val intent = Intent(this, SensorForegroundService::class.java)
            ContextCompat.startForegroundService(this, intent)
            Log.d("MainActivity", "ForegroundService 시작")
            resultTextView.text = "측정중"
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            allowPredictionUpdates = false
            val intent = Intent(this, SensorForegroundService::class.java)
            stopService(intent)
            Log.d("MainActivity", "ForegroundService 중지")
            resultTextView.text = "측정대기"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }
}
