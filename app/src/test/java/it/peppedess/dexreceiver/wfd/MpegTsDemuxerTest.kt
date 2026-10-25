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

    @Test
    fun ignoresDuplicatePayloadPacket() {
        val emitted = mutableListOf<ByteArray>()
        val demuxer = MpegTsDemuxer({ emitted += it })
        discoverVideoPid(demuxer)
        val pes = pes(byteArrayOf(0x11, 0x22))
        demuxer.consume(packet(0x101, true, pes, 0))
        demuxer.consume(packet(0x101, true, pes, 0))
        demuxer.flush()
        assertEquals(1, emitted.size)
        assertArrayEquals(byteArrayOf(0x11, 0x22), emitted.single())
    }

    @Test
    fun dropsVideoUntilNextPesStartAfterContinuityGap() {
        val emitted = mutableListOf<ByteArray>()
        var discontinuities = 0
        val demuxer = MpegTsDemuxer({ emitted += it }, onVideoDiscontinuity = { discontinuities++ })
        discoverVideoPid(demuxer)
        demuxer.consume(packet(0x101, true, pes(byteArrayOf(0x11)), 0))
        demuxer.consume(packet(0x101, false, byteArrayOf(0x22), 2))
        demuxer.consume(packet(0x101, false, byteArrayOf(0x33), 3))
        demuxer.consume(packet(0x101, true, pes(byteArrayOf(0x44)), 4))
        demuxer.flush()
        assertEquals(1, emitted.size)
        assertArrayEquals(byteArrayOf(0x44), emitted.single())
        assertEquals(1, discontinuities)
    }

    @Test
    fun adaptationOnlyPacketDoesNotAdvancePayloadContinuity() {
        val emitted = mutableListOf<ByteArray>()
        val demuxer = MpegTsDemuxer({ emitted += it })
        discoverVideoPid(demuxer)
        demuxer.consume(packet(0x101, true, pes(byteArrayOf(0x55)), 5))
        demuxer.consume(MpegTsPacket(0x101, false, 12, false, true, ByteArray(0)))
        demuxer.consume(packet(0x101, true, pes(byteArrayOf(0x66)), 6))
        demuxer.flush()
        assertEquals(2, emitted.size)
        assertArrayEquals(byteArrayOf(0x55), emitted[0])
        assertArrayEquals(byteArrayOf(0x66), emitted[1])
    }

    private fun discoverVideoPid(demuxer: MpegTsDemuxer) {
        demuxer.consume(packet(0, true, byteArrayOf(
            0, 0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0x00, 0x01, 0xE1.toByte(), 0x00, 0, 0, 0, 0
        ), 0))
        demuxer.consume(packet(0x100, true, byteArrayOf(
            0, 0x02, 0xB0.toByte(), 0x12, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0xE1.toByte(), 0x01, 0xF0.toByte(), 0x00,
            0x1B, 0xE1.toByte(), 0x01, 0xF0.toByte(), 0x00, 0, 0, 0, 0
        ), 0))
    }

    private fun pes(payload: ByteArray) = byteArrayOf(
        0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0, 0
    ) + payload

    private fun packet(pid: Int, start: Boolean, payload: ByteArray, cc: Int = 0): MpegTsPacket =
        MpegTsPacket(
            pid = pid,
            payloadUnitStart = start,
            continuityCounter = cc,
            hasPayload = true,
            hasAdaptationField = false,
            payload = payload
        )
}
