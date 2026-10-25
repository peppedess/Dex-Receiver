package it.peppedess.dexreceiver.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class H264AccessUnitAssemblerTest {
    @Test
    fun splitsFramesAtAccessUnitDelimiter() {
        val frames = mutableListOf<ByteArray>()
        val assembler = H264AccessUnitAssembler({ frames += it })
        assembler.consume(byteArrayOf(
            0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1e,
            0, 0, 0, 1, 0x68, 0xce.toByte(), 0x06, 0xe2.toByte(),
            0, 0, 0, 1, 0x65, 0x80.toByte(), 0x11,
            0, 0, 0, 1, 0x09, 0xf0.toByte(),
            0, 0, 0, 1, 0x41, 0x80.toByte(), 0x22
        ))
        assembler.flush()
        assertEquals(2, frames.size)
        assertTrue(frames[0].containsNal(0x65))
        assertTrue(frames[1].containsNal(0x41))
    }

    @Test
    fun buffersPartialAnnexBStartCodeAcrossChunks() {
        val frames = mutableListOf<ByteArray>()
        val assembler = H264AccessUnitAssembler({ frames += it })
        assembler.consume(byteArrayOf(0, 0))
        assembler.consume(byteArrayOf(0, 1, 0x65, 0x80.toByte(), 0x01))
        assembler.consume(byteArrayOf(0, 0, 1, 0x41, 0x80.toByte(), 0x02))
        assembler.flush()
        assertEquals(2, frames.size)
    }

    private fun ByteArray.containsNal(type: Int): Boolean {
        for (i in 0 until size - 4) {
            if (this[i] == 0.toByte() && this[i + 1] == 0.toByte() &&
                this[i + 2] == 0.toByte() && this[i + 3] == 1.toByte() &&
                (this[i + 4].toInt() and 0x1f) == type) return true
        }
        return false
    }
}
