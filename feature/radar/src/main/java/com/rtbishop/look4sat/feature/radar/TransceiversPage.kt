/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.feature.radar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.utility.DopplerFrequencyCalculator
import com.rtbishop.look4sat.core.domain.utility.TransponderMapper
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.infiniteMarquee
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun TransceiversPage(
    transceivers: List<SatRadio>,
    selectedUuid: String?,
    radioControl: RadioControlSubState,
    orbitalPos: OrbitalPos?,
    onAction: (RadarAction) -> Unit,
    modifier: Modifier = Modifier
) {
    if (transceivers.isEmpty()) {
        EmptyTransceiversContent(modifier)
    } else {
        val listState = rememberLazyListState()
        // Snap the expanded item to the top of the visible area
        LaunchedEffect(selectedUuid) {
            if (selectedUuid != null) {
                val index = transceivers.indexOfFirst { it.uuid == selectedUuid }
                if (index >= 0) {
                    kotlinx.coroutines.delay(300.milliseconds)
                    listState.animateScrollToItem(index)
                }
            }
        }
        LazyColumn(modifier = modifier.fillMaxSize(), state = listState) {
            itemsIndexed(items = transceivers, key = { _, radio -> radio.uuid }) { _, radio ->
                val isExpanded = radio.uuid == selectedUuid
                TransceiverItem(
                    radio = radio,
                    isExpanded = isExpanded,
                    radioControl = radioControl,
                    orbitalPos = orbitalPos,
                    onAction = onAction,
                    onToggle = { onAction(RadarAction.SelectTransmitter(radio.uuid)) }
                )
            }
        }
    }
}

