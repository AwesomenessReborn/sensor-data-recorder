package com.example.sensor_recorder

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sensor_recorder.sync.BluetoothSyncService
import com.example.sensor_recorder.sync.BtConnectionState
import com.example.sensor_recorder.sync.DeviceRole
import com.example.sensor_recorder.sync.SyncReport
import com.example.sensor_recorder.ui.theme.Sensor_recorderTheme
import kotlinx.coroutines.delay
import timber.log.Timber
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var sensorService: SensorRecorderService? = null
    private var btSyncService: BluetoothSyncService? = null
    private var isSensorServiceBound = mutableStateOf(false)
    private var isBtSyncServiceBound = mutableStateOf(false)

    private val sensorServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            sensorService = (binder as SensorRecorderService.LocalBinder).getService()
            isSensorServiceBound.value = true
            Timber.i("SensorRecorderService connected")
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            isSensorServiceBound.value = false
            sensorService = null
            Timber.w("SensorRecorderService disconnected")
        }
    }

    private val btSyncServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            btSyncService = (binder as BluetoothSyncService.LocalBinder).getService()
            isBtSyncServiceBound.value = true
            Timber.i("BluetoothSyncService connected")
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            isBtSyncServiceBound.value = false
            btSyncService = null
            Timber.w("BluetoothSyncService disconnected")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Timber.d("Permission results: $permissions")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Intent(this, SensorRecorderService::class.java).also { intent ->
            bindService(intent, sensorServiceConnection, Context.BIND_AUTO_CREATE)
        }
        Intent(this, BluetoothSyncService::class.java).also { intent ->
            bindService(intent, btSyncServiceConnection, Context.BIND_AUTO_CREATE)
        }

        requestBluetoothPermissions()
        ExportUtils.cleanupOldZips(this)
        enableEdgeToEdge()

        setContent {
            Sensor_recorderTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RecorderScreen(
                        modifier = Modifier.padding(innerPadding),
                        sensorService = sensorService,
                        btSyncService = btSyncService,
                        isSensorServiceBound = isSensorServiceBound.value
                    )
                }
            }
        }
    }

    private fun requestBluetoothPermissions() {
        Timber.d("Requesting BT permissions, SDK=${Build.VERSION.SDK_INT}")
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            Timber.e("BluetoothAdapter is null — device has no BT")
            Toast.makeText(this, "Bluetooth is not available on this device", Toast.LENGTH_LONG).show()
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            Timber.w("Bluetooth is disabled — user must enable manually")
        }

        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.BLUETOOTH_ADMIN)
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.isNotEmpty()) requestPermissionLauncher.launch(perms.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isSensorServiceBound.value) unbindService(sensorServiceConnection)
        if (isBtSyncServiceBound.value) unbindService(btSyncServiceConnection)
    }
}

data class ScreenState(val role: DeviceRole, val btState: BtConnectionState, val isRecording: Boolean)

