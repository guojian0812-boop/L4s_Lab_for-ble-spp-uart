/*
 * DTrac protocol encoder and paced frequency output for L4S-Labs.
 * Each frequency-report tick sends CMD_6666/BYP, waits 200 ms, then CMD_8888.
 * Frame bytes and CRC are matched against captured DTrac APP traffic.
 */
package com.rtbishop.look4sat.core.data.framework

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.util.WeakHashMap

internal object DtracFrequencyProtocol {
    private const val DTRAC_PROTOCOL_MARKER = "__L4S_DTRAC_BINARY_V1__"
    private const val BYP_TO_FREQUENCY_GAP_MS = 200L
    private val obfuscation = intArrayOf(
        0x16, 0x6C, 0x14, 0xE6, 0x2E, 0x91, 0x0D, 0x40,
        0x21, 0x35, 0xD5, 0x40, 0x13, 0x03, 0xE9, 0x80
    )

    private val ownerMutexes = WeakHashMap<Any, Mutex>()

    @Synchronized
    private fun mutexFor(owner: Any): Mutex =
        ownerMutexes.getOrPut(owner) { Mutex() }

    /** The settings UI selects DTrac by storing this marker in the format field. */
    fun isDtracFrequencyTemplate(format: String): Boolean =
        format.trim() == DTRAC_PROTOCOL_MARKER

    /**
     * Called by the existing once-per-second frequency reporter.
     * In DTrac mode every call sends one BYP frame, waits 200 ms, then sends one
     * frequency frame, even when the current frequency has not changed. A mutex
     * keeps the two frames together and prevents other reporter calls interleaving.
     * Non-DTrac mode continues to send the existing ASCII template unchanged.
     */
    suspend fun sendFrequencyOutput(
        owner: Any,
        active: Boolean,
        useDtrac: Boolean,
        frequencyHz: Long,
        asciiPayload: ByteArray?,
        sendFrame: suspend (ByteArray) -> Boolean
    ) {
        val mutex = mutexFor(owner)
        mutex.lock()
        try {
            if (!active) return

            if (!useDtrac) {
                val payload = asciiPayload ?: return
                sendFrame(payload)
                return
            }

            val frequencyFrame = encodeDownFrequencyCommand(frequencyHz)
                ?: throw IllegalArgumentException(
                    "DTrac frequency is outside uint32 Hz range: $frequencyHz"
                )
            val bypassFrame = encodeBypassModeCommand()

            // Every update: BYP first, then frequency after a 200 ms gap.
            if (!sendFrame(bypassFrame)) return
            delay(BYP_TO_FREQUENCY_GAP_MS)
            sendFrame(frequencyFrame)
        } catch (e: CancellationException) {
            throw e
        } finally {
            mutex.unlock()
        }
    }

    /** Captured CMD_6666: inner payload length=2, 'B' followed by zero padding. */
    fun encodeBypassModeCommand(): ByteArray =
        encodeCommandBody(
            byteArrayOf(0x66, 0x66, 0x02, 0x00, 'B'.code.toByte(), 0x00)
        )

    /** Captured CMD_8888: 8-byte payload, uint32 LE Hz followed by four zero bytes. */
    fun encodeDownFrequencyCommand(frequencyHz: Long): ByteArray? {
        if (frequencyHz <= 0L || frequencyHz > 0xFFFF_FFFFL) return null
        val body = ByteArray(12)
        putU16Le(body, 0, 0x8888)
        putU16Le(body, 2, 8)
        putU32Le(body, 4, frequencyHz)
        // body[8..11] remain zero to match the DTrac APP capture.
        return encodeCommandBody(body)
    }

    /**
     * Outer frame: AB CD | body-size LE | XOR(body + CRC LE) | DC BA.
     * CRC: CRC-16/XMODEM, poly=0x1021, init=0, refin/refout=false, xorout=0.
     */
    private fun encodeCommandBody(body: ByteArray): ByteArray {
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

        return ByteArray(body.size + 8).also { frame ->
            frame[0] = 0xAB.toByte()
            frame[1] = 0xCD.toByte()
            frame[2] = (body.size and 0xFF).toByte()
            frame[3] = ((body.size ushr 8) and 0xFF).toByte()
            encryptedBodyAndCrc.copyInto(frame, destinationOffset = 4)
            frame[frame.size - 2] = 0xDC.toByte()
            frame[frame.size - 1] = 0xBA.toByte()
        }
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

    private fun putU16Le(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putU32Le(dst: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) {
            dst[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
    }
}
