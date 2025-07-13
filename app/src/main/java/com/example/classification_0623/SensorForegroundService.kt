package com.example.classification_0623

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.*
import android.os.*
import androidx.core.app.NotificationCompat
import org.tensorflow.lite.Interpreter
import java.io.*
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.pow
import kotlin.math.sqrt

class SensorForegroundService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private val windowSize = 300
    private val slideSize = 150
    private val buffer = mutableListOf<FloatArray>()
    private lateinit var tflite: Interpreter

    private var windowCounter = 0
    private lateinit var predictionLogFile: File
    private lateinit var predictionLogWriter: BufferedWriter

    private lateinit var combinedRawFile: File
    private lateinit var combinedRawWriter: BufferedWriter

    override fun onCreate() {
        super.onCreate()
        createNotification()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, 20000) // 50Hz
        }

        // 모델 로드
        tflite = Interpreter(loadModelFile("model_classification1.tflite"))

        // 예측 로그 초기화
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val predictionLogFilename = "prediction_log_$timestamp.csv"
        predictionLogFile = File(dir, predictionLogFilename)
        predictionLogWriter = BufferedWriter(FileWriter(predictionLogFile, true))
        predictionLogWriter.write("timestamp,window,prediction\n")
        predictionLogWriter.flush()

        // 통합 원본 윈도우 파일 초기화
        val rawDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        combinedRawFile = File(rawDir, "raw_windows_combined.csv")
        val isNewFile = !combinedRawFile.exists()
        combinedRawWriter = BufferedWriter(FileWriter(combinedRawFile, true))
        if (isNewFile) {
            combinedRawWriter.write("window,x,y,z\n")
        }
    }

    private fun loadModelFile(fileName: String): MappedByteBuffer {
        val fileDescriptor = assets.openFd(fileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        tflite.close()
        predictionLogWriter.close()
        combinedRawWriter.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val (x, y, z) = event.values
        buffer.add(floatArrayOf(x, y, z))

        if (buffer.size >= windowSize) {
            val window = buffer.subList(0, windowSize)
            processWindow(window)
            buffer.subList(0, slideSize).clear()
        }
    }

    private fun processWindow(window: List<FloatArray>) {
        windowCounter += 1

        val svmList = window.map { sqrt(it[0].pow(2) + it[1].pow(2) + it[2].pow(2)) }
        val deltaList = svmList.zipWithNext { a, b -> kotlin.math.abs(b - a) }

        val rawVector = floatArrayOf(
            svmList.average().toFloat(),
            svmList.standardDeviation(),
            deltaList.average().toFloat(),
            deltaList.standardDeviation()
        )

        val mu = floatArrayOf(13.1613f, 3.3716f, 3.7332f, 4.7952f)
        val sigma = floatArrayOf(7.2705f, 3.8980f, 4.2321f, 6.3111f)

        val normalizedVector = FloatArray(4) { i ->
            (rawVector[i] - mu[i]) / sigma[i]
        }

        val inputBuffer = arrayOf(normalizedVector)
        val outputBuffer = Array(1) { FloatArray(5) }

        tflite.run(inputBuffer, outputBuffer)

        val prediction = outputBuffer[0].indices.maxByOrNull { outputBuffer[0][it] } ?: -1

        // 예측 로그 저장
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        predictionLogWriter.write("$timestamp,$windowCounter,${prediction + 1}\n")
        predictionLogWriter.flush()

        // 원본 윈도우 데이터를 하나의 파일에 누적 저장
        for (data in window) {
            combinedRawWriter.write("$windowCounter,${data[0]},${data[1]},${data[2]}\n")
        }
        combinedRawWriter.flush()

        // 예측 결과 전송
        val resultIntent = Intent("com.example.classification_0623.PREDICTION_RESULT")
        resultIntent.putExtra("prediction", prediction + 1)
        resultIntent.putExtra("windowIndex", windowCounter)
        sendBroadcast(resultIntent)

        if (prediction == 4) {
            println("[$windowCounter] 위험 행동 감지")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(1000)
            }
        } else {
            println("[$windowCounter] 예측 결과 클래스: ${prediction + 1}")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun createNotification() {
        val channelId = "sensor_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Sensor Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("IMU 수집 중")
            .setContentText("센서 데이터를 실시간 수집하고 있습니다.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        startForeground(1, notification)
    }

    private fun List<Float>.standardDeviation(): Float {
        val mean = this.average().toFloat()
        val variance = this.sumOf { (it - mean).toDouble().pow(2) } / this.size
        return sqrt(variance).toFloat()
    }
}
