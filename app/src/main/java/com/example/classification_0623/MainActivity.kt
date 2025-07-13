package com.example.classification_0623

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var resultTextView: TextView

    // 예측 결과 수신용 BroadcastReceiver
    private val predictionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val prediction = intent?.getIntExtra("prediction", -1)
            if (prediction != null && prediction > 0) {
                resultTextView.text = "예측 클래스: $prediction"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 화면이 꺼지지 않도록 설정
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // TextView 연결
        resultTextView = findViewById(R.id.resultTextView)

        // BroadcastReceiver 등록
        val filter = IntentFilter("com.example.classification_0623.PREDICTION_RESULT")
        registerReceiver(predictionReceiver, filter)

        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)

        startButton.setOnClickListener {
            val intent = Intent(this, SensorForegroundService::class.java)
            ContextCompat.startForegroundService(this, intent)
            Log.d("MainActivity", "ForegroundService 시작")
        }

        stopButton.setOnClickListener {
            val intent = Intent(this, SensorForegroundService::class.java)
            stopService(intent)
            Log.d("MainActivity", "ForegroundService 중지")
        }
    }

    // 리시버 해제
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(predictionReceiver)
    }
}
