/*
 * Look4Sat / L4S-Labs serial output abstractions.
 * Copyright (C) 2026 OpenAI
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat.core.domain.repository

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class SerialPortDeviceInfo(
    val deviceId: Int,
    val displayName: String,
    val vendorId: Int,
    val productId: Int
)

data class SerialConnectionState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val message: String = "未连接",
    val deviceId: Int? = null
)

/** Current receive-window bytes and diagnostics captured by the USB serial read callback. */
data class SerialReceiveSnapshot(
    val bytes: ByteArray = ByteArray(0),
    /** Total bytes received since the current port session was opened; clearing the window does not reset it. */
    val totalBytes: Long = 0L,
    val lastChunkBytes: Int = 0,
    val lastReceiveTimeMillis: Long = 0L
)

data class SerialOutputSettings(
    /** Android USB device ID; this can change after unplug/replug. */
    val deviceId: Int = -1,
    val baudRate: Int = 9600,
    val dataBits: Int = 8,
    /** 0=None, 1=Odd, 2=Even, 3=Mark, 4=Space. */
    val parity: Int = 0,
    /** 1=one stop bit, 15=one-and-a-half stop bits, 2=two stop bits. */
    val stopBits: Int = 1,
    val rotatorState: Boolean = false,
    val rotatorFormat: String = "P \$AZ \$EL",
    val frequencyState: Boolean = false,
    val frequencyFormat: String = "F \$FREQ\nM \$MODE\nT \$FREQ_T\nTC \$CTCSS_T\nRC \$CTCSS_R"
)

/** Shared USB serial connection used by the Settings dialog, debug console and automatic telemetry. */
interface ISerialOutput : IReporter {
    val settings: StateFlow<SerialOutputSettings>
    val connectionState: StateFlow<SerialConnectionState>
    val receivedBytes: SharedFlow<ByteArray>
    /** StateFlow keeps the current receive history available even if the debug dialog opens later. */
    val receiveSnapshot: StateFlow<SerialReceiveSnapshot>

    fun availableDevices(): List<SerialPortDeviceInfo>
    fun updateSettings(settings: SerialOutputSettings)
    fun openDevice(deviceId: Int)
    fun closeDevice()
    fun clearReceiveWindow()
    suspend fun sendRaw(bytes: ByteArray): Boolean
}
