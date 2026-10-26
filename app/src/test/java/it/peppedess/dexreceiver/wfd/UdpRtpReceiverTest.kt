package it.peppedess.dexreceiver.wfd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class UdpRtpReceiverTest {
    @Test fun bindsUdpPortAndDeliversParsedRtpPacket() {
        val received = CountDownLatch(1)
        val parsed = AtomicReference<RtpPacket?>(null)
        val receiver = UdpRtpReceiver(0, onPacket = {
            parsed.set(it)
            received.countDown()
        })

        try {
            assertTrue("RTP receiver should start", receiver.start())
            val boundPort = receiver.localPort()
            assertTrue("RTP receiver should bind an ephemeral UDP port, got $boundPort", boundPort in 1..65535)

            val packetBytes = byteArrayOf(
                0x80.toByte(), 0xE0.toByte(), 0x00, 0x2A,
                0x00, 0x00, 0x00, 0x01, 0x01, 0x02, 0x03, 0x04,
                0x65.toByte(), 0x88.toByte(), 0x84.toByte()
            )
            DatagramSocket().use { sender ->
                val destination = InetAddress.getByName("127.0.0.1")
                sender.send(DatagramPacket(packetBytes, packetBytes.size, destination, boundPort))
            }

            assertTrue(
                "RTP datagram was not delivered on UDP port $boundPort",
                received.await(5, TimeUnit.SECONDS)
            )
            val actual = parsed.get()
            assertNotNull("RTP callback did not provide a parsed packet", actual)
            requireNotNull(actual)
            assertEquals(42, actual.sequenceNumber)
            assertEquals(96, actual.payloadType)
            assertEquals(0x01020304L, actual.ssrc)
            assertTrue(actual.marker)
            assertEquals(3, actual.payload.size)
        } finally {
            receiver.stop()
        }
    }
}
