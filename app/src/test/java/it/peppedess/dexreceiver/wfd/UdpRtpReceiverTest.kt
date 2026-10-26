package it.peppedess.dexreceiver.wfd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpRtpReceiverTest {
    @Test fun parsesRtpDatagramFieldsAndPayload() {
        val packetBytes = byteArrayOf(
            0x80.toByte(), 0xE0.toByte(), 0x00, 0x2A,
            0x00, 0x00, 0x00, 0x01, 0x01, 0x02, 0x03, 0x04,
            0x65.toByte(), 0x88.toByte(), 0x84.toByte()
        )

        val packet = RtpPacketParser.parse(packetBytes)
        assertNotNull("Valid RTP datagram should be parsed", packet)
        requireNotNull(packet)
        assertEquals(42, packet.sequenceNumber)
        assertEquals(96, packet.payloadType)
        assertEquals(0x01020304L, packet.ssrc)
        assertTrue(packet.marker)
        assertEquals(3, packet.payload.size)
    }
}
