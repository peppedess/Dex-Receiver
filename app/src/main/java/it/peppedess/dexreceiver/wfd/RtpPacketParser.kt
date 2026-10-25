package it.peppedess.dexreceiver.wfd

/**
 * Minimal RFC 3550 RTP v2 packet decoder.
 *
 * It validates packet framing and exposes the payload without interpreting
 * its codec. WFD transport negotiation and MPEG-TS/H.264 decoding are separate.
 */
data class RtpPacket(
    val marker: Boolean,
    val payloadType: Int,
    val sequenceNumber: Int,
    val timestamp: Long,
    val ssrc: Long,
    val payload: ByteArray
)

object RtpPacketParser {
    fun parse(packet: ByteArray): RtpPacket? {
        if (packet.size < 12) return null
        val b0 = packet[0].toInt() and 0xff
        val b1 = packet[1].toInt() and 0xff
        if ((b0 ushr 6) != 2) return null

        val csrcCount = b0 and 0x0f
        val hasExtension = (b0 and 0x10) != 0
        val hasPadding = (b0 and 0x20) != 0
        var offset = 12 + csrcCount * 4
        if (offset > packet.size) return null

        if (hasExtension) {
            if (offset + 4 > packet.size) return null
            val extensionWords = u16(packet, offset + 2)
            val extensionBytes = extensionWords * 4
            if (extensionBytes > packet.size - offset - 4) return null
            offset += 4 + extensionBytes
        }

        var end = packet.size
        if (hasPadding) {
            val padding = packet.last().toInt() and 0xff
            if (padding == 0 || padding > end - offset) return null
            end -= padding
        }
        if (end < offset) return null

        return RtpPacket(
            marker = (b1 and 0x80) != 0,
            payloadType = b1 and 0x7f,
            sequenceNumber = u16(packet, 2),
            timestamp = u32(packet, 4),
            ssrc = u32(packet, 8),
            payload = packet.copyOfRange(offset, end)
        )
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun u32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xff) shl 24) or
        ((data[offset + 1].toLong() and 0xff) shl 16) or
        ((data[offset + 2].toLong() and 0xff) shl 8) or
        (data[offset + 3].toLong() and 0xff)
}