@Composable
fun RecorderScreen(
    modifier: Modifier = Modifier,
    sensorService: SensorRecorderService?,
    btSyncService: BluetoothSyncService?,
    isSensorServiceBound: Boolean
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("DeviceRolePrefs", Context.MODE_PRIVATE) }

    // ── Sensor service state ─────────────────────────────────────────────────
    var isRecording by remember { mutableStateOf(false) }
    var durationSeconds by remember { mutableStateOf(0L) }
    var accelCount by remember { mutableStateOf(0L) }
    var gyroCount by remember { mutableStateOf(0L) }
    var accelRate by remember { mutableStateOf(0.0) }
    var gyroRate by remember { mutableStateOf(0.0) }
    var lastRecordingDir by remember { mutableStateOf<File?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    // ── BT sync state ────────────────────────────────────────────────────────
    var deviceRole by remember {
        mutableStateOf(DeviceRole.valueOf(
            sharedPrefs.getString("role", DeviceRole.STANDALONE.name) ?: DeviceRole.STANDALONE.name
        ))
    }
    var btState by remember { mutableStateOf(BtConnectionState.IDLE) }
    var peerDeviceName by remember { mutableStateOf<String?>(null) }
    var syncReport by remember { mutableStateOf<SyncReport?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var connectedPeerAddresses by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Lets user open the device picker from RecordingScreen to add a 2nd device
    var showAddDeviceOverride by remember { mutableStateOf(false) }

    // ── Countdown state ──────────────────────────────────────────────────────
    var startDelaySeconds by remember { mutableIntStateOf(0) }
    var isCountingDown by remember { mutableStateOf(false) }
    var countdownValue by remember { mutableIntStateOf(0) }

    // ── Bluetooth scanning state ─────────────────────────────────────────────
    var isScanning by remember { mutableStateOf(false) }
    val scannedDevices = remember { mutableStateMapOf<String, Pair<BluetoothDevice, Int>>() }
    var isBluetoothEnabled by remember { mutableStateOf(true) }

    // ── Poll sensor service ──────────────────────────────────────────────────
    LaunchedEffect(isSensorServiceBound) {
        while (true) {
            if (isSensorServiceBound && sensorService != null) {
                isRecording = sensorService.isRecording.get()
                durationSeconds = if (isRecording)
                    (SystemClock.elapsedRealtimeNanos() - sensorService.startTimeNs) / 1_000_000_000L
                else 0L
                accelCount = sensorService.sampleCountAccel
                gyroCount = sensorService.sampleCountGyro
                accelRate = sensorService.getAccelRateHz()
                gyroRate = sensorService.getGyroRateHz()
                lastRecordingDir = sensorService.lastRecordingDir
            }
            delay(100)
        }
    }

    // ── Poll BT sync service and adapter status ──────────────────────────────
    LaunchedEffect(btSyncService) {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        while (true) {
            isBluetoothEnabled = adapter?.isEnabled == true
            btSyncService?.let { svc ->
                btState = svc.connectionState
                peerDeviceName = svc.peerDeviceName
                syncReport = svc.lastSyncReport
                errorMessage = svc.errorMessage
                connectedPeerAddresses = svc.connectedPeerAddresses
            }
            delay(200)
        }
    }

    // ── Bluetooth Scan Receiver ──────────────────────────────────────────────
    val scanReceiver = remember {
        object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                        val rssi: Int = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        if (device != null) {
                            scannedDevices[device.address] = device to rssi
                        }
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        // handled by polling
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> isScanning = true
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> isScanning = false
                }
            }
        }
    }

    DisposableEffect(context) {
        val filter = android.content.IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(scanReceiver, filter)
        onDispose {
            try { context.unregisterReceiver(scanReceiver) } catch (_: Exception) {}
        }
    }

    // ── Auto-start listener if role was persisted as WORKER ────────────────
    // onRoleSelected only fires on user tap; on cold start the service never
    // receives ACTION_LISTEN, so the RFCOMM server socket is never opened.
    LaunchedEffect(Unit) {
        if (deviceRole == DeviceRole.WORKER) {
            Timber.i("Auto-starting listener for persisted WORKER role")
            Intent(context, BluetoothSyncService::class.java).also {
                it.action = BluetoothSyncService.ACTION_LISTEN
                context.startForegroundService(it)
            }
        }
    }

    // ── Countdown Effect ─────────────────────────────────────────────────────
    LaunchedEffect(isCountingDown) {
        if (isCountingDown) {
            while (countdownValue > 0) {
                delay(1000)
                countdownValue--
            }
            isCountingDown = false
            if (!isRecording) {
                if (deviceRole == DeviceRole.CONTROLLER) {
                    btSyncService?.sendCommand("CMD_START")
                }
                sensorService?.startRecording()
            }
        }
    }

    val onRoleSelected: (DeviceRole) -> Unit = { newRole ->
        Timber.i("Device role changed to $newRole")
        deviceRole = newRole
        sharedPrefs.edit().putString("role", newRole.name).apply()
        if (newRole == DeviceRole.WORKER) {
            Intent(context, BluetoothSyncService::class.java).also {
                it.action = BluetoothSyncService.ACTION_LISTEN
                context.startForegroundService(it)
            }
        } else if (newRole == DeviceRole.STANDALONE) {
            Intent(context, BluetoothSyncService::class.java).also {
                it.action = BluetoothSyncService.ACTION_DISCONNECT
                context.startService(it)
            }
        } else if (newRole == DeviceRole.CONTROLLER) {
            Intent(context, BluetoothSyncService::class.java).also {
                it.action = BluetoothSyncService.ACTION_DISCONNECT
                context.startService(it)
            }
        }
    }

    val screenState = ScreenState(deviceRole, btState, isRecording)

    // ── Determine which screen to show ───────────────────────────────────────
    val showDevicePicker = deviceRole == DeviceRole.CONTROLLER &&
        (btState in listOf(BtConnectionState.IDLE, BtConnectionState.ERROR) || showAddDeviceOverride)
    val showConnecting = !showDevicePicker &&
        deviceRole == DeviceRole.CONTROLLER &&
        btState in listOf(BtConnectionState.CONNECTING, BtConnectionState.SYNCING)

    val connectDeviceAction: (BluetoothDevice) -> Unit = { device ->
        showAddDeviceOverride = false
        val intent = Intent(context, BluetoothSyncService::class.java).apply {
            action = BluetoothSyncService.ACTION_CONNECT
            putExtra(BluetoothSyncService.EXTRA_DEVICE_ADDRESS, device.address)
        }
        context.startForegroundService(intent)
    }

    Crossfade(targetState = screenState, modifier = modifier, label = "Screen Transition") { state ->
        when {
            state.role == DeviceRole.STANDALONE -> {
                RecordingScreen(
                    deviceRole = state.role,
                    btState = state.btState,
                    isRecording = isRecording,
                    durationSeconds = durationSeconds,
                    accelCount = accelCount,
                    gyroCount = gyroCount,
                    accelRate = accelRate,
                    gyroRate = gyroRate,
                    lastRecordingDir = lastRecordingDir,
                    isExporting = isExporting,
                    peerDeviceName = peerDeviceName,
                    syncReport = syncReport,
                    isCountingDown = isCountingDown,
                    countdownValue = countdownValue,
                    startDelaySeconds = startDelaySeconds,
                    onRoleSelected = onRoleSelected,
                    onStartDelayChange = { startDelaySeconds = it },
                    onRecordToggle = {
                        if (isRecording) {
                            sensorService?.stopRecording()
                        } else {
                            if (startDelaySeconds > 0) {
                                isCountingDown = true
                                countdownValue = startDelaySeconds
                            } else {
                                sensorService?.startRecording()
                            }
                        }
                    },
                    onSyncTap = { sensorService?.logSyncTap() },
                    onExportToggle = { isExporting = it }
                )
            }
            showDevicePicker -> {
                DevicePickerScreen(
                    deviceRole = state.role,
                    onRoleSelected = onRoleSelected,
                    errorMessage = if (state.btState == BtConnectionState.ERROR) errorMessage else null,
                    connectedPeerAddresses = connectedPeerAddresses,
                    isAddingDevice = showAddDeviceOverride,
                    isBluetoothEnabled = isBluetoothEnabled,
                    isScanning = isScanning,
                    scannedDevices = scannedDevices,
                    onStartScan = {
                        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                        try {
                            btManager.adapter?.startDiscovery()
                        } catch (e: SecurityException) {
                            Timber.e(e, "No permission to start discovery")
                        }
                    },
                    onBack = if (showAddDeviceOverride) {{ showAddDeviceOverride = false }} else null,
                    onDeviceSelected = connectDeviceAction
                )
            }
            showConnecting -> {
                ConnectingScreen(
                    state = state.btState,
                    peerDeviceName = peerDeviceName,
                    onCancel = {
                        showAddDeviceOverride = false
                        Intent(context, BluetoothSyncService::class.java).also {
                            it.action = BluetoothSyncService.ACTION_DISCONNECT
                            context.startService(it)
                        }
                    }
                )
            }
            state.role == DeviceRole.CONTROLLER -> { // READY or RECORDING
                RecordingScreen(
                    deviceRole = state.role,
                    btState = state.btState,
                    isRecording = isRecording,
                    durationSeconds = durationSeconds,
                    accelCount = accelCount,
                    gyroCount = gyroCount,
                    accelRate = accelRate,
                    gyroRate = gyroRate,
                    lastRecordingDir = lastRecordingDir,
                    isExporting = isExporting,
                    peerDeviceName = peerDeviceName,
                    syncReport = syncReport,
                    isCountingDown = isCountingDown,
                    countdownValue = countdownValue,
                    startDelaySeconds = startDelaySeconds,
                    onRoleSelected = onRoleSelected,
                    onStartDelayChange = { startDelaySeconds = it },
                    onRecordToggle = {
                        if (isRecording) {
                            btSyncService?.sendCommand("CMD_STOP")
                            sensorService?.stopRecording()
                        } else {
                            if (startDelaySeconds > 0) {
                                isCountingDown = true
                                countdownValue = startDelaySeconds
                            } else {
                                btSyncService?.sendCommand("CMD_START")
                                sensorService?.startRecording()
                            }
                        }
                    },
                    onSyncTap = {
                        btSyncService?.sendCommand("CMD_SYNC_TAP")
                        sensorService?.logSyncTap()
                    },
                    onExportToggle = { isExporting = it },
                    onSyncClocks = {
                        Intent(context, BluetoothSyncService::class.java).also {
                            it.action = BluetoothSyncService.ACTION_SYNC_CLOCKS
                            context.startService(it)
                        }
                    },
                    onAddDevice = { showAddDeviceOverride = true }
                )
            }
            state.role == DeviceRole.WORKER && !state.isRecording && state.btState in listOf(BtConnectionState.IDLE, BtConnectionState.CONNECTING, BtConnectionState.SYNCING) -> {
                ListenerWaitingScreen(
                    deviceRole = state.role,
                    onRoleSelected = onRoleSelected,
                    state = state.btState,
                    peerDeviceName = peerDeviceName
                )
            }
            else -> { // WORKER READY or RECORDING
                RecordingScreen(
                    deviceRole = state.role,
                    btState = state.btState,
                    isRecording = isRecording,
                    durationSeconds = durationSeconds,
                    accelCount = accelCount,
                    gyroCount = gyroCount,
                    accelRate = accelRate,
                    gyroRate = gyroRate,
                    lastRecordingDir = lastRecordingDir,
                    isExporting = isExporting,
                    peerDeviceName = peerDeviceName,
                    syncReport = syncReport,
                    isCountingDown = isCountingDown,
                    countdownValue = countdownValue,
                    startDelaySeconds = startDelaySeconds,
                    onRoleSelected = onRoleSelected,
                    onStartDelayChange = { startDelaySeconds = it },
                    onRecordToggle = {
                        if (isRecording) {
                            sensorService?.stopRecording()
                        }
                    },
                    onSyncTap = { sensorService?.logSyncTap() },
                    onExportToggle = { isExporting = it }
                )
            }
        }
    }
}