@Composable
fun CalculatorPage(
    transceivers: List<SatRadio>,
    selectedUuid: String?,
    orbitalPos: OrbitalPos?,
    onAction: (RadarAction) -> Unit,
    calculatorOffsetKHz: String = "",
    modifier: Modifier = Modifier
) {
    val calculatorTransceivers = remember(transceivers) {
        transceivers.filter(DopplerFrequencyCalculator::isNamedLinearTransponder)
            .let(DopplerFrequencyCalculator::deduplicateTransponders)
    }
    val selectedTransceiver = remember(calculatorTransceivers, selectedUuid) {
        calculatorTransceivers.firstOrNull { it.uuid == selectedUuid }
            ?: calculatorTransceivers.firstOrNull()
    }

    // Keep the per-satellite offset in sync with the transponder driving the
    // calculator: load the saved value on open/satellite change, and remember
    // its catnum so editing saves even when no transceiver is selected
    LaunchedEffect(selectedTransceiver?.uuid) {
        onAction(RadarAction.CalculatorTransponderChanged(selectedTransceiver?.catnum))
    }

    if (selectedTransceiver == null) {
        EmptyTransceiversContent(modifier)
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (calculatorTransceivers.size > 1) {
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    calculatorTransceivers.forEach { radio ->
                        FilterChip(
                            selected = radio.uuid == selectedTransceiver.uuid,
                            onClick = {
                                if (radio.uuid != selectedUuid) onAction(RadarAction.SelectTransmitter(radio.uuid))
                            },
                            label = {
                                Text(
                                    text = transceiverTitle(radio),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }
        }

        item {
            DopplerFrequencyCalculator(
                transponder = selectedTransceiver,
                orbitalPos = orbitalPos,
                offsetKHz = calculatorOffsetKHz,
                onOffsetChange = { onAction(RadarAction.ChangeCalculatorOffset(it)) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun EmptyTransceiversContent(modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(text = """¯\_(ツ)_/¯""", fontSize = 32.sp)
            Text(
                text = stringResource(R.string.empty_list_message),
                fontSize = 21.sp,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.radar_no_data),
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun TransceiverItem(
    radio: SatRadio,
    isExpanded: Boolean,
    radioControl: RadioControlSubState,
    orbitalPos: OrbitalPos?,
    onAction: (RadarAction) -> Unit,
    onToggle: () -> Unit
) {
    val bgColor = if (isExpanded) MaterialTheme.colorScheme.surfaceContainerHighest
    else MaterialTheme.colorScheme.surface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onToggle() }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // Header: [arrow slot] [name - (mode)] [icon slot]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Left icon slot — always reserved
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(if (isExpanded) 270f else 90f)
                    )
                }
                Text(
                    text = transceiverTitle(radio),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .infiniteMarquee()
                )
                // Right arrow slot — always reserved
                val iconTint = if (isExpanded) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_radios),
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Collapsed rows show the transponder band edges. The expanded row
            // shows the editable nominal base frequency and the live Doppler-corrected value.
            val radioStateMatches = isExpanded && radioControl.selectedTransponderUuid == radio.uuid
            UnifiedFrequencyRow(
                label = "TX",
                frequencyLow = if (radioStateMatches) radioControl.txBaseFrequencyHz ?: radio.uplinkLow else radio.uplinkLow,
                frequencyHigh = if (radioStateMatches) {
                    radioControl.txBaseFrequencyHz?.takeIf { it > 0L }?.let { base ->
                        orbitalPos?.getUplinkFreq(base)
                    } ?: radioControl.txPanel.frequencyHz ?: radio.uplinkHigh
                } else radio.uplinkHigh,
                showBaseAndDoppler = radioStateMatches,
                isConnected = if (isExpanded) radioControl.txPanel.isConnected else null
            )
            UnifiedFrequencyRow(
                label = "RX",
                frequencyLow = if (radioStateMatches) radioControl.rxBaseFrequencyHz ?: radio.downlinkLow else radio.downlinkLow,
                frequencyHigh = if (radioStateMatches) {
                    radioControl.rxBaseFrequencyHz?.takeIf { it > 0L }?.let { base ->
                        orbitalPos?.getDownlinkFreq(base)
                    } ?: radioControl.rxPanel.frequencyHz ?: radio.downlinkHigh
                } else radio.downlinkHigh,
                showBaseAndDoppler = radioStateMatches,
                isConnected = if (isExpanded) radioControl.rxPanel.isConnected else null
            )
        }

        // Expanded CAT control area
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            ExpandedRadioControl(
                radio = radio,
                radioControl = radioControl,
                onAction = onAction
            )
        }

        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.background)
    }
}

@Composable
private fun UnifiedFrequencyRow(
    label: String,
    frequencyLow: Long?,
    frequencyHigh: Long?,
    showBaseAndDoppler: Boolean = false,
    isConnected: Boolean?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // TX:/RX: label
        Text(
            text = "$label:",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp)
        )
        // First frequency: nominal base in expanded mode, band-low when collapsed.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
        ) {
            if (showBaseAndDoppler) {
                Text("基频", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FrequencyText(frequency = frequencyLow, modifier = Modifier.fillMaxWidth())
        }
        // Dash — always centered
        Text(
            text = "–",
            textAlign = TextAlign.Center,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(16.dp)
        )
        // Second frequency: live Doppler-corrected frequency when expanded.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
        ) {
            if (showBaseAndDoppler) {
                Text("多普勒", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FrequencyText(frequency = frequencyHigh, modifier = Modifier.fillMaxWidth())
        }
        // Connection dot on the right
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.width(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when (isConnected) {
                            true -> Color(0xFF4CAF50)
                            false -> Color(0xFFE57373)
                            null -> MaterialTheme.colorScheme.outlineVariant
                        }
                    )
            )
        }
    }
}

