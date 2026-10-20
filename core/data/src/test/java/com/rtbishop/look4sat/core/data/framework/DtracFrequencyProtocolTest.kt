package com.rtbishop.look4sat.core.data.framework

import org.junit.Assert.assertEquals
import org.junit.Test

class DtracFrequencyProtocolTest {
    @Test
    fun matchesCapturedAbCd999xFrames() {
        val frames = DtracFrequencyProtocol.encodeTransceiverCommands(
            rxFrequencyHz = 437_794_476L,
            txFrequencyHz = 145_991_841L,
            txMode = "FM",
            rxMode = "FM",
            txCtcssToneHz = 67.0,
            rxCtcssToneHz = null
        )

        // Sequence mirrors captured traffic: mode, TX tone, TX frequency, RX frequency,
        // RX tone, then leave monitor mode. Every frame uses the firmware's AB CD wrapper.
        assertEquals("AB CD 06 00 83 F5 16 E6 68 91 5C 09 DC BA", frames[0].hex())
        assertEquals("AB CD 08 00 8E F5 10 E6 2E 91 0D 40 21 5A DC BA", frames[1].hex())
        assertEquals("AB CD 0C 00 80 F5 1C E6 8F 39 BE 48 21 35 D5 40 3E D9 DC BA", frames[2].hex())
        assertEquals("AB CD 0C 00 81 F5 1C E6 82 A7 15 5A 21 35 D5 40 76 40 DC BA", frames[3].hex())
        assertEquals("AB CD 08 00 8F F5 10 E6 4D 91 0D 40 FC DF DC BA", frames[4].hex())
        assertEquals("AB CD 06 00 85 F5 16 E6 60 91 14 0D DC BA", frames[5].hex())
    }

    @Test
    fun frequencyOnlyUpdateUsesCmd9997() {
        assertEquals(
            "AB CD 0C 00 81 F5 1C E6 82 A7 15 5A 21 35 D5 40 76 40 DC BA",
            DtracFrequencyProtocol.encodeFrequencyCommand(437_794_476L).hex()
        )
    }

    @Test
    fun asciiTemplateStillExpandsPlaceholdersWhenDIsUnchecked() {
        val rendered = DtracFrequencyProtocol.formatAsciiTemplate(
            template = "F \$FREQ\nM \$MODE\nT \$FREQ_T\nTC \$CTCSS_T\nRC \$CTCSS_R",
            rxFrequencyHz = 437_794_476L,
            txFrequencyHz = 145_991_841L,
            txMode = "FM",
            rxMode = "FM",
            txCtcssToneHz = 67.0,
            rxCtcssToneHz = null
        )
        assertEquals(
            "F 437794476\r\nM FM\r\nT 145991841\r\nTC 67.0\r\nRC 0\r\n",
            rendered
        )
    }

    private fun ByteArray.hex(): String = joinToString(" ") {
        "%02X".format(it.toInt() and 0xFF)
    }
}
