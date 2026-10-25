package it.peppedess.dexreceiver.wfd

import org.junit.Assert.*
import org.junit.Test

class RtpReorderBufferTest {
    private fun packet(seq: Int) = RtpPacket(false, 33, seq, 0L, 1L, byteArrayOf(seq.toByte()))

    @Test fun emitsInOrderAndReordersLateArrivalWithinWindow() {
        val emitted = mutableListOf<Int>()
        val buffer = RtpReorderBuffer(3, { emitted += it.sequenceNumber })
        buffer.offer(packet(10))
        buffer.offer(packet(12))
        assertEquals(listOf(10), emitted)
        buffer.offer(packet(11))
        assertEquals(listOf(10, 11, 12), emitted)
    }

    @Test fun ignoresDuplicatesAndPacketsAlreadyPassed() {
        val emitted = mutableListOf<Int>()
        val buffer = RtpReorderBuffer(3, { emitted += it.sequenceNumber })
        buffer.offer(packet(5))
        buffer.offer(packet(6))
        buffer.offer(packet(6))
        buffer.offer(packet(5))
        assertEquals(listOf(5, 6), emitted)
    }

    @Test fun handlesSequenceNumberWraparound() {
        val emitted = mutableListOf<Int>()
        val buffer = RtpReorderBuffer(3, { emitted += it.sequenceNumber })
        buffer.offer(packet(65534))
        buffer.offer(packet(0))
        buffer.offer(packet(65535))
        assertEquals(listOf(65534, 65535, 0), emitted)
    }

    @Test fun advancesOnFullWindowAndReportsMissingPackets() {
        val emitted = mutableListOf<Int>()
        val losses = mutableListOf<Int>()
        val buffer = RtpReorderBuffer(2, { emitted += it.sequenceNumber }, { losses += it })
        buffer.offer(packet(100))
        buffer.offer(packet(102))
        buffer.offer(packet(103))
        buffer.offer(packet(104))
        assertEquals(listOf(100, 102, 103, 104), emitted)
        assertEquals(listOf(1), losses)
    }
}
