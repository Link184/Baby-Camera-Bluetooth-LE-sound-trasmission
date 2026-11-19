package com.link184.babycamera.link

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Runtime permissions the BLE link needs on this Android version. */
fun requiredBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/**
 * [PeerLink] over Bluetooth LE: the host runs a GATT server that publishes its L2CAP channel
 * number (PSM) and advertises; the joiner scans, reads the PSM over GATT, then opens an L2CAP
 * connection-oriented channel, which carries the byte stream. L2CAP channels need Android 10.
 * The host keeps advertising and accepting channels until [MAX_LISTENERS] joiners are connected.
 *
 * No Bluetooth bonding is used, so the system never shows a pairing dialog. That also means the
 * link is unencrypted at the Bluetooth level; the real app must encrypt in the app.
 */
@SuppressLint("MissingPermission") // checked in start()
class AndroidBleLink(context: Context) : PeerLink {

    private val appContext = context.applicationContext
    private val bluetoothManager: BluetoothManager? = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    /** All Bluetooth callbacks and all [Run] state are handled on this thread. */
    private val handler = Handler(HandlerThread("ble-link").apply { start() }.looper)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    private val _log = MutableSharedFlow<String>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _rssi = MutableStateFlow<Int?>(null)
    private val eventChannel = Channel<PeerEvent>(Channel.BUFFERED)

    /** Open channels, by peer id, for sending from any thread. */
    private val connections = ConcurrentHashMap<Int, Connection>()
    private val nextPeerId = AtomicInteger(1)
    private var current: Run? = null

    override val state: StateFlow<LinkState> = _state
    override val log: Flow<String> = _log
    override val events: Flow<PeerEvent> = eventChannel.receiveAsFlow()
    override val rssi: StateFlow<Int?> = _rssi

    override val supportsLongRange: Boolean =
        adapter?.isLeCodedPhySupported == true && adapter.isLeExtendedAdvertisingSupported

