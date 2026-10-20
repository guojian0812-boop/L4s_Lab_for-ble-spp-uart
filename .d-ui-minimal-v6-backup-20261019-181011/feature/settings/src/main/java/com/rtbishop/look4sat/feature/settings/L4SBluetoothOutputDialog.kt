/*
 * L4S-Labs - Bluetooth output UI extensions.
 * Based on Look4Sat v4.4.9; distributed under the same license as the project.
 */
package com.rtbishop.look4sat.feature.settings

import androidx.compose.material3.Checkbox
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.presentation.R
import kotlinx.coroutines.launch
import java.util.Locale

private enum class BluetoothConnectState {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    DISCONNECTED,
    FAILED
}

private enum class BluetoothAction { CONNECT, DISCONNECT }

private const val FD_DTRAC_MARKER = "__L4S_DTRAC_BINARY_V1__"
private const val FD_DTRAC_ASCII_LEGACY_MARKER = "__L4S_DTRAC_ASCII_V1__"
private const val FD_DTRAC_FORMAT_PREVIEW = "F \$FREQ\nM \$MODE\nT \$FREQ_T\nTC \$CTCSS_T\nRC \$CTCSS_R"
private fun isFdDtracMarker(value: String): Boolean =
    value.trim().removePrefix("\\") in setOf(FD_DTRAC_MARKER, FD_DTRAC_ASCII_LEGACY_MARKER)

/**
 * L4S-Labs Bluetooth dialog. Selected device IDs remain MAC addresses in settings; the UI shows
 * the human-friendly name and MAC together. Scanning includes nearby BLE/classic devices and
 * previously paired devices, so the same selector can be used for BLE GATT or Classic SPP.
 */
private const val DTRAC_ASCII_FORMAT_PREVIEW =
    "F \$FREQ\nM \$MODE\nT \$FREQ_T\nTC \$CTCSS_T\nRC \$CTCSS_R"

