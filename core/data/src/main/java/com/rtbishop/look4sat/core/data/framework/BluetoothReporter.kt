/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.framework

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.content.IntentFilter
import android.content.Intent
import android.content.BroadcastReceiver
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanCallback
import android.content.Context
import android.os.Build
import android.util.Log
import com.rtbishop.look4sat.core.domain.repository.IReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.BufferOverflow

/**
 * Bluetooth output supports a dual-mode Bluetooth serial module:
 *  - BLE GATT using service FFF0, notify characteristic FFF1, write characteristic FFF2
 *  - Classic Bluetooth SPP as a fallback
 *
 * Each configured endpoint accepts a Bluetooth MAC address, or the name of a paired device.
 * L4S's existing command templates are sent as UTF-8 bytes; no additional protocol is added.
 */
private fun UUID.hasUuidPrefix(prefix: String): Boolean =
    toString().startsWith(prefix, ignoreCase = true)

class BluetoothReporter(
    context: Context,
    private val bluetoothManager: BluetoothManager,
    private val reporterScope: CoroutineScope,
    rotatorDeviceId: String,
    frequencyDeviceId: String
) : IReporter {

    private val tag = "BTReporter"
    private val appContext = context.applicationContext
    private val rotatorTransport = BluetoothTransportRegistry.obtain(
        appContext, bluetoothManager, rotatorDeviceId, "Rotator"
    )
    private val frequencyTransport = BluetoothTransportRegistry.obtain(
        appContext, bluetoothManager, frequencyDeviceId, "Frequency"
    )

    companion object {
        /** Connect from the Bluetooth-output settings UI using the same persistent transport
         * instance that IReporter will later use to send rotation/frequency commands. */
        suspend fun connectDevice(
            context: Context,
            bluetoothManager: BluetoothManager,
            deviceId: String
        ): Boolean = withContext(Dispatchers.IO) {
            if (deviceId.isBlank()) return@withContext false
            try {
                BluetoothTransportRegistry.obtain(
                    context.applicationContext,
                    bluetoothManager,
                    deviceId,
                    "Settings"
                ).connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BTReporter", "Bluetooth settings connection failed: ${e.message}")
                false
            }
        }

        /** Disconnect the persistent transport used by both the UI and L4S output. */
        suspend fun disconnectDevice(
            context: Context,
            bluetoothManager: BluetoothManager,
            deviceId: String
        ): Boolean = withContext(Dispatchers.IO) {
            if (deviceId.isBlank()) return@withContext false
            try {
                BluetoothTransportRegistry.obtain(
                    context.applicationContext,
                    bluetoothManager,
                    deviceId,
                    "Settings"
                ).disconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BTReporter", "Bluetooth settings disconnect failed: ${e.message}")
                false
            }
        }

        /** Read live connection state from the persistent transport used for L4S output. */
        suspend fun isDeviceConnected(
            context: Context,
            bluetoothManager: BluetoothManager,
            deviceId: String
        ): Boolean = withContext(Dispatchers.IO) {
            if (deviceId.isBlank()) return@withContext false
            try {
                BluetoothTransportRegistry.obtain(
                    context.applicationContext,
                    bluetoothManager,
                    deviceId,
                    "Settings"
                ).isConnected()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("BTReporter", "Could not read Bluetooth connection state: ${e.message}")
                false
            }
        }

        /** Send raw debug bytes through the existing connected BLE/SPP transport. Does not open a new connection. */
        suspend fun sendRawBytes(
            context: Context,
            bluetoothManager: BluetoothManager,
            deviceId: String,
            bytes: ByteArray
        ): Boolean = withContext(Dispatchers.IO) {
            if (deviceId.isBlank() || bytes.isEmpty()) return@withContext false
            try {
                BluetoothTransportRegistry.obtain(
                    context.applicationContext, bluetoothManager, deviceId, "BluetoothDebug"
                ).sendRaw(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BTReporter", "Bluetooth debug send failed: ${e.message}")
                false
            }
        }

        /** Observe receive bytes from the shared transport that telemetry and the Connect button use. */
        fun observeReceivedBytes(
            context: Context,
            bluetoothManager: BluetoothManager,
            deviceId: String
        ): Flow<ByteArray> = if (deviceId.isBlank()) {
            kotlinx.coroutines.flow.emptyFlow()
        } else {
            BluetoothTransportRegistry.obtain(
                context.applicationContext, bluetoothManager, deviceId, "BluetoothDebug"
            ).receivedBytes
        }

        /**
         * Scan nearby BLE advertisements and classic discovery results, while also including
         * already-paired devices (important for Classic SPP devices that are not advertising BLE).
         * Each pair is (display name, MAC address). The UI requests Android runtime permissions.
         */
        suspend fun scanDevices(
            context: Context,
            bluetoothManager: BluetoothManager
        ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
            val adapter = bluetoothManager.adapter
                ?: throw IllegalStateException("Bluetooth adapter is unavailable")
            if (!adapter.isEnabled) {
                throw IllegalStateException("Bluetooth is turned off")
            }

            val appContext = context.applicationContext
            val found = ConcurrentHashMap<String, String>()
            fun rememberDevice(device: BluetoothDevice) {
                val address = try { device.address } catch (_: SecurityException) { return }
                val name = try { device.name.orEmpty() } catch (_: SecurityException) { "" }
                found.compute(address) { _, previous ->
                    if (name.isNotBlank()) name else previous.orEmpty()
                }
            }

            // Paired devices are available immediately, including Classic SPP devices.
            adapter.bondedDevices.forEach(::rememberDevice)

            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    rememberDevice(result.device)
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { rememberDevice(it.device) }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w("BTReporter", "BLE scan failed: error=$errorCode")
                }
            }
            val classicFilter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            var receiverRegistered = false
            var classicStarted = false
            var bleStarted = false
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != BluetoothDevice.ACTION_FOUND) return
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) rememberDevice(device)
                }
            }
            val scanner = adapter.bluetoothLeScanner

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(receiver, classicFilter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    appContext.registerReceiver(receiver, classicFilter)
                }
                receiverRegistered = true

                classicStarted = try {
                    adapter.startDiscovery()
                } catch (e: Exception) {
                    Log.w("BTReporter", "Classic Bluetooth discovery could not start: ${e.message}")
                    false
                }
                bleStarted = try {
                    if (scanner != null) {
                        scanner.startScan(scanCallback)
                        true
                    } else false
                } catch (e: Exception) {
                    Log.w("BTReporter", "BLE scan could not start: ${e.message}")
                    false
                }

                if (classicStarted || bleStarted) delay(10_000L)
            } finally {
                if (bleStarted && scanner != null) {
                    try { scanner.stopScan(scanCallback) } catch (_: Exception) {}
                }
                if (classicStarted) {
                    try { adapter.cancelDiscovery() } catch (_: Exception) {}
                }
                if (receiverRegistered) {
                    try { appContext.unregisterReceiver(receiver) } catch (_: Exception) {}
                }
            }

            if (!classicStarted && !bleStarted && found.isEmpty()) {
                throw IllegalStateException("Unable to start Bluetooth discovery")
            }
            found.entries
                .map { (address, name) ->
                    (name.ifBlank { "Unknown device" }) to address
                }
                .sortedWith(compareBy<Pair<String, String>> { it.first.lowercase(Locale.ROOT) }.thenBy { it.second })
        }
    }

    override fun reportRotation(format: String, azimuth: Double, elevation: Double) {
        reporterScope.launch {
            val el = if (elevation > 0.0) elevation else 0.0
            val command = format
                .replace("\$AZ", azimuth.toString())
                .replace("\$EL", el.toString())
                .unescapeControlChars()
            try {
                rotatorTransport.send(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Rotator send error: ${e.message}")
            }
        }
    }

    override fun reportFrequency(format: String, frequency: Long) {
        val payload = if (DtracFrequencyProtocol.isDtracFrequencyTemplate(format)) {
            DtracFrequencyProtocol.encodeFrequencyCommand(frequency)
        } else {
            format.replace("\$FREQ", frequency.toString())
                .unescapeControlChars()
                .toByteArray(Charsets.UTF_8)
        }
        reporterScope.launch {
            try {
                DtracFrequencyProtocol.sendPayload(
                    owner = this@BluetoothReporter,
                    active = true,
                    payload = payload
                ) { bytes -> frequencyTransport.sendBytes(bytes) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Frequency send error: ${e.message}")
            }
        }
    }

    override fun reportTransceiverData(
        format: String,
        rxFrequencyHz: Long,
        txFrequencyHz: Long?,
        txMode: String?,
        rxMode: String?,
        txCtcssToneHz: Double?,
        rxCtcssToneHz: Double?
    ) {
        val isDtrac = DtracFrequencyProtocol.isDtracFrequencyTemplate(format)
        val payloads = if (isDtrac) {
            DtracFrequencyProtocol.encodeTransceiverCommands(
                rxFrequencyHz = rxFrequencyHz,
                txFrequencyHz = txFrequencyHz,
                txMode = txMode,
                rxMode = rxMode,
                txCtcssToneHz = txCtcssToneHz,
                rxCtcssToneHz = rxCtcssToneHz
            )
        } else {
            listOf(
                DtracFrequencyProtocol.formatAsciiTemplate(
                    template = format,
                    rxFrequencyHz = rxFrequencyHz,
                    txFrequencyHz = txFrequencyHz,
                    txMode = txMode,
                    rxMode = rxMode,
                    txCtcssToneHz = txCtcssToneHz,
                    rxCtcssToneHz = rxCtcssToneHz
                ).toByteArray(Charsets.UTF_8)
            )
        }
        reporterScope.launch {
            try {
                DtracFrequencyProtocol.sendFrames(
                    owner = this@BluetoothReporter,
                    active = true,
                    frames = payloads
                ) { bytes -> frequencyTransport.sendBytes(bytes) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "DTrac command send error: ${e.message}")
            }
        }
    }

    private fun String.unescapeControlChars(): String =
        replace("\\r", "\r").replace("\\n", "\n").replace("\\t", "\t")
}

/**
 * Share open connections between the settings Connect button and IReporter output.
 * The user-entered ID is the key; a MAC and a name for the same physical device are separate
 * keys, so prefer keeping the same ID in the settings field.
 */
private object BluetoothTransportRegistry {
    private val transports = ConcurrentHashMap<String, BluetoothOutputTransport>()

    fun obtain(
        context: Context,
        bluetoothManager: BluetoothManager,
        deviceId: String,
        channelName: String
    ): BluetoothOutputTransport {
        val normalizedId = deviceId.trim()
        val key = normalizedId.lowercase(Locale.ROOT)
        return transports.computeIfAbsent(key) {
            BluetoothOutputTransport(context.applicationContext, bluetoothManager, normalizedId, channelName)
        }
    }
}

/** One serialized output channel for one Bluetooth device. */
private class BluetoothOutputTransport(
    context: Context,
    private val bluetoothManager: BluetoothManager,
    private val deviceId: String,
    private val channelName: String
) {
    companion object {
        private const val TAG = "BTTransport"
        private const val BLE_CONNECT_TIMEOUT_MS = 10_000L
        private const val BLE_WRITE_TIMEOUT_MS = 4_000L
        // Default ATT MTU 23 leaves 20 bytes for a characteristic value.
        private const val BLE_PAYLOAD_BYTES = 20

        private const val SPP_UUID_TEXT = "00001101-0000-1000-8000-00805f9b34fb"
        private const val BLE_SERVICE_UUID_PREFIX = "0000fff0"
        private const val BLE_NOTIFY_UUID_PREFIX = "0000fff1"
        private const val BLE_WRITE_UUID_PREFIX = "0000fff2"
        private const val CCCD_UUID_TEXT = "00002902-0000-1000-8000-00805f9b34fb"
        private val SPP_UUID: UUID = UUID.fromString(SPP_UUID_TEXT)
        private val CCCD_UUID: UUID = UUID.fromString(CCCD_UUID_TEXT)
        private val MAC_ADDRESS_REGEX = Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
    }

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val mutableReceivedBytes = MutableSharedFlow<ByteArray>(
        replay = 64,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedBytes: SharedFlow<ByteArray> = mutableReceivedBytes.asSharedFlow()

    @Volatile private var classicReaderSocket: BluetoothSocket? = null
    @Volatile private var classicReaderThread: Thread? = null

    @Volatile private var activeGatt: BluetoothGatt? = null
    @Volatile private var writeCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var pendingConnection: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null
    @Volatile private var bleServiceUnavailable = false
    @Volatile private var explicitlyDisconnected = false

    private var classicSocket: BluetoothSocket? = null
    private var classicStream: OutputStream? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                val started = try {
                    gatt.discoverServices()
                } catch (e: Exception) {
                    Log.w(TAG, "$channelName BLE service discovery failed: ${e.message}")
                    false
                }
                if (!started) failGatt(gatt, markServiceUnavailable = false)
            } else if (status != BluetoothGatt.GATT_SUCCESS ||
                newState == BluetoothProfile.STATE_DISCONNECTED
            ) {
                pendingConnection?.complete(false)
                pendingWrite?.complete(false)
                if (activeGatt === gatt) {
                    activeGatt = null
                    writeCharacteristic = null
                    notifyCharacteristic = null
                }
                try { gatt.close() } catch (_: Exception) {}
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failGatt(gatt, markServiceUnavailable = false)
                return
            }

            val service = gatt.services.firstOrNull { it.uuid.hasUuidPrefix(BLE_SERVICE_UUID_PREFIX) }
            val writable = service?.characteristics?.firstOrNull { it.uuid.hasUuidPrefix(BLE_WRITE_UUID_PREFIX) }
            val properties = writable?.properties ?: 0
            val canWrite = (properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

            if (writable == null || !canWrite) {
                Log.w(
                    TAG,
                    "$channelName BLE service/FFF2 write characteristic unavailable; trying Classic SPP"
                )
                failGatt(gatt, markServiceUnavailable = true)
                return
            }

            activeGatt = gatt
            writeCharacteristic = writable
            val notify = service?.characteristics?.firstOrNull { it.uuid.hasUuidPrefix(BLE_NOTIFY_UUID_PREFIX) }
            val notifyProperties = notify?.properties ?: 0
            val canNotify = (notifyProperties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                (notifyProperties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

            if (notify != null && canNotify) {
                notifyCharacteristic = notify
                enableNotifications(gatt, notify)
            } else {
                Log.w(TAG, "$channelName BLE FFF1 notify characteristic unavailable; write remains enabled")
                pendingConnection?.complete(true)
            }

            Log.i(TAG, "$channelName BLE FFF0 service found at $deviceId")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CCCD_UUID && descriptor.characteristic.uuid.hasUuidPrefix(BLE_NOTIFY_UUID_PREFIX)) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "$channelName BLE notifications enabled")
                } else {
                    Log.w(TAG, "$channelName BLE notification subscription failed (status=$status)")
                }
                // Outbound control still works if this module does not allow notifications.
                pendingConnection?.complete(true)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid.hasUuidPrefix(BLE_NOTIFY_UUID_PREFIX)) {
                logNotification(characteristic.value ?: byteArrayOf())
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid.hasUuidPrefix(BLE_NOTIFY_UUID_PREFIX)) {
                logNotification(value)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid.hasUuidPrefix(BLE_WRITE_UUID_PREFIX)) {
                pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
        }
    }

    /** Open and keep a persistent BLE GATT or Classic SPP connection. */
    suspend fun connect(): Boolean = mutex.withLock {
        if (deviceId.isBlank()) return@withLock false
        explicitlyDisconnected = false
        try {
            connectInternal()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$channelName connection failed: ${e.message}")
            false
        }
    }

    private suspend fun connectInternal(): Boolean {
        if (activeGatt != null && writeCharacteristic != null) return true
        if (classicStream != null && classicSocket?.isConnected == true) return true

        if (!bleServiceUnavailable && connectBle()) return true
        return connectClassic()
    }

    /** True while this transport still holds a usable BLE GATT or Classic SPP connection. */
    suspend fun isConnected(): Boolean = mutex.withLock {
        (activeGatt != null && writeCharacteristic != null) ||
            (classicStream != null && classicSocket?.isConnected == true)
    }

    /** Explicit user disconnect also prevents automatic reconnect on the next telemetry tick. */
    suspend fun disconnect(): Boolean = mutex.withLock {
        explicitlyDisconnected = true
        pendingConnection?.complete(false)
        pendingWrite?.complete(false)
        closeGatt()
        closeClassic()
        Log.i(TAG, "$channelName disconnected by user")
        true
    }

    /** Write raw bytes only if this exact transport is already connected. */
    suspend fun sendRaw(payload: ByteArray): Boolean = mutex.withLock {
        if (payload.isEmpty()) return@withLock false
        if (activeGatt != null && writeCharacteristic != null) {
            return@withLock writeBle(payload)
        }
        if (classicStream != null && classicSocket?.isConnected == true) {
            return@withLock writeClassicIfConnected(payload)
        }
        false
    }

    suspend fun send(text: String) = sendBytes(text.toByteArray(Charsets.UTF_8))

    suspend fun sendBytes(bytes: ByteArray) = mutex.withLock {
        if (deviceId.isBlank() || explicitlyDisconnected || bytes.isEmpty()) return@withLock
        val payload = bytes.copyOf()

        // Reuse a connection opened by the settings Connect button.
        if (activeGatt != null && writeCharacteristic != null) {
            if (writeBle(payload)) return@withLock
            closeGatt()
        }
        if (classicStream != null && writeClassicIfConnected(payload)) return@withLock

        // Try BLE GATT first, then Classic SPP.
        if (!bleServiceUnavailable && connectBle()) {
            if (writeBle(payload)) return@withLock
            closeGatt()
        }
        if (connectClassic() && writeClassicIfConnected(payload)) return@withLock
        throw IllegalStateException("Unable to send data: BLE GATT and Classic SPP connection both failed")
    }

    private fun connectClassic(): Boolean {
        if (classicStream != null && classicSocket?.isConnected == true) return true
        closeClassic()
        return try {
            val adapter = bluetoothManager.adapter
                ?: throw IllegalStateException("Bluetooth adapter is unavailable")
            val device = resolveDevice(adapter)
            try { adapter.cancelDiscovery() } catch (_: Exception) {}
            val socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            try {
                socket.connect()
                classicSocket = socket
                classicStream = socket.outputStream
                startClassicReader(socket)
                Log.i(TAG, "$channelName connected using Classic SPP: ${device.address}")
                true
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) {}
                throw e
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$channelName Classic SPP connection failed: ${e.message}")
            closeClassic()
            false
        }
    }

    private suspend fun connectBle(): Boolean {
        if (bleServiceUnavailable) return false

        val connected = withTimeoutOrNull(BLE_CONNECT_TIMEOUT_MS) {
            val result = CompletableDeferred<Boolean>()
            pendingConnection = result
            try {
                val adapter = bluetoothManager.adapter
                    ?: throw IllegalStateException("Bluetooth adapter is unavailable")
                val device = resolveDevice(adapter)
                val newGatt = device.connectGatt(
                    appContext,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
                if (newGatt == null) {
                    false
                } else {
                    activeGatt = newGatt
                    result.await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "$channelName BLE connection unavailable: ${e.message}; trying SPP")
                false
            } finally {
                if (pendingConnection === result) pendingConnection = null
            }
        } ?: false

        if (!connected) closeGatt()
        return connected
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val localEnabled = try {
            gatt.setCharacteristicNotification(characteristic, true)
        } catch (e: Exception) {
            Log.w(TAG, "$channelName unable to enable local notifications: ${e.message}")
            false
        }
        if (!localEnabled) {
            pendingConnection?.complete(true)
            return
        }

        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            Log.w(TAG, "$channelName BLE FFF1 has no CCCD descriptor; notification subscription skipped")
            pendingConnection?.complete(true)
            return
        }
        val descriptorValue = if (
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        ) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }

        val started = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, descriptorValue) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    descriptor.value = descriptorValue
                    gatt.writeDescriptor(descriptor)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$channelName BLE descriptor write failed: ${e.message}")
            false
        }
        if (!started) {
            Log.w(TAG, "$channelName unable to start BLE notification subscription")
            pendingConnection?.complete(true)
        }
    }

    private suspend fun writeBle(payload: ByteArray): Boolean {
        val gatt = activeGatt ?: return false
        val characteristic = writeCharacteristic ?: return false
        val properties = characteristic.properties
        val writeType = when {
            (properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> return false
        }

        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + BLE_PAYLOAD_BYTES, payload.size)
            val chunk = payload.copyOfRange(offset, end)
            val result = CompletableDeferred<Boolean>()
            pendingWrite = if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) result else null

            val accepted = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(characteristic, chunk, writeType) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        characteristic.writeType = writeType
                        characteristic.value = chunk
                        gatt.writeCharacteristic(characteristic)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "$channelName BLE write could not start: ${e.message}")
                false
            }

            if (!accepted) {
                pendingWrite = null
                return false
            }

            val successful = if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                val completed = withTimeoutOrNull(BLE_WRITE_TIMEOUT_MS) { result.await() } ?: false
                pendingWrite = null
                completed
            } else {
                // WRITE_NO_RESPONSE has no remote acknowledgement; pace packets conservatively.
                delay(30L)
                true
            }
            if (!successful) return false
            offset = end
        }
        return true
    }

    private fun writeClassicIfConnected(payload: ByteArray): Boolean {
        val stream = classicStream ?: return false
        return try {
            stream.write(payload)
            stream.flush()
            true
        } catch (e: Exception) {
            Log.w(TAG, "$channelName Classic SPP write failed: ${e.message}")
            closeClassic()
            false
        }
    }


    /** Accept a Bluetooth MAC address or an already-paired device name. */
    private fun resolveDevice(adapter: BluetoothAdapter): BluetoothDevice {
        val id = deviceId.trim()
        if (MAC_ADDRESS_REGEX.matches(id)) return adapter.getRemoteDevice(id)

        val matches = adapter.bondedDevices.filter { device ->
            device.name?.equals(id, ignoreCase = true) == true || device.address.equals(id, ignoreCase = true)
        }
        return when (matches.size) {
            1 -> matches.first()
            0 -> throw IllegalArgumentException(
                "'$id' is not a MAC address or a paired device name; pair the device or enter its MAC"
            )
            else -> throw IllegalArgumentException(
                "More than one paired device is named '$id'; use its MAC address instead"
            )
        }
    }

    private fun logNotification(value: ByteArray) {
        mutableReceivedBytes.tryEmit(value.copyOf())
        // L4S's reporter is an outbound control interface. Notifications are enabled and logged
        // for diagnostics; the existing IReporter contract has no inbound-data consumer.
        val text = value.toString(Charsets.UTF_8)
        Log.d(TAG, "$channelName BLE notify (${value.size} bytes): ${text.replace("\r", "\\r").replace("\n", "\\n")}")
    }

    private fun failGatt(gatt: BluetoothGatt, markServiceUnavailable: Boolean) {
        if (markServiceUnavailable) bleServiceUnavailable = true
        pendingConnection?.complete(false)
        pendingWrite?.complete(false)
        if (activeGatt === gatt) {
            activeGatt = null
            writeCharacteristic = null
            notifyCharacteristic = null
        }
        try { gatt.disconnect() } catch (_: Exception) {}
        try { gatt.close() } catch (_: Exception) {}
    }

    private fun closeGatt() {
        val gatt = activeGatt
        activeGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        if (gatt != null) {
            try { gatt.disconnect() } catch (_: Exception) {}
            try { gatt.close() } catch (_: Exception) {}
        }
    }

    private fun startClassicReader(socket: BluetoothSocket) {
        classicReaderSocket = socket
        val reader = Thread({
            try {
                val input = socket.inputStream
                val buffer = ByteArray(1024)
                while (classicReaderSocket === socket) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) mutableReceivedBytes.tryEmit(buffer.copyOf(count))
                }
            } catch (e: Exception) {
                if (classicReaderSocket === socket) {
                    Log.w(TAG, "$channelName Classic SPP reader stopped: ${e.message}")
                }
            } finally {
                if (classicReaderSocket === socket) classicReaderSocket = null
            }
        }, "L4S-Bluetooth-RX-$channelName")
        reader.isDaemon = true
        classicReaderThread = reader
        reader.start()
    }

    private fun stopClassicReader() {
        classicReaderSocket = null
        classicReaderThread?.interrupt()
        classicReaderThread = null
    }

    private fun closeClassic() {
        stopClassicReader()
        try { classicStream?.close() } catch (_: Exception) {}
        try { classicSocket?.close() } catch (_: Exception) {}
        classicStream = null
        classicSocket = null
    }
}
