package it.peppedess.dexreceiver.wfd

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receives RTP datagrams over UDP and validates the RTP header before delivery.
 * Payload decoding and WFD port negotiation are intentionally handled elsewhere.
 */
class UdpRtpReceiver(
    private val port: Int,
    private val onPacket: (RtpPacket) -> Unit,
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
            onLog("UDP $port non disponibile: ${t.message}")
            return false
        }
        socket = bound
        worker = Thread({
            val buffer = ByteArray(65507)
            while (running.get()) {
                try {
                    val datagram = DatagramPacket(buffer, buffer.size)
                    bound.receive(datagram)
                    val packet = RtpPacketParser.parse(
                        datagram.data.copyOfRange(datagram.offset, datagram.offset + datagram.length)
                    )
                    if (packet == null) onLog("Datagramma UDP scartato: header RTP non valido.")
                    else onPacket(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    // Periodic stop check.
                } catch (e: SocketException) {
                    if (running.get()) onLog("Socket UDP terminato: ${e.message}")
                    break
                } catch (t: Throwable) {
                    if (running.get()) onLog("Errore ricezione RTP: ${t.message}")
                }
            }
            running.set(false)
        }, "WFD-RTP-UDP").apply {
            isDaemon = true
            start()
        }
        onLog("Ricevitore RTP UDP in ascolto sulla porta $port.")
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
