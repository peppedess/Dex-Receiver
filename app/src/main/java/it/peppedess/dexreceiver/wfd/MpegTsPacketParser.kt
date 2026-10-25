package it.peppedess.dexreceiver.wfd

/** MPEG-2 Transport Stream packet framing (188-byte packets). */
data class MpegTsPacket(
    val pid: Int,
    val payloadUnitStart: Boolean,
    val continuityCounter: Int,
    val hasPayload: Boolean,
    val hasAdaptationField: Boolean,
    val payload: ByteArray
)

object MpegTsPacketParser {
    const val PACKET_SIZE = 188

    fun parse(packet: ByteArray): MpegTsPacket? {
        if (packet.size != PACKET_SIZE || (packet[0].toInt() and 0xff) != 0x47) return null
        val b1 = packet[1].toInt() and 0xff
        val b2 = packet[2].toInt() and 0xff
        val b3 = packet[3].toInt() and 0xff
        if ((b1 and 0x80) != 0) return null // transport_error_indicator
        val adaptationControl = (b3 ushr 4) and 0x03
        if (adaptationControl == 0) return null
        val hasAdaptation = adaptationControl == 2 || adaptationControl == 3
        val hasPayload = adaptationControl == 1 || adaptationControl == 3
        var offset = 4
        if (hasAdaptation) {
            if (offset >= packet.size) return null
            val adaptationLength = packet[offset].toInt() and 0xff
            offset += 1 + adaptationLength
            if (offset > packet.size) return null
        }
        if (hasPayload && offset >= packet.size) return null
        return MpegTsPacket(
            pid = ((b1 and 0x1f) shl 8) or b2,
            payloadUnitStart = (b1 and 0x40) != 0,
            continuityCounter = b3 and 0x0f,
            hasPayload = hasPayload,
            hasAdaptationField = hasAdaptation,
            payload = if (hasPayload) packet.copyOfRange(offset, packet.size) else ByteArray(0)
        )
    }

    /** Splits aligned 188-byte TS packets; invalid sync bytes reject the whole payload. */
    fun split(payload: ByteArray): List<MpegTsPacket>? {
        if (payload.isEmpty() || payload.size % PACKET_SIZE != 0) return null
        val result = ArrayList<MpegTsPacket>(payload.size / PACKET_SIZE)
        var offset = 0
        while (offset < payload.size) {
            val parsed = parse(payload.copyOfRange(offset, offset + PACKET_SIZE)) ?: return null
            result.add(parsed)
            offset += PACKET_SIZE
        }
        return result
    }
}
