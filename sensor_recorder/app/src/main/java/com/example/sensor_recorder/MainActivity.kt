package com.example.sensor_recorder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sensor_recorder.ui.theme.Sensor_recorderTheme
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var service: SensorRecorderService? = null
    private var isBound = mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            val localBinder = binder as SensorRecorderService.LocalBinder
            service = localBinder.getService()
            isBound.value = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound.value = false
            service = null
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Handle permission result if needed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Bind to service
        Intent(this, SensorRecorderService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        // Clean up old ZIP files on app start
        ExportUtils.cleanupOldZips(this)

        enableEdgeToEdge()
        setContent {
            Sensor_recorderTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RecorderScreen(
                        modifier = Modifier.padding(innerPadding),
                        service = service,
                        isBound = isBound.value
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound.value) {
            unbindService(connection)
            isBound.value = false
        }
    }
}

@Composable
fun RecorderScreen(
    modifier: Modifier = Modifier,
    service: SensorRecorderService?,
    isBound: Boolean
) {
    var isRecording by remember { mutableStateOf(false) }
    var durationSeconds by remember { mutableStateOf(0L) }
    var accelCount by remember { mutableStateOf(0L) }
    var gyroCount by remember { mutableStateOf(0L) }
    var accelRate by remember { mutableStateOf(0.0) }
    var gyroRate by remember { mutableStateOf(0.0) }
    var accelValues by remember { mutableStateOf(FloatArray(3)) }
    var gyroValues by remember { mutableStateOf(FloatArray(3)) }
    var lastRecordingDir by remember { mutableStateOf<File?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Poll service for status
    LaunchedEffect(isBound) {
        while (true) {
            if (isBound && service != null) {
                isRecording = service.isRecording.get()
                if (isRecording) {
                    val elapsedNs = SystemClock.elapsedRealtimeNanos() - service.startTimeNs
                    durationSeconds = elapsedNs / 1_000_000_000L
                } else {
                    durationSeconds = 0
                }
                accelCount = service.sampleCountAccel
                gyroCount = service.sampleCountGyro
                accelRate = service.getAccelRateHz()
                gyroRate = service.getGyroRateHz()
                accelValues = service.lastAccelValues
                gyroValues = service.lastGyroValues
                lastRecordingDir = service.lastRecordingDir
            }
            delay(100) // Update every 100ms
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "IMU Recorder",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isRecording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isRecording) "● RECORDING" else "READY",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isRecording) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = String.format(Locale.US, "%02d:%02d", durationSeconds / 60, durationSeconds % 60),
                    style = MaterialTheme.typography.displayMedium
                )
            }
        }

        // Stats Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(label = "Accel Samples", value = String.format(Locale.US, "%,d", accelCount))
            StatItem(label = "Gyro Samples", value = String.format(Locale.US, "%,d", gyroCount))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(label = "Accel Rate", value = String.format(Locale.US, "%.1f Hz", accelRate))
            StatItem(label = "Gyro Rate", value = String.format(Locale.US, "%.1f Hz", gyroRate))
        }
        
        HorizontalDivider()

        // Live Values
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Live Data",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                SensorValueColumn("Accel (m/s²)", accelValues)
                SensorValueColumn("Gyro (rad/s)", gyroValues)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Controls
        Button(
            onClick = {
                if (isBound && service != null) {
                    if (isRecording) {
                        // Stop
                        val intent = Intent(service, SensorRecorderService::class.java).apply {
                            action = SensorRecorderService.ACTION_STOP
                        }
                        service.startService(intent) // Send command via startService
                    } else {
                        // Start
                        val intent = Intent(service, SensorRecorderService::class.java).apply {
                            action = SensorRecorderService.ACTION_START
                        }
                        service.startForegroundService(intent)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = if (isRecording) "STOP RECORDING" else "START RECORDING",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Button(
            onClick = {
                if (isBound && service != null) {
                    val intent = Intent(service, SensorRecorderService::class.java).apply {
                        action = SensorRecorderService.ACTION_SYNC_TAP
                    }
                    service.startService(intent)
                }
            },
            enabled = isRecording,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Text(
                text = "SYNC TAP ⚡",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Export button (visible only when recording is stopped and a recording exists)
        if (!isRecording && lastRecordingDir != null) {
            Button(
                onClick = {
                    isExporting = true
                    lastRecordingDir?.let { dir ->
                        val success = ExportUtils.shareRecording(context, dir)
                        if (!success) {
                            Toast.makeText(context, "Failed to export recording", Toast.LENGTH_SHORT).show()
                        }
                    }
                    isExporting = false
                },
                enabled = !isExporting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Text(
                    text = if (isExporting) "EXPORTING..." else "SHARE RECORDING",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SensorValueColumn(title: String, values: FloatArray) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        if (values.isNotEmpty() && values.size >= 3) {
            Text(text = String.format(Locale.US, "X: %10.6f", values[0]), style = MaterialTheme.typography.bodyMedium)
            Text(text = String.format(Locale.US, "Y: %10.6f", values[1]), style = MaterialTheme.typography.bodyMedium)
            Text(text = String.format(Locale.US, "Z: %10.6f", values[2]), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