@Composable
internal fun L4SBluetoothOutputDialog(
    initialRotatorState: Boolean,
    initialRotatorAddress: String,
    initialRotatorFormat: String,
    initialFrequencyState: Boolean,
    initialFrequencyAddress: String,
    initialFrequencyFormat: String,
    onDismiss: () -> Unit,
    onSave: (Boolean, String, String, Boolean, String, String) -> Unit,
    connectBluetooth: suspend (String) -> Boolean,
    disconnectBluetooth: suspend (String) -> Boolean,
    scanBluetoothDevices: suspend () -> List<Pair<String, String>>,
    isBluetoothDeviceConnected: suspend (String) -> Boolean,
    sendBluetoothRaw: suspend (String, ByteArray) -> Boolean,
    observeBluetoothReceivedBytes: (String) -> kotlinx.coroutines.flow.Flow<ByteArray>
) {
    var rotatorEnabled by remember(initialRotatorState) { mutableStateOf(initialRotatorState) }
    var rotatorAddress by remember(initialRotatorAddress) { mutableStateOf(initialRotatorAddress) }
    var rotatorFormat by remember(initialRotatorFormat) { mutableStateOf(initialRotatorFormat) }
    var frequencyEnabled by remember(initialFrequencyState) { mutableStateOf(initialFrequencyState) }
    var frequencyAddress by remember(initialFrequencyAddress) { mutableStateOf(initialFrequencyAddress) }
    var useDtracProtocol by remember(initialFrequencyFormat) {
        mutableStateOf(isFdDtracMarker(initialFrequencyFormat))
    }
    var frequencyFormat by remember(initialFrequencyFormat) {
        mutableStateOf(
            if (isFdDtracMarker(initialFrequencyFormat) ||
                initialFrequencyFormat.trim() == "F \$FREQ" || initialFrequencyFormat.isBlank()
            ) FD_DTRAC_FORMAT_PREVIEW else initialFrequencyFormat
        )
    }

    // The dialog is recreated when dismissed. Query the shared transport registry each time
    // it opens so the status reflects the real BLE/SPP connection, not only Compose state.
    var rotatorConnectState by remember(initialRotatorAddress) {
        mutableStateOf(BluetoothConnectState.DISCONNECTED)
    }
    var frequencyConnectState by remember(initialFrequencyAddress) {
        mutableStateOf(BluetoothConnectState.DISCONNECTED)
    }
    var debugDeviceId by remember { mutableStateOf<String?>(null) }
    var showDebugTargetPicker by remember { mutableStateOf(false) }

    LaunchedEffect(rotatorAddress) {
        val connected = try {
            rotatorAddress.isNotBlank() && isBluetoothDeviceConnected(rotatorAddress.trim())
        } catch (_: Exception) {
            false
        }
        if (rotatorConnectState != BluetoothConnectState.CONNECTING &&
            rotatorConnectState != BluetoothConnectState.DISCONNECTING) {
            rotatorConnectState = if (connected) BluetoothConnectState.CONNECTED
                else BluetoothConnectState.DISCONNECTED
        }
    }
    LaunchedEffect(frequencyAddress) {
        val connected = try {
            frequencyAddress.isNotBlank() && isBluetoothDeviceConnected(frequencyAddress.trim())
        } catch (_: Exception) {
            false
        }
        if (frequencyConnectState != BluetoothConnectState.CONNECTING &&
            frequencyConnectState != BluetoothConnectState.DISCONNECTING) {
            frequencyConnectState = if (connected) BluetoothConnectState.CONNECTED
                else BluetoothConnectState.DISCONNECTED
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.prefs_bt_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EnableRow(
                    title = stringResource(R.string.prefs_bt_rotator_switch),
                    enabled = rotatorEnabled,
                    onEnabledChanged = { rotatorEnabled = it }
                )
                BluetoothDevicePickerField(
                    label = stringResource(R.string.prefs_bt_rotator_device_hint),
                    deviceId = rotatorAddress,
                    onDeviceIdChanged = {
                        rotatorAddress = it
                        rotatorConnectState = BluetoothConnectState.DISCONNECTED
                    },
                    scanBluetoothDevices = scanBluetoothDevices
                )
                BluetoothConnectionButtons(
                    deviceId = rotatorAddress,
                    state = rotatorConnectState,
                    onStateChanged = { rotatorConnectState = it },
                    connectBluetooth = connectBluetooth,
                    disconnectBluetooth = disconnectBluetooth
                )
                OutlinedTextField(
                    value = rotatorFormat,
                    onValueChange = { rotatorFormat = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.prefs_bt_rotator_output_hint)) },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(4.dp))
                EnableRow(
                    title = stringResource(R.string.prefs_bt_frequency_switch),
                    enabled = frequencyEnabled,
                    onEnabledChanged = { frequencyEnabled = it }
                )
                BluetoothDevicePickerField(
                    label = stringResource(R.string.prefs_bt_frequency_device_hint),
                    deviceId = frequencyAddress,
                    onDeviceIdChanged = {
                        frequencyAddress = it
                        frequencyConnectState = BluetoothConnectState.DISCONNECTED
                    },
                    scanBluetoothDevices = scanBluetoothDevices
                )
                BluetoothConnectionButtons(
                    deviceId = frequencyAddress,
                    state = frequencyConnectState,
                    onStateChanged = { frequencyConnectState = it },
                    connectBluetooth = connectBluetooth,
                    disconnectBluetooth = disconnectBluetooth
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = useDtracProtocol,
                        onCheckedChange = { useDtracProtocol = it }
                    )
                    Text("D")
                }
                if (useDtracProtocol) {
                    OutlinedTextField(
                        value = DTRAC_ASCII_FORMAT_PREVIEW,
                        onValueChange = {},
                        readOnly = true,
                        minLines = 5,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("DTrac 协议格式（自动填充实际值）") }
                    )
                    Text(
                        "F=\$FREQ（RX 接收频率，Hz）；M=\$MODE（接收模式优先）；T=\$FREQ_T（TX 发射频率，Hz）；TC=\$CTCSS_T（发送 CTCSS，Hz）；RC=\$CTCSS_R（接收 CTCSS，Hz）。亚音为 0 表示关闭。以上是模板说明，实际数值由当前转发器自动生成，无需手动填写。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedTextField(
                        value = frequencyFormat,
                        onValueChange = { frequencyFormat = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("频率格式（未勾选 D 时使用）") },
                        minLines = 5,
                        maxLines = 5
                    )
                    Text(
                        "未勾选 DTrac 时也会替换 \$FREQ、\$MODE、\$FREQ_T、\$CTCSS_T、\$CTCSS_R；每行自动以 CRLF 发送。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val debugDevicesConnected = rotatorConnectState == BluetoothConnectState.CONNECTED ||
                    frequencyConnectState == BluetoothConnectState.CONNECTED
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (debugDevicesConnected) "蓝牙已连接" else "连接蓝牙后可使用调试窗口",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        enabled = debugDevicesConnected,
                        onClick = {
                            val connectedDevices = listOf(
                                rotatorAddress.trim().takeIf {
                                    rotatorConnectState == BluetoothConnectState.CONNECTED && rotatorAddress.isNotBlank()
                                },
                                frequencyAddress.trim().takeIf {
                                    frequencyConnectState == BluetoothConnectState.CONNECTED && frequencyAddress.isNotBlank()
                                }
                            ).filterNotNull().distinct()
                            when (connectedDevices.size) {
                                1 -> debugDeviceId = connectedDevices.first()
                                in 2..Int.MAX_VALUE -> showDebugTargetPicker = true
                            }
                        }
                    ) {
                        Text("蓝牙调试")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    rotatorEnabled,
                    rotatorAddress.trim(),
                    rotatorFormat,
                    frequencyEnabled,
                    frequencyAddress.trim(),
                    if (useDtracProtocol) "__L4S_DTRAC_ASCII_V1__" else frequencyFormat
                )
                onDismiss()
            }) {
                Text(text = stringResource(R.string.btn_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.btn_cancel))
            }
        }
    )

    if (showDebugTargetPicker) {
        val rotatorReady = rotatorConnectState == BluetoothConnectState.CONNECTED && rotatorAddress.isNotBlank()
        val frequencyReady = frequencyConnectState == BluetoothConnectState.CONNECTED && frequencyAddress.isNotBlank()
        AlertDialog(
            onDismissRequest = { showDebugTargetPicker = false },
            title = { Text("选择调试设备") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rotatorReady) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                debugDeviceId = rotatorAddress.trim()
                                showDebugTargetPicker = false
                            }
                        ) { Text("转台 · ${rotatorAddress.trim()}") }
                    }
                    if (frequencyReady && frequencyAddress.trim() != rotatorAddress.trim()) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                debugDeviceId = frequencyAddress.trim()
                                showDebugTargetPicker = false
                            }
                        ) { Text("频率 · ${frequencyAddress.trim()}") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDebugTargetPicker = false }) { Text("取消") }
            }
        )
    }

    debugDeviceId?.let { selectedId ->
        BluetoothDebugDialog(
            deviceId = selectedId,
            sendRaw = sendBluetoothRaw,
            observeReceivedBytes = observeBluetoothReceivedBytes,
            onDismiss = { debugDeviceId = null }
        )
    }
}

