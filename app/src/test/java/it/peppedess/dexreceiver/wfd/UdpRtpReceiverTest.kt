package it.peppedess.dexreceiver.wfd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpRtpReceiverTest {
    @Test fun bindsEphemeralUdpPortAndCanStop() {
        val receiver = UdpRtpReceiver(0, onPacket = {})
        try {
            assertTrue("RTP receiver should start", receiver.start())
            val boundPort = receiver.localPort()
            assertTrue(
                "RTP receiver should bind an ephemeral UDP port, got $boundPort",
                boundPort in 1..65535
            )
            assertTrue("RTP receiver should report running after start", receiver.isRunning())
        } finally {
            receiver.stop()
        }
        assertTrue("RTP receiver should stop", !receiver.isRunning())
    }

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