    override fun start(role: Role, longRange: Boolean) {
        handler.post {
            current?.close()
            current = null
            val bluetooth = adapter
            val missing = requiredBlePermissions().filter {
                appContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            val failure = when {
                bluetooth == null -> "This device has no Bluetooth"
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ->
                    "This prototype needs Android 10+ (L2CAP channels); Android 8/9 support comes later"
                missing.isNotEmpty() -> "Permission not granted: ${missing.joinToString { it.substringAfterLast('.') }}"
                !bluetooth.isEnabled -> "Bluetooth is off"
                else -> null
            }
            if (bluetooth == null || failure != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                fail(failure ?: "Bluetooth unavailable")
                return@post
            }
            if (longRange && !supportsLongRange) log("Long range not supported here - using normal range")
            log(
                "Bluetooth ready: codedPhy=${bluetooth.isLeCodedPhySupported} " +
                    "extendedAdvertising=${bluetooth.isLeExtendedAdvertisingSupported} " +
                    "sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}",
            )
            Run(bluetooth, role, longRange && supportsLongRange).also { current = it }.start()
        }
    }

    override suspend fun send(peer: Peer, bytes: ByteArray) {
        val connection = connections[peer.id] ?: return
        try {
            connection.outgoing.send(bytes)
        } catch (e: ClosedSendChannelException) {
            // Closed meanwhile; its Left event tells the caller.
        }
    }

    override fun broadcast(bytes: ByteArray) {
        for (connection in connections.values) connection.outgoing.trySend(bytes)
    }

    override fun disconnect(peer: Peer) {
        connections[peer.id]?.close()
    }

    override fun stop() {
        handler.post {
            current?.close()
            current = null
            _state.value = LinkState.Idle
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        _log.tryEmit(message)
    }

    private fun fail(reason: String) {
        log("FAILED: $reason")
        current?.close()
        current = null
        _state.value = LinkState.Failed(reason)
    }

    /**
     * One L2CAP channel to a peer. Its own reader emits the peer's [PeerEvent]s in order, and its
     * own writer drains [outgoing], so a congested peer only holds up itself.
     */
    private inner class Connection(val peer: Peer, private val socket: BluetoothSocket) {
        val outgoing = Channel<ByteArray>(OUTGOING_QUEUE_FRAMES)
        val address: String = socket.remoteDevice.address

        /** [onClosed] runs on the handler thread once the channel is gone, whoever closed it. */
        fun open(onClosed: (Connection) -> Unit) {
            connections[peer.id] = this
            ioScope.launch {
                try {
                    val output = socket.outputStream
                    for (bytes in outgoing) {
                        output.write(bytes)
                        output.flush()
                    }
                } catch (e: IOException) {
                    close() // the reader then reports it
                }
            }
            ioScope.launch {
                eventChannel.send(PeerEvent.Joined(peer))
                val buffer = ByteArray(16 * 1024)
                try {
                    val input = socket.inputStream
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        eventChannel.send(PeerEvent.Data(peer, buffer.copyOf(read)))
                    }
                } catch (e: IOException) {
                    // Reported below.
                }
                close()
                eventChannel.send(PeerEvent.Left(peer))
                handler.post { onClosed(this@Connection) }
            }
        }

        /** Safe from any thread, any number of times. */
        fun close() {
            connections.remove(peer.id, this)
            outgoing.close()
            runCatching { socket.close() }
        }
    }

    /** One start()..stop() attempt. Callbacks from a superseded run are ignored via [active]. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class Run(
        private val bluetooth: BluetoothAdapter,
        private val role: Role,
        private val coded: Boolean,
    ) {
        @Volatile
        private var gattServer: BluetoothGattServer? = null
        private var serverSocket: BluetoothServerSocket? = null
        private var advertiseCallback: AdvertiseCallback? = null
        private var advertisingSetCallback: AdvertisingSetCallback? = null
        private var scanCallback: ScanCallback? = null
        private var gatt: BluetoothGatt? = null
        private var psmReceived = false

        /** The joiner's channel while it is being opened. */
        @Volatile
        private var pendingSocket: BluetoothSocket? = null

        /** This run's open channels: up to [MAX_LISTENERS] on the host, one on the joiner. */
        private val open = LinkedHashMap<Int, Connection>()

        /** What the host shows while nobody is connected. */
        private var hostStep = "Starting"

        private val active get() = current === this

        private val advertising get() = advertiseCallback != null || advertisingSetCallback != null

        private val rssiPoll = object : Runnable {
            override fun run() {
                if (!active) return
                gatt?.readRemoteRssi()
                handler.postDelayed(this, 1_000)
            }
        }

        fun start() = when (role) {
            Role.Host -> startHost()
            Role.Join -> startJoin()
        }

        // ---- Host: GATT server + L2CAP listener + advertising ----

        private fun startHost() {
            hostStep = "Opening L2CAP channel"
            publishHostState()
            val server = try {
                bluetooth.listenUsingInsecureL2capChannel()
            } catch (e: IOException) {
                return fail("Cannot open an L2CAP channel: ${e.message}")
            }
            serverSocket = server
            val psm = server.psm
            log("L2CAP channel listening, PSM=$psm")
            val psmValue = byteArrayOf((psm and 0xFF).toByte(), (psm shr 8 and 0xFF).toByte())

            val characteristic = BluetoothGattCharacteristic(
                UUID.fromString(BLE_PSM_CHARACTERISTIC_UUID),
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
            val service = BluetoothGattService(
                UUID.fromString(BLE_SERVICE_UUID),
                BluetoothGattService.SERVICE_TYPE_PRIMARY,
            ).apply { addCharacteristic(characteristic) }

            val callback = object : BluetoothGattServerCallback() {
                override fun onServiceAdded(status: Int, added: BluetoothGattService) {
                    handler.post {
                        if (!active) return@post
                        if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising() else fail("Adding the GATT service failed (status $status)")
                    }
                }

                override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                    val connected = newState == BluetoothProfile.STATE_CONNECTED
                    if (connected && coded) {
                        gattServer?.setPreferredPhy(
                            device,
                            BluetoothDevice.PHY_LE_CODED_MASK,
                            BluetoothDevice.PHY_LE_CODED_MASK,
                            BluetoothDevice.PHY_OPTION_S8,
                        )
                    }
                    handler.post {
                        if (!active) return@post
                        log("GATT ${device.address} ${if (connected) "connected" else "disconnected"} (status $status)")
                        // Many Bluetooth chips stop advertising once someone connects.
                        if (connected) keepAdvertising(restart = true)
                    }
                }

                override fun onCharacteristicReadRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    offset: Int,
                    requested: BluetoothGattCharacteristic,
                ) {
                    val server = gattServer ?: return
                    if (requested.uuid == characteristic.uuid && offset <= psmValue.size) {
                        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, psmValue.copyOfRange(offset, psmValue.size))
                        handler.post { if (active) log("PSM read by ${device.address}") }
                    } else {
                        server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    }
                }

                override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
                    handler.post {
                        if (active) log("Radio mode to ${device.address}: send=${phyName(txPhy)} receive=${phyName(rxPhy)} (status $status)")
                    }
                }
            }
            val opened = bluetoothManager?.openGattServer(appContext, callback)
                ?: return fail("Cannot open a GATT server")
            gattServer = opened
            if (!opened.addService(service)) return fail("Cannot add the GATT service")

            ioScope.launch {
                try {
                    while (true) {
                        val accepted = server.accept()
                        handler.post { onSocketConnected(accepted) }
                    }
                } catch (e: IOException) {
                    // Closed by close(); nothing to report.
                }
            }
        }

        private fun publishHostState() {
            _state.value = if (open.isEmpty()) {
                LinkState.Working(hostStep)
            } else {
                LinkState.Connected(open.values.map { it.peer })
            }
        }

        /** Advertises while there is room for another listener, and stops when full. */
        private fun keepAdvertising(restart: Boolean) {
            if (open.size >= MAX_LISTENERS) {
                if (advertising) {
                    log("$MAX_LISTENERS listeners connected - not accepting more")
                    stopAdvertising()
                }
                return
            }
            if (advertising && !restart) return
            stopAdvertising()
            startAdvertising()
        }

        /** Advertising problems only end the run while nobody is listening yet. */
        private fun advertisingFailed(reason: String) {
            if (open.isEmpty()) fail(reason) else log("Cannot advertise for more listeners: $reason")
        }

        private fun startAdvertising() {
            val advertiser = bluetooth.bluetoothLeAdvertiser
                ?: return advertisingFailed("This phone cannot advertise over Bluetooth LE")
            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid.fromString(BLE_SERVICE_UUID))
                .setIncludeDeviceName(false)
                .build()
            if (coded) {
                val parameters = AdvertisingSetParameters.Builder()
                    .setLegacyMode(false)
                    .setConnectable(true)
                    .setScannable(false)
                    .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
                    .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                    .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                    .build()
                val callback = object : AdvertisingSetCallback() {
                    override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                        handler.post {
                            if (!active || advertisingSetCallback !== this) return@post
                            if (status == ADVERTISE_SUCCESS) {
                                log("Advertising in long-range mode (tx power $txPower dBm)")
                                hostStep = "Advertising (long range) - waiting for a joiner"
                                publishHostState()
                            } else {
                                advertisingSetCallback = null
                                advertisingFailed("Long-range advertising failed (status $status)")
                            }
                        }
                    }
                }
                advertisingSetCallback = callback
                advertiser.startAdvertisingSet(parameters, data, null, null, null, callback)
            } else {
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build()
                val callback = object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                        handler.post {
                            if (!active || advertiseCallback !== this) return@post
                            log("Advertising")
                            hostStep = "Advertising - waiting for a joiner"
                            publishHostState()
                        }
                    }

                    override fun onStartFailure(errorCode: Int) {
                        handler.post {
                            if (!active || advertiseCallback !== this) return@post
                            advertiseCallback = null
                            advertisingFailed("Advertising failed (error $errorCode)")
                        }
                    }
                }
                advertiseCallback = callback
                advertiser.startAdvertising(settings, data, callback)
            }
        }

        private fun stopAdvertising() {
            val advertiser = bluetooth.bluetoothLeAdvertiser
            advertiseCallback?.let { runCatching { advertiser?.stopAdvertising(it) } }
            advertisingSetCallback?.let { runCatching { advertiser?.stopAdvertisingSet(it) } }
            advertiseCallback = null
            advertisingSetCallback = null
        }

        // ---- Join: scan, GATT connect, read PSM, open L2CAP ----

        private fun startJoin() {
            val scanner = bluetooth.bluetoothLeScanner ?: return fail("This phone cannot scan over Bluetooth LE")
            val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(BLE_SERVICE_UUID)).build())
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .apply {
                    if (coded) {
                        // Long-range adverts are "extended" adverts, which legacy scanning ignores.
                        setLegacy(false)
                        setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                    }
                }
                .build()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    handler.post { onFound(result) }
                }

                override fun onScanFailed(errorCode: Int) {
                    handler.post { if (active) fail("Scan failed (error $errorCode)") }
                }
            }
            scanCallback = callback
            scanner.startScan(filters, settings, callback)
            log(if (coded) "Scanning (normal and long-range adverts)" else "Scanning")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) log("Note: on Android 10/11, Location must be switched on for scanning")
            _state.value = LinkState.Working("Scanning for a host")
        }

        private fun stopScan() {
            scanCallback?.let { runCatching { bluetooth.bluetoothLeScanner?.stopScan(it) } }
            scanCallback = null
        }

        private fun onFound(result: ScanResult) {
            if (!active || gatt != null) return
            stopScan()
            val onCoded = result.primaryPhy == BluetoothDevice.PHY_LE_CODED
            log("Found host ${result.device.address}: signal ${result.rssi} dBm, radio mode ${phyName(result.primaryPhy)}")
            _rssi.value = result.rssi
            _state.value = LinkState.Working("Connecting")
            // Deprecated in API 37, but it's the only variant that delivers callbacks on our thread.
            @Suppress("DEPRECATION")
            gatt = result.device.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                if (onCoded) BluetoothDevice.PHY_LE_CODED_MASK else BluetoothDevice.PHY_LE_1M_MASK,
                handler,
            )
        }

        private val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (!active) return
                if (newState != BluetoothProfile.STATE_CONNECTED) {
                    return fail("Bluetooth link lost (GATT status $status)")
                }
                log("GATT connected (status $status)")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (coded) {
                    g.setPreferredPhy(
                        BluetoothDevice.PHY_LE_CODED_MASK,
                        BluetoothDevice.PHY_LE_CODED_MASK,
                        BluetoothDevice.PHY_OPTION_S8,
                    )
                }
                g.readPhy()
                handler.post(rssiPoll)
                _state.value = LinkState.Working("Reading the host's channel number")
                if (!g.discoverServices()) fail("Service discovery failed to start")
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (!active) return
                val characteristic = g.getService(UUID.fromString(BLE_SERVICE_UUID))
                    ?.getCharacteristic(UUID.fromString(BLE_PSM_CHARACTERISTIC_UUID))
                    ?: return fail("Host has no BabyCamera service (status $status)")
                if (!g.readCharacteristic(characteristic)) fail("Reading the channel number failed to start")
            }

            // Android 13+ calls this one; older versions call the deprecated one below.
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) = onPsmRead(status, value)

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
                onPsmRead(status, characteristic.value)

            override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
                if (active && status == BluetoothGatt.GATT_SUCCESS) _rssi.value = rssi
            }

            override fun onPhyRead(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
                if (active) log("Radio mode: send=${phyName(txPhy)} receive=${phyName(rxPhy)}")
            }

            override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
                if (active) log("Radio mode now: send=${phyName(txPhy)} receive=${phyName(rxPhy)} (status $status)")
            }
        }

        private fun onPsmRead(status: Int, value: ByteArray?) {
            if (!active || psmReceived) return
            psmReceived = true
            if (status != BluetoothGatt.GATT_SUCCESS || value == null || value.size < 2) {
                return fail("Reading the channel number failed (status $status)")
            }
            val psm = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
            log("Host PSM=$psm - opening L2CAP channel")
            _state.value = LinkState.Working("Opening the data channel")
            val device = gatt?.device ?: return
            ioScope.launch {
                try {
                    val channel = device.createInsecureL2capChannel(psm)
                    pendingSocket = channel
                    channel.connect()
                    handler.post { onSocketConnected(channel) }
                } catch (e: IOException) {
                    handler.post { if (active) fail("L2CAP connect failed: ${e.message}") }
                }
            }
        }

        // ---- Both roles ----

        private fun onSocketConnected(socket: BluetoothSocket) {
            pendingSocket = null
            if (!active) return runCatching { socket.close() }.let { }
            val address = socket.remoteDevice.address
            if (role == Role.Host) {
                // A phone that reconnects before its old channel timed out: the old one is dead.
                open.values.filter { it.address == address }.forEach {
                    log("$address reconnected - closing its old channel")
                    open.remove(it.peer.id)
                    it.close()
                }
                if (open.size >= MAX_LISTENERS) {
                    log("Refusing $address: already $MAX_LISTENERS listeners")
                    runCatching { socket.close() }
                    return
                }
            }
            val connection = Connection(Peer(nextPeerId.getAndIncrement(), address), socket)
            open[connection.peer.id] = connection
            log("L2CAP channel open to $address (max packet ${socket.maxTransmitPacketSize} bytes, ${open.size} connected)")
            connection.open(::onConnectionClosed)
            when (role) {
                Role.Join -> _state.value = LinkState.Connected(listOf(connection.peer))
                Role.Host -> {
                    publishHostState()
                    keepAdvertising(restart = true)
                }
            }
        }

        private fun onConnectionClosed(connection: Connection) {
            if (!active || open.remove(connection.peer.id) == null) return
            when (role) {
                Role.Join -> fail("Data channel closed")
                Role.Host -> {
                    log("${connection.address} disconnected (${open.size} still connected)")
                    publishHostState()
                    keepAdvertising(restart = false)
                }
            }
        }

        fun close() {
            handler.removeCallbacks(rssiPoll)
            stopScan()
            stopAdvertising()
            open.values.forEach { it.close() }
            open.clear()
            runCatching { pendingSocket?.close() }
            runCatching { serverSocket?.close() }
            gatt?.let {
                runCatching { it.disconnect() }
                runCatching { it.close() }
            }
            gattServer?.let {
                runCatching { it.clearServices() }
                runCatching { it.close() }
            }
            _rssi.value = null
        }
    }

    private companion object {
        const val TAG = "BabyCamBle"

        /** Per-peer send queue; broadcast frames beyond it are dropped for that peer only. */
        const val OUTGOING_QUEUE_FRAMES = 16

        fun phyName(phy: Int) = when (phy) {
            BluetoothDevice.PHY_LE_1M -> "normal (1M)"
            BluetoothDevice.PHY_LE_2M -> "fast (2M)"
            BluetoothDevice.PHY_LE_CODED -> "long range (coded)"
            else -> "unknown($phy)"
        }
    }
}
