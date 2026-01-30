package com.example.classification_0623.geo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*

class GeoLocationTracker(
    private val context: Context,
    private val priority: Int = Priority.PRIORITY_HIGH_ACCURACY,
    private val updateIntervalMs: Long = 60_000L, // 캐시 갱신용(1분). tick은 GeoReporter가 5분 보장.
) {
    private val TAG = "GeoLocationTracker"
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @Volatile private var handlerThread: HandlerThread? = null
    @Volatile private var callback: LocationCallback? = null

    @Volatile private var latestLoc: Location? = null
    @Volatile private var latestAtMs: Long = 0L

    private fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED)
    }

    fun start() {
        if (!hasPermission()) {
            Log.w(TAG, "Location permission not granted.")
            return
        }
        if (callback != null) return

        handlerThread = HandlerThread("geo_location_thread").apply { start() }

        val request = LocationRequest.Builder(priority, updateIntervalMs)
            .setMinUpdateIntervalMillis(updateIntervalMs / 2)
            .setMaxUpdateDelayMillis(updateIntervalMs)
            .setWaitForAccurateLocation(false)
            .build()

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                latestLoc = loc
                latestAtMs = System.currentTimeMillis()
                Log.d(TAG, "cache updated lat=${loc.latitude} lon=${loc.longitude}")
            }
        }

        try {
            client.requestLocationUpdates(
                request,
                callback!!,
                handlerThread!!.looper
            )
            Log.i(TAG, "started updates interval=$updateIntervalMs ms priority=$priority")
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException: ${se.message}", se)
        }
    }

    fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
        handlerThread?.quitSafely()
        handlerThread = null
        Log.i(TAG, "stopped")
    }

    /** 최근 maxAgeMs 이내 캐시가 있으면 반환 */
    fun getCachedIfFresh(maxAgeMs: Long): Location? {
        val loc = latestLoc ?: return null
        val age = System.currentTimeMillis() - latestAtMs
        return if (age <= maxAgeMs) loc else null
    }

    /** 캐시가 없을 때 1회 one-shot */
    fun getCurrentOnce(onResult: (Location?) -> Unit) {
        if (!hasPermission()) {
            onResult(null)
            return
        }
        try {
            client.getCurrentLocation(priority, null)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        latestLoc = loc
                        latestAtMs = System.currentTimeMillis()
                        Log.d(TAG, "one-shot lat=${loc.latitude} lon=${loc.longitude}")
                    }
                    onResult(loc)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "one-shot failed: ${e.message}")
                    onResult(null)
                }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException(one-shot): ${se.message}", se)
            onResult(null)
        }
    }
}
