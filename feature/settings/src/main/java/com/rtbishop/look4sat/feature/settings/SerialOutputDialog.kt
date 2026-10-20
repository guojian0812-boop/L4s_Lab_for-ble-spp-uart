/*
 * L4S-Labs USB serial settings and raw serial console.
 * Copyright (C) 2026 OpenAI
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.repository.ISerialOutput
import com.rtbishop.look4sat.core.domain.repository.SerialOutputSettings
import com.rtbishop.look4sat.core.domain.repository.SerialPortDeviceInfo
import kotlinx.coroutines.launch
import java.util.Locale

private const val DTRAC_PROTOCOL_MARKER = "__L4S_DTRAC_BINARY_V1__"
private const val LEGACY_DTRAC_PROTOCOL_MARKER = "__L4S_DTRAC_ASCII_V1__"
private const val DTRAC_ASCII_FORMAT_PREVIEW = "F \$FREQ\nM \$MODE\nT \$FREQ_T\nTC \$CTCSS_T\nRC \$CTCSS_R"
private fun isDtracMarker(value: String): Boolean =
    value.trim().removePrefix("\\") in setOf(DTRAC_PROTOCOL_MARKER, LEGACY_DTRAC_PROTOCOL_MARKER)

@Composable
fun SerialOutputDialog(serialOutput: ISerialOutput, onDismiss: () -> Unit) {
    val savedSettings by serialOutput.settings.collectAsState()
    val connection by serialOutput.connectionState.collectAsState()
    var draft by remember { mutableStateOf(savedSettings) }
    var useDtracProtocol by remember(savedSettings.frequencyFormat) {
        mutableStateOf(isDtracMarker(savedSettings.frequencyFormat))
    }
    var frequencyFormatText by remember(savedSettings.frequencyFormat) {
        mutableStateOf(
            if (isDtracMarker(savedSettings.frequencyFormat) ||
                savedSettings.frequencyFormat.trim() == "F \$FREQ M \$MODE T \$FREQ_T TC \$CTCSS_T RC \$CTCSS_R" ||
                savedSettings.frequencyFormat.isBlank()
            ) DTRAC_ASCII_FORMAT_PREVIEW else savedSettings.frequencyFormat
        )
    }
    fun settingsForSave(): SerialOutputSettings = draft.copy(
        frequencyFormat = if (useDtracProtocol) DTRAC_PROTOCOL_MARKER else frequencyFormatText
    )
    var devices by remember { mutableStateOf(serialOutput.availableDevices()) }
    var deviceMenu by remember { mutableStateOf(false) }
    var debugVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            serialOutput.updateSettings(settingsForSave())
            onDismiss()
        },
        title = { Text("串口输出") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("USB 串口设备", style = MaterialTheme.typography.titleSmall)
                Box {
                    OutlinedButton(onClick = { deviceMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        val selected = devices.firstOrNull { it.deviceId == draft.deviceId }
                        Text(selected?.displayName ?: "请选择 USB 串口设备", maxLines = 1)
                    }
                    DropdownMenu(expanded = deviceMenu, onDismissRequest = { deviceMenu = false }) {
                        devices.forEach { device ->
                            DropdownMenuItem(
                                text = { Text(device.displayName, maxLines = 2) },
                                onClick = {
                                    draft = draft.copy(deviceId = device.deviceId)
                                    deviceMenu = false
                                }
                            )
                        }
                    }
                }
                TextButton(onClick = { devices = serialOutput.availableDevices() }) {
                    Text("刷新 USB 设备列表")
                }
                if (devices.isEmpty()) {
                    Text("未发现受支持的 USB 串口设备。请连接 OTG 串口转换器后刷新。", color = MaterialTheme.colorScheme.error)
                }
                SerialChoice(
                    label = "波特率",
                    selected = draft.baudRate,
                    options = listOf(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)
                        .map { it.toString() to it },
                    onSelect = { draft = draft.copy(baudRate = it) }
                )
                SerialChoice(
                    label = "数据位",
                    selected = draft.dataBits,
                    options = listOf(5, 6, 7, 8).map { it.toString() to it },
                    onSelect = { draft = draft.copy(dataBits = it) }
                )
                SerialChoice(
                    label = "校验位",
                    selected = draft.parity,
                    options = listOf("无" to 0, "奇校验" to 1, "偶校验" to 2, "Mark" to 3, "Space" to 4),
                    onSelect = { draft = draft.copy(parity = it) }
                )
                SerialChoice(
                    label = "停止位",
                    selected = draft.stopBits,
                    options = listOf("1" to 1, "1.5" to 15, "2" to 2),
                    onSelect = { draft = draft.copy(stopBits = it) }
                )

                Text("自动输出格式", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = draft.rotatorState, onCheckedChange = { draft = draft.copy(rotatorState = it) })
                    Text("输出转台方位角/仰角")
                }
                OutlinedTextField(
                    value = draft.rotatorFormat,
                    onValueChange = { draft = draft.copy(rotatorFormat = it) },
                    label = { Text("转台格式（\$AZ / \$EL）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = draft.frequencyState, onCheckedChange = { draft = draft.copy(frequencyState = it) })
                    Text("输出频率")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = useDtracProtocol,
                        onCheckedChange = { useDtracProtocol = it }
                    )
                    Text("D")
                }
                OutlinedTextField(
                    value = frequencyFormatText,
                    onValueChange = { frequencyFormatText = it },
                    enabled = !useDtracProtocol,
                    label = { Text("频率格式") },
                    minLines = 5,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("连接状态：${connection.message}", color = if (connection.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            serialOutput.updateSettings(settingsForSave())
                            if (draft.deviceId >= 0) serialOutput.openDevice(draft.deviceId)
                        },
                        enabled = !connection.isConnecting && devices.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text(if (connection.isConnecting) "正在打开…" else "打开串口") }
                    OutlinedButton(
                        onClick = { serialOutput.closeDevice() },
                        enabled = connection.isConnected || connection.isConnecting,
                        modifier = Modifier.weight(1f)
                    ) { Text("关闭串口") }
                }
                OutlinedButton(onClick = { serialOutput.updateSettings(settingsForSave()); debugVisible = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("串口调试窗口")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { serialOutput.updateSettings(settingsForSave()); onDismiss() }) { Text("保存设置") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )

    if (debugVisible) {
        SerialDebugDialog(serialOutput = serialOutput, onDismiss = { debugVisible = false })
    }
}

@Composable
private fun SerialChoice(
    label: String,
    selected: Int,
    options: List<Pair<String, Int>>,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.width(170.dp)) {
                Text(options.firstOrNull { it.second == selected }?.first ?: selected.toString())
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (text, value) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun SerialDebugDialog(serialOutput: ISerialOutput, onDismiss: () -> Unit) {
    val connection by serialOutput.connectionState.collectAsState()
    val scope = rememberCoroutineScope()
    var sendText by remember { mutableStateOf("") }
    var sendHex by remember { mutableStateOf(false) }
    var receiveHex by remember { mutableStateOf(false) }
    val receiveSnapshot by serialOutput.receiveSnapshot.collectAsState()
    val receiveData = receiveSnapshot.bytes
    var sendStatus by remember { mutableStateOf("") }
    val receiveScroll = rememberScrollState()
    val receiveText = remember(receiveData, receiveHex) {
        if (receiveHex) receiveData.toHexString() else receiveData.toVisibleAscii()
    }

    LaunchedEffect(receiveText) {
        receiveScroll.scrollTo(receiveScroll.maxValue)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("串口调试") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("连接状态：${connection.message}", style = MaterialTheme.typography.bodySmall)
                Text("发送数据", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !sendHex, onClick = { sendHex = false }, label = { Text("ASCII") })
                    FilterChip(selected = sendHex, onClick = { sendHex = true }, label = { Text("HEX") })
                }
                OutlinedTextField(
                    value = sendText,
                    onValueChange = { sendText = it },
                    label = { Text(if (sendHex) "输入 HEX，例如 46 20 31 32 33" else "输入 ASCII 文本") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            val bytes = if (sendHex) parseHexBytes(sendText) else sendText.toByteArray(Charsets.UTF_8)
                            if (bytes == null) {
                                sendStatus = "HEX 格式错误：请输入完整的两位十六进制字节"
                            } else {
                                scope.launch {
                                    val ok = serialOutput.sendRaw(bytes)
                                    sendStatus = if (ok) "已发送 ${bytes.size} 字节" else "发送失败：请确认串口已打开"
                                }
                            }
                        },
                        enabled = connection.isConnected && sendText.isNotEmpty()
                    ) { Text("发送") }
                    Spacer(Modifier.width(8.dp))
                    Text(sendStatus, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("接收数据", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    FilterChip(selected = !receiveHex, onClick = { receiveHex = false }, label = { Text("ASCII") })
                    Spacer(Modifier.width(4.dp))
                    FilterChip(selected = receiveHex, onClick = { receiveHex = true }, label = { Text("HEX") })
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { serialOutput.clearReceiveWindow() }) { Text("清空") }
                }
                Text(
                    text = if (receiveSnapshot.totalBytes == 0L) {
                        "尚未收到串口数据（底层接收回调尚未触发）"
                    } else {
                        "累计接收 ${receiveSnapshot.totalBytes} 字节；当前窗口 ${receiveData.size} 字节；最近一包 ${receiveSnapshot.lastChunkBytes} 字节"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (receiveSnapshot.totalBytes == 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                ) {
                    Box(modifier = Modifier.padding(8.dp).verticalScroll(receiveScroll)) {
                        Text(receiveText.ifEmpty { "等待串口接收数据…" }, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

private fun parseHexBytes(raw: String): ByteArray? {
    val normalized = raw.replace("0x", "", ignoreCase = true)
        .replace(Regex("[\\s,:;_-]+"), "")
    if (normalized.isEmpty() || normalized.length % 2 != 0) return null
    val output = ByteArray(normalized.length / 2)
    for (index in output.indices) {
        val value = normalized.substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return null
        output[index] = value.toByte()
    }
    return output
}

private fun ByteArray.toHexString(): String = joinToString(" ") {
    String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF)
} + "\n"

private fun ByteArray.toVisibleAscii(): String = buildString {
    for (byte in this@toVisibleAscii) {
        when (val value = byte.toInt() and 0xFF) {
            13 -> append("\\r")
            10 -> append("\n")
            9 -> append("\\t")
            in 32..126 -> append(value.toChar())
            else -> append(String.format(Locale.ROOT, "\\x%02X", value))
        }
    }
}

private const val MAX_RECEIVE_BYTES = 4000
