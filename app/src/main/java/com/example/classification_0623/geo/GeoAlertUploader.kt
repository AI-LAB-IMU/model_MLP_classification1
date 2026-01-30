package com.example.classification_0623.geo

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class GeoAlertUploader(
    private val httpClient: OkHttpClient,
    private val baseUrl: String,
    private val path: String
) {
    private val TAG = "GeoAlertUploader"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * 동기 전송 (백그라운드 스레드에서만 호출!)
     * 성공(2xx)이면 true
     */
    fun sendSync(event: GeoEvent): Boolean {
        val url = baseUrl + path
        val bodyJson = JSONObject().apply {
            put("device_id", event.deviceId)
            put("timestamp", event.timestampIso)
            put("latitude", event.latitude)
            put("longitude", event.longitude)
        }.toString()

        Log.i(TAG, "GEO POST -> $url")
        Log.i(TAG, "GEO body -> $bodyJson")

        val req = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .post(bodyJson.toRequestBody(jsonMedia))
            .build()

        return try {
            httpClient.newCall(req).execute().use { res ->
                val respBody = res.body?.string()
                Log.i(TAG, "GEO resp code=${res.code} msg=${res.message} body=$respBody")
                res.isSuccessful
            }
        } catch (e: IOException) {
            Log.e(TAG, "GEO POST failed: ${e.message}", e)
            false
        }
    }
}

