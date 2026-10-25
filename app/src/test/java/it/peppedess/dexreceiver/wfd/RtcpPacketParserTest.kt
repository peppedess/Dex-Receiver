package it.peppedess.dexreceiver.wfd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class RtcpPacketParserTest {
    @Test
    fun parsesReceiverReport() {
        val packet = byteArrayOf(
            0x80.toByte(), 201.toByte(), 0, 1,
            0, 0, 0, 1
        )
        val parsed = RtcpPacketParser.parseCompound(packet)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.size)
        assertEquals(201, parsed[0].packetType)
        assertEquals(0, parsed[0].count)
        assertEquals(4, parsed[0].payload.size)
    }

    @Test
    fun parsesCompoundSenderAndSourceDescriptionPackets() {
        val compound = ByteArray(36)
        compound[0] = 0x80.toByte()
        compound[1] = 200.toByte()
        compound[2] = 0
        compound[3] = 6 // 28-byte Sender Report
        compound[28] = 0x81.toByte()
        compound[29] = 202.toByte()
        compound[30] = 0
        compound[31] = 1 // 8-byte SDES
        compound[32] = 0
        compound[33] = 0
        compound[34] = 0
        compound[35] = 1

        val parsed = RtcpPacketParser.parseCompound(compound)
        assertNotNull(parsed)
        assertEquals(listOf(200, 202), parsed!!.map { it.packetType })
    }

    @Test
    fun rejectsInvalidVersionAndTruncatedCompoundPacket() {
        assertNull(RtcpPacketParser.parseCompound(byteArrayOf(0, 201.toByte(), 0, 0)))
        assertNull(RtcpPacketParser.parseCompound(byteArrayOf(0x80.toByte(), 201.toByte(), 0, 2, 0, 0, 0, 1)))
    }

    @Test
    fun rejectsInvalidPadding() {
        val padded = byteArrayOf(
            0xA0.toByte(), 201.toByte(), 0, 1,
            0, 0, 0, 0
        )
        assertNull(RtcpPacketParser.parseCompound(padded))
    }
}
