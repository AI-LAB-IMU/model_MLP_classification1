package com.example.classification_0623.geo

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class GeoReporter(
    private val context: Context,
    httpClient: OkHttpClient,
    baseUrl: String,
    path: String,
    private val intervalMs: Long,
    private val deviceIdProvider: () -> String,
    private val timestampProvider: () -> String,
) {
    private val TAG = "GeoReporter"

    private val uploader = GeoAlertUploader(httpClient, baseUrl, path)

    /**
     * 최신 위치 캐시를 계속 갱신.
     * 실제 DB 기록/서버 전송 기준은 tickRunnable의 intervalMs.
     */
    private val tracker = GeoLocationTracker(
        context = context,
        priority = com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
        updateIntervalMs = 240_000L
    )

    /**
     * 오프라인 큐 파일.
     * 서버 전송 실패 시 jsonl 형태로 남겨두고 다음 flush 때 재전송.
     */
    private val queueFile = File(context.filesDir, "geo_queue.jsonl")

    private val ioExec = Executors.newSingleThreadExecutor()
    private val flushing = AtomicBoolean(false)

    private var tickThread: HandlerThread? = null
    private var tickHandler: Handler? = null

    @Volatile private var started = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            tickOnce()
            tickHandler?.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        if (started) return
        started = true

        tracker.start()

        tickThread = HandlerThread("geo_tick_thread").apply {
            start()
        }

        tickHandler = Handler(tickThread!!.looper)

        // 시작하자마자 1회 기록/전송 시도.
        // 이후 intervalMs마다 반복.
        tickHandler?.post(tickRunnable)

        Log.i(TAG, "started interval=${intervalMs}ms")
    }

    fun stop() {
        started = false
        tickHandler?.removeCallbacksAndMessages(null)
        tickThread?.quitSafely()
        tickThread = null
        tickHandler = null

        tracker.stop()
        Log.i(TAG, "stopped")
    }

    /**
     * 5분 tick마다 반드시 이벤트를 만든다.
     *
     * 1. 최근 2분 이내 캐시 위치가 있으면 해당 위치 전송.
     * 2. 캐시가 없으면 one-shot 위치 요청.
     * 3. one-shot도 실패하면 latitude=null, longitude=null로 전송.
     */
    private fun tickOnce() {
        val cached: Location? = tracker.getCachedIfFresh(2 * 60_000L)
        if (cached != null) {
            recordAndFlush(
                lat = cached.latitude,
                lon = cached.longitude,
                tag = "TICK(cache)"
            )
            return
        }

        tracker.getCurrentOnce { loc ->
            if (loc != null) {
                recordAndFlush(
                    lat = loc.latitude,
                    lon = loc.longitude,
                    tag = "TICK(one-shot)"
                )
            } else {
                Log.w(TAG, "TICK: location null -> queue null GPS event")

                recordAndFlush(
                    lat = null,
                    lon = null,
                    tag = "TICK(null)"
                )
            }
        }
    }

    /**
     * 위치가 있든 없든 이벤트를 만들고 queue에 저장 후 flush 시도.
     */
    private fun recordAndFlush(
        lat: Double?,
        lon: Double?,
        tag: String
    ) {
        val event = GeoEvent(
            deviceId = deviceIdProvider(),
            timestampIso = timestampProvider(),
            latitude = lat,
            longitude = lon
        )

        appendQueue(event)
        Log.i(TAG, "$tag queued lat=$lat lon=$lon")

        flushQueueAsync()
    }

    /**
     * jsonl 한 줄 추가.
     * latitude/longitude가 null이면 JSON null로 저장.
     */
    private fun appendQueue(event: GeoEvent) {
        val line = JSONObject().apply {
            put("device_id", event.deviceId)
            put("timestamp", event.timestampIso)
            put("latitude", event.latitude ?: JSONObject.NULL)
            put("longitude", event.longitude ?: JSONObject.NULL)
        }.toString()

        try {
            queueFile.appendText(line + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "queue append failed: ${e.message}", e)
        }
    }

    /**
     * queue flush는 동시에 하나만 실행.
     */
    private fun flushQueueAsync() {
        if (!flushing.compareAndSet(false, true)) return

        ioExec.execute {
            try {
                flushQueueOnce()
            } finally {
                flushing.set(false)
            }
        }
    }

    /**
     * queue에 쌓인 이벤트를 순서대로 서버에 전송.
     * 성공한 이벤트는 제거.
     * 실패한 이벤트는 remaining에 남겨 다음에 재시도.
     */
    private fun flushQueueOnce() {
        if (!queueFile.exists()) return

        val lines = try {
            queueFile.readLines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "queue read failed: ${e.message}", e)
            return
        }

        if (lines.isEmpty()) return

        val remaining = ArrayList<String>(lines.size)

        for (line in lines) {
            val ok = try {
                val obj = JSONObject(line)

                val latitude: Double? =
                    if (obj.isNull("latitude")) null else obj.getDouble("latitude")

                val longitude: Double? =
                    if (obj.isNull("longitude")) null else obj.getDouble("longitude")

                val event = GeoEvent(
                    deviceId = obj.getString("device_id"),
                    timestampIso = obj.getString("timestamp"),
                    latitude = latitude,
                    longitude = longitude
                )
                uploader.sendSync(event)
            } catch (e: Exception) {
                Log.w(TAG, "queue parse/send failed: ${e.message}")
                false
            }

            if (!ok) {
                remaining.add(line)
            }
        }

        try {
            if (remaining.isEmpty()) {
                queueFile.delete()
                Log.i(TAG, "flush: all success -> queue cleared")
            } else {
                queueFile.writeText(remaining.joinToString("\n") + "\n")
                Log.w(TAG, "flush: remaining=${remaining.size} kept")
            }
        } catch (e: Exception) {
            Log.e(TAG, "queue rewrite failed: ${e.message}", e)
        }
    }
}