@Composable
private fun ExpandedRadioControl(
    radio: SatRadio,
    radioControl: RadioControlSubState,
    onAction: (RadarAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )

        // Editable nominal TX/RX base frequencies, in MHz. Applying both together
        // preserves the exact pair entered by the user instead of rounding one through
        // the transponder passband mapper.
        val initialTxBase = radioControl.txBaseFrequencyHz ?: radio.uplinkLow ?: 0L
        val initialRxBase = radioControl.rxBaseFrequencyHz
            ?: radio.uplinkLow?.let { TransponderMapper.mapUplinkToDownlink(it, radio) }
            ?: radio.downlinkLow ?: 0L
        var txBaseDraft by remember(radio.uuid) { mutableStateOf(formatMHzInput(initialTxBase)) }
        var rxBaseDraft by remember(radio.uuid) { mutableStateOf(formatMHzInput(initialRxBase)) }
        LaunchedEffect(radio.uuid, radioControl.txBaseFrequencyHz) {
            radioControl.txBaseFrequencyHz?.let { txBaseDraft = formatMHzInput(it) }
        }
        LaunchedEffect(radio.uuid, radioControl.rxBaseFrequencyHz) {
            radioControl.rxBaseFrequencyHz?.let { rxBaseDraft = formatMHzInput(it) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "基频设置（MHz）",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = txBaseDraft,
                    onValueChange = { txBaseDraft = it },
                    label = { Text("TX 基频") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = rxBaseDraft,
                    onValueChange = { rxBaseDraft = it },
                    label = { Text("RX 基频") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
            val parsedTxBase = parseMHzInput(txBaseDraft)
            val parsedRxBase = parseMHzInput(rxBaseDraft)
            OutlinedButton(
                onClick = {
                    val txHz = parsedTxBase ?: 0
                    val rxHz = parsedRxBase ?: return@OutlinedButton
                    onAction(RadarAction.SetBaseFrequencies(txHz, rxHz))
                },
                enabled = parsedRxBase != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("应用 TX/RX 基频") }
        }

        // Editable TX/RX modes. The choices are the intersection supported by both
        // controller backends in this project (FT-817 CAT and IC-705 CI-V).
        val currentTxMode = radioControl.txPanel.mode ?: radio.uplinkMode ?: "FM"
        val currentRxMode = radioControl.rxPanel.mode ?: radio.downlinkMode
            ?: radio.uplinkMode?.let { TransponderMapper.mapUplinkModeToDownlinkMode(it, radio.isInverted) }
            ?: "FM"
        RadioModeEditor(
            title = "发送模式",
            selectedMode = currentTxMode,
            modes = EDITABLE_RADIO_MODES,
            onModeSelected = { onAction(RadarAction.SetRadioModes(it, currentRxMode)) }
        )
        RadioModeEditor(
            title = "接收模式",
            selectedMode = currentRxMode,
            modes = EDITABLE_RX_RADIO_MODES,
            onModeSelected = { onAction(RadarAction.SetRadioModes(currentTxMode, it)) }
        )

        CtcssToneEditor(
            title = "发送亚音 TX CTCSS（TC，Hz，关闭发送 0）",
            selectedToneHz = radioControl.ctcssTone,
            onApply = { onAction(RadarAction.SetCtcssTone(it)) }
        )
        CtcssToneEditor(
            title = "接收亚音 RX CTCSS（RC，Hz，关闭发送 0）",
            selectedToneHz = radioControl.rxCtcssTone,
            onApply = { onAction(RadarAction.SetRxCtcssTone(it)) }
        )

        // Connection and tracking buttons are intentionally hidden in this screen.
        // Radio output/connection is configured from Data Output settings.

        // Error
        radioControl.errorMessage?.let { msg ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text(text = msg, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            }
        }
    }
}


private val EDITABLE_RADIO_MODES = listOf("FM", "USB", "LSB", "CW", "CW-R", "AM")
private val EDITABLE_RX_RADIO_MODES = EDITABLE_RADIO_MODES + "SSTV(BY)"

@Composable
private fun RadioModeEditor(
    title: String,
    selectedMode: String,
    modes: List<String> = EDITABLE_RADIO_MODES,
    onModeSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            modes.forEach { mode ->
                FilterChip(
                    selected = selectedMode.equals(mode, ignoreCase = true),
                    onClick = { onModeSelected(mode) },
                    label = { Text(mode, fontSize = 12.sp) }
                )
            }
        }
    }
}

private fun formatMHzInput(frequencyHz: Long): String =
    if (frequencyHz <= 0L) "" else String.format(Locale.ENGLISH, "%.6f", frequencyHz / 1_000_000.0)

private fun parseMHzInput(value: String): Long? {
    val mhz = value.trim().replace(',', '.').toDoubleOrNull() ?: return null
    if (!mhz.isFinite() || mhz <= 0.0 || mhz > 10_000.0) return null
    return (mhz * 1_000_000.0).roundToLong()
}

private enum class EditedField { TX, PASSBAND, RX }

