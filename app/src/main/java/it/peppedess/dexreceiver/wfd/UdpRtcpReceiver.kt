package it.peppedess.dexreceiver.wfd

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/** Receives and validates RTCP compound datagrams on the negotiated RTCP port. */
class UdpRtcpReceiver(
    private val port: Int,
    private val onPackets: (List<RtcpPacket>) -> Unit,
    private val onLog: (String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var worker: Thread? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val bound = try {
            DatagramSocket(null).apply {
                reuseAddress = false
                bind(InetSocketAddress(port))
                soTimeout = 1000
            }
        } catch (t: Throwable) {
            running.set(false)
            onLog("RTCP UDP $port non disponibile: ${t.message}")
            return false
        }
        socket = bound
        worker = Thread({
            val buffer = ByteArray(65507)
            while (running.get()) {
                try {
                    val datagram = DatagramPacket(buffer, buffer.size)
                    bound.receive(datagram)
                    val bytes = datagram.data.copyOfRange(datagram.offset, datagram.offset + datagram.length)
                    val packets = RtcpPacketParser.parseCompound(bytes)
                    if (packets == null) onLog("Datagramma RTCP non valido scartato.")
                    else onPackets(packets)
                } catch (_: java.net.SocketTimeoutException) {
                    // Allows a prompt stop check.
                } catch (e: SocketException) {
                    if (running.get()) onLog("Socket RTCP terminato: ${e.message}")
                    break
                } catch (t: Throwable) {
                    if (running.get()) onLog("Errore ricezione RTCP: ${t.message}")
                }
            }
            running.set(false)
        }, "WFD-RTCP-UDP").apply {
            isDaemon = true
            start()
        }
        onLog("Ricevitore RTCP UDP in ascolto sulla porta $port.")
        return true
    }

    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        worker?.interrupt()
        worker = null
    }

    fun isRunning(): Boolean = running.get()
}