// ── Composables ──────────────────────────────────────────────────────────────

@Composable
fun DevicePickerScreen(
    deviceRole: DeviceRole,
    onRoleSelected: (DeviceRole) -> Unit,
    errorMessage: String?,
    connectedPeerAddresses: Set<String> = emptySet(),
    isAddingDevice: Boolean = false,
    isBluetoothEnabled: Boolean = true,
    isScanning: Boolean = false,
    scannedDevices: Map<String, Pair<BluetoothDevice, Int>> = emptyMap(),
    onStartScan: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    onDeviceSelected: (BluetoothDevice) -> Unit
) {
    val context = LocalContext.current
    val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bondedDevices: List<BluetoothDevice> = try {
        btManager.adapter?.bondedDevices?.toList() ?: emptyList()
    } catch (e: SecurityException) {
        Timber.e(e, "No BT permission to list bonded devices")
        emptyList()
    }

    // Auto-start scan when screen opens
    LaunchedEffect(Unit) {
        if (isBluetoothEnabled && !isScanning) onStartScan()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("IMU Recorder", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        // Bluetooth Status Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isBluetoothEnabled)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isBluetoothEnabled) "Bluetooth Enabled" else "Bluetooth Disabled",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isBluetoothEnabled) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else if (isBluetoothEnabled) {
                    TextButton(onClick = onStartScan, contentPadding = PaddingValues(0.dp)) {
                        Text("Scan", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Only show role selector when not in "add device" overlay mode
        if (!isAddingDevice) {
            DeviceRoleSelector(selectedRole = deviceRole, onRoleSelected = onRoleSelected)
            Spacer(Modifier.height(16.dp))
        }

        if (errorMessage != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Connection Failed", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            Intent(context, BluetoothSyncService::class.java).also {
                                it.action = BluetoothSyncService.ACTION_DISCONNECT
                                context.startService(it)
                            }
                        }) {
                            Text("Dismiss", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(errorMessage, color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        val title = if (isAddingDevice) "Add another worker device" else "Select worker device"
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (onBack != null) {
                TextButton(onClick = onBack) { Text("Cancel") }
            }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // ── Section: Paired Devices ─────────────────────────────────────────
            item {
                Text("PAIRED DEVICES", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
            }

            if (bondedDevices.isEmpty()) {
                item {
                    Text("No paired devices found.", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 16.dp))
                }
            } else {
                items(bondedDevices) { device ->
                    val name = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
                    val isAlreadyConnected = device.address in connectedPeerAddresses
                    DeviceListItem(
                        name = name,
                        address = device.address,
                        isPaired = true,
                        isConnected = isAlreadyConnected,
                        rssi = scannedDevices[device.address]?.second,
                        onClick = { if (!isAlreadyConnected) onDeviceSelected(device) }
                    )
                }
            }

            // ── Section: Other Devices (Scanned) ──────────────────────────────
            val otherDevices = scannedDevices.values
                .filter { it.first.address !in bondedDevices.map { b -> b.address } }
                .sortedByDescending { it.second }

            if (otherDevices.isNotEmpty() || isScanning) {
                item {
                    Text("OTHER DEVICES", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
                }

                items(otherDevices) { (device, rssi) ->
                    val name = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
                    DeviceListItem(
                        name = name,
                        address = device.address,
                        isPaired = false,
                        isConnected = false,
                        rssi = rssi,
                        onClick = { onDeviceSelected(device) }
                    )
                }
            }
        }

        if (!isBluetoothEnabled) {
            Text(
                "Bluetooth is disabled. Please enable it in Settings.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

@Composable
fun DeviceListItem(
    name: String,
    address: String,
    isPaired: Boolean,
    isConnected: Boolean,
    rssi: Int?,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                name,
                color = if (isConnected)
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                else
                    MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Column {
                if (isConnected) {
                    Text("● Already connected",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium)
                } else {
                    Text(address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rssi != null) {
                    val strength = when {
                        rssi > -60 -> "Strong"
                        rssi > -80 -> "Good"
                        else -> "Weak"
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${rssi}dBm", style = MaterialTheme.typography.labelSmall)
                        Text(strength, style = MaterialTheme.typography.labelSmall,
                            color = if (strength == "Strong") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!isPaired) {
                    Spacer(Modifier.width(8.dp))
                    Text("Pair Required", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        },
        modifier = if (isConnected) Modifier
        else Modifier.clickable { onClick() }
    )
    HorizontalDivider()
}

@Composable
fun ConnectingScreen(
    state: BtConnectionState,
    peerDeviceName: String?,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        val text = if (state == BtConnectionState.CONNECTING) {
            "Connecting to ${peerDeviceName ?: "device"}..."
        } else {
            "Syncing clocks with ${peerDeviceName ?: "device"}..."
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onCancel) {
            Text("Cancel")
        }
    }
}

@Composable
fun ListenerWaitingScreen(
    deviceRole: DeviceRole,
    onRoleSelected: (DeviceRole) -> Unit,
    state: BtConnectionState,
    peerDeviceName: String?
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("IMU Recorder", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        DeviceRoleSelector(selectedRole = deviceRole, onRoleSelected = onRoleSelected)
        
        Spacer(Modifier.weight(1f))
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        
        val text = if (state == BtConnectionState.SYNCING) {
            "Syncing clocks with ${peerDeviceName ?: "controller"}..."
        } else {
            "Waiting for controller..."
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (state != BtConnectionState.SYNCING) {
            Text("Make sure the controller device selects this phone.", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    deviceRole: DeviceRole,
    btState: BtConnectionState,
    isRecording: Boolean,
    durationSeconds: Long,
    accelCount: Long,
    gyroCount: Long,
    accelRate: Double,
    gyroRate: Double,
    lastRecordingDir: File?,
    isExporting: Boolean,
    peerDeviceName: String?,
    syncReport: SyncReport?,
    isCountingDown: Boolean,
    countdownValue: Int,
    startDelaySeconds: Int,
    onRoleSelected: (DeviceRole) -> Unit,
    onStartDelayChange: (Int) -> Unit,
    onRecordToggle: () -> Unit,
    onSyncTap: () -> Unit,
    onExportToggle: (Boolean) -> Unit,
    onSyncClocks: (() -> Unit)? = null,
    onAddDevice: (() -> Unit)? = null
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        // ── Scrollable content ───────────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        Text("IMU Recorder", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        // Role selector (hidden while recording)
        if (!isRecording && !isCountingDown) {
            DeviceRoleSelector(
                selectedRole = deviceRole,
                onRoleSelected = onRoleSelected
            )
        }

        // Status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isRecording) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isRecording) "● RECORDING" else "READY",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isRecording) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    String.format(Locale.US, "%02d:%02d", durationSeconds / 60, durationSeconds % 60),
                    style = MaterialTheme.typography.displayMedium
                )
            }
        }

        // BT status card (Controller or Worker only)
        if (deviceRole != DeviceRole.STANDALONE) {
            BluetoothStatusCard(
                role = deviceRole,
                state = btState,
                peerDeviceName = peerDeviceName,
                onSyncClocks = onSyncClocks,
                onAddDevice = if (!isRecording && !isCountingDown) onAddDevice else null
            )
        }

        // Stats
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatItem("Accel Samples", String.format(Locale.US, "%,d", accelCount))
            StatItem("Gyro Samples", String.format(Locale.US, "%,d", gyroCount))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatItem("Accel Rate", String.format(Locale.US, "%.1f Hz", accelRate))
            StatItem("Gyro Rate", String.format(Locale.US, "%.1f Hz", gyroRate))
        }

        HorizontalDivider()

        // Sync report card
        if (deviceRole == DeviceRole.CONTROLLER && syncReport != null) {
            SyncReportCard(report = syncReport)
        }

        Spacer(Modifier.height(8.dp))
        } // end scrollable column

        // ── Pinned bottom section ────────────────────────────────────────────
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

        // START DELAY SELECTOR
        if (!isRecording && !isCountingDown && deviceRole != DeviceRole.WORKER) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Start Delay:", style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow {
                    listOf(0, 3, 5).forEachIndexed { index, delayValue ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                            onClick = { onStartDelayChange(delayValue) },
                            selected = startDelaySeconds == delayValue
                        ) {
                            Text(if (delayValue == 0) "None" else "${delayValue}s")
                        }
                    }
                }
            }
        }

        // START / STOP
        val isStartEnabled = if (deviceRole == DeviceRole.CONTROLLER) {
            btState == BtConnectionState.READY && syncReport != null
        } else {
            true
        }

        if (isCountingDown) {
            Card(
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Starting in $countdownValue...",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Button(
                onClick = onRecordToggle,
                enabled = isRecording || (isStartEnabled && deviceRole != DeviceRole.WORKER),
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    if (isRecording) "STOP RECORDING" else "START RECORDING",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        // SYNC TAP
        Button(
            onClick = onSyncTap,
            enabled = isRecording,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("SYNC TAP ⚡", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        // SHARE RECORDING
        if (!isRecording && lastRecordingDir != null) {
            Button(
                onClick = {
                    onExportToggle(true)
                    if (!ExportUtils.shareRecording(context, lastRecordingDir)) {
                        Toast.makeText(context, "Failed to export recording", Toast.LENGTH_SHORT).show()
                    }
                    onExportToggle(false)
                },
                enabled = !isExporting,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text(if (isExporting) "EXPORTING..." else "SHARE RECORDING", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        } // end pinned column
    } // end outer column
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceRoleSelector(selectedRole: DeviceRole, onRoleSelected: (DeviceRole) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        DeviceRole.values().forEachIndexed { index, role ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = DeviceRole.values().size),
                onClick = { onRoleSelected(role) },
                selected = role == selectedRole
            ) {
                Text(role.name)
            }
        }
    }
}

@Composable
fun BluetoothStatusCard(
    role: DeviceRole,
    state: BtConnectionState,
    peerDeviceName: String?,
    onSyncClocks: (() -> Unit)? = null,
    onAddDevice: (() -> Unit)? = null
) {
    val statusText = when (state) {
        BtConnectionState.IDLE -> if (role == DeviceRole.WORKER) "● Listening..." else "○ Not connected"
        BtConnectionState.CONNECTING -> "⟳ Connecting..."
        BtConnectionState.SYNCING -> "⟳ Syncing clocks..."
        BtConnectionState.READY -> "● Ready"
        BtConnectionState.RECORDING -> "● Recording"
        BtConnectionState.ERROR -> "✕ Error"
    }
    val peerNames = peerDeviceName
        ?.split(", ")
        ?.filter { it.isNotBlank() }
        ?: emptyList()
    val showPeerList = state in listOf(BtConnectionState.READY, BtConnectionState.RECORDING, BtConnectionState.SYNCING) && peerNames.isNotEmpty()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Bluetooth Sync", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(statusText, style = MaterialTheme.typography.bodyLarge)
            if (showPeerList) {
                Spacer(Modifier.height(4.dp))
                peerNames.forEach { name ->
                    Text("  • $name", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (role == DeviceRole.CONTROLLER) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onAddDevice != null) {
                        Button(onClick = onAddDevice) {
                            Text("Add Device")
                        }
                    }
                    if (onSyncClocks != null) {
                        OutlinedButton(
                            onClick = onSyncClocks,
                            enabled = state == BtConnectionState.READY
                        ) {
                            Text(if (state == BtConnectionState.SYNCING) "Syncing..." else "Sync Clocks")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SyncReportCard(report: SyncReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Sync Report", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            
            report.workers.forEachIndexed { index, worker ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                
                Text(worker.deviceName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(
                    String.format(Locale.US, "Clock offset:  %.2f ms ± %.2f ms", worker.offsetNs / 1_000_000.0, worker.stdDevNs / 1_000_000.0),
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Samples used:  ${worker.sampleCount}", style = MaterialTheme.typography.bodySmall)
                if (worker.workerAccelCount > 0 || worker.workerGyroCount > 0) {
                    Text(
                        String.format(Locale.US, "Worker data:   %,d accel, %,d gyro", worker.workerAccelCount, worker.workerGyroCount),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}