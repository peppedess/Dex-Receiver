package it.peppedess.dexreceiver.wfd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UdpRtpReceiverTest {
    @Test fun bindsUdpPortAndDeliversParsedRtpPacket() {
        val received = CountDownLatch(1)
        var parsed: RtpPacket? = null
        val receiver = UdpRtpReceiver(0, onPacket = {
            parsed = it
            received.countDown()
        })
        try {
            assertTrue("RTP receiver should bind", receiver.start())
            val boundPort = receiver.localPort()
            assertTrue("RTP receiver should expose its assigned UDP port", boundPort in 1..65535)

            val bytes = byteArrayOf(
                0x80.toByte(), 0xE0.toByte(), 0x00, 0x2A,
                0x00, 0x00, 0x00, 0x01, 0x01, 0x02, 0x03, 0x04,
                0x65.toByte(), 0x88.toByte(), 0x84.toByte()
            )
            DatagramSocket().use { sender ->
                sender.send(
                    DatagramPacket(
                        bytes,
                        bytes.size,
                        InetAddress.getLoopbackAddress(),
                        boundPort
                    )
                )
            }

            assertTrue("RTP datagram should be delivered", received.await(3, TimeUnit.SECONDS))
            val packet = requireNotNull(parsed) { "Receiver callback returned no parsed RTP packet" }
            assertEquals(42, packet.sequenceNumber)
            assertEquals(96, packet.payloadType)
            assertEquals(0x01020304L, packet.ssrc)
            assertTrue(packet.marker)
            assertEquals(3, packet.payload.size)
        } finally {
            receiver.stop()
        }
    }
}
