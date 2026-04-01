// BluetoothSyncService.kt
package com.example.sensor_recorder.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.sensor_recorder.MainActivity
import com.example.sensor_recorder.R
import com.example.sensor_recorder.SensorRecorderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Encapsulates the state for a single peer connection.
 */
data class PeerConnection(
    val device: BluetoothDevice,
    val socket: BluetoothSocket,
    val reader: BufferedReader,
    val writer: BufferedWriter,
    val pongChannel: Channel<SyncMessage.Pong>,
    val estimator: ClockOffsetEstimator,
    @Volatile var clockOffsetResult: ClockOffsetEstimator.Result? = null,
    @Volatile var lastStatusReport: SyncMessage.Status? = null,
    @Volatile var isSyncing: Boolean = false
)

class BluetoothSyncService : Service() {

    // ── Binder ──────────────────────────────────────────────────────────────
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothSyncService = this@BluetoothSyncService
    }

    override fun onBind(intent: Intent): IBinder = binder

    // ── State exposed to UI (polled by MainActivity) ─────────────────────────
    @Volatile var connectionState: BtConnectionState = BtConnectionState.IDLE
    @Volatile var peerDeviceName: String? = null // Summary string for multiple peers
    @Volatile var lastSyncReport: SyncReport? = null
    @Volatile var errorMessage: String? = null
    @Volatile var connectedPeerAddresses: Set<String> = emptySet()

    // ── Internal ─────────────────────────────────────────────────────────────
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val peers = CopyOnWriteArrayList<PeerConnection>()
    private var serverSocket: BluetoothServerSocket? = null
    private var currentSessionId: String? = null
    private var role: DeviceRole = DeviceRole.STANDALONE
    private val MAX_PEERS = 3

    // ── Binding to SensorRecorderService ─────────────────────────────────────
    private var sensorService: SensorRecorderService? = null
    private val sensorServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            sensorService = (binder as SensorRecorderService.LocalBinder).getService()
            Timber.d("BluetoothSyncService bound to SensorRecorderService")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            sensorService = null
            Timber.w("BluetoothSyncService lost SensorRecorderService binding")
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        Timber.i("BluetoothSyncService created")
        createNotificationChannel()
        bindService(
            Intent(this, SensorRecorderService::class.java),
            sensorServiceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("BluetoothSyncService destroyed")
        handleDisconnect()
        try { unbindService(sensorServiceConnection) } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_CONNECT -> {
                role = DeviceRole.CONTROLLER
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: return START_NOT_STICKY
                val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val device = btManager.adapter.getRemoteDevice(address)
                connectToDevice(device)
            }
            ACTION_LISTEN -> {
                role = DeviceRole.WORKER
                startListening()
            }
            ACTION_DISCONNECT -> handleDisconnect()
            ACTION_SYNC_CLOCKS -> {
                if (role == DeviceRole.CONTROLLER && peers.isNotEmpty()) {
                    peers.forEach { peer ->
                        serviceScope.launch { runClockSync(peer) }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    // ── Controller: connect to device ────────────────────────────────────────
    private fun connectToDevice(device: BluetoothDevice) {
        // Prevent duplicate connections
        if (peers.any { it.device.address == device.address }) {
            Timber.w("Already connected to ${device.address}")
            return
        }

        serviceScope.launch {
            var attempt = 0
            var connected = false
            errorMessage = null // Clear stale errors from previous attempts

            // Only show CONNECTING state if no peers are already READY — avoids
            // hiding an established connection when the user adds a second device.
            val hadReadyPeers = peers.any { it.clockOffsetResult != null }
            if (!hadReadyPeers) updateConnectionState(BtConnectionState.CONNECTING)
            startForegroundIfNeeded()

            while (attempt < 3 && !connected) {
                attempt++
                try {
                    Timber.i("Connecting to ${device.name} (${device.address}) - Attempt $attempt")

                    val btSocket = connectWithFallback(device)

                    val peer = PeerConnection(
                        device = device,
                        socket = btSocket,
                        reader = BufferedReader(InputStreamReader(btSocket.inputStream)),
                        writer = BufferedWriter(OutputStreamWriter(btSocket.outputStream)),
                        pongChannel = Channel(Channel.BUFFERED),
                        estimator = ClockOffsetEstimator()
                    )
                    peers.add(peer)
                    connected = true
                    Timber.i("Connected to ${device.name}")

                    startReaderLoop(peer)
                    runClockSync(peer)
                    break
                } catch (e: Exception) {
                    Timber.e(e, "Connection failed on attempt $attempt")
                    if (attempt == 3) {
                        errorMessage = "Connection to ${device.name} failed: ${e.message}"
                        // Only go to ERROR if we have no other working peers
                        if (peers.none { it.clockOffsetResult != null }) {
                            updateConnectionState(BtConnectionState.ERROR)
                        } else {
                            updateConnectionState() // recompute aggregate — keeps READY
                        }
                    } else {
                        delay(1000) // Slightly longer wait before retrying
                    }
                }
            }
        }
    }

    private fun safeClose(socket: BluetoothSocket?) {
        try {
            socket?.close()
        } catch (e: Exception) {
            Timber.v("Error closing socket: ${e.message}")
        }
    }

    private fun safeClose(ss: BluetoothServerSocket?) {
        try {
            ss?.close()
        } catch (e: Exception) {
            Timber.v("Error closing server socket: ${e.message}")
        }
    }

    @Suppress("DiscouragedPrivateApi")
    private suspend fun connectWithFallback(device: BluetoothDevice): BluetoothSocket {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter

        // List of strategies to try sequentially
        val strategies = listOf(
            // 1. Standard SDP Secure
            { device.createRfcommSocketToServiceRecord(SERVICE_UUID) },
            // 2. Standard SDP Insecure (Often works when Secure fails)
            { device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID) },
            // 3. Reflection Secure (Channel 1)
            { device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket },
            // 4. Reflection Insecure (Channel 1)
            { device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket }
        )

        var lastEx: Exception? = null
        for ((index, strategy) in strategies.withIndex()) {
            var socket: BluetoothSocket? = null
            try {
                socket = strategy()
                if (socket == null) continue

                // Ensure discovery is OFF. Discovery significantly degrades connection success rate.
                if (adapter.isDiscovering) {
                    adapter.cancelDiscovery()
                    delay(200) // Give the radio a moment to settle
                }

                Timber.d("Trying Strategy $index for ${device.name}...")
                withContext(Dispatchers.IO) {
                    socket.connect()
                }
                Timber.i("Strategy $index succeeded for ${device.name}")
                return socket
            } catch (e: Exception) {
                Timber.w("Strategy $index failed for ${device.name}: ${e.message}")
                lastEx = e
                safeClose(socket)
                socket = null // Assist GC
            }
        }
        throw lastEx ?: IOException("All connection methods failed for ${device.name}")
    }

    // ── Controller: clock sync ────────────────────────────────────────────────
    private suspend fun runClockSync(peer: PeerConnection) {
        Timber.i("Starting clock sync for ${peer.device.name} (10 rounds)")
        peer.isSyncing = true
        updateConnectionState()
        peer.estimator.clear()

        // Clear any old pongs
        while (peer.pongChannel.tryReceive().isSuccess) {}

        repeat(10) { i ->
            try {
                val tSend = SystemClock.elapsedRealtimeNanos()
                sendLineToPeer(peer, SyncMessage.Ping(tSend).toJson())

                val pong = withTimeoutOrNull(2000) { peer.pongChannel.receive() }
                if (pong == null) {
                    Timber.w("Clock sync round $i timed out for ${peer.device.name}")
                    return@repeat
                }

                val tRecv = SystemClock.elapsedRealtimeNanos()
                peer.estimator.addSample(tSend, pong.tRecvNs, pong.tReplyNs, tRecv)
                delay(100)
            } catch (e: Exception) {
                Timber.e(e, "Clock sync error on round $i for ${peer.device.name}")
                removePeer(peer)
                return
            }
        }

        val result = peer.estimator.estimate()
        peer.isSyncing = false
        if (result == null) {
            Timber.w("Clock sync failed for ${peer.device.name}")
            errorMessage = "Clock sync failed for ${peer.device.name}"
            updateConnectionState()
            return
        }

        peer.clockOffsetResult = result
        Timber.i("Clock sync done for ${peer.device.name}: offset=${result.offsetNs / 1_000_000}ms")
        sendLineToPeer(peer, SyncMessage.SyncDone.toJson())

        // Push offset into SensorRecorderService
        val name = peer.device.name ?: peer.device.address
        sensorService?.btClockOffsets?.put(name, result)

        // Publish a preliminary SyncReport so the UI's START button becomes enabled.
        // workerAccelCount/gyroCount will be filled in properly after CMD_STOP.
        lastSyncReport = SyncReport(peers.mapNotNull { p ->
            val r = p.clockOffsetResult ?: return@mapNotNull null
            WorkerReport(
                deviceName = p.device.name ?: p.device.address,
                offsetNs = r.offsetNs,
                stdDevNs = r.stdDevNs,
                sampleCount = r.sampleCount,
                workerAccelCount = 0,
                workerGyroCount = 0
            )
        })

        updateConnectionState()
    }

    // ── Controller: send command ──────────────────────────────────────────────
    fun sendCommand(commandType: String) {
        val sessionId = currentSessionId ?: run {
            val id = UUID.randomUUID().toString().take(8)
            currentSessionId = id
            id
        }
        Timber.d("Sending command $commandType to ${peers.size} peers")
        val json = SyncMessage.Command(commandType, sessionId).toJson()
        peers.forEach { sendLineToPeer(it, json) }

        if (commandType == "CMD_START") updateConnectionState(BtConnectionState.RECORDING)
    }

    // ── Worker: listen for incoming connection ────────────────────────────────
    private fun startListening() {
        serviceScope.launch {
            try {
                Timber.i("Worker: opening RFCOMM server socket")
                updateConnectionState(BtConnectionState.CONNECTING)
                startForegroundIfNeeded()

                val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val ss = btManager.adapter.listenUsingRfcommWithServiceRecord("IMUSyncService", SERVICE_UUID)
                serverSocket = ss

                val btSocket = withContext(Dispatchers.IO) { ss.accept() }
                ss.close()
                serverSocket = null

                val peer = PeerConnection(
                    device = btSocket.remoteDevice,
                    socket = btSocket,
                    reader = BufferedReader(InputStreamReader(btSocket.inputStream)),
                    writer = BufferedWriter(OutputStreamWriter(btSocket.outputStream)),
                    pongChannel = Channel(Channel.BUFFERED),
                    estimator = ClockOffsetEstimator()
                )
                peers.add(peer)
                Timber.i("Worker: Controller connected from ${btSocket.remoteDevice.name}")

                updateConnectionState(BtConnectionState.SYNCING)
                startReaderLoop(peer)
            } catch (e: IOException) {
                Timber.e(e, "Worker: server socket error")
                errorMessage = "Listen failed: ${e.message}"
                updateConnectionState(BtConnectionState.ERROR)
                handleDisconnect()
            }
        }
    }

    // ── Per-peer reader loop ──────────────────────────────────────────────────
    private fun startReaderLoop(peer: PeerConnection) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                while (peer.socket.isConnected) {
                    val line = peer.reader.readLine() ?: break
                    Timber.v("Reader [${peer.device.name}] raw: $line")
                    val msg = SyncMessage.fromJson(line)
                    if (msg == null) {
                        Timber.w("Reader [${peer.device.name}] unknown/unparseable message: $line")
                        continue
                    }
                    handleMessage(msg, peer)
                }
            } catch (e: IOException) {
                Timber.e(e, "Reader loop error for ${peer.device.name}")
            }
            removePeer(peer)
        }
    }

    private fun handleMessage(msg: SyncMessage, peer: PeerConnection) {
        when (msg) {
            is SyncMessage.Ping -> {
                Timber.d("Worker: PING from ${peer.device.name}")
                val tRecv = SystemClock.elapsedRealtimeNanos()
                val tReply = SystemClock.elapsedRealtimeNanos()
                sendLineToPeer(peer, SyncMessage.Pong(msg.tNs, tRecv, tReply).toJson())
            }
            is SyncMessage.Command -> {
                val tNs = SystemClock.elapsedRealtimeNanos()
                when (msg.commandType) {
                    "CMD_START" -> {
                        currentSessionId = msg.sessionId
                        sensorService?.startRecording()
                        updateConnectionState(BtConnectionState.RECORDING)
                    }
                    "CMD_STOP" -> {
                        sensorService?.stopRecording()
                        updateConnectionState(BtConnectionState.READY)
                        serviceScope.launch {
                            delay(500)
                            val accel = sensorService?.sampleCountAccel ?: 0
                            val gyro = sensorService?.sampleCountGyro ?: 0
                            sendLineToPeer(peer, SyncMessage.Status(false, accel, gyro).toJson())
                        }
                    }
                    "CMD_SYNC_TAP" -> sensorService?.logSyncTap()
                }
                sendLineToPeer(peer, SyncMessage.Ack(msg.commandType, msg.sessionId, tNs).toJson())
            }
            is SyncMessage.Pong -> peer.pongChannel.trySend(msg)
            is SyncMessage.Status -> {
                peer.lastStatusReport = msg
                val allReported = peers.all { it.lastStatusReport != null }
                if (allReported) {
                    lastSyncReport = SyncReport(peers.map { p ->
                        WorkerReport(
                            deviceName = p.device.name ?: p.device.address,
                            offsetNs = p.clockOffsetResult?.offsetNs ?: 0,
                            stdDevNs = p.clockOffsetResult?.stdDevNs ?: 0,
                            sampleCount = p.clockOffsetResult?.sampleCount ?: 0,
                            workerAccelCount = p.lastStatusReport?.accelCount ?: 0,
                            workerGyroCount = p.lastStatusReport?.gyroCount ?: 0
                        )
                    })
                    updateConnectionState(BtConnectionState.READY)
                    currentSessionId = null
                    peers.forEach { it.lastStatusReport = null }
                }
            }
            is SyncMessage.SyncDone -> {
                Timber.i("Worker: clock sync complete, transitioning to READY")
                updateConnectionState(BtConnectionState.READY)
            }
            is SyncMessage.Ack -> Timber.d("Ack from ${peer.device.name} for ${msg.cmd}")
            else -> Timber.w("Unhandled message: $msg")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private fun sendLineToPeer(peer: PeerConnection, json: String) {
        try {
            peer.writer.write(json + "\n")
            peer.writer.flush()
        } catch (e: IOException) {
            Timber.e(e, "Send failed to ${peer.device.name}")
            removePeer(peer)
        }
    }

    private fun updateConnectionState(newState: BtConnectionState? = null) {
        if (newState != null) {
            connectionState = newState
        } else {
            connectionState = when {
                peers.isEmpty() -> BtConnectionState.IDLE
                peers.any { it.isSyncing } -> BtConnectionState.SYNCING
                peers.all { it.clockOffsetResult != null } -> BtConnectionState.READY
                else -> BtConnectionState.CONNECTING
            }
        }
        peerDeviceName = if (peers.isEmpty()) null
            else peers.joinToString(", ") { it.device.name ?: it.device.address }
        connectedPeerAddresses = peers.map { it.device.address }.toSet()
    }

    private fun removePeer(peer: PeerConnection) {
        peers.remove(peer)
        try { peer.reader.close() } catch (_: Exception) {}
        try { peer.writer.close() } catch (_: Exception) {}
        safeClose(peer.socket)
        updateConnectionState()
        if (peers.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Worker automatically re-opens the RFCOMM listener so the controller
            // can reconnect without the user manually switching tabs.
            if (role == DeviceRole.WORKER) {
                Timber.i("Worker: peer disconnected, auto-restarting listener")
                startListening()
            }
        }
    }

    private fun handleDisconnect() {
        peers.forEach { p ->
            safeClose(p.socket)
        }
        peers.clear()
        safeClose(serverSocket)
        serverSocket = null
        errorMessage = null
        updateConnectionState(BtConnectionState.IDLE)
        currentSessionId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startForegroundIfNeeded() {
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Bluetooth Sync Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IMU Sync")
            .setContentText("Bluetooth sync active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val ACTION_CONNECT = "com.example.sensor_recorder.CONNECT"
        const val ACTION_LISTEN = "com.example.sensor_recorder.LISTEN"
        const val ACTION_DISCONNECT = "com.example.sensor_recorder.DISCONNECT"
        const val ACTION_SYNC_CLOCKS = "com.example.sensor_recorder.SYNC_CLOCKS"
        const val EXTRA_DEVICE_ADDRESS = "DEVICE_ADDRESS"
        private const val CHANNEL_ID = "BtSyncChannel"
        private const val NOTIFICATION_ID = 2
    }
}
