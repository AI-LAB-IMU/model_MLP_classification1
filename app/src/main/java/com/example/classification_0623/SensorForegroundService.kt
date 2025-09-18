// ==================== SensorForegroundService.kt ====================
package com.example.classification_0623

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.collections.ArrayDeque


class SensorForegroundService : Service(), SensorEventListener {

    private val TAG = "RealtimePredict"

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // 50 Hz 윈도우(6s/3s)
    private val windowSize = 300
    private val slideSize  = 150
    private val buffer = mutableListOf<FloatArray>()   // FIR+디시메이션 후 50 Hz 샘플(x,y,z)

    private var windowCounter = 0

    // CSV 로그
    private lateinit var predictionLogFile: File
    private lateinit var predictionLogWriter: BufferedWriter

    // === TFLite 모델 (assets/model_classification.tflite) ===
    private lateinit var tflite: Interpreter
    private val numClasses = 5
    // 모델 출력이 확률(softmax 내장)이라고 가정
    private val MODEL_OUTPUT_IS_PROBS = true

    // 스케일러(원특징 기준 4개 평균/표준편차)
    private lateinit var mu4: FloatArray           // length 4
    private lateinit var sigma4: FloatArray        // length 4

    // === 보수화 파라미터(기존) ===
    private val TH_P5 = 0.90f
    private val TH_P4 = 0.70f
    private val MARGIN_P5 = 0.30f
    private val MARGIN_P4 = 0.18f
    private val GATE_Z_STD   = 0.80f
    private val GATE_Z_DSTD  = 0.90f
    private val TH_P3 = 0.50f
    private val MARGIN_P3 = 0.08f
    private val TH_ACTIVITY = 9.85f
    private val TH_D_ACTIVITY = 0.15f
    private val TH_ACTIVE_STD = 1.0f
    private val TH_ACTIVE_DSTD = 1.2f

    // --- 2↔3 보수화(중강도 밴드) ---
    private val STRONG_P3 = 0.65f
    private val STRONG_MARGIN_P3 = 0.15f
    private val MOD_STD_MIN = 0.30f
    private val MOD_DSTD_MIN = 0.30f

    // --- 2↔3 히스테리시스 & 스파이크(“털기/지우기”) 가드 & 간단 스무딩 ---
    private val HYST_UP_CONSEC = 2      // 3으로 올릴 땐 최소 2 창 연속 충족
    private val HYST_DOWN_CONSEC = 1    // 2로 내릴 땐 1 창이면 충분
    private val TH_P3_UP = 0.55f        // 상승 문턱
    private val TH_P3_DOWN = 0.45f      // 하강 문턱
    private val MARGIN_P3_UP = 0.10f
    private val MARGIN_P3_DOWN = 0.05f

    private val SHAKE_DSTD_HIGH = 1.60f           // x4(d_svm_std) 매우 큼
    private val SHAKE_MEAN_MAX = TH_ACTIVITY + 0.8f  // 평균 활동은 낮음
    private val P3_STRONG_MIN = 0.60f             // p3 강증거 기준
    private val JERK_RATIO_TH = 1.30f             // x4/(x2+eps) 비정상적 비율

    private val recentP = ArrayDeque<FloatArray>()   // 최근 3창 [p1..p5]
    private val recentX = ArrayDeque<FloatArray>()   // 최근 3창 [x1..x4]
    private val recentGradeCand = ArrayDeque<Int>()  // 최근 3창 grade 후보(2/3용)
    private var consec3Cond = 0
    private var consec2Cond = 0
    private var state23 = 2  // 2/3 상태 기억 (초기 2)

    // === 타임스탬프(마이크로초 표기용) ===
    private var baseWallMs: Long = 0L
    private var baseNano: Long = 0L
    private val sdfMillis = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // === 100→50 Hz AA FIR + 2:1 디시메이터 ===
    // Kaiser(β=5.65), fs_in=100 Hz, cutoff≈22.5 Hz, numtaps=91
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

    override fun onCreate() {
        super.onCreate()
        createNotification()

        baseWallMs = System.currentTimeMillis()
        baseNano = System.nanoTime()

        // 센서 매니저/가속도계
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            // 100 Hz 요청(10,000 µs)
            sensorManager.registerListener(this, it, 10_000, 0)
        }

        // FIR 디시메이터 초기화 (100 -> 50 Hz)
        decimator = FirDecimator3D(HB_TAPS, 2)

        // 스케일러/모델 로드 (assets/scaler.json, assets/model_classification.tflite)
        loadScalerFromAssets("scaler.json")
        initTfliteFromAssets("model_classification.tflite")

