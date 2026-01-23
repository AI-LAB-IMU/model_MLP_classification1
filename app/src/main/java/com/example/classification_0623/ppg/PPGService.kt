package com.example.classification_0623.ppg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.time.Instant
import com.samsung.android.service.health.tracking.*
import com.samsung.android.service.health.tracking.data.*

/**
 * 포그라운드 서비스: PPG(GREEN/IR/RED)만 수집/CSV 기록 + 12초 주기 서버 업로드
 * - IMU/Motion/SpO2 완전 제거
 */
/**
 * PPG(GREEN/IR/RED) 수집 + CSV 기록 + 12초 주기 서버 업로드 + UIHandler 브로드캐스트 갱신
 * - IMU/Motion/SpO2 완전 제거
 * - CSV는 ppg_green/ppg_ir/ppg_red만 "timestamp,value" 형식으로 저장
 * - 업로드 페이로드: device_id, timestamp(ISO-8601), ppg_green[], ppg_ir[], ppg_red[] (값 리스트만)
 * - UI 갱신: ACTION_PPG_UPDATE 브로드캐스트로 elapsed_seconds, chunk_count 전달 (기존 호환)
 */
class PPGService : Service() {
    companion object {
        private const val TAG = "PPGService"
        private const val CH_ID = "ppp_upload_channel"
        private const val NOTI_ID = 1001
        private const val WINDOW_MS = 12_000L
        private const val ENDPOINT = "http://210.125.91.90:8000/api/ingest/"

        const val ACTION_PPG_UPDATE = "PPG_UPDATE"
        const val EXTRA_ELAPSED_SECS = "elapsed_seconds"
        const val EXTRA_CHUNK_COUNT = "chunk_count"

        private const val LAG_MS = 50L // 20~100ms 범위 권장
    }

    private lateinit var fileStreamer: FileStreamer
    private lateinit var batchBuffer: BatchBuffer
    private lateinit var uploader: HttpUploader
    private lateinit var scope: CoroutineScope

    private var uploadJob: Job? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private var uiTicking = false

    private var subjectNumber: String = ""
    private var subjectName: String = ""
    private var deviceId: String = ""

    private var healthTrackingService: HealthTrackingService? = null
    private var greenTracker: HealthTracker? = null
    private var irTracker: HealthTracker? = null
    private var redTracker: HealthTracker? = null

    // UI 표시용 상태
    private var elapsedSeconds: Int = 0
    private var chunkCount: Int = 0

    // 1) 앵커 보관 (클래스 필드)
    private var anchorEpochMs: Long = 0L
    private var anchorMonoMs: Long = 0L
    private var nextWindowIdx: Long = 1L // 첫 창 끝 = anchor + 12s

