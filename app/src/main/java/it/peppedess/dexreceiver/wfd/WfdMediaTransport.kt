package it.peppedess.dexreceiver.wfd

import it.peppedess.dexreceiver.media.H264AccessUnitAssembler

/**
 * UDP/RTP -> MPEG-TS -> PAT/PMT -> H.264 PES -> Annex-B access units.
 * The caller can feed emitted access units directly to H264SurfaceDecoder.
 */
class WfdMediaTransport(
    private val rtpPort: Int,
    private val onH264AccessUnit: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit = {}
) {
    private val assembler = H264AccessUnitAssembler(onH264AccessUnit, onLog)
    private val demuxer = MpegTsDemuxer(
        onVideoPes = assembler::consume,
        onLog = onLog
    )
    private var receiver: UdpRtpReceiver? = null
    private val reorderBuffer = RtpReorderBuffer(
        maxBufferedPackets = 32,
        onPacket = ::consumeOrderedRtp,
        onPacketLoss = { count -> onLog("Persi $count pacchetti RTP: salto al successivo pacchetto disponibile.") }
    )

    @Synchronized
    fun start(): Boolean {
        if (receiver?.isRunning() == true) return true
        val next = UdpRtpReceiver(
            port = rtpPort,
            onPacket = reorderBuffer::offer,
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
        reorderBuffer.reset()
        demuxer.flush()
        assembler.flush()
        demuxer.reset()
        assembler.reset()
        onLog("Trasporto WFD UDP/RTP arrestato.")
    }

    fun isRunning(): Boolean = receiver?.isRunning() == true

    private fun consumeOrderedRtp(rtp: RtpPacket) {
        val packets = MpegTsPacketParser.split(rtp.payload)
        if (packets == null) {
            onLog("Payload RTP non allineato a pacchetti MPEG-TS da 188 byte.")
        } else {
            packets.forEach(demuxer::consume)
        }
    }
}
