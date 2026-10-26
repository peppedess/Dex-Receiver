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
        val port = DatagramSocket(0).use { it.localPort }
        val received = CountDownLatch(1)
        var parsed: RtpPacket? = null
        val receiver = UdpRtpReceiver(port, onPacket = { parsed = it; received.countDown() })
        try {
            assertTrue("RTP receiver should bind", receiver.start())
            val bytes = byteArrayOf(
                0x80.toByte(), 0xE0.toByte(), 0x00, 0x2A,
                0x00, 0x00, 0x00, 0x01, 0x01, 0x02, 0x03, 0x04,
                0x65, 0x88, 0x84
            )
            DatagramSocket().use { sender ->
                sender.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
            }
            assertTrue("RTP datagram should be delivered", received.await(2, TimeUnit.SECONDS))
            assertEquals(42, parsed?.sequenceNumber)
            assertEquals(96, parsed?.payloadType)
            assertEquals(0x01020304L, parsed?.ssrc)
            assertTrue(parsed?.marker == true)
        } finally {
            receiver.stop()
        }
    }
}
