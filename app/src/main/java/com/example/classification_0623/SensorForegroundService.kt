// ==================== SensorForegroundService.kt ====================
package com.example.classification_0623

import android.Manifest
import com.example.classification_0623.geo.GeoReporter
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class SensorForegroundService : Service(), SensorEventListener {

    private val TAG = "IMURawUpload"

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // ===================== IMU 윈도우/슬라이드 설정 =====================
    // 센서 요청 주기: 50Hz
    // FIR AA 필터 + 2:1 디시메이션 → 최종 25Hz
    //
    // windowSize = 300샘플 = 12초 @ 25Hz
    // slideSize  = 150샘플 = 6초 @ 25Hz
    //
    // 즉, 12초 윈도우를 만들고 6초씩 밀어서 전송
    // => 50% overlap
    private val windowSize = 300
    private val slideSize  = 150

    // FIR 디시메이션 후 25Hz 샘플 버퍼 (x, y, z)
    private val buffer          = mutableListOf<FloatArray>()

    // 각 25Hz 샘플의 타임스탬프 버퍼 (start/end 추출용)
    private val timestampBuffer = mutableListOf<String>()

    private var windowCounter = 0

    // 50Hz → 25Hz AA FIR + 2:1 디시메이터
    private val HB_TAPS = floatArrayOf(
        0.00010228f, -0.00012024f, -0.00024596f, 0.00011137f, 0.00045327f, -0.00000000f, -0.00069560f, -0.00026395f,
        0.00091269f, 0.00071509f, -0.00101310f, -0.00135490f, 0.00088289f, 0.00213500f, -0.00040322f, -0.00294544f,
        -0.00052425f, 0.00361219f, 0.00194731f, -0.00390655f, -0.00383298f, 0.00356775f, 0.00604264f, -0.00233712f,
        -0.00831752f, 0.00000000f, 0.01027755f, 0.00357098f, -0.01143349f, -0.00837851f, 0.01120646f, 0.01427664f,
        -0.00894053f, -0.02096597f, 0.00387657f, 0.02801127f, 0.00498968f, -0.03488098f, -0.01939832f, 0.04100433f,
        0.04361350f, -0.04583849f, -0.09347310f, 0.04893612f, 0.31401166f, 0.45002595f, 0.31401166f, 0.04893612f,
        -0.09347310f, -0.04583849f, 0.04361350f, 0.04100433f, -0.01939832f, -0.03488098f, 0.00498968f, 0.02801127f,
        0.00387657f, -0.02096597f, -0.00894053f, 0.01427664f, 0.01120646f, -0.00837851f, -0.01143349f, 0.00357098f,
        0.01027755f, 0.00000000f, -0.00831752f, -0.00233712f, 0.00604264f, 0.00356775f, -0.00383298f, -0.00390655f,
        0.00194731f, 0.00361219f, -0.00052425f, -0.00294544f, -0.00040322f, 0.00213500f, 0.00088289f, -0.00135490f,
        -0.00101310f, 0.00071509f, 0.00091269f, -0.00026395f, -0.00069560f, -0.00000000f, 0.00045327f, 0.00011137f,
        -0.00024596f, -0.00012024f, 0.00010228f
    )

    private lateinit var decimator: FirDecimator3D

    // ===================== 서버 연동 설정 =====================
    private val BASE_URL        = "http://210.125.91.90:8000"
    private val IMU_RAW_PATH    = "/api/v1/events/imu-raw"
    private val GEO_ALERT_PATH  = "/api/v1/events/geo-alert"
    private val GEO_INTERVAL_MS = 5 * 60 * 1000L

    private var geoReporter: GeoReporter? = null
    private var geoStarted = false

    private var deviceId: String = "unknown"

    private val httpClient: OkHttpClient = OkHttpClient()

    // 타임스탬프
    private var baseWallMs: Long = 0L
    private var baseNano:   Long = 0L
    private val sdfMillis = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // CSV 로그
    private lateinit var rawLogFile:   File
    private lateinit var rawLogWriter: BufferedWriter

    // device_id 유틸
    private fun normalizedModel(): String {
        val m = (Build.MODEL ?: Build.DEVICE ?: "unknown").trim()
        return m.replace("\\s+".toRegex(), "").replace("/", "_")
    }

    private fun ensurePrefixed(id: String): String {
        if (id.contains("_")) return id
        return "${normalizedModel()}_${id}"
    }

    private fun getOrCreateDeviceIdInService(): String {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val saved = prefs.getString("device_id", null)
        if (!saved.isNullOrBlank()) {
            val fixed = ensurePrefixed(saved)
            if (fixed != saved) prefs.edit().putString("device_id", fixed).apply()
            return fixed
        }
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val base = androidId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val id = ensurePrefixed(base)
        prefs.edit().putString("device_id", id).apply()
        return id
    }

    // 위치/GPS 디버그 유틸
    private fun hasLocationPerm(): Boolean {
        val fine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotification()

        baseWallMs = System.currentTimeMillis()
        baseNano   = System.nanoTime()

        if (deviceId == "unknown") deviceId = getOrCreateDeviceIdInService()

        // GEO
        geoReporter = GeoReporter(
            context           = this,
            httpClient        = httpClient,
            baseUrl           = BASE_URL,
            path              = GEO_ALERT_PATH,
            intervalMs        = GEO_INTERVAL_MS,
            deviceIdProvider  = { deviceId },
            timestampProvider = { isoUtcNow() }
        )

        // ===================== IMU 센서 등록 =====================
        // 50Hz로 센서 데이터를 요청한다.
        // Android SensorManager의 samplingPeriodUs는 마이크로초 단위.
        // 20,000us = 0.02초 = 50Hz
        //
        // 이후 FirDecimator3D에서 2:1 디시메이션을 적용해서
        // 최종 25Hz IMU 샘플만 버퍼에 저장한다.
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, 20_000, 0)
        }

        // FIR 디시메이터 초기화
        // 입력 50Hz → 출력 25Hz
        decimator = FirDecimator3D(HB_TAPS, 2)

        // CSV 로그
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
        val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        rawLogFile   = File(dir, "imu_raw_${ts}.csv")
        rawLogWriter = BufferedWriter(FileWriter(rawLogFile, true))
        rawLogWriter.write("timestamp,x,y,z\n")
        rawLogWriter.flush()

        Log.d(TAG, "Service created device_id=$deviceId")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val raw = intent?.getStringExtra("device_id") ?: getOrCreateDeviceIdInService()
        deviceId = ensurePrefixed(raw)
        getSharedPreferences("app_prefs", MODE_PRIVATE)
            .edit()
            .putString("device_id", deviceId)
            .apply()

        Log.i(TAG, "onStartCommand device_id=$deviceId")
        Log.i(
            TAG,
            "GEO debug: geoReporterNull=${geoReporter == null}, geoStarted=$geoStarted, perm=${hasLocationPerm()}, locEnabled=${isLocationEnabled()}"
        )

        if (!geoStarted && geoReporter != null) {
            geoReporter!!.start()
            geoStarted = true
            Log.i(TAG, "GeoReporter started")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        runCatching { geoReporter?.stop() }
        geoReporter = null
        geoStarted  = false

        sendBroadcast(Intent("com.example.classification_0623.SERVICE_STATUS").apply {
            putExtra("running", false)
        })

        sensorManager.unregisterListener(this)

        if (::rawLogWriter.isInitialized) {
            runCatching { rawLogWriter.close() }
        }

        stopForeground(true)

        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ===================== IMU 센서 이벤트 =====================
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // 50Hz → FIR AA 필터 → 25Hz 디시메이션
        val out = decimator.process(x, y, z) ?: return

        val ts = isoUtcNow()

        // CSV 로컬 기록 (25Hz 기준)
        rawLogWriter.write("$ts,${out[0]},${out[1]},${out[2]}\n")
        rawLogWriter.flush()

        // 25Hz 버퍼에 누적
        buffer.add(out)
        timestampBuffer.add(ts)

        // 300샘플 도달 시 윈도우 전송
        // 300 samples @ 25Hz = 12초
        if (buffer.size >= windowSize) {
            windowCounter++

            val windowSamples = buffer.subList(0, windowSize).toList()
            val startTs = timestampBuffer.first()               // 윈도우 첫 샘플 시각
            val endTs   = timestampBuffer[windowSize - 1]       // 윈도우 마지막 샘플 시각

            sendWindowToServer(windowSamples, startTs, endTs)

            // 150샘플만 제거
            // 150 samples @ 25Hz = 6초
            // 즉, 12초 윈도우에서 6초를 겹치게 유지하므로 50% overlap
            buffer.subList(0, slideSize).clear()
            timestampBuffer.subList(0, slideSize).clear()

            Log.d(
                TAG,
                "window=$windowCounter sent | samples=${windowSamples.size} @ 25Hz | window=12s | overlap=50% | slide=6s"
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ===================== 서버 전송 =====================
    private fun sendWindowToServer(
        samples: List<FloatArray>,
        startTimestamp: String,
        endTimestamp: String
    ) {
        val url = BASE_URL + IMU_RAW_PATH

        val samplesArray = JSONArray()
        for (sample in samples) {
            samplesArray.put(
                JSONArray().apply {
                    put(sample[0].toDouble()) // x
                    put(sample[1].toDouble()) // y
                    put(sample[2].toDouble()) // z
                }
            )
        }

        val body = JSONObject().apply {
            put("device_id",       deviceId)
            put("window_index",    windowCounter)
            put("start_timestamp", startTimestamp)
            put("end_timestamp",   endTimestamp)

            // 최종 서버 전송 기준:
            // 25Hz, 12초, 300 samples
            put("sample_rate",     25)
            put("window_sec",      12)

            put("samples",         samplesArray)
        }.toString()

        Log.i(
            TAG,
            "IMU POST -> $url | window=$windowCounter | ${samples.size} samples | 25Hz | 12s | 50% overlap"
        )
        Log.d(TAG, "IMU POST body preview -> ${body.take(500)}")

        val req = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "IMU POST failed: ${e.message}", e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val respBody = res.body?.string()
                    Log.d(
                        TAG,
                        "IMU POST response code=${res.code} window=$windowCounter body=$respBody"
                    )
                }
            }
        })
    }

    // ===================== 타임스탬프 유틸 =====================
    // 서버 전송/CSV 공용 ISO 8601 UTC
    private fun isoUtcNow(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } else {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())
        }
    }

    // ===================== 포그라운드 알림 =====================
    private fun createNotification() {
        val channelId = "sensor_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Sensor Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("IMU 수집 중")
            .setContentText("가속도 데이터를 서버로 전송 중입니다.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
        startForeground(1, notification)
    }
}

// ===================== FIR 디시메이터 =====================
private class FirDecimator3D(
    private val h: FloatArray,
    private val M: Int
) {
    private val L = h.size
    private val xb = FloatArray(L)
    private val yb = FloatArray(L)
    private val zb = FloatArray(L)
    private var idx     = 0
    private var inCount = 0

    fun process(x: Float, y: Float, z: Float): FloatArray? {
        xb[idx] = x
        yb[idx] = y
        zb[idx] = z

        idx = (idx + 1) % L
        inCount++

        if (inCount < L) return null
        if (((inCount - L) % M) != 0) return null

        var sx = 0f
        var sy = 0f
        var sz = 0f

        var p = idx
        for (k in 0 until L) {
            p = if (p == 0) L - 1 else p - 1
            val hk = h[k]
            sx += hk * xb[p]
            sy += hk * yb[p]
            sz += hk * zb[p]
        }
        return floatArrayOf(sx, sy, sz)
    }
}