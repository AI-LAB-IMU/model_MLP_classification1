package com.example.classification_0623.ppg

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class HttpUploader (private val endpointUrl: String) {
    private val gson = Gson()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Payload(
        val device_id: String,
        val timestamp: String, // 배치 시작(또는 중심) 시각 ISO-8601
        val ppg_green: List<Float>,
        val ppg_ir: List<Float>,
        val ppg_red: List<Float>
    )

    suspend fun upload(payload: Payload) = withContext(Dispatchers.IO) {
        val json = gson.toJson(payload)
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(endpointUrl).post(body).build()

        client.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string()
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}: ${resp.message} | body=${respBody}")
            }
        }
    }
}