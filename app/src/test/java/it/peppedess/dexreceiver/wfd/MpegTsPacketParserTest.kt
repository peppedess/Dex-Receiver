package it.peppedess.dexreceiver.wfd

import org.junit.Assert.*
import org.junit.Test

class MpegTsPacketParserTest {
    @Test fun parsesPayloadPacket() {
        val bytes = ByteArray(188) { 0xff.toByte() }
        bytes[0] = 0x47
        bytes[1] = 0x41 // PUSI + PID high bits
        bytes[2] = 0x00
        bytes[3] = 0x15 // payload only, continuity counter 5
        val parsed = MpegTsPacketParser.parse(bytes)!!
        assertEquals(0x100, parsed.pid)
        assertTrue(parsed.payloadUnitStart)
        assertEquals(5, parsed.continuityCounter)
        assertTrue(parsed.hasPayload)
        assertFalse(parsed.hasAdaptationField)
        assertEquals(184, parsed.payload.size)
    }

    @Test fun parsesAdaptationAndPayload() {
        val bytes = ByteArray(188) { 0xff.toByte() }
        bytes[0] = 0x47
        bytes[1] = 0x00
        bytes[2] = 0x20
        bytes[3] = 0x30
        bytes[4] = 7
        val parsed = MpegTsPacketParser.parse(bytes)!!
        assertTrue(parsed.hasAdaptationField)
        assertTrue(parsed.hasPayload)
        assertEquals(176, parsed.payload.size)
    }

    @Test fun readsAdaptationDiscontinuityIndicator() {
        val bytes = ByteArray(188) { 0xff.toByte() }
        bytes[0] = 0x47
        bytes[1] = 0x00
        bytes[2] = 0x20
        bytes[3] = 0x30
        bytes[4] = 1
        bytes[5] = 0x80.toByte()
        assertTrue(MpegTsPacketParser.parse(bytes)!!.discontinuity)
    }

    @Test fun rejectsInvalidFramingAndSplitsAlignedPayloads() {
        assertNull(MpegTsPacketParser.parse(ByteArray(187)))
        assertNull(MpegTsPacketParser.parse(ByteArray(188)))
        val one = ByteArray(188) { 0xff.toByte() }.also {
            it[0] = 0x47; it[1] = 0; it[2] = 0; it[3] = 0x10
        }
        assertEquals(2, MpegTsPacketParser.split(one + one)!!.size)
        assertNull(MpegTsPacketParser.split(one + byteArrayOf(1)))
    }
}
