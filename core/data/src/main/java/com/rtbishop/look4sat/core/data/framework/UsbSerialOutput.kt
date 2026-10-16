/*
 * Look4Sat / L4S-Labs USB serial transport.
 * Copyright (C) 2026 OpenAI
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat.core.data.framework

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.rtbishop.look4sat.core.domain.repository.ISerialOutput
import com.rtbishop.look4sat.core.domain.repository.SerialConnectionState
import com.rtbishop.look4sat.core.domain.repository.SerialOutputSettings
import com.rtbishop.look4sat.core.domain.repository.SerialPortDeviceInfo
import com.rtbishop.look4sat.core.domain.repository.SerialReceiveSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

class UsbSerialOutput(
    context: Context,
    private val reporterScope: CoroutineScope
) : ISerialOutput {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val prefs = appContext.getSharedPreferences(
        "${appContext.packageName}_serial_output", Context.MODE_PRIVATE
    )
    private val permissionAction = "${appContext.packageName}.USB_SERIAL_PERMISSION"
    private val portLock = Any()

    @Volatile private var serialPort: UsbSerialPort? = null
    @Volatile private var usbConnection: UsbDeviceConnection? = null
    @Volatile private var ioManager: SerialInputOutputManager? = null
    @Volatile private var pendingDeviceId: Int? = null

    private val _settings = MutableStateFlow(readSettings())
    override val settings = _settings.asStateFlow()
    private val _connectionState = MutableStateFlow(SerialConnectionState())
    override val connectionState = _connectionState.asStateFlow()
    private val _receivedBytes = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val receivedBytes = _receivedBytes.asSharedFlow()
    private val receiveSnapshotLock = Any()
    private val _receiveSnapshot = MutableStateFlow(SerialReceiveSnapshot())
    override val receiveSnapshot = _receiveSnapshot.asStateFlow()

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != permissionAction) return
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice)
            }
            val expectedId = pendingDeviceId
            if (device == null || expectedId == null || device.deviceId != expectedId) return
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                openGrantedDevice(device.deviceId)
            } else {
                pendingDeviceId = null
                _connectionState.value = SerialConnectionState(
                    message = "USB 权限被拒绝",
                    deviceId = device.deviceId
                )
            }
        }
    }

    init {
        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(permissionReceiver, filter)
        }
    }

    override fun availableDevices(): List<SerialPortDeviceInfo> = runCatching {
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).map { driver ->
            val dev = driver.device
            val title = listOfNotNull(dev.manufacturerName, dev.productName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "USB Serial" }
            SerialPortDeviceInfo(
                deviceId = dev.deviceId,
                displayName = "$title  (VID:${String.format(Locale.ROOT, "%04X", dev.vendorId)} PID:${String.format(Locale.ROOT, "%04X", dev.productId)})",
                vendorId = dev.vendorId,
                productId = dev.productId
            )
        }
    }.getOrElse {
        Log.e(TAG, "Enumerating USB serial devices failed", it)
        emptyList()
    }

    override fun updateSettings(settings: SerialOutputSettings) {
        _settings.value = settings
        prefs.edit()
            .putInt(KEY_DEVICE_ID, settings.deviceId)
            .putInt(KEY_BAUD, settings.baudRate)
            .putInt(KEY_BITS, settings.dataBits)
            .putInt(KEY_PARITY, settings.parity)
            .putInt(KEY_STOP_BITS, settings.stopBits)
            .putBoolean(KEY_ROTATOR_ENABLED, settings.rotatorState)
            .putString(KEY_ROTATOR_FORMAT, settings.rotatorFormat)
            .putBoolean(KEY_FREQ_ENABLED, settings.frequencyState)
            .putString(KEY_FREQ_FORMAT, settings.frequencyFormat)
            .apply()
    }

    override fun openDevice(deviceId: Int) {
        val driver = findDriver(deviceId)
        if (driver == null) {
            _connectionState.value = SerialConnectionState(
                message = "未找到可用串口驱动；请重新扫描 USB 设备",
                deviceId = deviceId
            )
            return
        }
        pendingDeviceId = deviceId
        _connectionState.value = SerialConnectionState(
            isConnecting = true,
            message = if (usbManager.hasPermission(driver.device)) "正在打开串口…" else "正在请求 USB 权限…",
            deviceId = deviceId
        )
        if (usbManager.hasPermission(driver.device)) {
            openGrantedDevice(deviceId)
            return
        }
        try {
            val mutability = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                deviceId,
                Intent(permissionAction).setPackage(appContext.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or mutability
            )
            usbManager.requestPermission(driver.device, pendingIntent)
        } catch (e: Exception) {
            pendingDeviceId = null
            _connectionState.value = SerialConnectionState(
                message = "申请 USB 权限失败：${e.message ?: "未知错误"}",
                deviceId = deviceId
            )
        }
    }

    private fun openGrantedDevice(deviceId: Int) {
        reporterScope.launch(Dispatchers.IO) {
            closeResources(updateState = false, message = "正在打开串口…")
            try {
                val driver = findDriver(deviceId) ?: throw IOException("串口设备已经移除或驱动不支持")
                if (!usbManager.hasPermission(driver.device)) throw IOException("没有 USB 设备权限")
                val connection = usbManager.openDevice(driver.device)
                    ?: throw IOException("无法打开 USB 设备")
                val port = driver.ports.firstOrNull() ?: run {
                    connection.close()
                    throw IOException("USB 设备没有可用串口")
                }
                try {
                    port.open(connection)
                    val config = _settings.value
                    val stopBits = when (config.stopBits) {
                        15 -> UsbSerialPort.STOPBITS_1_5
                        2 -> UsbSerialPort.STOPBITS_2
                        else -> UsbSerialPort.STOPBITS_1
                    }
                    port.setParameters(config.baudRate, config.dataBits, stopBits, config.parity)
                } catch (e: Exception) {
                    runCatching { port.close() }
                    connection.close()
                    throw e
                }
                synchronized(receiveSnapshotLock) {
                    _receiveSnapshot.value = SerialReceiveSnapshot()
                }
                synchronized(portLock) {
                    usbConnection = connection
                    serialPort = port
                    ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                        override fun onNewData(data: ByteArray) {
                            val chunk = data.copyOf()
                            _receivedBytes.tryEmit(chunk)
                            synchronized(receiveSnapshotLock) {
                                val previous = _receiveSnapshot.value
                                val combined = previous.bytes + chunk
                                val visible = if (combined.size > MAX_RECEIVE_HISTORY_BYTES) {
                                    combined.copyOfRange(combined.size - MAX_RECEIVE_HISTORY_BYTES, combined.size)
                                } else {
                                    combined
                                }
                                _receiveSnapshot.value = previous.copy(
                                    bytes = visible,
                                    totalBytes = previous.totalBytes + chunk.size,
                                    lastChunkBytes = chunk.size,
                                    lastReceiveTimeMillis = System.currentTimeMillis()
                                )
                            }
                        }

                        override fun onRunError(e: Exception) {
                            Log.e(TAG, "USB serial read failed", e)
                            reporterScope.launch(Dispatchers.IO) {
                                closeResources(
                                    updateState = true,
                                    message = "串口接收失败：${e.message ?: "未知错误"}"
                                )
                            }
                        }
                    }).also { it.start() }
                }
                pendingDeviceId = null
                _connectionState.value = SerialConnectionState(
                    isConnected = true,
                    message = "已连接：${driver.device.deviceName}",
                    deviceId = deviceId
                )
                Log.i(TAG, "USB serial connected id=$deviceId baud=${_settings.value.baudRate}")
            } catch (e: Exception) {
                pendingDeviceId = null
                Log.e(TAG, "Opening USB serial failed", e)
                closeResources(updateState = false, message = e.message ?: "打开串口失败")
                _connectionState.value = SerialConnectionState(
                    message = "打开串口失败：${e.message ?: "未知错误"}",
                    deviceId = deviceId
                )
            }
        }
    }

    override fun closeDevice() {
        pendingDeviceId = null
        _connectionState.value = SerialConnectionState(message = "已关闭")
        reporterScope.launch(Dispatchers.IO) {
            closeResources(updateState = false, message = "已关闭")
        }
    }

    override fun clearReceiveWindow() {
        synchronized(receiveSnapshotLock) {
            // Keep totalBytes/lastChunkBytes so the user can tell whether bytes were received.
            _receiveSnapshot.value = _receiveSnapshot.value.copy(bytes = ByteArray(0))
        }
    }

    private fun closeResources(updateState: Boolean, message: String) {
        val manager: SerialInputOutputManager?
        val port: UsbSerialPort?
        val connection: UsbDeviceConnection?
        synchronized(portLock) {
            manager = ioManager
            port = serialPort
            connection = usbConnection
            ioManager = null
            serialPort = null
            usbConnection = null
        }
        runCatching { manager?.stop() }
        runCatching { port?.close() }
        runCatching { connection?.close() }
        if (updateState) _connectionState.value = SerialConnectionState(message = message)
    }

    override suspend fun sendRaw(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext false
        val port = serialPort ?: return@withContext false
        try {
            port.write(bytes, WRITE_TIMEOUT_MS)
            true
        } catch (e: Exception) {
            Log.e(TAG, "USB serial write failed", e)
            _connectionState.value = SerialConnectionState(
                message = "串口发送失败：${e.message ?: "未知错误"}",
                deviceId = _settings.value.deviceId
            )
            reporterScope.launch(Dispatchers.IO) {
                closeResources(updateState = false, message = "发送失败")
            }
            false
        }
    }

    override fun reportRotation(format: String, azimuth: Double, elevation: Double) {
        if (!_settings.value.rotatorState || !_connectionState.value.isConnected) return
        val el = if (elevation > 0.0) elevation else 0.0
        val command = format
            .replace("\$AZ", azimuth.toString())
            .replace("\$EL", el.toString())
            .unescapeControlChars()
        reporterScope.launch(Dispatchers.IO) { sendRaw(command.toByteArray(Charsets.UTF_8)) }
    }

    override fun reportFrequency(format: String, frequency: Long) {
        val active = _settings.value.frequencyState && _connectionState.value.isConnected
        val useDtrac = DtracFrequencyProtocol.isDtracFrequencyTemplate(format)
        val asciiPayload = if (useDtrac) null else format
            .replace("\$FREQ", frequency.toString())
            .unescapeControlChars()
            .toByteArray(Charsets.UTF_8)

        reporterScope.launch(Dispatchers.IO) {
            try {
                DtracFrequencyProtocol.sendFrequencyOutput(
                    owner = this@UsbSerialOutput,
                    active = active,
                    useDtrac = useDtrac,
                    frequencyHz = frequency,
                    asciiPayload = asciiPayload
                ) { payload -> sendRaw(payload) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Frequency send error: ${e.message}")
            }
        }
    }

    private fun findDriver(deviceId: Int) = runCatching {
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            .firstOrNull { it.device.deviceId == deviceId }
    }.getOrNull()

    private fun readSettings(): SerialOutputSettings {
        val defaults = SerialOutputSettings()
        val storedRotatorFormat = prefs.getString(KEY_ROTATOR_FORMAT, null)
        val storedFrequencyFormat = prefs.getString(KEY_FREQ_FORMAT, null)
        // Upgrade previous built-in defaults without overwriting a genuinely customized template.
        val rotatorFormat = when (storedRotatorFormat) {
            null -> defaults.rotatorFormat
            "AZ \$AZ EL \$EL\\r\\n" -> defaults.rotatorFormat
            else -> storedRotatorFormat
        }
        val frequencyFormat = when (storedFrequencyFormat) {
            null -> defaults.frequencyFormat
            "F \$FREQ\\r\\n" -> defaults.frequencyFormat
            else -> storedFrequencyFormat
        }
        return SerialOutputSettings(
            deviceId = prefs.getInt(KEY_DEVICE_ID, -1),
            baudRate = prefs.getInt(KEY_BAUD, 9600),
            dataBits = prefs.getInt(KEY_BITS, 8),
            parity = prefs.getInt(KEY_PARITY, 0),
            stopBits = prefs.getInt(KEY_STOP_BITS, 1),
            rotatorState = prefs.getBoolean(KEY_ROTATOR_ENABLED, false),
            rotatorFormat = rotatorFormat,
            frequencyState = prefs.getBoolean(KEY_FREQ_ENABLED, false),
            frequencyFormat = frequencyFormat
        )
    }

    private fun String.unescapeControlChars(): String =
        replace("\\r", "\r").replace("\\n", "\n").replace("\\t", "\t")

    companion object {
        private const val TAG = "L4SSerial"
        private const val WRITE_TIMEOUT_MS = 1000
        private const val MAX_RECEIVE_HISTORY_BYTES = 4000
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_BAUD = "baud_rate"
        private const val KEY_BITS = "data_bits"
        private const val KEY_PARITY = "parity"
        private const val KEY_STOP_BITS = "stop_bits"
        private const val KEY_ROTATOR_ENABLED = "rotator_enabled"
        private const val KEY_ROTATOR_FORMAT = "rotator_format"
        private const val KEY_FREQ_ENABLED = "frequency_enabled"
        private const val KEY_FREQ_FORMAT = "frequency_format"
    }
}
