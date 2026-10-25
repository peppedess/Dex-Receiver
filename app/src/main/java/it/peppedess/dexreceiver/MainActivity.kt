package it.peppedess.dexreceiver

import android.app.Activity
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import it.peppedess.dexreceiver.wfd.WfdRtspTcpServer

/**
 * DeX Receiver protocol-development build.
 *
 * Hosts a local RTSP endpoint on TCP/7236. It is not yet a Miracast/WFD sink:
 * no Wi-Fi Display discovery/advertisement, WFD capability negotiation,
 * RTP media transport, or video/audio decoding is implemented.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private var wifiP2pManager: WifiP2pManager? = null
    @Volatile private var rtspServer: WfdRtspTcpServer? = null
    private val logLines = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        wifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as? WifiP2pManager
        log("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
        log("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("Framework Wi-Fi Direct: ${if (wifiP2pManager != null) "presente" else "assente"}")
        log("Endpoint RTSP previsto: TCP 7236")
        log("WFD sink/discovery e ricezione video: non ancora implementati.")
        updateStatus("PRONTO · RTSP NON AVVIATO")
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
            text = "WIFI DISPLAY · RTSP PROTOCOL"
            textSize = 11f
            letterSpacing = 0.15f
            setTextColor(0xFFAAA3C5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 28)
        }
        status = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFFBBA5FF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 22, 0, 22)
        }
        val start = Button(this).apply {
            text = "AVVIA SERVER RTSP · 7236"
            setOnClickListener { startRtspServer() }
        }
        val stop = Button(this).apply {
            text = "FERMA SERVER"
            setOnClickListener { stopRtspServer() }
        }
        val note = TextView(this).apply {
            text = "STATO SVILUPPO: il server accetta connessioni RTSP e gestisce richieste di base. La negoziazione Wi‑Fi Display, SETUP/PLAY, RTP e decodifica video non sono ancora disponibili: il Galaxy non potrà ancora trasmettere DeX a questa app."
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

    private fun startRtspServer() {
        if (rtspServer?.isRunning() == true) {
            log("Il server RTSP è già attivo.")
            return
        }
        val server = WfdRtspTcpServer(7236) { message ->
            log(message)
            if (message.startsWith("RTSP in ascolto")) updateStatus("RTSP ATTIVO · TCP 7236")
            if (message.startsWith("Errore server")) updateStatus("ERRORE SERVER")
        }
        rtspServer = server
        updateStatus("AVVIO SERVER RTSP…")
        if (!server.start()) {
            log("Il server non è stato avviato.")
            updateStatus("AVVIO NON RIUSCITO")
        }
    }

    private fun stopRtspServer() {
        rtspServer?.stop()
        rtspServer = null
        updateStatus("SERVER FERMO")
        log("Server RTSP arrestato.")
    }

    private fun updateStatus(value: String) {
        runOnUiThread { status.text = value }
    }

    private fun log(message: String) {
        runOnUiThread {
            logLines.add("• $message")
            while (logLines.size > 100) logLines.removeAt(0)
            logView.text = logLines.joinToString("\n\n")
        }
    }

    override fun onDestroy() {
        stopRtspServer()
        super.onDestroy()
    }
}
