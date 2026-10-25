package it.peppedess.dexreceiver

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * DeX Receiver 0.1.1 - Android networking diagnostic prototype.
 *
 * The public Android SDK does not expose the Wi-Fi Display sink registration
 * methods required to advertise this app as a Miracast receiver. This build
 * deliberately does not claim to be a WFD sink.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private var wifiP2pManager: WifiP2pManager? = null
    @Volatile private var server: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val logLines = mutableListOf<String>()
    private val permissionRequest = 410

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        wifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as? WifiP2pManager
        log("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
        log("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("Wi-Fi Direct framework: ${if (wifiP2pManager != null) "presente" else "non disponibile"}")
        log("Nota: il normale SDK Android non espone l'API pubblica per registrare un WFD sink.")
        updateStatus("PRONTO · TEST RETE")
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
            text = "ANDROID NETWORK DIAGNOSTIC"
            textSize = 11f
            letterSpacing = 0.15f
            setTextColor(0xFFAAA3C5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 28)
        }
        status = TextView(this).apply {
            textSize = 20f
            setTextColor(0xFFBBA5FF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 22, 0, 22)
        }
        val start = Button(this).apply {
            text = "AVVIA TEST TCP 7236"
            setOnClickListener { startTcpTest() }
        }
        val stop = Button(this).apply {
            text = "FERMA TEST"
            setOnClickListener { stopTcpTest() }
        }
        val note = TextView(this).apply {
            text = "ATTENZIONE: questo test apre una porta TCP locale, ma NON pubblica il dispositivo come ricevitore Miracast e NON riceve il video DeX. Serve a verificare soltanto la rete e la connettività di base."
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

    private fun startTcpTest() {
        if (server != null) {
            log("Il test TCP è già attivo.")
            return
        }
        executor.execute {
            try {
                val listener = ServerSocket(7236)
                server = listener
                runOnUiThread {
                    updateStatus("TEST TCP ATTIVO · 7236")
                    log("Porta TCP 7236 in ascolto. Non è un endpoint Miracast completo.")
                }
                while (!listener.isClosed) {
                    val client = listener.accept()
                    executor.execute {
                        client.use { socket ->
                            socket.soTimeout = 5000
                            val buffer = ByteArray(2048)
                            val count = socket.getInputStream().read(buffer)
                            if (count > 0) {
                                val received = String(buffer, 0, count, Charsets.UTF_8)
                                log("Connessione ricevuta: ${received.take(500)}")
                                val reply = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                                socket.getOutputStream().write(reply.toByteArray(Charsets.UTF_8))
                            }
                        }
                    }
                }
            } catch (t: Exception) {
                if (server?.isClosed != true) log("Errore listener: ${t.message}")
            }
        }
    }

    private fun stopTcpTest() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        updateStatus("FERMATO")
        log("Test TCP arrestato.")
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
        stopTcpTest()
        executor.shutdownNow()
        super.onDestroy()
    }
}
