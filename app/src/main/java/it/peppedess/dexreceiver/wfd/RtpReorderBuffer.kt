package it.peppedess.dexreceiver.wfd

/**
 * Small bounded RTP reorder window. The first packet establishes the sequence
 * baseline; later packets are emitted in sequence order. A gap is declared only
 * when the bounded window fills, avoiding timer/thread assumptions.
 */
class RtpReorderBuffer(
    private val maxBufferedPackets: Int = 32,
    private val onPacket: (RtpPacket) -> Unit,
    private val onPacketLoss: (Int) -> Unit = {}
) {
    private val pending = HashMap<Int, RtpPacket>()
    private var expectedSequence: Int? = null

    init {
        require(maxBufferedPackets in 1..32767)
    }

    @Synchronized
    fun offer(packet: RtpPacket) {
        val expected = expectedSequence
        if (expected == null) {
            expectedSequence = (packet.sequenceNumber + 1) and 0xffff
            onPacket(packet)
            return
        }

        val distance = (packet.sequenceNumber - expected) and 0xffff
        if (distance >= 0x8000) return // duplicate or too late
        if (pending.containsKey(packet.sequenceNumber)) return

        if (distance == 0) {
            emit(packet)
            drainContiguous()
            return
        }

        pending[packet.sequenceNumber] = packet
        if (pending.size > maxBufferedPackets) {
            val next = pending.keys.minByOrNull { (it - expectedSequence!!) and 0xffff } ?: return
            val lost = (next - expectedSequence!!) and 0xffff
            if (lost > 0) onPacketLoss(lost)
            expectedSequence = next
            drainContiguous()
        }
    }

    @Synchronized
    fun reset() {
        pending.clear()
        expectedSequence = null
    }

    private fun drainContiguous() {
        while (true) {
            val expected = expectedSequence ?: return
            val next = pending.remove(expected) ?: return
            emit(next)
        }
    }

    private fun emit(packet: RtpPacket) {
        onPacket(packet)
        expectedSequence = (packet.sequenceNumber + 1) and 0xffff
    }
}
