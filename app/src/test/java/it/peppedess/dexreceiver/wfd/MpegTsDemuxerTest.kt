package it.peppedess.dexreceiver.wfd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MpegTsDemuxerTest {
    @Test
    fun discoversH264PidFromPatAndPmtAndEmitsPesPayload() {
        val emitted = mutableListOf<ByteArray>()
        val logs = mutableListOf<String>()
        val demuxer = MpegTsDemuxer({ emitted += it }, { logs += it })

        demuxer.consume(packet(pid = 0, start = true, payload = byteArrayOf(
            0,
            0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0x00, 0x01, 0xE1.toByte(), 0x00, 0, 0, 0, 0
        )))
        demuxer.consume(packet(pid = 0x100, start = true, payload = byteArrayOf(
            0,
            0x02, 0xB0.toByte(), 0x12, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0xE1.toByte(), 0x01, 0xF0.toByte(), 0x00,
            0x1B, 0xE1.toByte(), 0x01, 0xF0.toByte(), 0x00,
            0, 0, 0, 0
        )))
        val video = byteArrayOf(
            0, 0, 1, 0xE0.toByte(), 0, 10, 0x80.toByte(), 0, 0,
            0, 0, 1, 0x65, 0x11, 0x22
        )
        demuxer.consume(packet(pid = 0x101, start = true, payload = video))
        demuxer.flush()

        assertEquals(1, emitted.size)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0x65, 0x11, 0x22), emitted.single())
        assertTrue(logs.any { it.contains("PID 256") })
        assertTrue(logs.any { it.contains("PID 257") })
    }

    @Test
    fun ignoresPesOnNonVideoPid() {
        val emitted = mutableListOf<ByteArray>()
        val demuxer = MpegTsDemuxer({ emitted += it })
        demuxer.consume(packet(pid = 0x222, start = true, payload = byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0, 0, 0)))
        demuxer.flush()
        assertTrue(emitted.isEmpty())
    }

    private fun packet(pid: Int, start: Boolean, payload: ByteArray): MpegTsPacket =
        MpegTsPacket(
            pid = pid,
            payloadUnitStart = start,
            continuityCounter = 0,
            hasPayload = true,
            hasAdaptationField = false,
            payload = payload
        )
}
