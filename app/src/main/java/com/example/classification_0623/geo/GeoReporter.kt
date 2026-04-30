package com.example.classification_0623.geo

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Handler
import android.os.HandlerThread
import android.location.Location
import okhttp3.OkHttpClient

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

    // 최신 위치 캐시를 계속 갱신(4분마다). 실제 “기록/전송”은 tick에서만.
    private val tracker = GeoLocationTracker(
        context = context,
        priority = com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
        updateIntervalMs = 240_000L
    )

    // 오프라인 큐 파일 (jsonl)
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

        tickThread = HandlerThread("geo_tick_thread").apply { start() }
        tickHandler = Handler(tickThread!!.looper)

        // 시작하자마자 한번 찍고(테스트/즉시성), 이후 5분마다
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


    private fun tickOnce() {
        val cached: Location? = tracker.getCachedIfFresh(2 * 60_000L)
        if (cached != null) {
            recordAndFlush(cached.latitude, cached.longitude, "TICK(cache)")
            return
        }

        tracker.getCurrentOnce { loc ->
            if (loc != null) recordAndFlush(loc.latitude, loc.longitude, "TICK(one-shot)")
            else Log.w(TAG, "TICK: location null (no fix / no permission)")
        }
    }

    private fun recordAndFlush(lat: Double, lon: Double, tag: String) {
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

    private fun appendQueue(event: GeoEvent) {
        // jsonl 한 줄 추가
        val line = JSONObject().apply {
            put("device_id", event.deviceId)
            put("timestamp", event.timestampIso)
            put("latitude", event.latitude)
            put("longitude", event.longitude)
        }.toString()

        try {
            queueFile.appendText(line + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "queue append failed: ${e.message}", e)
        }
    }


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
                val event = GeoEvent(
                    deviceId = obj.getString("device_id"),
                    timestampIso = obj.getString("timestamp"),
                    latitude = obj.getDouble("latitude"),
                    longitude = obj.getDouble("longitude")
                )
                uploader.sendSync(event)
            } catch (e: Exception) {
                Log.w(TAG, "queue parse/send failed: ${e.message}")
                false
            }

            if (!ok) {
                // 실패한 건 남겨둠
                remaining.add(line)
            }
        }

        try {
            if (remaining.isEmpty()) {
                queueFile.delete()
                Log.i(TAG, "flush: all success -> queue cleared")
            } else {
                queueFile.writeText(remaining.joinToString("\n") + "\n")
                Log.w(TAG, "flush: remaining=${remaining.size} (kept)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "queue rewrite failed: ${e.message}", e)
        }
    }
}
