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
        onLog = onLog,
        onVideoDiscontinuity = assembler::discardPartial
    )
    private var receiver: UdpRtpReceiver? = null
    @Volatile private var paused = true
    @Volatile private var rtcpReceiver: UdpRtcpReceiver? = null
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
        val rtcp = UdpRtcpReceiver(
            port = rtpPort + 1,
            onPackets = { packets ->
                packets.forEach { packet ->
                    when (packet.packetType) {
                        200 -> onLog("RTCP Sender Report ricevuto (report blocks: ${packet.count}).")
                        201 -> onLog("RTCP Receiver Report ricevuto (report blocks: ${packet.count}).")
                        202 -> onLog("RTCP SDES ricevuto.")
                        203 -> onLog("RTCP BYE ricevuto.")
                        else -> onLog("RTCP packet type ${packet.packetType} ricevuto.")
                    }
                }
            },
            onLog = onLog
        )
        if (!rtcp.start()) {
            next.stop()
            return false
        }
        receiver = next
        rtcpReceiver = rtcp
        paused = true
        onLog("Trasporto WFD RTP/RTCP pronto sulle porte $rtpPort/${rtpPort + 1} (in attesa di PLAY).")
        return true
    }

    @Synchronized
    fun stop() {
        receiver?.stop()
        receiver = null
        rtcpReceiver?.stop()
        rtcpReceiver = null
        paused = true
        reorderBuffer.reset()
        demuxer.flush()
        assembler.flush()
        demuxer.reset()
        assembler.reset()
        onLog("Trasporto WFD UDP/RTP arrestato.")
    }

    fun isRunning(): Boolean = receiver?.isRunning() == true

    fun pause() {
        paused = true
        onLog("Flusso RTP in pausa.")
    }

    fun resume() {
        if (isRunning()) {
            paused = false
            onLog("Flusso RTP ripreso.")
        }
    }

    private fun consumeOrderedRtp(rtp: RtpPacket) {
        if (paused) return
        val packets = MpegTsPacketParser.split(rtp.payload)
        if (packets == null) {
            onLog("Payload RTP non allineato a pacchetti MPEG-TS da 188 byte.")
            demuxer.resetVideoStream()
        } else {
            packets.forEach(demuxer::consume)
        }
    }
}