    // CSV/Listener guard
    //private var filesReady: Boolean = false
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)



    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTI_ID, buildNotification("측정 준비"))

        fileStreamer = FileStreamer(this)
        batchBuffer = BatchBuffer()
        uploader = HttpUploader(ENDPOINT)
        scope = CoroutineScope(Dispatchers.IO)

        deviceId = buildDeviceId()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        subjectNumber = intent?.getStringExtra("subject_number") ?: ""
        subjectName = intent?.getStringExtra("subject_name") ?: ""

        fileStreamer.startSession(subjectNumber, subjectName)

        // 세션/버퍼/앵커 초기화
        batchBuffer.clear()
        anchorEpochMs = 0L
        anchorMonoMs = 0L
        nextWindowIdx = 1L
        chunkCount = 0
        elapsedSeconds = 0

        //filesReady = true

        // PPG 트래커 등록
        startPpgTracking()

        // 업로더/UI 시작
        startUploader()
        startUiTicker()
        updateNotification("측정 및 업로드 시작")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopUiTicker()
        stopUploader()
        stopPpgTracking()
        //filesReady = false
        fileStreamer.endSession()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------- UIHandler (1Hz, 앵커 기반 표시 ) -------------------
    private fun startUiTicker() {
        if (uiTicking) return
        uiTicking = true
        uiHandler.post(uiTick)
    }

    private fun stopUiTicker() {
        uiTicking = false
        uiHandler.removeCallbacks(uiTick)
    }

    private val uiTick = object : Runnable {
        override fun run() {
            if (!uiTicking) return
            // 앵커가 설정되어 있으면 단조시계 기반으로 경과를 계산한다.
            val newElapsed = if (anchorMonoMs == 0L) 0 else ((SystemClock.elapsedRealtime() - anchorMonoMs) / 1000L).toInt()
            if (newElapsed != elapsedSeconds) {
                elapsedSeconds = newElapsed
                sendUiUpdate()
                updateNotification("측정 중 · chunk=${chunkCount} · ${elapsedSeconds}s")
            }
            uiHandler.postDelayed(this, 1_000L)
        }
    }

    private fun sendUiUpdate() {
        val i = Intent(ACTION_PPG_UPDATE).apply {
            putExtra(EXTRA_ELAPSED_SECS, elapsedSeconds)
            putExtra(EXTRA_CHUNK_COUNT, chunkCount)
        }
        sendBroadcast(i)
    }

    // ------------------- 업로더 (12초 주기) -------------------
    private fun startUploader() {
        uploadJob?.cancel()
        uploadJob = scope.launch(Dispatchers.IO) {
            // 첫 샘플 앵커가 설정될 때까지 대기
            while (isActive&& anchorEpochMs == 0L) delay(10)
            while (isActive) {
                val windowStart = anchorEpochMs + (nextWindowIdx - 1) * WINDOW_MS
                val windowEnd   = anchorEpochMs + nextWindowIdx * WINDOW_MS
                // 단조시계 기준으로 지터 가드 포함 대기
                val deadlineMono = anchorMonoMs + nextWindowIdx * WINDOW_MS + LAG_MS
                val nowMono = SystemClock.elapsedRealtime()
                if (deadlineMono > nowMono) delay(deadlineMono - nowMono)

                // 정확한 경계로 스냅샷 (누적 방지의 핵심)
                val snap = batchBuffer.snapshotRange(windowStart, windowEnd)
                val g = snap.greenValues
                val i = snap.irValues
                val r = snap.redValues

                // 다음 윈도우로 전진 (빈 배치여도 정렬 유지를 위해 전진)
                nextWindowIdx += 1


                if (g.isEmpty() && i.isEmpty() && r.isEmpty()) {
                    Log.w(TAG, "업로드 스킵: 빈 배치 [${windowStart}, ${windowEnd})")
                    continue
                }
                val isoTs = Instant.ofEpochMilli(snap.windowStartMs).toString()
                val payload = HttpUploader.Payload(
                    device_id = deviceId,
                    timestamp = isoTs,
                    ppg_green = g,
                    ppg_ir = i,
                    ppg_red = r
                )
                chunkCount += 1
                sendUiUpdate()

                try {
                    uploader.upload(payload)

                    Log.i(TAG, "업로드 성공: $isoTs (g=${g.size}, i=${i.size}, r=${r.size}) · chunk=${chunkCount}")

                } catch (e: Exception) {
                    Log.e(TAG, "업로드 실패: $isoTs",e)
                }
            }
        }
    }

    private fun stopUploader() {
        uploadJob?.cancel()
        uploadJob = null
    }

    // ------------------- PPG 수집 -------------------
    private fun startPpgTracking() {
        healthTrackingService = HealthTrackingService(object : ConnectionListener {
            override fun onConnectionSuccess() {
                Log.d(TAG, "Connected to HealthTrackingService")

                greenTracker = healthTrackingService?.getHealthTracker(HealthTrackerType.PPG_GREEN)
                greenTracker?.setEventListener(ppgGreenListener)

                if (healthTrackingService?.trackingCapability?.supportHealthTrackerTypes
                        ?.contains(HealthTrackerType.PPG_IR) == true) {
                    irTracker = healthTrackingService?.getHealthTracker(HealthTrackerType.PPG_IR)
                    irTracker?.setEventListener(ppgIrListener)
                }
                if (healthTrackingService?.trackingCapability?.supportHealthTrackerTypes
                        ?.contains(HealthTrackerType.PPG_RED) == true) {
                    redTracker = healthTrackingService?.getHealthTracker(HealthTrackerType.PPG_RED)
                    redTracker?.setEventListener(ppgRedListener)
                }
            }
            override fun onConnectionEnded() { Log.d(TAG, "HealthTrackingService connection ended") }
            override fun onConnectionFailed(e:HealthTrackerException?) {
                Log.e(TAG, "Connection failed: ${e?.message}")
            }
        }, this)

        healthTrackingService?.connectService()
    }

    private fun stopPpgTracking() {
        greenTracker?.unsetEventListener()
        irTracker?.unsetEventListener()
        redTracker?.unsetEventListener()
        healthTrackingService?.disconnectService()
    }
    private fun onFirstSampleArrived(tsEpochMs: Long) {
        if (anchorEpochMs == 0L) {
            anchorEpochMs = tsEpochMs            // 에폭기준 (샘플 ts 단위와 동일)
            anchorMonoMs  = SystemClock.elapsedRealtime() // 스케줄 기준 (단조증가)
            Log.i(TAG, "anchorEpochMs=$anchorEpochMs, anchorMonoMs=$anchorMonoMs")}
    }

    // --- 리스너 구현 ---
    private val ppgGreenListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            //if (!filesReady || dataPoints.isEmpty()) return

            if (dataPoints.isEmpty()) return
            val batch = ArrayList<Pair<Long, Float>>(dataPoints.size)
            for (dp in dataPoints) {
                val v = dp.getValue(ValueKey.PpgGreenSet.PPG_GREEN).toFloat()
                val ts = dp.timestamp
                val tsMs = toMs(dp.timestamp)
                batch.add(tsMs to v)
            }
            // ✅ 첫 샘플에서 앵커 고정 (12초 경계 정렬)
            onFirstSampleArrived(batch.first().first)

            // 파일 기록 + 버퍼 적재
            for ((ts, v) in batch) {
                fileStreamer.appendGreen(ts, v)
                batchBuffer.addGreen(ts, v)
            }
            // (선택) 디버그 로그 only
            //if (BuildConfig.DEBUG) {
            //val first = batch.first().first
            //val last  = batch.last().first
            //Log.d(TAG, "GREEN batch size=${batch.size}, ts=[${first}..${last}]")
            //}
        }
        override fun onFlushCompleted() {}
        override fun onError(error: HealthTracker.TrackerError?) {
            Log.e(TAG, "GREEN Tracker Error: $error")
        }
    }

    private val ppgIrListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            if ( dataPoints.isEmpty()) return

            val batch = ArrayList<Pair<Long, Float>>(dataPoints.size)
            for (dp in dataPoints) {
                val v = dp.getValue(ValueKey.PpgIrSet.PPG_IR).toFloat()
                val tsMs = toMs(dp.timestamp)
                batch.add(tsMs to v)
            }
            for ((ts, v) in batch) {
                fileStreamer.appendIr(ts, v)
                batchBuffer.addIr(ts, v)
            }

        }
        override fun onFlushCompleted() {}
        override fun onError(error: HealthTracker.TrackerError?) {
            Log.e(TAG, "IR Tracker Error: $error")
        }
    }

    private val ppgRedListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            if ( dataPoints.isEmpty()) return
            val batch = ArrayList<Pair<Long, Float>>(dataPoints.size)
            for (dp in dataPoints) {
                val v = dp.getValue(ValueKey.PpgRedSet.PPG_RED).toFloat()
                val tsMs = toMs(dp.timestamp)
                batch.add(tsMs to v)
            }
            for ((ts, v) in batch) {
                fileStreamer.appendRed(ts, v)
                batchBuffer.addRed(ts, v)
            }

        }
        override fun onFlushCompleted() {}
        override fun onError(error: HealthTracker.TrackerError?) {
            Log.e(TAG, "RED Tracker Error: $error")
        }

    }

    // ------------------- 유틸 -------------------
    private fun buildDeviceId(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val model = Build.MODEL ?: "unknown"
        return "${model}_${androidId}"
    }
    private fun toMs(raw: Long): Long = when {
        raw < 1_000_000_000L -> raw * 1000L            // seconds → ms (보수적 처리)
        raw <= 9_999_999_999L -> raw * 1000L           // 10-digit seconds → ms
        raw <= 9_999_999_999_999L -> raw               // 13-digit ms → ms
        else -> raw / 1_000_000L                        // ns → ms (fallback)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CH_ID, "PPG Upload", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("PPG 수집/업로드")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTI_ID, buildNotification(text))
    }
}