package com.example.sensor_recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class SensorRecorderService : Service(), SensorEventListener {
    private val binder = LocalBinder()
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    var isRecording = AtomicBoolean(false)
        private set
    private val accelBuffer = ConcurrentLinkedQueue<String>()
    private val gyroBuffer = ConcurrentLinkedQueue<String>()
    private val annotationBuffer = ConcurrentLinkedQueue<String>()
    private var recordingDir: File? = null
    var lastRecordingDir: File? = null
        private set
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    var startTimeNs: Long = 0
    var sampleCountAccel: Long = 0
    var sampleCountGyro: Long = 0
    var lastAccelTimestamp: Long = 0
    var lastGyroTimestamp: Long = 0
    var lastAccelValues = FloatArray(3)
    var lastGyroValues = FloatArray(3)
    private var recordingStartEpochMs: Long = 0
    private var epochOffsetNs: Long = 0

    // Rate tracking
    private val accelIntervals = CircularBuffer(50)
    private val gyroIntervals = CircularBuffer(50)

    inner class LocalBinder : Binder() {
        fun getService(): SensorRecorderService = this@SensorRecorderService
    }
    
    fun getAccelRateHz(): Double {
        val avgInterval = accelIntervals.average()
        return if (avgInterval > 0) 1_000_000_000.0 / avgInterval else 0.0
    }

    fun getGyroRateHz(): Double {
        val avgInterval = gyroIntervals.average()
        return if (avgInterval > 0) 1_000_000_000.0 / avgInterval else 0.0
    }

    override fun onBind(intent: Intent): IBinder { return binder }
    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        
        // Register listeners immediately for preview
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
            ACTION_SYNC_TAP -> logSyncTap()
        }
        return START_NOT_STICKY
    }
    fun startRecording() {
        if (isRecording.get()) return
        startForeground(NOTIFICATION_ID, createNotification())
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SensorRecorder::WakeLock")
        wakeLock?.acquire(4 * 60 * 60 * 1000L)
        setupRecordingFiles()
        
        // Reset counters and buffers
        sampleCountAccel = 0
        sampleCountGyro = 0
        accelIntervals.clear()
        gyroIntervals.clear()
        
        startTimeNs = SystemClock.elapsedRealtimeNanos()
        recordingStartEpochMs = System.currentTimeMillis()
        epochOffsetNs = recordingStartEpochMs * 1_000_000L - startTimeNs
        isRecording.set(true)
        startFileFlusher()
    }
    fun stopRecording() {
        if (!isRecording.get()) return
        isRecording.set(false)
        try { wakeLock?.release() } catch (e: Exception) { Log.e(TAG, "Error releasing wakelock", e) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.launch {
            flushBuffers()
            writeMetadata()
            lastRecordingDir = recordingDir  // Preserve for export
        }
    }
    fun logSyncTap() {
        if (!isRecording.get()) return
        val timestamp = SystemClock.elapsedRealtimeNanos()
        annotationBuffer.add("$timestamp,sync_tap")
    }
    private fun setupRecordingFiles() {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val baseDir = getExternalFilesDir(null) ?: filesDir
        recordingDir = File(baseDir, "Recordings/$timestamp")
        recordingDir?.mkdirs()
    }
    private fun startFileFlusher() {
        serviceScope.launch {
            while (isRecording.get()) {
                flushBuffers()
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    private fun flushBuffers() {
        recordingDir?.let {
            appendToFile(File(it, "Accelerometer.csv"), accelBuffer, "timestamp_ns,x,y,z")
            appendToFile(File(it, "Gyroscope.csv"), gyroBuffer, "timestamp_ns,x,y,z")
            appendToFile(File(it, "Annotation.csv"), annotationBuffer, "timestamp_ns,label")
        }
    }
    private fun appendToFile(file: File, buffer: ConcurrentLinkedQueue<String>, header: String) {
        if (buffer.isEmpty()) return
        val isNew = !file.exists()
        try {
            BufferedWriter(FileWriter(file, true)).use { writer ->
                if (isNew) { writer.write(header); writer.newLine() }
                var line = buffer.poll()
                while (line != null) { writer.write(line); writer.newLine(); line = buffer.poll() }
            }
        } catch (e: Exception) { Log.e(TAG, "Error writing to file: ${e.message}") }
    }
    private fun writeMetadata() {
        recordingDir?.let {
            val metadata = """{"recording_start_epoch_ms": $recordingStartEpochMs, "epoch_offset_ns": $epochOffsetNs, "accel_sample_count": $sampleCountAccel, "gyro_sample_count": $sampleCountGyro}"""
            File(it, "Metadata.json").writeText(metadata)
        }
    }
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val timestamp = event.timestamp
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val line = "$timestamp,$x,$y,$z"
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            if (lastAccelTimestamp != 0L) {
                accelIntervals.add(timestamp - lastAccelTimestamp)
            }
            lastAccelValues = event.values.clone()
            lastAccelTimestamp = timestamp
            
            if (isRecording.get()) {
                accelBuffer.add(line)
                sampleCountAccel++
            }
        } else if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
             if (lastGyroTimestamp != 0L) {
                gyroIntervals.add(timestamp - lastGyroTimestamp)
            }
            lastGyroValues = event.values.clone()
            lastGyroTimestamp = timestamp

            if (isRecording.get()) {
                gyroBuffer.add(line)
                sampleCountGyro++
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Sensor Recording Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IMU Recording")
            .setContentText("Recording sensor data in background...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    // Simple Circular Buffer for rolling average
    private class CircularBuffer(val size: Int) {
        private val buffer = LongArray(size)
        private var head = 0
        private var count = 0
        private var sum: Long = 0

        @Synchronized
        fun add(value: Long) {
            if (count < size) {
                buffer[head] = value
                sum += value
                count++
            } else {
                sum -= buffer[head]
                buffer[head] = value
                sum += value
            }
            head = (head + 1) % size
        }
        
        @Synchronized
        fun clear() {
            count = 0
            head = 0
            sum = 0
        }

        @Synchronized
        fun average(): Double {
            return if (count == 0) 0.0 else sum.toDouble() / count
        }
    }

    companion object {
        const val TAG = "SensorRecorderService"
        const val CHANNEL_ID = "SensorRecorderChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_SYNC_TAP = "ACTION_SYNC_TAP"
    }
}
