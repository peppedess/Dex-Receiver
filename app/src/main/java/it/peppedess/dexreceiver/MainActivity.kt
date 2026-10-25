package it.peppedess.dexreceiver

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
 * The app offers P2P diagnostics and a local RTSP endpoint, not a complete WFD sink.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private var wifiP2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    @Volatile private var rtspServer: WfdRtspTcpServer? = null
    private val logLines = mutableListOf<String>()

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) {
                val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                    WifiP2pManager.WIFI_P2P_STATE_ENABLED
                log("Wi-Fi Direct: ${if (enabled) "abilitato" else "disabilitato"}")
            } else if (intent.action == WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) {
                requestPeerList()
            } else if (intent.action == WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION) {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
                log("Discovery P2P: ${if (state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED) "avviata" else "terminata"}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        wifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as? WifiP2pManager
        log("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
        log("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("Framework Wi-Fi Direct: ${if (wifiP2pManager != null) "presente" else "assente"}")
        log("RTSP locale: TCP 7236 · WFD sink non ancora implementato")
        updateStatus("PRONTO · DIAGNOSTICA P2P")
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
            }
            registerReceiver(p2pReceiver, filter)
            receiverRegistered = true
        }
    }

    override fun onPause() {
        if (receiverRegistered) {
            unregisterReceiver(p2pReceiver)
            receiverRegistered = false
        }
        super.onPause()
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
            text = "WIFI DIRECT · RTSP PROTOCOL"
            textSize = 11f
            letterSpacing = 0.15f
            setTextColor(0xFFAAA3C5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }
        status = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFFBBA5FF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 18)
        }
        val discover = Button(this).apply {
            text = "CERCA DISPOSITIVI WI-FI DIRECT"
            setOnClickListener { discoverPeers() }
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
            text = "La ricerca elenca i peer Wi-Fi Direct visibili: non equivale alla discovery Miracast/WFD. Il server RTSP è ancora parziale; SETUP/PLAY, RTP e decodifica video non sono implementati. Questa versione non riceve ancora DeX."
            textSize = 13f
            setTextColor(0xFFD0CCDD.toInt())
            setPadding(0, 14, 0, 14)
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
        root.addView(discover)
        root.addView(start)
        root.addView(stop)
        root.addView(note)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun discoverPeers() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 410)
            log("Concedi la posizione per consentire la scansione Wi-Fi Direct, poi premi di nuovo il pulsante.")
            return
        }
        val manager = wifiP2pManager
        val channel = p2pChannel ?: manager?.initialize(this, mainLooper) { log("Canale Wi-Fi Direct disconnesso.") }
            ?.also { p2pChannel = it }
        if (manager == null || channel == null) {
            log("Wi-Fi Direct non disponibile su questo dispositivo.")
            return
        }
        updateStatus("RICERCA PEER WI-FI DIRECT…")
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("Richiesta di discovery Wi-Fi Direct avviata.")
            }
            override fun onFailure(reason: Int) {
                log("Discovery non avviata. Codice errore: $reason")
                updateStatus("DISCOVERY NON DISPONIBILE")
            }
        })
    }

    private fun requestPeerList() {
        val manager = wifiP2pManager ?: return
        val channel = p2pChannel ?: return
        try {
            manager.requestPeers(channel) { peers ->
                val list = peers.deviceList
                log("Peer Wi-Fi Direct trovati: ${list.size}")
                if (list.isEmpty()) log("Nessun peer visibile. Questo non dimostra l'assenza del supporto WFD.")
                list.forEach { device ->
                    log("Peer: ${device.deviceName.ifBlank { "senza nome" }} · ${device.deviceAddress} · stato ${device.status}")
                }
                updateStatus("PEER TROVATI · ${list.size}")
            }
        } catch (e: SecurityException) {
            log("Permesso mancante per leggere i peer Wi-Fi Direct.")
        }
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
