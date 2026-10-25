package it.peppedess.dexreceiver

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pWfdInfo
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * DeX Receiver 0.1 - WFD capability/RTSP transport proof of concept.
 * This is not a complete Miracast receiver.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    @Volatile private var server: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val logLines = mutableListOf<String>()
    private val permissionRequest = 410

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        manager = getSystemService(WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(this, mainLooper, null)
        log("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
        log("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("Wi-Fi P2P manager: ${if (manager != null) "disponibile" else "non disponibile"}")
        updateStatus("IN ATTESA")
        requestNeededPermissions()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 34, 28, 24)
            setBackgroundColor(0xFF10101A.toInt())
        }
        val title = TextView(this).apply {
            text = "DeX Receiver"
            textSize = 30f
            setTextColor(0xFFF4F1FF.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "WIRELESS DISPLAY · WFD PROTOTYPE"
            textSize = 11f
            letterSpacing = 0.15f
            setTextColor(0xFFAAA3C5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 28)
        }
        status = TextView(this).apply {
            textSize = 22f
            setTextColor(0xFFBBA5FF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 22, 0, 22)
        }
        val start = Button(this).apply {
            text = "AVVIA RICEVITORE (TEST)"
            setOnClickListener { startReceiverTest() }
        }
        val stop = Button(this).apply {
            text = "FERMA"
            setOnClickListener { stopReceiver() }
        }
        val note = TextView(this).apply {
            text = "Questa build verifica la pubblicazione Wi-Fi Display e la porta RTSP. " +
                "Non è ancora un ricevitore video Miracast completo: la gestione RTP/H.264/AAC " +
                "e la compatibilità del firmware devono essere verificate."
            textSize = 13f
            setTextColor(0xFFD0CCDD.toInt())
            setPadding(0, 18, 0, 18)
        }
        logView = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFB8C3D8.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = ScrollView(this).apply { addView(logView) }
        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(start)
        root.addView(stop)
        root.addView(note)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun requestNeededPermissions() {
        val needed = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION).filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), permissionRequest)
    }

    private fun startReceiverTest() {
        val m = manager
        val ch = channel
        if (m == null || ch == null) {
            updateStatus("WI-FI P2P NON DISPONIBILE")
            log("Impossibile ottenere WifiP2pManager/Channel.")
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions()
            log("Concedi il permesso Posizione e riprova.")
            return
        }
        try {
            val wfd = WifiP2pWfdInfo().apply {
                setWfdEnabled(true)
                setDeviceType(WifiP2pWfdInfo.DEVICE_TYPE_PRIMARY_SINK)
                setSessionAvailable(true)
                setControlPort(7236)
                setMaxThroughput(50)
            }
            m.setWFDInfo(ch, wfd, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("setWFDInfo: richiesta accettata dal framework.")
                    startRtspProbeListener()
                }
                override fun onFailure(reason: Int) {
                    log("setWFDInfo rifiutato. Codice errore: $reason")
                    updateStatus("WFD NON ATTIVATO")
                }
            })
        } catch (t: Throwable) {
            log("Errore WFD: ${t.javaClass.simpleName}: ${t.message}")
            updateStatus("WFD NON DISPONIBILE")
        }
    }

    private fun startRtspProbeListener() {
        executor.execute {
            try {
                server?.close()
                val socketServer = ServerSocket(7236)
                server = socketServer
                runOnUiThread {
                    updateStatus("TEST ATTIVO · PORTA 7236")
                    log("Socket TCP 7236 in ascolto. Questo è solo un test RTSP.")
                }
                while (!socketServer.isClosed) {
                    val client = socketServer.accept()
                    executor.execute {
                        client.use { socket ->
                            socket.soTimeout = 5000
                            val buffer = ByteArray(4096)
                            val count = socket.getInputStream().read(buffer)
                            if (count > 0) {
                                val request = String(buffer, 0, count, Charsets.US_ASCII)
                                log("Connessione TCP ricevuta:\n${request.take(700)}")
                                val cseq = Regex("(?im)^CSeq:\\s*(\\d+)").find(request)?.groupValues?.get(1) ?: "1"
                                val response = "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nPublic: OPTIONS, GET_PARAMETER\r\nContent-Length: 0\r\n\r\n"
                                socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (server?.isClosed != true) log("Listener RTSP: ${t.message}")
            }
        }
    }

    private fun stopReceiver() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        updateStatus("FERMATO")
        log("Ricevitore di test arrestato.")
    }

    private fun updateStatus(value: String) {
        runOnUiThread { status.text = value }
    }

    private fun log(message: String) {
        runOnUiThread {
            logLines.add("• $message")
            while (logLines.size > 80) logLines.removeAt(0)
            logView.text = logLines.joinToString("\n\n")
        }
    }

    override fun onDestroy() {
        stopReceiver()
        executor.shutdownNow()
        super.onDestroy()
    }
}
