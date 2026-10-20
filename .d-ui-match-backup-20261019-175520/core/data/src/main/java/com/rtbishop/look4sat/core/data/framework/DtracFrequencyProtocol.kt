/*
 * DTrac protocol encoder for the L4S-Labs radio firmware.
 *
 * Wire format confirmed against the supplied uart.c and captured traffic:
 *   AB CD | body size LE | XOR(body + CRC16/XMODEM) | DC BA
 * Firmware commands handled by uart.c: 0x9995 mode, 0x9999 RX CTCSS,
 * 0x9998 TX CTCSS, 0x9996 TX frequency, 0x9997 RX frequency,
 * and 0x9993 monitor status.
 */
package com.rtbishop.look4sat.core.data.framework

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.abs

internal object DtracFrequencyProtocol {
    // Keep the stored marker so existing D checkbox preferences continue to work.
    private const val DTRAC_BINARY_MARKER = "__L4S_DTRAC_BINARY_V1__"
    private const val DTRAC_ASCII_LEGACY_MARKER = "__L4S_DTRAC_ASCII_V1__"

    private const val FRAME_START_0 = 0xAB
    private const val FRAME_START_1 = 0xCD
    private const val FRAME_END_0 = 0xDC
    private const val FRAME_END_1 = 0xBA

    private const val CMD_RX_CTCSS = 0x9999
    private const val CMD_TX_CTCSS = 0x9998
    private const val CMD_RX_FREQUENCY = 0x9997
    private const val CMD_TX_FREQUENCY = 0x9996
    private const val CMD_MODE = 0x9995
    private const val CMD_MONITOR = 0x9993

    private const val INTER_FRAME_GAP_MS = 25L

    private val obfuscation = intArrayOf(
        0x16, 0x6C, 0x14, 0xE6, 0x2E, 0x91, 0x0D, 0x40,
        0x21, 0x35, 0xD5, 0x40, 0x13, 0x03, 0xE9, 0x80
    )

    // Matches the common 50-entry CTCSS_Options index table used by this firmware.
    // The radio command takes an OPTION INDEX, not a tone frequency in Hz.
    private val ctcssOptionsHz = doubleArrayOf(
        67.0, 69.3, 71.9, 74.4, 77.0, 79.7, 82.5, 85.4, 88.5, 91.5,
        94.8, 97.4, 100.0, 103.5, 107.2, 110.9, 114.8, 118.8, 123.0, 127.3,
        131.8, 136.5, 141.3, 146.2, 151.4, 156.7, 159.8, 162.2, 165.5, 167.9,
        171.3, 173.8, 177.3, 179.9, 183.5, 186.2, 189.9, 192.8, 196.6, 199.5,
        203.5, 206.5, 210.7, 218.1, 225.7, 229.1, 233.6, 241.8, 250.3, 254.1
    )

    private val ownerMutexes = WeakHashMap<Any, Mutex>()

    @Synchronized
    private fun mutexFor(owner: Any): Mutex = ownerMutexes.getOrPut(owner) { Mutex() }

    /** A checked D box is stored as this marker in the existing preference field. */
    fun isDtracFrequencyTemplate(format: String): Boolean =
        format.trim().removePrefix("\\") in setOf(
            DTRAC_BINARY_MARKER,
            DTRAC_ASCII_LEGACY_MARKER
        )

