package it.nido.locale

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var txtDevice: TextView
    private lateinit var txtState: TextView
    private lateinit var txtDetails: TextView
    private lateinit var txtWarnings: TextView
    private lateinit var btnScan: Button
    private lateinit var btnForget: Button
    private lateinit var btnTest: Button
    private lateinit var btnStop: Button
    private lateinit var listFound: LinearLayout
    private lateinit var txtScanHint: TextView

    private data class Found(val address: String, val name: String, var rssi: Int) {
        override fun toString() = "$name   ($address)   ${rssi} dBm"
    }

    private val found = mutableListOf<Found>()
    private var scanning = false

    /** Avvertenza accettata in questa apertura dell'app. */
    private var accepted = false
    /** true quando siamo noi ad aprire una schermata di sistema (impostazioni, permessi). */
    private var internalNav = false
    private var disclaimer: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtDevice = findViewById(R.id.txtDevice)
        txtState = findViewById(R.id.txtState)
        txtDetails = findViewById(R.id.txtDetails)
        txtWarnings = findViewById(R.id.txtWarnings)
        btnScan = findViewById(R.id.btnScan)
        btnForget = findViewById(R.id.btnForget)
        btnTest = findViewById(R.id.btnTest)
        btnStop = findViewById(R.id.btnStop)
        listFound = findViewById(R.id.listFound)
        txtScanHint = findViewById(R.id.txtScanHint)


        btnScan.setOnClickListener { if (scanning) stopScan() else startScan() }
        btnForget.setOnClickListener { forget() }
        btnTest.setOnClickListener {
            internalNav = true  // la schermata di allarme non e' una nuova apertura dell'app
            NidoService.start(this, NidoService.ACTION_TEST_ALARM)
        }
        btnStop.setOnClickListener { NidoService.start(this, NidoService.ACTION_STOP_ALARM) }
        txtWarnings.setOnClickListener { fixFirstWarning() }

        // Una rotazione dello schermo non e' una nuova apertura dell'app
        if (savedInstanceState != null) {
            accepted = savedInstanceState.getBoolean("accepted", false)
            internalNav = true
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean("accepted", accepted)
    }

    override fun onStart() {
        super.onStart()
        // L'avvertenza va accettata ogni volta che l'app viene aperta
        // (ma non quando si torna da una schermata di impostazioni aperta dall'app stessa).
        if (!internalNav) accepted = false
        if (!accepted) showDisclaimer()
    }

    override fun onResume() {
        super.onResume()
        internalNav = false
        if (accepted && Perms.missing(this).isEmpty() && Prefs.address(this) != null) {
            runCatching { NidoService.start(this) }
        }
        ui.post(refresh)
    }

    override fun onDestroy() {
        disclaimer?.dismiss()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- avvertenza

    private fun showDisclaimer() {
        if (disclaimer?.isShowing == true) return
        disclaimer = AlertDialog.Builder(this)
            .setTitle(R.string.disclaimer_title)
            .setMessage(R.string.disclaimer_text)
            .setCancelable(false)
            .setPositiveButton(R.string.disclaimer_accept) { _, _ ->
                accepted = true
                askPermissions()
                if (Perms.missing(this).isEmpty() && Prefs.address(this) != null) {
                    runCatching { NidoService.start(this) }
                }
            }
            .setNegativeButton(R.string.disclaimer_decline) { _, _ ->
                // Chi non accetta non usa l'app: la sorveglianza viene fermata.
                if (Perms.canConnect(this)) runCatching { NidoService.start(this, NidoService.ACTION_SHUTDOWN) }
                finishAndRemoveTask()
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
        stopScan()
    }

    // ---------------------------------------------------------------- permessi

    private fun askPermissions() {
        internalNav = true
        val missing = Perms.missing(this)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (accepted && Perms.missing(this).isEmpty() && Prefs.address(this) != null) NidoService.start(this)
        ui.post(refresh)
    }

    private fun warnings(): List<Pair<String, () -> Unit>> {
        val w = mutableListOf<Pair<String, () -> Unit>>()
        if (Perms.missing(this).isNotEmpty())
            w += "Permessi mancanti (Bluetooth / notifiche): tocca per concederli" to { askPermissions() }
        val bt = getSystemService(BluetoothManager::class.java)?.adapter
        if (bt != null && !bt.isEnabled)
            w += "Bluetooth spento: tocca per accenderlo" to {
                runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName))
            w += "Ottimizzazione batteria attiva: Android potrebbe chiudere l'app. Tocca per disattivarla" to {
                @SuppressLint("BatteryLife")
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                runCatching { startActivity(i) }
            }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent())
            w += "Allarme a schermo intero non consentito: tocca per abilitarlo" to {
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                }
            }
        if (!nm.areNotificationsEnabled())
            w += "Notifiche disattivate: tocca per attivarle" to {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        return w
    }

    private fun fixFirstWarning() {
        internalNav = true
        warnings().firstOrNull()?.second?.invoke()
    }

    // ---------------------------------------------------------------- scansione e abbinamento

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName ?: r.device.name
            if (!NidoProtocol.isNidoName(name)) return
            val existing = found.firstOrNull { it.address == r.device.address }
            if (existing != null) existing.rssi = r.rssi
            else found += Found(r.device.address, name!!, r.rssi)
            renderFound()
        }

        override fun onScanFailed(errorCode: Int) {
            txtScanHint.text = "Scansione fallita (codice $errorCode)"
            scanning = false
            updateScanButton()
        }
    }

    private val scanTimeout = Runnable { stopScan() }

    private fun startScan() {
        if (Perms.missing(this).isNotEmpty()) { askPermissions(); return }
        val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) { txtScanHint.text = "Bluetooth non disponibile o spento"; return }
        found.clear()
        renderFound()
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        scanning = true
        txtScanHint.text = "Cerco i tappetini (nome \"Nido_…\")… Metti un peso sul tappetino se non compare."
        listFound.visibility = View.VISIBLE
        txtScanHint.visibility = View.VISIBLE
        ui.postDelayed(scanTimeout, 20_000)
        updateScanButton()
    }

    private fun stopScan() {
        ui.removeCallbacks(scanTimeout)
        if (!scanning) return
        runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(scanCb) }
        scanning = false
        if (found.isEmpty()) txtScanHint.text = "Nessun tappetino trovato. Riprova vicino al tappetino."
        updateScanButton()
    }

    private fun updateScanButton() {
        btnScan.text = if (scanning) "Ferma ricerca" else if (Prefs.address(this) == null) "Cerca tappetino" else "Cambia tappetino"
    }

    private fun renderFound() {
        listFound.removeAllViews()
        for (f in found) {
            listFound.addView(Button(this).apply {
                text = "${f.name}\n${f.address}  ·  segnale ${f.rssi} dBm"
                isAllCaps = false
                setOnClickListener { pair(f) }
            })
        }
    }

    private fun pair(f: Found) {
        stopScan()
        Prefs.save(this, f.address, f.name)
        found.clear()
        renderFound()
        listFound.visibility = View.GONE
        txtScanHint.visibility = View.GONE
        NidoService.start(this, NidoService.ACTION_SHUTDOWN)
        ui.postDelayed({ NidoService.start(this) }, 500)
    }

    private fun forget() {
        AlertDialog.Builder(this)
            .setTitle("Dimenticare il tappetino?")
            .setMessage("La sorveglianza verra' interrotta.")
            .setPositiveButton("Dimentica") { _, _ ->
                NidoService.start(this, NidoService.ACTION_SHUTDOWN)
                Prefs.clear(this)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // ---------------------------------------------------------------- interfaccia

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    private fun render() {
        val addr = Prefs.address(this)
        val s = NidoService.status
        txtDevice.text = if (addr == null) "Nessun tappetino abbinato" else "${Prefs.name(this) ?: "Nido"}  ·  $addr"

        val (state, color) = when {
            addr == null -> "Abbina il tappetino per iniziare" to R.color.muted
            s.alarm -> "ALLARME" to R.color.alarm
            !s.running -> "Sorveglianza non attiva" to R.color.warn
            !s.bluetoothOn -> "Bluetooth spento" to R.color.warn
            !s.connected -> "In attesa del tappetino…" to R.color.muted
            s.seated -> "Bambino a bordo" to R.color.ok
            else -> "Seggiolino vuoto" to R.color.muted
        }
        txtState.text = state
        txtState.setTextColor(getColor(color))

        txtDetails.text = buildString {
            if (s.alarm) appendLine(s.alarmReason ?: "")
            s.batteryMv?.let { appendLine("Batteria: ${NidoProtocol.batteryPercent(it)}%  ($it mV)") }
            if (s.lastDataAt > 0) append("Ultimo dato: ${DateFormat.getTimeInstance().format(Date(s.lastDataAt))}")
        }.trim()

        val w = warnings()
        txtWarnings.visibility = if (w.isEmpty()) View.GONE else View.VISIBLE
        txtWarnings.text = w.joinToString("\n\n") { "⚠ " + it.first }

        btnForget.visibility = if (addr == null) View.GONE else View.VISIBLE
        btnTest.visibility = if (addr == null || s.alarm) View.GONE else View.VISIBLE
        btnStop.visibility = if (s.alarm) View.VISIBLE else View.GONE
        updateScanButton()
    }
}
