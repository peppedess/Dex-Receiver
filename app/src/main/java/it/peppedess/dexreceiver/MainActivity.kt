package it.peppedess.dexreceiver

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pWfdInfo
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import it.peppedess.dexreceiver.wfd.WfdRtspTcpServer
import it.peppedess.dexreceiver.wfd.WfdRtspSession
import it.peppedess.dexreceiver.wfd.WfdMediaTransport
import it.peppedess.dexreceiver.media.H264SurfaceDecoder
import rikka.shizuku.Shizuku

/**
 * DeX Receiver experimental WFD sink prototype. Full source interoperability
 * and complete Wi-Fi Display capability negotiation are not yet verified.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var videoSurface: SurfaceView
    @Volatile private var videoDecoder: H264SurfaceDecoder? = null
    @Volatile private var mediaTransport: WfdMediaTransport? = null
    private var wifiP2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    @Volatile private var rtspServer: WfdRtspTcpServer? = null
    private val logLines = mutableListOf<String>()
    private val shizukuRequestCode = 7206
    private val shizukuBinderReceived = Shizuku.OnBinderReceivedListener { refreshShizukuStatus() }
    private val shizukuBinderDead = Shizuku.OnBinderDeadListener { log("Shizuku non è più attivo.") }
    private val shizukuPermissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == shizukuRequestCode) {
            log(if (grantResult == PackageManager.PERMISSION_GRANTED) "Permesso Shizuku concesso." else "Permesso Shizuku negato.")
            refreshShizukuStatus()
        }
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) {
                val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                    WifiP2pManager.WIFI_P2P_STATE_ENABLED
                log("Wi-Fi Direct: ${if (enabled) "abilitato" else "disabilitato"}")
            } else if (intent.action == WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) {
                requestPeerList()
            } else if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                handleP2pConnectionChange()
            } else if (intent.action == WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION) {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
                log("Discovery P2P: ${if (state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED) "avviata" else "terminata"}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        Shizuku.addBinderReceivedListener(shizukuBinderReceived)
        Shizuku.addBinderDeadListener(shizukuBinderDead)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResult)
        refreshShizukuStatus()
        wifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as? WifiP2pManager
        log("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
        log("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("Framework Wi-Fi Direct: ${if (wifiP2pManager != null) "presente" else "assente"}")
        if (Build.VERSION.SDK_INT < 34) {
            log("API pubblica per pubblicizzare un WFD sink: assente su Android API ${Build.VERSION.SDK_INT}.")
        } else {
            val configureWfd = checkSelfPermission("android.permission.CONFIGURE_WIFI_DISPLAY") == PackageManager.PERMISSION_GRANTED
            log("Permesso privilegiato CONFIGURE_WIFI_DISPLAY: ${if (configureWfd) "concesso" else "non disponibile"}")
        }
        log("RTSP TCP 7236 · RTP/TS/H.264 sperimentale · negoziazione WFD incompleta")
        updateStatus("PRONTO · DIAGNOSTICA P2P")
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
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
        videoSurface = SurfaceView(this).apply {
            setBackgroundColor(0xFF050508.toInt())
        }
        val rootCheck = Button(this).apply {
            text = "VERIFICA ACCESSO ROOT"
            setOnClickListener { verifyRootAccess() }
        }
        val shizuku = Button(this).apply {
            text = "COLLEGA SHIZUKU"
            setOnClickListener { connectShizuku() }
        }
        val discover = Button(this).apply {
            text = "CERCA DISPOSITIVI WI-FI DIRECT"
            setOnClickListener { discoverPeers() }
        }
        val start = Button(this).apply {
            text = "AVVIA SERVER RTSP · 7236"
            setOnClickListener { startRtspServer() }
        }
        val group = Button(this).apply {
            text = "CREA RETE WI-FI DIRECT"
            setOnClickListener { createReceiverGroup() }
        }
        val stop = Button(this).apply {
            text = "FERMA SERVER"
            setOnClickListener { stopRtspServer() }
        }
        val note = TextView(this).apply {
            text = "Ricevitore WFD sperimentale. Su Android 14+ viene richiesto al framework l’annuncio PRIMARY_SINK e la modalità LISTEN. Se CONFIGURE_WIFI_DISPLAY è negato, il gruppo P2P e RTSP restano solo diagnostici: DeX potrebbe non vedere il telefono."
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
        root.addView(videoSurface, LinearLayout.LayoutParams(-1, 220))
        root.addView(rootCheck)
        root.addView(shizuku)
        root.addView(discover)
        root.addView(group)
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

    private fun handleP2pConnectionChange() {
        val manager = wifiP2pManager ?: return
        val channel = p2pChannel ?: return
        try {
            manager.requestConnectionInfo(channel) { info ->
                if (info.groupFormed) {
                    log("Connessione Wi-Fi Direct stabilita · Group Owner: ${info.isGroupOwner}")
                    log("Indirizzo Group Owner: ${info.groupOwnerAddress?.hostAddress ?: "non disponibile"}")
                    if (info.isGroupOwner) startRtspServer()
                    updateStatus(if (info.isGroupOwner) "GRUPPO RICEVITORE ATTIVO" else "COLLEGATO AL GRUPPO P2P")
                } else {
                    log("Nessun gruppo Wi-Fi Direct attivo.")
                }
            }
        } catch (e: SecurityException) {
            log("Permesso insufficiente per leggere lo stato della connessione P2P.")
        }
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

    private fun createReceiverGroup() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 411)
            log("Concedi la posizione e premi di nuovo CREA RETE WI-FI DIRECT.")
            return
        }
        val manager = wifiP2pManager
        val channel = p2pChannel ?: manager?.initialize(this, mainLooper) { log("Canale Wi-Fi Direct disconnesso.") }
            ?.also { p2pChannel = it }
        if (manager == null || channel == null) {
            log("Wi-Fi Direct non disponibile.")
            return
        }
        updateStatus("CREAZIONE RETE WI-FI DIRECT…")
        // Ask the Android Wi-Fi framework to advertise this device as a WFD primary sink.
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val wfdInfo = WifiP2pWfdInfo().apply {
                    setEnabled(true)
                    setDeviceType(WifiP2pWfdInfo.DEVICE_TYPE_PRIMARY_SINK)
                    setSessionAvailable(true)
                    setControlPort(7236)
                    setMaxThroughput(50)
                }
                manager.setWfdInfo(channel, wfdInfo, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { log("Framework WFD configured as PRIMARY_SINK on RTSP 7236.") }
                    override fun onFailure(reason: Int) { log("setWfdInfo rejected ($reason); CONFIGURE_WIFI_DISPLAY may be unavailable.") }
                })
                manager.startListening(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { log("Wi-Fi Direct LISTEN request accepted.") }
                    override fun onFailure(reason: Int) { log("startListening failed ($reason).") }
                })
            } catch (e: SecurityException) {
                log("WFD advertisement blocked: CONFIGURE_WIFI_DISPLAY is signature/privileged; root/Shizuku do not grant it.")
            } catch (t: Throwable) {
                log("WFD advertisement error: " + t.javaClass.simpleName + ": " + t.message)
            }
        }
        val groupConfig = buildWfdGroupConfig()
        manager.createGroup(channel, groupConfig, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("Gruppo Wi-Fi Direct creato. Il dispositivo dovrebbe essere Group Owner.")
                manager.requestConnectionInfo(channel) { info ->
                    log("Group Owner: ${info.isGroupOwner} · indirizzo: ${info.groupOwnerAddress?.hostAddress ?: "non ancora assegnato"}")
                    if (info.groupFormed) {
                        updateStatus("RETE P2P ATTIVA · RTSP IN AVVIO")
                        startRtspServer()
                    } else {
                        updateStatus("GRUPPO IN CONFIGURAZIONE")
                    }
                }
                manager.requestGroupInfo(channel) { groupInfo ->
                    if (groupInfo != null) {
                        log("SSID: ${groupInfo.networkName}")
                        log("Client connessi: ${groupInfo.clientList.size}")
                    }
                }
            }
            override fun onFailure(reason: Int) {
                log("Creazione gruppo fallita. Codice: $reason")
                updateStatus("RETE WI-FI DIRECT NON CREATA")
            }
        })
    }
    private fun verifyRootAccess() {
        updateStatus("VERIFICA ROOT…")
        Thread {
            val result = RootAccess.run("id; getenforce; getprop ro.build.version.release")
            val isRoot = result.exitCode == 0 && result.output.contains("uid=0(root)")
            runOnUiThread {
                log("Verifica root: ${if (isRoot) "CONFERMATO" else "NON DISPONIBILE"}")
                result.output.lineSequence().filter { it.isNotBlank() }.forEach { log("ROOT> $it") }
                if (result.timedOut) log("Comando root interrotto per timeout.")
                updateStatus(if (isRoot) "ROOT ATTIVO · UID 0" else "ROOT NON CONFERMATO")
            }
        }.start()
    }

    private fun refreshShizukuStatus() {
        try {
            val running = Shizuku.pingBinder()
            val granted = running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            if (!running) {
                log("Shizuku: non attivo.")
            } else {
                val uid = Shizuku.getUid()
                val identity = when (uid) {
                    0 -> "ROOT (UID 0)"
                    2000 -> "ADB SHELL (UID 2000)"
                    else -> "UID $uid"
                }
                log("Shizuku attivo · identità: $identity · app autorizzata: $granted")
                log("Nota: Shizuku non trasforma l'UID shell in un'app firmata dal sistema; CONFIGURE_WIFI_DISPLAY resta un permesso signature.")
            }
        } catch (t: Throwable) {
            log("Shizuku non disponibile: " + t.javaClass.simpleName)
        }
    }

    private fun connectShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                log("Avvia Shizuku tramite Debug wireless, poi torna qui.")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                log("Shizuku già autorizzato.")
            } else {
                Shizuku.requestPermission(shizukuRequestCode)
            }
        } catch (t: Throwable) {
            log("Connessione Shizuku non riuscita: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun buildWfdGroupConfig(): WifiP2pConfig {
        val config = WifiP2pConfig()
        config.groupOwnerIntent = 15
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val infoClass = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
                val info = infoClass.getDeclaredConstructor().newInstance()
                infoClass.getMethod("setEnabled", Boolean::class.javaPrimitiveType).invoke(info, true)
                infoClass.getMethod("setDeviceType", Int::class.javaPrimitiveType).invoke(info, 1)
                infoClass.getMethod("setSessionAvailable", Boolean::class.javaPrimitiveType).invoke(info, true)
                infoClass.getMethod("setControlPort", Int::class.javaPrimitiveType).invoke(info, 7236)
                config.javaClass.getMethod("setWfdInfo", infoClass).invoke(config, info)
                log("WFD info allegato alla configurazione P2P (tentativo best-effort).")
            } catch (t: Throwable) {
                log("setWfdInfo bloccato dal firmware: " + (t.cause?.message ?: t.message ?: t.javaClass.simpleName))
            }
        } else {
            log("WifiP2pWfdInfo non disponibile su questa versione Android.")
        }
        return config
    }
    private fun startRtspServer() {
        if (rtspServer?.isRunning() == true) {
            log("Il server RTSP è già attivo.")
            return
        }
        val server = WfdRtspTcpServer(
            port = 7236,
            onLog = { message ->
                log(message)
                if (message.startsWith("RTSP in ascolto")) updateStatus("RTSP ATTIVO · TCP 7236")
                if (message.startsWith("Errore server")) updateStatus("ERRORE SERVER")
            },
            sessionFactory = {
                WfdRtspSession(
                    mediaPort = 19000,
                    onStartMedia = { port -> startMediaReceiver(port) },
                    onStopMedia = { stopMediaReceiver() },
                    onPauseMedia = { mediaTransport?.pause() },
                    onResumeMedia = { mediaTransport?.resume() }
                )
            }
        )
        rtspServer = server
        updateStatus("AVVIO SERVER RTSP…")
        if (!server.start()) {
            log("Il server non è stato avviato.")
            updateStatus("AVVIO NON RIUSCITO")
        }
    }

    private fun startMediaReceiver(port: Int): Boolean {
        return try {
            if (!videoSurface.holder.surface.isValid) {
                log("Surface video non pronta: SETUP rifiutato.")
                return false
            }
            stopMediaReceiver()
            val decoder = H264SurfaceDecoder(videoSurface.holder.surface) { message -> log(message) }
            if (!decoder.start(1920, 1080)) return false
            val transport = WfdMediaTransport(
                rtpPort = port,
                onH264AccessUnit = { accessUnit ->
                    if (!decoder.queueAccessUnit(accessUnit)) {
                        log("Frame H.264 non accettato dal decoder.")
                    }
                },
                onLog = { message -> log(message) }
            )
            if (!transport.start()) {
                decoder.stop()
                return false
            }
            videoDecoder = decoder
            mediaTransport = transport
            updateStatus("FLUSSO RTP ATTIVO · UDP $port")
            true
        } catch (t: Throwable) {
            log("Avvio pipeline video fallito: ${t.message}")
            false
        }
    }

    private fun stopMediaReceiver() {
        mediaTransport?.stop()
        mediaTransport = null
        videoDecoder?.stop()
        videoDecoder = null
    }

    private fun stopRtspServer() {
        rtspServer?.stop()
        rtspServer = null
        stopMediaReceiver()
        stopReceiverGroup()
        updateStatus("SERVER E RETE P2P FERMI")
        log("Server RTSP arrestato.")
    }

    private fun stopReceiverGroup() {
        val manager = wifiP2pManager ?: return
        val channel = p2pChannel ?: return
        try {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("Gruppo Wi-Fi Direct rimosso.")
                }
                override fun onFailure(reason: Int) {
                    log("Rimozione gruppo P2P non riuscita. Codice: $reason")
                }
            })
        } catch (e: SecurityException) {
            log("Permesso insufficiente per rimuovere il gruppo Wi-Fi Direct.")
        }
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
        Shizuku.removeBinderReceivedListener(shizukuBinderReceived)
        Shizuku.removeBinderDeadListener(shizukuBinderDead)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResult)
        stopRtspServer()
        super.onDestroy()
    }
}
