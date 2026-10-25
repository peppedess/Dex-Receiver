package it.peppedess.dexreceiver.wfd

import org.junit.Assert.*
import org.junit.Test

class RtpPacketParserTest {
    @Test fun parsesBasicRtpHeaderAndPayload() {
        val packet = byteArrayOf(
            0x80.toByte(), 0xE0.toByte(), 0x12, 0x34,
            0x01, 0x02, 0x03, 0x04,
            0x11, 0x22, 0x33, 0x44,
            0x55, 0x66
        )
        val parsed = RtpPacketParser.parse(packet)!!
        assertTrue(parsed.marker)
        assertEquals(96, parsed.payloadType)
        assertEquals(0x1234, parsed.sequenceNumber)
        assertEquals(0x01020304L, parsed.timestamp)
        assertEquals(0x11223344L, parsed.ssrc)
        assertArrayEquals(byteArrayOf(0x55, 0x66), parsed.payload)
    }

    @Test fun skipsCsrcAndHeaderExtension() {
        val packet = byteArrayOf(
            0x91.toByte(), 0x60, 0, 1, 0, 0, 0, 2, 0, 0, 0, 3,
            0, 0, 0, 4, // one CSRC
            0xBE.toByte(), 0xDE.toByte(), 0, 1, 1, 2, 3, 4, // extension
            9, 8
        )
        assertArrayEquals(byteArrayOf(9, 8), RtpPacketParser.parse(packet)!!.payload)
    }

    @Test fun validatesPaddingAndMalformedPackets() {
        assertNull(RtpPacketParser.parse(byteArrayOf(1, 2, 3)))
        val wrongVersion = ByteArray(12)
        assertNull(RtpPacketParser.parse(wrongVersion))
        val invalidPadding = ByteArray(13)
        invalidPadding[0] = 0xA0.toByte()
        assertNull(RtpPacketParser.parse(invalidPadding))
    }
}