@Composable
private fun DopplerFrequencyCalculator(
    transponder: SatRadio,
    orbitalPos: OrbitalPos?,
    offsetKHz: String = "",
    onOffsetChange: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (orbitalPos == null || !DopplerFrequencyCalculator.isLinearTransponder(transponder)) return

    var lastEditedField by remember { mutableStateOf(EditedField.TX) }
    var txFrequencyHz by remember { mutableStateOf(0L) }
    var rxFrequencyHz by remember { mutableStateOf(0L) }
    var passbandPosition by remember { mutableStateOf(0.5f) }
    var stepSizeKHz by remember { mutableIntStateOf(1) }

    val offsetHz = offsetKHz.toDoubleOrNull()?.let { it * 1000 }?.toLong() ?: 0L

    val txLow = transponder.uplinkLow ?: return
    val txHigh = transponder.uplinkHigh ?: return
    val rxLow = transponder.downlinkLow ?: return
    val rxHigh = transponder.downlinkHigh ?: return
    val txRange = txHigh - txLow
    val rxRange = rxHigh - rxLow
    if (txRange <= 0L || rxRange <= 0L) return

    // 原始转发器值（逆转多普勒），用于 passband 映射计算
    val rawTxLow = orbitalPos.getDownlinkFreq(txLow)
    val rawTxHigh = orbitalPos.getDownlinkFreq(txHigh)
    val rawRxLow = orbitalPos.getUplinkFreq(rxLow)
    val rawRxHigh = orbitalPos.getUplinkFreq(rxHigh)
    val rawTxRange = rawTxHigh - rawTxLow
    val rawRxRange = rawRxHigh - rawRxLow
    val rawTransponder = transponder.copy(
        uplinkLow = rawTxLow, uplinkHigh = rawTxHigh,
        downlinkLow = rawRxLow, downlinkHigh = rawRxHigh
    )

    // 初始化
    LaunchedEffect(Unit) {
        if (txFrequencyHz == 0L) {
            txFrequencyHz = txLow + txRange / 2
            rxFrequencyHz = DopplerFrequencyCalculator.computeDownlinkFromUplinkWithOffset(
                txFrequencyHz, rawTransponder, orbitalPos, offsetHz
            ) ?: (rxLow + rxRange / 2)
        }
    }

    // 实时重算：卫星移动时，锚点侧不变，重算另一侧
    LaunchedEffect(orbitalPos) {
        if (lastEditedField == EditedField.TX) {
            // TX 是锚点，不变；重算 RX
            rxFrequencyHz = DopplerFrequencyCalculator.computeDownlinkFromUplinkWithOffset(
                txFrequencyHz, rawTransponder, orbitalPos, offsetHz
            ) ?: rxFrequencyHz
        } else if (lastEditedField == EditedField.RX) {
            // RX 是锚点，不变；重算 TX
            txFrequencyHz = DopplerFrequencyCalculator.computeUplinkFromDownlinkWithOffset(
                rxFrequencyHz, rawTransponder, orbitalPos, offsetHz
            ) ?: txFrequencyHz
        } else if (lastEditedField == EditedField.PASSBAND) {
            // 相对位置不变（passbandPosition），TX 用地面频率，RX 经 Transceiver 完整路径计算
            txFrequencyHz = txLow + (passbandPosition * txRange).toLong()
            rxFrequencyHz = DopplerFrequencyCalculator.computeDownlinkFromUplinkWithOffset(
                txFrequencyHz, rawTransponder, orbitalPos, offsetHz
            ) ?: rxFrequencyHz
        }
    }

    val txSliderValue = if (lastEditedField == EditedField.PASSBAND) {
        passbandPosition
    } else {
        ((txFrequencyHz - txLow).toFloat() / txRange).coerceIn(0f, 1f)
    }
    val rxSliderValue = if (lastEditedField == EditedField.PASSBAND) {
        ((rxFrequencyHz - rxLow).toFloat() / rxRange).coerceIn(0f, 1f)
    } else {
        ((rxFrequencyHz - rxLow).toFloat() / rxRange).coerceIn(0f, 1f)
    }

    fun updateTx(newTxHz: Long) {
        when (lastEditedField) {
            EditedField.PASSBAND -> {
                // Passband 模式：滑块位置 → passbandPosition → 地面频率，RX 经 Transceiver 计算
                val pos = ((newTxHz - txLow).toFloat() / txRange).coerceIn(0f, 1f)
                passbandPosition = pos
                txFrequencyHz = txLow + (pos * txRange).toLong()
                rxFrequencyHz = DopplerFrequencyCalculator.computeDownlinkFromUplinkWithOffset(
                    txFrequencyHz, rawTransponder, orbitalPos, offsetHz
                ) ?: rxFrequencyHz
            }
            else -> {
                txFrequencyHz = newTxHz.coerceIn(txLow, txHigh)
                rxFrequencyHz = DopplerFrequencyCalculator.computeDownlinkFromUplinkWithOffset(
                    txFrequencyHz, rawTransponder, orbitalPos, offsetHz
                ) ?: rxFrequencyHz
            }
        }
    }

    fun updateRx(newRxHz: Long) {
        when (lastEditedField) {
            EditedField.PASSBAND -> {
                // Passband 模式：从 RX 反推 TX 位置，TX 用地面频率
                val pos = ((newRxHz - rxLow).toFloat() / rxRange).coerceIn(0f, 1f)
                val txFromRx = TransponderMapper.mapDownlinkToUplink(newRxHz, rawTransponder)
                passbandPosition = if (txFromRx != null) {
                    ((txFromRx - rawTxLow).toFloat() / rawTxRange).coerceIn(0f, 1f)
                } else pos
                txFrequencyHz = txLow + (passbandPosition * txRange).toLong()
                rxFrequencyHz = DopplerFrequencyCalculator.computeDownlinkFromUplinkWithOffset(
                    txFrequencyHz, rawTransponder, orbitalPos, offsetHz
                ) ?: rxFrequencyHz
            }
            else -> {
                rxFrequencyHz = newRxHz.coerceIn(rxLow, rxHigh)
                txFrequencyHz = DopplerFrequencyCalculator.computeUplinkFromDownlinkWithOffset(
                    rxFrequencyHz, rawTransponder, orbitalPos, offsetHz
                ) ?: txFrequencyHz
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 左半边: TX + RX 按钮
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = lastEditedField == EditedField.TX,
                        onClick = {
                            when (lastEditedField) {
                                EditedField.TX -> {
                                    passbandPosition = ((txFrequencyHz - txLow).toFloat() / txRange).coerceIn(0f, 1f)
                                    lastEditedField = EditedField.PASSBAND
                                    rxFrequencyHz = DopplerFrequencyCalculator.computeDownlinkFromUplinkWithOffset(
                                        txFrequencyHz, rawTransponder, orbitalPos, offsetHz
                                    ) ?: rxFrequencyHz
                                }
                                EditedField.PASSBAND -> { lastEditedField = EditedField.TX }
                                else -> { lastEditedField = EditedField.TX }
                            }
                        },
                        label = {
                            Text("TX", fontSize = 13.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth())
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    FilterChip(
                        selected = lastEditedField == EditedField.RX,
                        onClick = {
                            when (lastEditedField) {
                                EditedField.RX -> {
                                    txFrequencyHz = DopplerFrequencyCalculator.computeUplinkFromDownlinkWithOffset(
                                        rxFrequencyHz, rawTransponder, orbitalPos, offsetHz
                                    ) ?: txFrequencyHz
                                    passbandPosition = ((txFrequencyHz - txLow).toFloat() / txRange).coerceIn(0f, 1f)
                                    lastEditedField = EditedField.PASSBAND
                                }
                                EditedField.PASSBAND -> {
                                    lastEditedField = EditedField.RX
                                    txFrequencyHz = DopplerFrequencyCalculator.computeUplinkFromDownlinkWithOffset(
                                        rxFrequencyHz, rawTransponder, orbitalPos, offsetHz
                                    ) ?: txFrequencyHz
                                }
                                else -> { lastEditedField = EditedField.RX }
                            }
                        },
                        label = {
                            Text("RX", fontSize = 13.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth())
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
                // 右半边: Offset 输入框（带细线边框）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center
                ) {
                    BasicTextField(
                        value = offsetKHz,
                        onValueChange = onOffsetChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        decorationBox = { innerTextField ->
                            if (offsetKHz.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.radar_doppler_offset_hint),
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            }

            // TX 行
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { updateTx(txFrequencyHz - stepSizeKHz * 1000L) },
                        modifier = Modifier.width(32.dp).height(28.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = MaterialTheme.shapes.extraSmall
                    ) { Text("−", fontSize = 14.sp) }

                    Slider(
                        value = txSliderValue,
                        onValueChange = { norm ->
                            val rawFreq = txLow + (txRange * norm).toLong()
                            val snapped = ((rawFreq + 500) / 1000L) * 1000L
                            updateTx(snapped.coerceIn(txLow, txHigh))
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).height(20.dp).padding(horizontal = 4.dp)
                    )

                    OutlinedButton(
                        onClick = { updateTx(txFrequencyHz + stepSizeKHz * 1000L) },
                        modifier = Modifier.width(32.dp).height(28.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = MaterialTheme.shapes.extraSmall
                    ) { Text("+", fontSize = 14.sp) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = String.format(Locale.ENGLISH, "%.4f", txLow / 1_000_000.0),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = String.format(Locale.ENGLISH, "%.6f", txFrequencyHz / 1_000_000.0),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = " MHz",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = String.format(Locale.ENGLISH, "%.4f", txHigh / 1_000_000.0),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End
                    )
                }
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )

            // RX 行
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { updateRx(rxFrequencyHz - stepSizeKHz * 1000L) },
                        modifier = Modifier.width(32.dp).height(28.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = MaterialTheme.shapes.extraSmall
                    ) { Text("−", fontSize = 14.sp) }

                    Slider(
                        value = rxSliderValue,
                        onValueChange = { norm ->
                            val rawFreq = rxLow + (rxRange * norm).toLong()
                            val snapped = ((rawFreq + 500) / 1000L) * 1000L
                            updateRx(snapped.coerceIn(rxLow, rxHigh))
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).height(20.dp).padding(horizontal = 4.dp)
                    )

                    OutlinedButton(
                        onClick = { updateRx(rxFrequencyHz + stepSizeKHz * 1000L) },
                        modifier = Modifier.width(32.dp).height(28.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = MaterialTheme.shapes.extraSmall
                    ) { Text("+", fontSize = 14.sp) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = String.format(Locale.ENGLISH, "%.4f", rxLow / 1_000_000.0),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = String.format(Locale.ENGLISH, "%.6f", rxFrequencyHz / 1_000_000.0),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = " MHz",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = String.format(Locale.ENGLISH, "%.4f", rxHigh / 1_000_000.0),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End
                    )
                }
            }
        }
}

@Composable
private fun FrequencyText(frequency: Long?, modifier: Modifier = Modifier) {
    val text = frequency?.let {
        stringResource(id = R.string.radar_link_low, it / 1000000f)
    } ?: stringResource(R.string.radar_no_link)
    Text(
        text = text,
        textAlign = TextAlign.Center,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}

@Composable
private fun CtcssToneEditor(
    title: String,
    selectedToneHz: Double?,
    onApply: (Double?) -> Unit
) {
    var draft by remember { mutableStateOf(selectedToneHz?.let { String.format(Locale.ENGLISH, "%.1f", it) } ?: "") }
    LaunchedEffect(selectedToneHz) {
        draft = selectedToneHz?.let { String.format(Locale.ENGLISH, "%.1f", it) } ?: ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("例如 67.0") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            val parsedTone = draft.trim().replace(',', '.').toDoubleOrNull()
            val toneValid = draft.isBlank() || parsedTone == 0.0 ||
                (parsedTone != null && parsedTone.isFinite() && parsedTone in 50.0..300.0)
            OutlinedButton(
                onClick = { onApply(parsedTone?.takeIf { it > 0.0 }) },
                enabled = toneValid
            ) { Text("应用") }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            maxItemsInEachRow = 5,
            modifier = Modifier.fillMaxWidth()
        ) {
            val chipModifier = Modifier.weight(1f)
            FilterChip(
                selected = selectedToneHz == null,
                onClick = { onApply(null) },
                label = {
                    Text(
                        text = stringResource(R.string.radar_off),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                modifier = chipModifier
            )
            RadarViewModel.CTCSS_TONES.forEach { tone ->
                FilterChip(
                    selected = selectedToneHz == tone,
                    onClick = { onApply(tone) },
                    label = {
                        Text(
                            text = String.format(Locale.ENGLISH, "%.1f", tone),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    modifier = chipModifier
                )
            }
        }
    }
}

private fun transceiverTitle(radio: SatRadio): String {
    val title = if (radio.isInverted) "INV: ${radio.info}" else radio.info
    val mode = "${radio.downlinkMode ?: "--"}/${radio.uplinkMode ?: "--"}"
    return "$title ($mode)"
}
