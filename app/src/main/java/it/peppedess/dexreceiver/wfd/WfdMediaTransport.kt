package it.peppedess.dexreceiver.wfd

/**
 * Connects UDP/RTP reception to MPEG-TS framing and PAT/PMT/PES demuxing.
 * Emits H.264 PES elementary payloads; access-unit parsing is still required
 * before those bytes can safely be queued into MediaCodec.
 */
class WfdMediaTransport(
    private val rtpPort: Int,
    private val onH264Pes: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit = {}
) {
    private val demuxer = MpegTsDemuxer(onH264Pes) { onLog(it) }
    private var receiver: UdpRtpReceiver? = null

    @Synchronized
    fun start(): Boolean {
        if (receiver?.isRunning() == true) return true
        val next = UdpRtpReceiver(
            port = rtpPort,
            onPacket = { rtp ->
                val packets = MpegTsPacketParser.split(rtp.payload)
                if (packets == null) {
                    onLog("Payload RTP non allineato a pacchetti MPEG-TS da 188 byte.")
                } else {
                    packets.forEach(demuxer::consume)
                }
            },
            onLog = onLog
        )
        if (!next.start()) return false
        receiver = next
        onLog("Trasporto WFD UDP/RTP pronto sulla porta $rtpPort.")
        return true
    }

    @Synchronized
    fun stop() {
        receiver?.stop()
        receiver = null
        demuxer.flush()
        demuxer.reset()
        onLog("Trasporto WFD UDP/RTP arrestato.")
    }

    fun isRunning(): Boolean = receiver?.isRunning() == true
}
