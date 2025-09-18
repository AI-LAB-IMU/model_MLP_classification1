package com.example.classification_0623

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class SensorForegroundService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "SensorForegroundService"
        private const val NOTIF_CHANNEL_ID = "fg_channel"
        private const val NOTIF_ID = 1001

        private const val MODEL_ASSET = "model_classification.tflite"
        private const val SCALER_ASSET = "scaler.json"

        const val ACTION_SERVICE_STATUS = "com.example.classification_0623.SERVICE_STATUS"
        const val ACTION_BENCH_DONE = "com.example.classification_0623.BENCH_DONE"
    }

    private var mode: String = "realtime" // "realtime" | "benchmark"
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private var tflite: Interpreter? = null
    private var numClasses: Int = 5
    private var inputDim: Int = 4

    private var logWriter: BufferedWriter? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Running…"))

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // 스케일러/모델 로드 (Context를 넘겨야 함!)
        try {
            loadScalerFromAssets(SCALER_ASSET)
        } catch (e: Exception) {
            Log.w(TAG, "Scaler load warning: ${e.message}")
        }
        initTfliteFromAssets(MODEL_ASSET)

        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mode = intent?.getStringExtra("mode") ?: "realtime"
        Log.i(TAG, "onStartCommand mode=$mode")

        if (mode == "benchmark") {
            // 센서 비활성화 후 벤치마크만 수행
            try { sensorManager.unregisterListener(this) } catch (_: Exception) {}
            runBenchmarkAndQuit()
            return START_NOT_STICKY
        }

        // realtime: 센서 등록
        accelerometer?.let {
            // 고주파 샘플링(필요에 맞게 조정 가능)
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }

        openRealtimeLog()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { sensorManager.unregisterListener(this) } catch (_: Exception) {}
        try { logWriter?.close() } catch (_: Exception) {}
        tflite?.close()
        Log.d(TAG, "Service destroyed")
    }

    // ===== Notification =====
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID, "Foreground", NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Sensor Service")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun notifyBenchmarkResult(title: String, body: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val n = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(body.take(48))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(1002, n)
    }

    // ===== Model / Scaler =====
    private fun initTfliteFromAssets(assetPath: String) {
        // 반드시 Context(this)를 넘겨야 함!
        val mbb = FileUtil.loadMappedFile(this, assetPath)
        val opts = Interpreter.Options().apply {
            // XNNPACK 기본 사용 (useNNAPI=false)
            // setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
            // setUseNNAPI(false)
        }
        tflite = Interpreter(mbb, opts).also { itp ->
            try {
                inputDim = itp.getInputTensor(0).numElements()
                numClasses = itp.getOutputTensor(0).numElements()
            } catch (_: Exception) { /* keep defaults */ }
        }
        Log.i(TAG, "TFLite loaded: inputDim=$inputDim, numClasses=$numClasses")
    }

    private fun loadScalerFromAssets(assetPath: String) {
        // 존재 확인용
        assets.open(assetPath).use { it.readBytes() }
        Log.i(TAG, "Scaler found: $assetPath")
    }

    // ===== Realtime =====
    override fun onSensorChanged(event: android.hardware.SensorEvent?) {
        if (mode != "realtime" || event == null) return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val itp = tflite ?: return
        // TODO: 실제 윈도우링/피처추출 결과로 채우기
        val input = Array(1) { FloatArray(inputDim) { 0f } }
        val output = Array(1) { FloatArray(numClasses) }

        val t0 = android.os.SystemClock.elapsedRealtimeNanos()
        itp.run(input, output)
        val t1 = android.os.SystemClock.elapsedRealtimeNanos()
        val infMs = (t1 - t0) / 1_000_000.0

        try {
            logWriter?.apply {
                write("${System.currentTimeMillis()},$infMs")
                newLine()
                flush()
            }
        } catch (_: Exception) {}
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op
    }

    private fun openRealtimeLog() {
        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "tflite_realtime_$ts.csv")
            logWriter = BufferedWriter(FileWriter(file, true)).apply {
                write("timestamp_ms,inference_ms\n")
                flush()
            }
            Log.i(TAG, "Realtime log -> ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "openRealtimeLog error: ${e.message}")
        }
    }

    // ===== Benchmark (XNNPACK, N=2000) =====
    private fun runBenchmarkAndQuit() {
        val itp = tflite
        if (itp == null) {
            sendBenchDoneBroadcast(error = "TFLite not loaded (null)")
            notifyBenchmarkResult("Benchmark failed", "TFLite not loaded (null)")
            finishAndStop()
            return
        }

        try {
            // 입력/출력 텐서 크기 안전하게 가져오기
            val inputDimLocal = try { itp.getInputTensor(0).numElements() } catch (_: Exception) { inputDim }
            val numClassesLocal = try { itp.getOutputTensor(0).numElements() } catch (_: Exception) { numClasses }

            val input = Array(1) { FloatArray(inputDimLocal) { 0f } }
            val output = Array(1) { FloatArray(numClassesLocal) }

            // 메모리 피크 추적
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pid = android.os.Process.myPid()
            fun samplePssKb(): Int = am.getProcessMemoryInfo(intArrayOf(pid)).first().totalPss
            var peakKb = samplePssKb()

            // Warm-up
            repeat(50) { itp.run(input, output) }

            // 본 측정: 시간 단위를 Double(ms)로 저장
            val runs = 2000
            val times = ArrayList<Double>(runs)
            for (i in 0 until runs) {
                val t0 = android.os.SystemClock.elapsedRealtimeNanos()
                itp.run(input, output)
                val t1 = android.os.SystemClock.elapsedRealtimeNanos()
                val elapsedMs = (t1 - t0) / 1_000_000.0   // <-- Double(ms)
                times += elapsedMs
                if ((i and 0xF) == 0) peakKb = maxOf(peakKb, samplePssKb())
            }
            peakKb = maxOf(peakKb, samplePssKb())

            // 퍼센타일 계산 (정렬 후 인덱스 픽)
            times.sort()
            fun pct(q: Double): Double {
                val idx = (q * (times.size - 1)).coerceIn(0.0, (times.size - 1).toDouble()).toInt()
                return times[idx]
            }
            val p50 = pct(0.50)
            val p90 = pct(0.90)
            val p99 = pct(0.99)
            val peakMb = peakKb / 1024.0

            // CSV 저장 (소수점 3자리)
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
            val file = File(dir, "tflite_bench.csv")
            FileOutputStream(file, false).bufferedWriter().use { w ->
                w.appendLine("Configuration,p50(ms),p90(ms),p99(ms),Peak RSS(MB)")
                w.appendLine("XNNPACK (default),${"%.3f".format(p50)},${"%.3f".format(p90)},${"%.3f".format(p99)},${"%.2f".format(peakMb)}")
            }

            // 결과 공지: 브로드캐스트 + 알림
            sendBenchDoneBroadcast(path = file.absolutePath)
            notifyBenchmarkResult("Benchmark done", file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Benchmark error", e)
            sendBenchDoneBroadcast(error = e.message ?: "unknown error")
            notifyBenchmarkResult("Benchmark failed", e.message ?: "unknown error")
        } finally {
            finishAndStop()
        }
    }

    private fun sendBenchDoneBroadcast(path: String? = null, error: String? = null) {
        val intent = Intent(ACTION_BENCH_DONE).apply {
            path?.let { putExtra("path", it) }
            error?.let { putExtra("error", it) }
        }
        sendBroadcast(intent)
    }

    private fun finishAndStop() {
        sendBroadcast(Intent(ACTION_SERVICE_STATUS).apply { putExtra("running", false) })
        stopForeground(true)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
