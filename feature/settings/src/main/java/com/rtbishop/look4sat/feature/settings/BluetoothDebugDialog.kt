/*
 * L4S-Labs Bluetooth raw-data debug console.
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun BluetoothDebugDialog(
    deviceId: String,
    sendRaw: suspend (String, ByteArray) -> Boolean,
    observeReceivedBytes: (String) -> Flow<ByteArray>,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val receiveFlow = remember(deviceId) { observeReceivedBytes(deviceId) }
    var sendText by remember { mutableStateOf("") }
    var sendHex by remember { mutableStateOf(false) }
    var receiveHex by remember { mutableStateOf(false) }
    var receiveData by remember(deviceId) { mutableStateOf(ByteArray(0)) }
    var sendStatus by remember { mutableStateOf("") }
    val receiveScroll = rememberScrollState()
    val receiveText = remember(receiveData, receiveHex) {
        if (receiveHex) receiveData.toHexString() else receiveData.toVisibleAscii()
    }

    LaunchedEffect(deviceId, receiveFlow) {
        receiveFlow.collect { bytes ->
            if (bytes.isNotEmpty()) {
                receiveData = (receiveData + bytes).takeLast(MAX_BLUETOOTH_RECEIVE_BYTES).toByteArray()
            }
        }
    }
    LaunchedEffect(receiveText) {
        receiveScroll.scrollTo(receiveScroll.maxValue)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("蓝牙调试 · $deviceId") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = sendText.isNotEmpty(),
                        onClick = {
                            val bytes = if (sendHex) parseBluetoothHexBytes(sendText) else sendText.toByteArray(Charsets.UTF_8)
                            if (bytes == null) {
                                sendStatus = "HEX 格式错误：请输入完整的两位十六进制字节"
                            } else {
                                scope.launch {
                                    val ok = try { sendRaw(deviceId, bytes) } catch (_: Exception) { false }
                                    sendStatus = if (ok) "已发送 ${bytes.size} 字节" else "发送失败：连接已断开或写入失败"
                                }
                            }
                        }
                    ) { Text("发送") }
                    Text(sendStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("接收数据", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    FilterChip(selected = !receiveHex, onClick = { receiveHex = false }, label = { Text("ASCII") })
                    FilterChip(selected = receiveHex, onClick = { receiveHex = true }, label = { Text("HEX") })
                    TextButton(onClick = { receiveData = ByteArray(0) }) { Text("清空") }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                ) {
                    Box(modifier = Modifier.padding(8.dp).verticalScroll(receiveScroll)) {
                        Text(receiveText.ifEmpty { "等待蓝牙接收数据…" }, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

private fun parseBluetoothHexBytes(raw: String): ByteArray? {
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

private const val MAX_BLUETOOTH_RECEIVE_BYTES = 4000