    /**
     * Full sync for the supplied uart.c command IDs. The firmware has one active
     * modulation field, so RX mode takes precedence; TX mode is a fallback.
     * Tone commands carry the firmware's CTCSS_Options INDEX (0..49), or 99 to OFF.
     */
    fun encodeTransceiverCommands(
        rxFrequencyHz: Long,
        txFrequencyHz: Long?,
        txMode: String?,
        rxMode: String?,
        txCtcssToneHz: Double?,
        rxCtcssToneHz: Double?
    ): List<ByteArray> {
        val selectedMode = rxMode?.takeIf { it.isNotBlank() } ?: txMode
        val modePayload = byteArrayOf(modeChar(selectedMode).code.toByte(), 0x00)
        val rxTonePayload = u32Le(ctcssIndexOrOff(rxCtcssToneHz))
        val txTonePayload = u32Le(ctcssIndexOrOff(txCtcssToneHz))
        val txFrequencyPayload = u32Le(validFrequency(txFrequencyHz ?: 0L)) + byteArrayOf(0, 0, 0, 0)
        val rxFrequencyPayload = u32Le(validFrequency(rxFrequencyHz)) + byteArrayOf(0, 0, 0, 0)
        val monitorPayload = byteArrayOf('N'.code.toByte(), 0x00) // N = normal RX, monitor off

        // Keep the captured firmware order for TX/mode/frequencies, then apply RX tone.
        // CMD_9998 may call BK4819_ExitSubAu() for TX tone OFF; CMD_9999 follows it so
        // the firmware can reconfigure RX tone detection last without dropping TX tone.
        return listOf(
            encodeCommand(CMD_MODE, modePayload),
            encodeCommand(CMD_TX_CTCSS, txTonePayload),
            encodeCommand(CMD_TX_FREQUENCY, txFrequencyPayload),
            encodeCommand(CMD_RX_FREQUENCY, rxFrequencyPayload),
            encodeCommand(CMD_RX_CTCSS, rxTonePayload),
            encodeCommand(CMD_MONITOR, monitorPayload)
        )
    }

    /** Compatibility overload for earlier transceiver reporters with one shared tone field. */
    fun encodeTransceiverCommands(
        rxFrequencyHz: Long,
        txFrequencyHz: Long?,
        txMode: String?,
        rxMode: String?,
        ctcssToneHz: Double?
    ): ByteArray = encodeTransceiverCommands(
        rxFrequencyHz = rxFrequencyHz,
        txFrequencyHz = txFrequencyHz,
        txMode = txMode,
        rxMode = rxMode,
        txCtcssToneHz = ctcssToneHz,
        rxCtcssToneHz = ctcssToneHz
    ).fold(ByteArray(0)) { acc, frame -> acc + frame }

    /** Periodic frequency-only update uses the firmware's 0x9997 RX-frequency command. */
    fun encodeFrequencyCommand(frequencyHz: Long): ByteArray =
        encodeCommand(
            CMD_RX_FREQUENCY,
            u32Le(validFrequency(frequencyHz)) + byteArrayOf(0, 0, 0, 0)
        )

    /** Kept for compatibility with callers from earlier patch revisions. */
    fun encodeDownFrequencyCommand(frequencyHz: Long): ByteArray = encodeFrequencyCommand(frequencyHz)

    /** Kept for compatibility with earlier code; this now uses the firmware's mode command. */
    fun encodeBypassModeCommand(): ByteArray =
        encodeCommand(CMD_MODE, byteArrayOf('B'.code.toByte(), 0x00))

    private fun validFrequency(value: Long): Long =
        value.takeIf { it in 1L..0xFFFF_FFFFL } ?: 0L

    private fun modeChar(value: String?): Char {
        val mode = value?.trim()?.uppercase(Locale.ROOT)?.replace(" ", "").orEmpty()
        return when (mode) {
            "AM" -> 'A'
            "USB", "SSB" -> 'U'
            "BYP", "BYPASS", "SSTV(BY)", "SSTV(BYP)", "SSTVBY", "SSTV", "BY" -> 'B'
            "RAW" -> 'R'
            // Firmware CMD_9995 supports only F/A/U and (when enabled) B/R.
            // FM/NFM and unsupported names safely fall back to FM.
            else -> 'F'
        }
    }

    private fun ctcssIndexOrOff(toneHz: Double?): Long {
        if (toneHz == null || !toneHz.isFinite()) return 99L
        val index = ctcssOptionsHz.indexOfFirst { abs(it - toneHz) < 0.06 }
        return if (index >= 0) index.toLong() else 99L
    }

    private fun u32Le(value: Long): ByteArray = ByteArray(4) { i ->
        ((value ushr (i * 8)) and 0xFF).toByte()
    }