@Composable
private fun EnableRow(
    title: String,
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, modifier = Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = onEnabledChanged)
    }
}

@Composable
private fun BluetoothDevicePickerField(
    label: String,
    deviceId: String,
    onDeviceIdChanged: (String) -> Unit,
    scanBluetoothDevices: suspend () -> List<Pair<String, String>>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceNamePreferences = remember(context) {
        context.getSharedPreferences("l4s_labs_bluetooth_device_names", Context.MODE_PRIVATE)
    }
    var fieldText by remember(deviceId) {
        val savedName = deviceNamePreferences.getString(deviceId.trim().lowercase(Locale.ROOT), null)
        mutableStateOf(formatBluetoothDeviceLabel(savedName, deviceId))
    }
    var filterText by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var menuExpanded by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<String?>(null) }

    val scanPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasAllPermissions(context, requiredScanPermissions())) {
            performDeviceScan(
                scope = scope,
                scanBluetoothDevices = scanBluetoothDevices,
                onScanningChanged = { isScanning = it },
                onDevicesChanged = { devices = it },
                onMessageChanged = { scanMessage = it },
                onExpandChanged = { menuExpanded = it },
                context = context
            )
        } else {
            scanMessage = context.getString(R.string.prefs_bt_permission_denied)
            menuExpanded = true
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = fieldText,
            onValueChange = {
                fieldText = it
                filterText = it
                // Keep the selected MAC/name label from becoming the transport ID. Raw manual
                // IDs are still accepted when the field contains no display-label separator.
                if (!it.contains(" · ")) onDeviceIdChanged(it)
                if (devices.isNotEmpty()) menuExpanded = true
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true,
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    TextButton(
                        enabled = !isScanning,
                        onClick = {
                            filterText = ""
                            menuExpanded = true
                            val missing = missingPermissions(context, requiredScanPermissions())
                            if (missing.isEmpty()) {
                                performDeviceScan(
                                    scope = scope,
                                    scanBluetoothDevices = scanBluetoothDevices,
                                    onScanningChanged = { isScanning = it },
                                    onDevicesChanged = { devices = it },
                                    onMessageChanged = { scanMessage = it },
                                    onExpandChanged = { menuExpanded = it },
                                    context = context
                                )
                            } else {
                                scanPermissionLauncher.launch(missing)
                            }
                        }
                    ) {
                        // Keep the field width stable while scanning; the spinner conveys activity.
                        Text(text = stringResource(R.string.prefs_bt_scan))
                    }
                }
            }
        )

        val filteredDevices = devices.filter { (name, address) ->
            filterText.isBlank() || name.contains(filterText, ignoreCase = true) ||
                address.contains(filterText, ignoreCase = true)
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier
                .widthIn(min = 240.dp, max = 360.dp)
                .heightIn(max = 300.dp)
        ) {
            when {
                isScanning -> DropdownMenuItem(
                    text = { Text(stringResource(R.string.prefs_bt_scanning)) },
                    enabled = false,
                    onClick = {}
                )
                filteredDevices.isEmpty() -> DropdownMenuItem(
                    text = {
                        Text(
                            scanMessage ?: stringResource(R.string.prefs_bt_no_devices),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    enabled = false,
                    onClick = {}
                )
                else -> filteredDevices.forEach { (name, address) ->
                    DropdownMenuItem(
                        text = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(1.dp)
                            ) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        onClick = {
                            val id = address.trim()
                            if (name.isNotBlank() && !name.equals("Unknown device", ignoreCase = true)) {
                                deviceNamePreferences.edit()
                                    .putString(id.lowercase(Locale.ROOT), name)
                                    .apply()
                            }
                            onDeviceIdChanged(id)
                            fieldText = formatBluetoothDeviceLabel(name, id)
                            filterText = ""
                            menuExpanded = false
                            scanMessage = null
                        }
                    )
                }
            }
        }
    }

    scanMessage?.let { message ->
        Text(
            text = message,
            color = if (message.contains("failed", ignoreCase = true) ||
                message.contains("denied", ignoreCase = true)) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatBluetoothDeviceLabel(name: String?, deviceId: String): String {
    val id = deviceId.trim()
    val displayName = name?.trim().orEmpty()
    return when {
        id.isBlank() -> ""
        displayName.isBlank() || displayName.equals("Unknown device", ignoreCase = true) -> id
        else -> "$displayName · $id"
    }
}

private fun performDeviceScan(
    scope: kotlinx.coroutines.CoroutineScope,
    scanBluetoothDevices: suspend () -> List<Pair<String, String>>,
    onScanningChanged: (Boolean) -> Unit,
    onDevicesChanged: (List<Pair<String, String>>) -> Unit,
    onMessageChanged: (String?) -> Unit,
    onExpandChanged: (Boolean) -> Unit,
    context: Context
) {
    scope.launch {
        onScanningChanged(true)
        onDevicesChanged(emptyList())
        onMessageChanged(context.getString(R.string.prefs_bt_scanning))
        onExpandChanged(true)
        try {
            val found = scanBluetoothDevices()
            onDevicesChanged(found)
            onMessageChanged(
                if (found.isEmpty()) context.getString(R.string.prefs_bt_no_devices) else null
            )
        } catch (e: Exception) {
            onDevicesChanged(emptyList())
            val detail = e.message?.takeIf { it.isNotBlank() }
            onMessageChanged(
                if (detail == null) context.getString(R.string.prefs_bt_scan_failed)
                else context.getString(R.string.prefs_bt_scan_failed) + ": " + detail
            )
        } finally {
            onScanningChanged(false)
            onExpandChanged(true)
        }
    }
}

@Composable
private fun BluetoothConnectionButtons(
    deviceId: String,
    state: BluetoothConnectState,
    onStateChanged: (BluetoothConnectState) -> Unit,
    connectBluetooth: suspend (String) -> Boolean,
    disconnectBluetooth: suspend (String) -> Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingAction by remember { mutableStateOf<BluetoothAction?>(null) }

    val executeAction: (BluetoothAction) -> Unit = { action ->
        if (action == BluetoothAction.CONNECT) {
            onStateChanged(BluetoothConnectState.CONNECTING)
        } else {
            onStateChanged(BluetoothConnectState.DISCONNECTING)
        }
        scope.launch {
            val success = try {
                when (action) {
                    BluetoothAction.CONNECT -> connectBluetooth(deviceId.trim())
                    BluetoothAction.DISCONNECT -> disconnectBluetooth(deviceId.trim())
                }
            } catch (_: Exception) {
                false
            }
            onStateChanged(
                when {
                    action == BluetoothAction.CONNECT && success -> BluetoothConnectState.CONNECTED
                    action == BluetoothAction.DISCONNECT && success -> BluetoothConnectState.DISCONNECTED
                    else -> BluetoothConnectState.FAILED
                }
            )
        }
    }

    val connectionPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val action = pendingAction
        pendingAction = null
        if (action != null && hasAllPermissions(context, requiredConnectionPermissions())) {
            executeAction(action)
        } else {
            onStateChanged(BluetoothConnectState.FAILED)
        }
    }

    fun requestAction(action: BluetoothAction) {
        if (deviceId.isBlank()) {
            onStateChanged(BluetoothConnectState.FAILED)
            return
        }
        val missing = missingPermissions(context, requiredConnectionPermissions())
        if (missing.isEmpty()) {
            executeAction(action)
        } else {
            pendingAction = action
            connectionPermissionLauncher.launch(missing)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                enabled = deviceId.isNotBlank() && state != BluetoothConnectState.CONNECTING &&
                    state != BluetoothConnectState.DISCONNECTING,
                onClick = { requestAction(BluetoothAction.CONNECT) }
            ) {
                Text(stringResource(R.string.prefs_bt_connect))
            }
            OutlinedButton(
                enabled = deviceId.isNotBlank() && state != BluetoothConnectState.DISCONNECTING &&
                    state != BluetoothConnectState.CONNECTING,
                onClick = { requestAction(BluetoothAction.DISCONNECT) }
            ) {
                Text(stringResource(R.string.prefs_bt_disconnect))
            }
Spacer(Modifier.weight(1f))
        }
        val statusText = when (state) {
            BluetoothConnectState.IDLE -> null
            BluetoothConnectState.CONNECTING -> stringResource(R.string.prefs_bt_connecting)
            BluetoothConnectState.CONNECTED -> stringResource(R.string.prefs_bt_connected)
            BluetoothConnectState.DISCONNECTING -> stringResource(R.string.prefs_bt_disconnecting)
            BluetoothConnectState.DISCONNECTED -> stringResource(R.string.prefs_bt_disconnected)
            BluetoothConnectState.FAILED -> stringResource(R.string.prefs_bt_connect_failed)
        }
        if (statusText != null) {
            Text(
                text = statusText,
                color = when (state) {
                    BluetoothConnectState.CONNECTED -> MaterialTheme.colorScheme.primary
                    BluetoothConnectState.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun requiredScanPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun requiredConnectionPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyArray()
    }

private fun missingPermissions(context: Context, permissions: Array<String>): Array<String> =
    permissions.filter {
        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()

private fun hasAllPermissions(context: Context, permissions: Array<String>): Boolean =
    permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
