package it.peppedess.dexreceiver

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pWfdInfo
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import it.peppedess.dexreceiver.wfd.WfdRtspSinkClient
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
    @Volatile private var rtspSinkClient: WfdRtspSinkClient? = null
    @Volatile private var rtspSourceAddress: String? = null
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
        val firmwareDiag = Button(this).apply {
            text = "DIAGNOSTICA WFD FIRMWARE"
            setOnClickListener { runWfdFirmwareDiagnostics() }
        }
        val discover = Button(this).apply {
            text = "CERCA DISPOSITIVI WI-FI DIRECT"
            setOnClickListener { discoverPeers() }
        }
        val start = Button(this).apply {
            text = "AVVIA CLIENT RTSP · 7236"
            setOnClickListener { startRtspSinkClient() }
        }
        val group = Button(this).apply {
            text = "ATTENDI CONNESSIONE WI-FI DIRECT"
            setOnClickListener { createReceiverGroup() }
        }
        val stop = Button(this).apply {
            text = "FERMA RICEZIONE"
            setOnClickListener { stopRtspReceiver() }
        }
        val note = TextView(this).apply {
            text = "Client RTSP sink: il Samsung (source) deve essere Group Owner; questo Xiaomi si collega al suo TCP 7236. La negoziazione WFD completa verrà aggiunta nel passaggio successivo."
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
        root.addView(firmwareDiag)
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
                    val groupOwner = info.groupOwnerAddress?.hostAddress
                    log("Connessione Wi-Fi Direct stabilita · questo dispositivo Group Owner: ${info.isGroupOwner}")
                    log("Indirizzo Group Owner: ${groupOwner ?: "non disponibile"}")
                    if (info.isGroupOwner) {
                        rtspSourceAddress = null
                        log("Ruolo P2P errato per il sink: il Samsung source deve essere Group Owner. Non avvio un server RTSP.")
                        updateStatus("P2P ATTIVO · ATTESA SOURCE COME GO")
                    } else if (!groupOwner.isNullOrBlank()) {
                        rtspSourceAddress = groupOwner
                        updateStatus("SOURCE P2P CONNESSO · AVVIO RTSP CLIENT")
                        startRtspSinkClient(groupOwner)
                    } else {
                        log("Indirizzo del source non disponibile: client RTSP non avviato.")
                    }
                } else {
                    rtspSourceAddress = null
                    log("Nessun gruppo Wi-Fi Direct attivo.")
                    updateStatus("IN ATTESA DI WI-FI DIRECT")
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
            log("Concedi la posizione e premi di nuovo ATTENDI CONNESSIONE.")
            return
        }
        val manager = wifiP2pManager
        val channel = p2pChannel ?: manager?.initialize(this, mainLooper) { log("Canale Wi-Fi Direct disconnesso.") }
            ?.also { p2pChannel = it }
        if (manager == null || channel == null) {
            log("Wi-Fi Direct non disponibile.")
            return
        }
        updateStatus("ATTIVAZIONE ASCOLTO WI-FI DIRECT…")
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
                    override fun onSuccess() { log("Framework WFD configurato come PRIMARY_SINK · porta sink 7236.") }
                    override fun onFailure(reason: Int) { log("Annuncio WFD rifiutato ($reason): CONFIGURE_WIFI_DISPLAY potrebbe mancare.") }
                })
                manager.startListening(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        log("Wi-Fi Direct LISTEN attivo. Attendo che il Samsung avvii il gruppo come source.")
                        updateStatus("IN ASCOLTO · ATTESA SAMSUNG DEX")
                    }
                    override fun onFailure(reason: Int) {
                        log("Wi-Fi Direct LISTEN non riuscito ($reason).")
                        updateStatus("ASCOLTO P2P NON DISPONIBILE")
                    }
                })
            } catch (e: SecurityException) {
                log("Annuncio/listen WFD bloccato dal firmware: ${e.message}")
                updateStatus("WFD BLOCCATO DAL FIRMWARE")
            } catch (t: Throwable) {
                log("Errore avvio ascolto WFD: ${t.message}")
                updateStatus("ERRORE ASCOLTO WFD")
            }
        } else {
            log("L'API pubblica per WFD sink/listen richiede Android 14 o successivo.")
            updateStatus("API WFD NON DISPONIBILE")
        }
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


    /**
     * Read-only firmware inspection. Does not change permissions, packages or system files.
     */
    private fun runWfdFirmwareDiagnostics() {
        updateStatus("ANALISI FIRMWARE WFD…")
        val command = """
            echo '=== DEVICE ==='
            getprop ro.build.fingerprint
            getprop ro.build.version.release
            echo '=== APP PERMISSION ==='
            cmd package check-permission android.permission.CONFIGURE_WIFI_DISPLAY it.peppedess.dexreceiver 0 2>&1
            echo '=== APP PACKAGE PERMISSION STATE ==='
            dumpsys package it.peppedess.dexreceiver 2>&1 | grep -A8 -B3 -E 'CONFIGURE_WIFI_DISPLAY|requested permissions|install permissions|runtime permissions'
            echo '=== PERMISSION DEFINITION / HOLDERS ==='
            dumpsys package permissions 2>&1 | grep -A14 -B4 'android.permission.CONFIGURE_WIFI_DISPLAY'
            echo '=== WIFI / DISPLAY PACKAGES ==='
            pm list packages -f 2>/dev/null | grep -Ei 'wifi|display|miracast' | head -80
            echo '=== PERMISSION XML REFERENCES ==='
            grep -R -n -A3 -B2 'CONFIGURE_WIFI_DISPLAY\|wifi_known_signers' /system/etc/permissions /system_ext/etc/permissions /product/etc/permissions /vendor/etc/permissions 2>/dev/null | head -100
        """.trimIndent()
        Thread {
            val result = RootAccess.run(command, 25)
            runOnUiThread {
                log("===== DIAGNOSTICA FIRMWARE WFD =====")
                result.output.lineSequence().filter { it.isNotBlank() }.forEach { log("FW> $it") }
                if (result.timedOut) log("Diagnostica interrotta per timeout.")
                log("===== FINE DIAGNOSTICA =====")
                updateStatus(if (result.exitCode == 0) "DIAGNOSTICA COMPLETATA" else "DIAGNOSTICA PARZIALE · ${result.exitCode}")
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

    private fun startRtspSinkClient(sourceAddress: String? = rtspSourceAddress) {
        if (rtspSinkClient?.isRunning() == true) {
            log("Il client RTSP sink è già connesso.")
            return
        }
        val address = sourceAddress?.takeIf { it.isNotBlank() }
        if (address == null) {
            log("Nessun source Wi-Fi Direct disponibile. Connetti prima il Samsung come Group Owner.")
            updateStatus("IN ATTESA DEL SOURCE P2P")
            return
        }
        val client = WfdRtspSinkClient(
            sourceAddress = address,
            controlPort = 7236,
            mediaPort = 19000,
            onStartMedia = { port -> startMediaReceiver(port) },
            onStopMedia = { stopMediaReceiver() },
            onPauseMedia = { mediaTransport?.pause() },
            onResumeMedia = { mediaTransport?.resume() },
            onLog = { message ->
                log(message)
                if (message.startsWith("RTSP sink connesso")) updateStatus("RTSP CLIENT CONNESSO · M1/M2")
                if (message.startsWith("M4 capability")) updateStatus("WFD CAPABILITIES NEGOZIATE")
                if (message.startsWith("M6 SETUP")) updateStatus("RTP PREPARATO · IN ATTESA PLAY")
                if (message.startsWith("M7 PLAY")) updateStatus("RICEZIONE VIDEO ATTIVA")
                if (message.startsWith("Connessione RTSP sink terminata")) updateStatus("RTSP DISCONNESSO")
            }
        )
        rtspSinkClient = client
        updateStatus("CONNESSIONE RTSP AL SOURCE…")
        if (!client.start()) {
            rtspSinkClient = null
            log("Avvio client RTSP sink non riuscito.")
            updateStatus("AVVIO CLIENT RTSP FALLITO")
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

    private fun stopRtspReceiver() {
        rtspSinkClient?.stop()
        rtspSinkClient = null
        rtspSourceAddress = null
        stopMediaReceiver()
        stopReceiverGroup()
        updateStatus("RICEZIONE E RETE P2P FERME")
        log("Client RTSP sink e gruppo P2P arrestati.")
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
        stopRtspReceiver()
        super.onDestroy()
    }
}
