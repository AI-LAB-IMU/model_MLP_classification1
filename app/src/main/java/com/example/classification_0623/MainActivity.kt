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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.classification_0623.ppg.PPGService

class MainActivity : AppCompatActivity() {

    private lateinit var resultTextView: TextView

    // 상태 라인 분리 (메인 상태 + PPG 진행상황)
    private var mainLine: String = "측정대기"
    private var ppgLine:  String = ""

    private val ACTION_PPG_UPDATE   = "PPG_UPDATE"
    private val EXTRA_ELAPSED_SECS  = "elapsed_seconds"
    private val EXTRA_CHUNK_COUNT   = "chunk_count"

    private fun renderStatus() {
        resultTextView.text = if (ppgLine.isBlank()) mainLine else "$mainLine\n$ppgLine"
    }

    // 서비스 종료 감지용 리시버
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.classification_0623.SERVICE_STATUS") {
                val running = intent.getBooleanExtra("running", true)
                if (!running) {
                    mainLine = "측정대기"
                    ppgLine  = ""
                    renderStatus()
                }
            }
        }
    }

    // PPG 진행상황 리시버
    private val ppgUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PPG_UPDATE) return
            val chunk = intent.getIntExtra(EXTRA_CHUNK_COUNT, 0)
            val time  = intent.getIntExtra(EXTRA_ELAPSED_SECS, 0)
            Log.d("MainActivity", "📥 PPG_UPDATE - chunk: $chunk, time: $time")
            ppgLine = "PPG chunk: $chunk | ${time}s"
            renderStatus()
        }
    }

    // 알림 권한 (Android 13+)
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 필요 시 결과 처리 */ }

    // 위치 + BODY_SENSORS 권한 요청
    private val requiredPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val fine      = perms[Manifest.permission.ACCESS_FINE_LOCATION]   == true
            val coarse    = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            val locationOk = fine || coarse
            val bodyOk     =
                perms[Manifest.permission.BODY_SENSORS] == true ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED

            if (locationOk && bodyOk) {
                startForegroundSvcs()
            } else {
                Log.w("MainActivity", "Required permissions denied. locationOk=$locationOk, bodyOk=$bodyOk")
                mainLine = "권한 필요"
                ppgLine  = ""
                renderStatus()
                Toast.makeText(this, "위치/센서 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }

    private fun hasLocationPermission(): Boolean {
        val fine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun hasBodySensorsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED

    private fun hasRequiredPermissions(): Boolean = hasLocationPermission() && hasBodySensorsPermission()

    private fun startForegroundSvcs() {
        // 1) IMU (raw x,y,z → 서버 전송)
        val imuIntent = Intent(this, SensorForegroundService::class.java)
        ContextCompat.startForegroundService(this, imuIntent)

        // 2) PPG
        val ppgIntent = Intent(this, PPGService::class.java).apply {
            putExtra("subject_number", "0")
            putExtra("subject_name", "unknown")
        }
        ContextCompat.startForegroundService(this, ppgIntent)

        Log.d("MainActivity", "ForegroundService 시작: IMU(raw) + PPG")
        mainLine = "측정중"
        renderStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON      or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD  or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED  or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        resultTextView = findViewById(R.id.resultTextView)
        renderStatus()

        // SERVICE_STATUS 리시버 등록
        val filter = IntentFilter("com.example.classification_0623.SERVICE_STATUS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        // PPG_UPDATE 리시버 등록
        val ppgFilter = IntentFilter(ACTION_PPG_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ppgUpdateReceiver, ppgFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(ppgUpdateReceiver, ppgFilter)
        }

        // 알림 권한 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Start 버튼
        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (!hasRequiredPermissions()) {
                requiredPermLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.BODY_SENSORS
                    )
                )
            } else {
                startForegroundSvcs()
            }
        }

        // Stop 버튼
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, SensorForegroundService::class.java))
            stopService(Intent(this, PPGService::class.java))

            Log.d("MainActivity", "ForegroundService 중지: IMU + PPG")
            mainLine = "측정대기"
            ppgLine  = ""
            renderStatus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        unregisterReceiver(ppgUpdateReceiver)
    }
}