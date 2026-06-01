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
        private const val ENDPOINT_APNEA = "http://210.125.91.90:8000/apnea/api/ingest/"

        const val ACTION_PPG_UPDATE = "PPG_UPDATE"
        const val EXTRA_ELAPSED_SECS = "elapsed_seconds"
        const val EXTRA_CHUNK_COUNT = "chunk_count"

        // 윈도우 종료 후 GREEN/IR/RED 콜백이 버퍼에 들어올 시간을 조금 기다림
        private const val LAG_MS = 1_000L
    }

    private lateinit var fileStreamer: FileStreamer
    private lateinit var batchBuffer: BatchBuffer
    private lateinit var uploader: HttpUploader
    private lateinit var uploaderApnea: HttpUploader

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

    private var elapsedSeconds: Int = 0
    private var chunkCount: Int = 0

    // 첫 샘플 기준 앵커
    private var anchorEpochMs: Long = 0L
    private var anchorMonoMs: Long = 0L
    private var nextWindowIdx: Long = 1L

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    override fun onCreate() {
        super.onCreate()

        createChannel()
        startForeground(NOTI_ID, buildNotification("측정 준비"))

        fileStreamer = FileStreamer(this)
        batchBuffer = BatchBuffer()
        uploader = HttpUploader(ENDPOINT)
        uploaderApnea = HttpUploader(ENDPOINT_APNEA)
        scope = CoroutineScope(Dispatchers.IO)

        deviceId = buildDeviceId()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        subjectNumber = intent?.getStringExtra("subject_number") ?: ""
        subjectName = intent?.getStringExtra("subject_name") ?: ""

        fileStreamer.startSession(subjectNumber, subjectName)

        batchBuffer.clear()
        anchorEpochMs = 0L
        anchorMonoMs = 0L
        nextWindowIdx = 1L
        chunkCount = 0
        elapsedSeconds = 0

        startPpgTracking()
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

        fileStreamer.endSession()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------- UIHandler -------------------
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

            val newElapsed =
                if (anchorMonoMs == 0L) 0
                else ((SystemClock.elapsedRealtime() - anchorMonoMs) / 1000L).toInt()

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

    // ------------------- 업로더 -------------------
    private fun startUploader() {
        uploadJob?.cancel()

        uploadJob = scope.launch(Dispatchers.IO) {
            // 첫 GREEN 샘플 기준 앵커가 잡힐 때까지 대기
            while (isActive && anchorEpochMs == 0L) {
                delay(10)
            }

            while (isActive) {
                val windowStart = anchorEpochMs + (nextWindowIdx - 1) * WINDOW_MS
                val windowEnd = anchorEpochMs + nextWindowIdx * WINDOW_MS

                val deadlineMono = anchorMonoMs + nextWindowIdx * WINDOW_MS + LAG_MS
                val nowMono = SystemClock.elapsedRealtime()

                if (deadlineMono > nowMono) {
                    delay(deadlineMono - nowMono)
                }

                val snap = batchBuffer.snapshotRange(windowStart, windowEnd)
                val g = snap.greenValues
                val i = snap.irValues
                val r = snap.redValues

                nextWindowIdx += 1

                // 서버가 ppg_green 필수라서 GREEN 없으면 보내지 않음
                if (g.isEmpty()) {
                    Log.w(
                        TAG,
                        "[PPG_SKIP] GREEN empty " +
                                "g=${g.size} ir=${i.size} red=${r.size} " +
                                "windowStart=${Instant.ofEpochMilli(windowStart)} " +
                                "windowEnd=${Instant.ofEpochMilli(windowEnd)}"
                    )
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
                    val beforeUploadMs = System.currentTimeMillis()

                    Log.i(
                        TAG,
                        "[PPG_SUMMARY] chunk=$chunkCount " +
                                "delayEnd=${beforeUploadMs - windowEnd}ms " +
                                "g=${g.size} ir=${i.size} red=${r.size} " +
                                "windowStart=${Instant.ofEpochMilli(windowStart)}"
                    )

                    uploaderApnea.upload(payload)

                    val afterUploadMs = System.currentTimeMillis()

                    Log.i(
                        TAG,
                        "[PPG_UPLOAD_DONE] chunk=$chunkCount " +
                                "httpElapsed=${afterUploadMs - beforeUploadMs}ms " +
                                "delayEnd=${afterUploadMs - windowEnd}ms"
                    )

                } catch (e: Exception) {
                    val errorMs = System.currentTimeMillis()

                    Log.e(
                        TAG,
                        "[PPG_UPLOAD_ERROR] chunk=$chunkCount " +
                                "delayEnd=${errorMs - windowEnd}ms " +
                                "g=${g.size} ir=${i.size} red=${r.size}",
                        e
                    )
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

                if (
                    healthTrackingService?.trackingCapability?.supportHealthTrackerTypes
                        ?.contains(HealthTrackerType.PPG_IR) == true
                ) {
                    irTracker = healthTrackingService?.getHealthTracker(HealthTrackerType.PPG_IR)
                    irTracker?.setEventListener(ppgIrListener)
                }

                if (
                    healthTrackingService?.trackingCapability?.supportHealthTrackerTypes
                        ?.contains(HealthTrackerType.PPG_RED) == true
                ) {
                    redTracker = healthTrackingService?.getHealthTracker(HealthTrackerType.PPG_RED)
                    redTracker?.setEventListener(ppgRedListener)
                }
            }

            override fun onConnectionEnded() {
                Log.d(TAG, "HealthTrackingService connection ended")
            }

            override fun onConnectionFailed(e: HealthTrackerException?) {
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
            val nowEpochMs = System.currentTimeMillis()
            val nowMonoMs = SystemClock.elapsedRealtime()

            anchorEpochMs = tsEpochMs

            // HealthTracker가 batch로 늦게 주는 샘플의 실제 시점에 맞춰 mono 기준 보정
            anchorMonoMs = nowMonoMs - (nowEpochMs - tsEpochMs)

            Log.i(
                TAG,
                "[PPG_ANCHOR] " +
                        "anchor=${Instant.ofEpochMilli(anchorEpochMs)} " +
                        "rawAgeMs=${nowEpochMs - tsEpochMs} " +
                        "correctedAnchorMonoMs=$anchorMonoMs"
            )
        }
    }

    // ------------------- 리스너 구현 -------------------

    private val ppgGreenListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            if (dataPoints.isEmpty()) return

            val batch = ArrayList<Pair<Long, Float>>(dataPoints.size)

            for (dp in dataPoints) {
                val v = dp.getValue(ValueKey.PpgGreenSet.PPG_GREEN).toFloat()
                val tsMs = toMs(dp.timestamp)
                batch.add(tsMs to v)
            }

            // 먼저 GREEN 데이터를 버퍼에 넣고, 그 다음 앵커를 열어줌
            for ((ts, v) in batch) {
                fileStreamer.appendGreen(ts, v)
                batchBuffer.addGreen(ts, v)
            }

            onFirstSampleArrived(batch.first().first)
        }

        override fun onFlushCompleted() {}

        override fun onError(error: HealthTracker.TrackerError?) {
            Log.e(TAG, "GREEN Tracker Error: $error")
        }
    }

    private val ppgIrListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            if (dataPoints.isEmpty()) return

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
            if (dataPoints.isEmpty()) return

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
        raw < 1_000_000_000L -> raw * 1000L
        raw <= 9_999_999_999L -> raw * 1000L
        raw <= 9_999_999_999_999L -> raw
        else -> raw / 1_000_000L
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CH_ID,
                "PPG Upload",
                NotificationManager.IMPORTANCE_LOW
            )
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