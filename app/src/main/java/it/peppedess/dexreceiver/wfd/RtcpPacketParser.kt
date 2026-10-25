package it.peppedess.dexreceiver.wfd

/** Parsed RTCP packet header (RFC 3550). Compound packet framing is validated. */
data class RtcpPacket(
    val packetType: Int,
    val count: Int,
    val payload: ByteArray
)

object RtcpPacketParser {
    fun parseCompound(datagram: ByteArray): List<RtcpPacket>? {
        if (datagram.size < 4 || datagram.size % 4 != 0) return null
        val packets = mutableListOf<RtcpPacket>()
        var offset = 0
        while (offset < datagram.size) {
            if (datagram.size - offset < 4) return null
            val first = datagram[offset].toInt() and 0xff
            if ((first ushr 6) != 2) return null
            val count = first and 0x1f
            val packetType = datagram[offset + 1].toInt() and 0xff
            if (packetType !in 192..223) return null
            val lengthWordsMinusOne = ((datagram[offset + 2].toInt() and 0xff) shl 8) or
                (datagram[offset + 3].toInt() and 0xff)
            val packetLength = (lengthWordsMinusOne + 1) * 4
            if (packetLength < 4 || packetLength > datagram.size - offset) return null
            val padded = (first and 0x20) != 0
            var payloadEnd = offset + packetLength
            if (padded) {
                val padding = datagram[payloadEnd - 1].toInt() and 0xff
                if (padding == 0 || padding > packetLength - 4) return null
                payloadEnd -= padding
            }
            packets += RtcpPacket(
                packetType = packetType,
                count = count,
                payload = datagram.copyOfRange(offset + 4, payloadEnd)
            )
            offset += packetLength
        }
        return packets
    }
}