    /** Builds one AB CD command frame matching UART_IsCommandAvailable() in uart.c. */
    private fun encodeCommand(commandId: Int, payload: ByteArray): ByteArray {
        val bodySize = 4 + payload.size // CMD header (ID + Size) and payload; CRC is outside Size
        val body = ByteArray(bodySize)
        putU16Le(body, 0, commandId)
        putU16Le(body, 2, payload.size)
        payload.copyInto(body, destinationOffset = 4)

        val crc = crc16Xmodem(body)
        val encryptedBodyAndCrc = ByteArray(body.size + 2)
        body.copyInto(encryptedBodyAndCrc)
        encryptedBodyAndCrc[body.size] = (crc and 0xFF).toByte()
        encryptedBodyAndCrc[body.size + 1] = ((crc ushr 8) and 0xFF).toByte()
        for (i in encryptedBodyAndCrc.indices) {
            encryptedBodyAndCrc[i] = (
                (encryptedBodyAndCrc[i].toInt() and 0xFF) xor obfuscation[i % obfuscation.size]
            ).toByte()
        }

        return ByteArray(bodySize + 8).also { frame ->
            frame[0] = FRAME_START_0.toByte()
            frame[1] = FRAME_START_1.toByte()
            putU16Le(frame, 2, bodySize)
            encryptedBodyAndCrc.copyInto(frame, destinationOffset = 4)
            frame[frame.size - 2] = FRAME_END_0.toByte()
            frame[frame.size - 1] = FRAME_END_1.toByte()
        }
    }

    private fun putU16Le(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun crc16Xmodem(data: ByteArray): Int {
        var crc = 0
        for (value in data) {
            crc = crc xor ((value.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc
    }

    /** Replace all placeholders in the ASCII template when D is unchecked. */
    fun formatAsciiTemplate(
        template: String,
        rxFrequencyHz: Long,
        txFrequencyHz: Long?,
        txMode: String?,
        rxMode: String?,
        txCtcssToneHz: Double?,
        rxCtcssToneHz: Double?
    ): String {
        val modeValue = (rxMode?.takeIf { it.isNotBlank() } ?: txMode?.takeIf { it.isNotBlank() }) ?: "FM"
        val rendered = template
            .replace("\$FREQ_T", txFrequencyHz?.takeIf { it > 0L }?.toString() ?: "0")
            .replace("\$CTCSS_T", formatTone(txCtcssToneHz))
            .replace("\$CTCSS_R", formatTone(rxCtcssToneHz))
            .replace("\$MODE", modeValue)
            .replace("\$FREQ", rxFrequencyHz.coerceAtLeast(0L).toString())
            .replace("\\r", "\r")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\n", "\r\n")
        return if (rendered.isEmpty() || rendered.endsWith("\r\n")) rendered else "$rendered\r\n"
    }

    /** Compatibility overload for earlier callers with one shared CTCSS field. */
    fun formatAsciiTemplate(
        template: String,
        rxFrequencyHz: Long,
        txFrequencyHz: Long?,
        txMode: String?,
        rxMode: String?,
        ctcssToneHz: Double?
    ): String = formatAsciiTemplate(
        template = template,
        rxFrequencyHz = rxFrequencyHz,
        txFrequencyHz = txFrequencyHz,
        txMode = txMode,
        rxMode = rxMode,
        txCtcssToneHz = ctcssToneHz,
        rxCtcssToneHz = ctcssToneHz
    )

    private fun formatTone(toneHz: Double?): String =
        toneHz?.takeIf { it.isFinite() && it in 50.0..300.0 }
            ?.let { String.format(Locale.ROOT, "%.1f", it) }
            ?: "0"

    suspend fun sendPayload(
        owner: Any,
        active: Boolean,
        payload: ByteArray,
        sendBytes: suspend (ByteArray) -> Unit
    ) = sendFrames(owner, active, listOf(payload), sendBytes)

    suspend fun sendFrames(
        owner: Any,
        active: Boolean,
        frames: List<ByteArray>,
        sendBytes: suspend (ByteArray) -> Unit
    ) {
        val mutex = mutexFor(owner)
        mutex.withLock {
            if (!active) return
            frames.forEachIndexed { index, frame ->
                if (frame.isNotEmpty()) sendBytes(frame)
                if (index < frames.lastIndex) delay(INTER_FRAME_GAP_MS)
            }
        }
    }

    /** Backwards-compatible bridge for the older frequency reporter signature. */
    suspend fun sendFrequencyOutput(
        owner: Any,
        active: Boolean,
        useDtrac: Boolean,
        frequencyHz: Long,
        asciiPayload: ByteArray?,
        sendFrame: suspend (ByteArray) -> Boolean
    ) {
        val payload = if (useDtrac) encodeFrequencyCommand(frequencyHz) else asciiPayload ?: return
        sendFrames(owner, active, listOf(payload)) { bytes -> sendFrame(bytes); Unit }
    }
}