        // CSV 로그: 앱 전용 외부 디렉토리(권한 불필요)
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        predictionLogFile = File(dir, "tflite_realtime_${timestamp}.csv")
        predictionLogWriter = BufferedWriter(FileWriter(predictionLogFile, true))
        predictionLogWriter.write(
            "timestamp,window,grade," +
                    "p1,p2,p3,p4,p5," +
                    "svm_mean,svm_std,d_svm_mean,d_svm_std\n"
        )
        predictionLogWriter.flush()

        Log.d(TAG, "Service created")
    }

    override fun onDestroy() {
        super.onDestroy()
        // 브로드캐스트로 "중단됨" 알려서 액티비티가 UI를 고정하도록
        sendBroadcast(Intent("com.example.classification_0623.SERVICE_STATUS").apply {
            putExtra("running", false)
        })

        sensorManager.unregisterListener(this)
        if (::predictionLogWriter.isInitialized) runCatching { predictionLogWriter.close() }
        if (::tflite.isInitialized) runCatching { tflite.close() }
        // 포그라운드 알림 제거
        stopForeground(true)

        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]

        // 100 Hz 입력 → FIR AA → 2:1 디시메이션 → 50 Hz 출력
        val out = decimator.process(x, y, z)
        if (out != null) {
            buffer.add(out) // out: floatArrayOf(x50, y50, z50)

            if (buffer.size >= windowSize) {
                val window = buffer.subList(0, windowSize).toList()
                processWindow(window)
                buffer.subList(0, slideSize).clear()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ===== 작은 유틸들 =====
    private fun pushFixed(q: ArrayDeque<FloatArray>, v: FloatArray, maxLen: Int) {
        q.addLast(v); while (q.size > maxLen) q.removeFirst()
    }
    private fun pushFixedInt(q: ArrayDeque<Int>, v: Int, maxLen: Int) {
        q.addLast(v); while (q.size > maxLen) q.removeFirst()
    }
    private fun meanOf(q: ArrayDeque<FloatArray>): FloatArray {
        val head = q.firstOrNull() ?: return floatArrayOf()
        val m = FloatArray(head.size)
        for (a in q) {
            for (i in a.indices) {
                m[i] += a[i]
            }
        }
        val n = q.size.toFloat()
        for (i in m.indices) {
            m[i] /= n
        }
        return m
    }

    private fun median3(a: Int, b: Int, c: Int): Int {
        val x = intArrayOf(a,b,c); java.util.Arrays.sort(x); return x[1]
    }

    private fun processWindow(window: List<FloatArray>) {
        windowCounter += 1
        if (window.size < 4) return

        // 1) 원특징 4개 (SVM/ΔSVM)
        val svm = FloatArray(window.size) { i ->
            val v = window[i]
            sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])
        }
        val dsvm = FloatArray(window.size - 1) { i -> abs(svm[i + 1] - svm[i]) }

        val x1 = mean(svm)           // svm_mean
        val x2 = stdPop(svm)         // svm_std (ddof=0)
        val x3 = mean(dsvm)          // d_svm_mean
        val x4 = stdPop(dsvm)        // d_svm_std (ddof=0)
        val raw4 = floatArrayOf(x1, x2, x3, x4)

        // 2) 표준화(4)  → TFLite 입력은 정규화된 4D
        val norm4 = FloatArray(4) { i -> (raw4[i] - mu4[i]) / (if (sigma4[i]==0f) 1f else sigma4[i]) }

        // 3) TFLite 추론 (입력 1x4, 출력 1x5)
        val input = arrayOf(norm4)                // shape: [1,4]
        val out = Array(1) { FloatArray(numClasses) }  // shape: [1,5]
        tflite.run(input, out)

        // 모델이 확률을 직접 출력한다고 가정
        val p = if (MODEL_OUTPUT_IS_PROBS) out[0] else softmax(out[0])

        // === 공통 계산 ===
        val p1 = p.getOrElse(0){0f}; val p2 = p.getOrElse(1){0f}
        val p3c = p.getOrElse(2){0f}; val p4 = p.getOrElse(3){0f}; val p5 = p.getOrElse(4){0f}
        val baseArgmax = p.indices.maxByOrNull { p[it] } ?: 0
        val baseGrade = (baseArgmax + 1).coerceIn(1, 5) // 1..5

        // 피처 게이트
        val zStd  = if (sigma4[1] != 0f) (x2 - mu4[1]) / sigma4[1] else 0f
        val zDStd = if (sigma4[3] != 0f) (x4 - mu4[3]) / sigma4[3] else 0f
        val allowHigh = (abs(zStd) >= GATE_Z_STD) || (abs(zDStd) >= GATE_Z_DSTD)

        val isStatic = (x1 < TH_ACTIVITY) && (x3 < TH_D_ACTIVITY)
        val isActiveAny = (x2 >= TH_ACTIVE_STD) || (x4 >= TH_ACTIVE_DSTD)

        // 최근 3창 평균으로 약간 스무딩
        pushFixed(recentP, floatArrayOf(p1,p2,p3c,p4,p5), 3)
        pushFixed(recentX, floatArrayOf(x1,x2,x3,x4), 3)
        val pMean = meanOf(recentP) // [mp1..mp5]
        val xMean = meanOf(recentX) // [mx1..mx4]
        val mp1 = pMean.getOrElse(0){p1}; val mp2 = pMean.getOrElse(1){p2}; val mp3 = pMean.getOrElse(2){p3c}
        val mx1 = xMean.getOrElse(0){x1}; val mx2 = xMean.getOrElse(1){x2}; val mx4 = xMean.getOrElse(3){x4}

        val maxLow = max(mp1, mp2)
        val allow3Up   = (mp3 >= TH_P3_UP)   && ((mp3 - maxLow) >= MARGIN_P3_UP)
        val allow3Down = (mp3 <  TH_P3_DOWN) ||  ((mp3 - maxLow) <  MARGIN_P3_DOWN)

        // jerk guard: 짧은 강한 흔들림이면 2로 유지
        val jerkRatio = mx4 / (mx2 + 1e-6f)
        val isJerkLike = (x4 >= SHAKE_DSTD_HIGH || mx4 >= SHAKE_DSTD_HIGH) &&
                (x1 <= SHAKE_MEAN_MAX && mx1 <= SHAKE_MEAN_MAX) &&
                (p3c < P3_STRONG_MIN && mp3 < P3_STRONG_MIN) &&
                (jerkRatio >= JERK_RATIO_TH)

        // 4·5는 최우선 (확률+마진+게이트)
        var grade: Int? = null
        when {
            allowHigh && (p5 >= TH_P5) && ((p5 - p3c) >= MARGIN_P5) -> grade = 5
            allowHigh && (p4 >= TH_P4) && ((p4 - p3c) >= MARGIN_P4) -> grade = 4
        }

        if (grade == null) {
            // 정적은 1
            if (isStatic) {
                // 중앙값 필터용 후보도 1로 넣어주되, 최종 1은 즉시 확정
                pushFixedInt(recentGradeCand, 1, 3)
                grade = 1
            } else {
                // 중강도 밴드: 계단/빨래 보호
                val inModerateBand = (x2 in MOD_STD_MIN..(TH_ACTIVE_STD - 1e-6f)) &&
                        (x4 in MOD_DSTD_MIN..(TH_ACTIVE_DSTD - 1e-6f))

                // jerk-like면 강제 2 유지
                if (isJerkLike) {
                    consec3Cond = 0
                    consec2Cond += 1
                    if (consec2Cond >= HYST_DOWN_CONSEC) state23 = 2
                } else {
                    // 히스테리시스: 3으로 올릴 땐 엄격, 2로 내릴 땐 완화 + 중강도 밴드 고려
                    if (allow3Up && isActiveAny) {
                        consec3Cond += 1; consec2Cond = 0
                        if (consec3Cond >= HYST_UP_CONSEC) state23 = 3
                    } else if (allow3Down || inModerateBand) {
                        consec2Cond += 1; consec3Cond = 0
                        if (consec2Cond >= HYST_DOWN_CONSEC) state23 = 2
                    } else {
                        // 유지
                        consec3Cond = 0; consec2Cond = 0
                    }
                }

                // 후보 등급 업데이트 (2/3만 다룸)
                pushFixedInt(recentGradeCand, state23, 3)

                // 3-창 중앙값 필터
                val gCand = if (recentGradeCand.size == 3) {
                    val a = recentGradeCand.elementAt(0)
                    val b = recentGradeCand.elementAt(1)
                    val c = recentGradeCand.elementAt(2)
                    median3(a,b,c)
                } else state23

                // 최종: 2/3 영역만 여기서 확정 (안전차원)
                grade = min(gCand, 3)

                // 추가 보수화: 강한 3 증거가 없고 중강도 밴드면 2로
                val strong3 = (mp3 >= STRONG_P3) && ((mp3 - maxLow) >= STRONG_MARGIN_P3)
                if (grade == 3 && inModerateBand && !strong3) {
                    grade = 2
                }
            }
        }

        // === Logcat 출력 ===
        val probsStr = String.format(Locale.US, "%.3f, %.3f, %.3f, %.3f, %.3f", p1, p2, p3c, p4, p5)
        val featStr = String.format(Locale.US,
            "svm_mean=%.3f, svm_std=%.3f, d_svm_mean=%.3f, d_svm_std=%.3f", x1, x2, x3, x4)
        Log.i(TAG, "window=$windowCounter, grade=$grade, p=[$probsStr], feat=[$featStr]")

        // 4) CSV 로그
        val ts = nowTimestampMicros()
        predictionLogWriter.apply {
            write("$ts,$windowCounter,$grade,")
            for (i in 0 until 5) write(String.format(Locale.US, "%.6f,", p.getOrElse(i){0f}))
            write(String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f\n", x1, x2, x3, x4))
            flush()
        }

        // 5) 브로드캐스트 (0..4로 전송 → 액티비티에서 +1)
        sendBroadcast(Intent("com.example.classification_0623.PREDICTION_RESULT").apply {
            setPackage(packageName)
            putExtra("prediction", grade!! - 1)
            putExtra("windowIndex", windowCounter)
        })

        // 6) 경고 진동 (5일 때)
        if (grade == 5) {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(1000)
            }
        }
    }

    private fun createNotification() {
        val channelId = "sensor_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Sensor Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("IMU 수집 중")
            .setContentText("센서 데이터를 기반으로 분류 예측 중입니다.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
        startForeground(1, notification)
    }

    // ==== JSON 로더 ==== (스케일러만 유지)
    private fun loadScalerFromAssets(fileName: String) {
        val text = assets.open(fileName).bufferedReader().use { it.readText() }
        val obj = JSONObject(text)
        // mean_/scale_ 또는 mean/scale를 모두 지원
        fun getArr(vararg keys: String): FloatArray {
            for (k in keys) if (obj.has(k)) return jaToFloatArray(obj.getJSONArray(k))
            throw RuntimeException("Missing keys: ${keys.joinToString()}")
        }
        mu4 = getArr("mean_", "mean")
        sigma4 = getArr("scale_", "scale")
    }

    private fun jaToFloatArray(ja: JSONArray): FloatArray {
        val out = FloatArray(ja.length())
        for (i in 0 until ja.length()) out[i] = ja.getDouble(i).toFloat()
        return out
    }

    // ==== TFLite 로더 ==== (압축 여부 무관하게 동작)
    private fun initTfliteFromAssets(modelName: String) {
        val mbb = try {
            val fd = assets.openFd(modelName) // 무압축일 때만 성공
            FileInputStream(fd.fileDescriptor).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        } catch (_: Exception) {
            val bytes = assets.open(modelName).use { it.readBytes() } // 압축되어 있을 때
            java.nio.ByteBuffer.allocateDirect(bytes.size).apply {
                order(java.nio.ByteOrder.nativeOrder())
                put(bytes)
                rewind()
            }
        }

        val opts = Interpreter.Options().apply {
            setUseXNNPACK(true)
            setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
        }
        tflite = Interpreter(mbb, opts)
    }

    // ==== 수학 유틸 ====
    private fun softmax(logits: FloatArray): FloatArray {
        val m = logits.maxOrNull() ?: 0f
        var sum = 0f
        val exps = FloatArray(logits.size)
        for (i in logits.indices) {
            val e = exp((logits[i] - m).toDouble()).toFloat()
            exps[i] = e; sum += e
        }
        for (i in logits.indices) exps[i] /= if (sum == 0f) 1f else sum
        return exps
    }
    private fun mean(a: FloatArray): Float {
        var s = 0.0
        for (v in a) s += v
        return (s / a.size).toFloat()
    }
    private fun stdPop(a: FloatArray): Float {
        if (a.isEmpty()) return 0f
        val m = mean(a)
        var acc = 0.0
        for (v in a) {
            val d = v - m
            acc += (d * d)
        }
        return sqrt((acc / a.size).toFloat())
    }
    private fun nowTimestampMicros(): String {
        val us = baseWallMs * 1000L + (System.nanoTime() - baseNano) / 1000L
        val ms = us / 1000L
        val microsRemainder = (us % 1000L).toInt()
        val base = sdfMillis.format(Date(ms))
        return String.format("%s%03d", base, microsRemainder)
    }
}

/** 선형 위상 FIR를 이용해 3축을 동시에 2:1 디시메이션하는 간단 클래스 */
private class FirDecimator3D(
    private val h: FloatArray,         // FIR taps (h[0..L-1])
    private val M: Int                  // decimation factor (여기선 2)
) {
    private val L = h.size
    private val xb = FloatArray(L)
    private val yb = FloatArray(L)
    private val zb = FloatArray(L)
    private var idx = 0
    private var inCount = 0

    /** 100 Hz 입력 하나를 넣고, 디시메이션 타이밍이면 50 Hz 출력(x,y,z)을 반환. 아니면 null */
    fun process(x: Float, y: Float, z: Float): FloatArray? {
        xb[idx] = x; yb[idx] = y; zb[idx] = z
        idx = (idx + 1) % L
        inCount++

        if (inCount < L) return null
        if (((inCount - L) % M) != 0) return null

        var sx = 0f; var sy = 0f; var sz = 0f
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
