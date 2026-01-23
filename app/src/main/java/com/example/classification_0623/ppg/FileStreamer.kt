package com.example.classification_0623.ppg

import android.content.Context
//import android.util.Log
import android.os.Environment
//import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

//import java.io.FileWriter
//import java.io.IOException
//import java.security.KeyException
/**
 * PPG 3채널만 CSV로 기록. 형식: "timestamp,value"\n
 */
class FileStreamer(private val ctx: Context) {
    private var sessionDir: File? = null
    private var greenFile: File? = null
    private var irFile: File? = null
    private var redFile: File? = null

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun startSession(subjectNumber: String, subjectName: String) {
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?:ctx.filesDir
        val safeNumber = subjectNumber.replace(Regex("[^0-9A-Za-z_-]"), "_")
        val safeName   = subjectName.replace(Regex("[^0-9A-Za-z_-]"), "_")
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val dir = File(base, "PPG/PPG_${stamp}_${safeNumber}_${safeName}").apply{mkdirs()}
        sessionDir = dir

        // PPG 이외 CSV 사전 정리(이전 런 잔존/타 컴포넌트 생성물 제거)
        dir.listFiles()?.forEach { f ->
            val name = f.name.lowercase()
            val isPpg = name == "ppg_green.csv" || name == "ppg_ir.csv" || name == "ppg_red.csv"
            if (name.endsWith(".csv") && !isPpg) {
                try { f.delete() } catch (_: Exception) {}
            }
        }

        greenFile = File(dir, "ppg_green.csv").apply { writeHeader(this) }
        irFile = File(dir, "ppg_ir.csv").apply { writeHeader(this) }
        redFile = File(dir, "ppg_red.csv").apply { writeHeader(this) }
    }
    private fun writeHeader(f: File) {
        FileOutputStream(f, false).use { fos ->
            fos.write("timestamp,value\n".toByteArray(Charset.forName("UTF-8")))
        }
    }

    fun appendGreen(tsMillis: Long, value: Float) = appendLine(greenFile, tsMillis, value)
    fun appendIr(tsMillis: Long, value: Float) = appendLine(irFile, tsMillis, value)
    fun appendRed(tsMillis: Long, value: Float) = appendLine(redFile, tsMillis, value)

    private fun appendLine(file: File?, tsMillis: Long, value: Float) {
        if (file == null) return
        val tsStr= sdf.format(Date(tsMillis))
        val line = "$tsStr,$value\n"
        FileOutputStream(file, true).use { it.write(line.toByteArray(Charset.forName("UTF-8"))) }
    }
    fun endSession() {
        // 파일 핸들 별도 유지 안 하므로 별도 close 불필요
        sessionDir = null
        greenFile = null
        irFile = null
        redFile = null
    }
}
