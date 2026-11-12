package it.nido.locale

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Servizio in primo piano: resta connesso al tappetino e fa scattare l'allarme
 * se la connessione si perde mentre il bambino e' seduto.
 *
 * Tutta la logica gira sul thread principale (le callback GATT vengono
 * ripassate all'handler), cosi' lo stato non ha problemi di concorrenza.
 */
@SuppressLint("MissingPermission")
class NidoService : Service() {

    data class Status(
        val running: Boolean = false,
        val bluetoothOn: Boolean = true,
        val connected: Boolean = false,
        val receiving: Boolean = false,
        val seated: Boolean = false,
        val batteryMv: Int? = null,
        val lastDataAt: Long = 0L,       // System.currentTimeMillis()
        val alarm: Boolean = false,
        val alarmReason: String? = null,
    )

    companion object {
        private const val TAG = "NidoService"

        const val ACTION_START = "it.nido.locale.START"
        const val ACTION_STOP_ALARM = "it.nido.locale.STOP_ALARM"
        const val ACTION_TEST_ALARM = "it.nido.locale.TEST_ALARM"
        const val ACTION_SHUTDOWN = "it.nido.locale.SHUTDOWN"

        const val CH_STATUS = "status"
        const val CH_ALARM = "alarm_v1"
        const val CH_INFO = "info"
        const val NOTIF_STATUS = 1
        const val NOTIF_ALARM = 2
        const val NOTIF_BATTERY = 3

        /** Due "0" consecutivi (~4 s) per considerare il seggiolino vuoto. */
        private const val EMPTY_CONFIRMATIONS = 2
        /** Il tappetino trasmette ogni ~2,2 s: oltre questo silenzio la connessione e' persa. */
        private const val DATA_TIMEOUT_MS = 15_000L
        private const val WATCHDOG_MS = 3_000L
        private const val LOW_BATTERY_PCT = 20

        @Volatile
        var status = Status()
            private set

        fun start(c: Context, action: String = ACTION_START) {
            val i = Intent(c, NidoService::class.java).setAction(action)
            c.startForegroundService(i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager
    private lateinit var alarm: AlarmPlayer

    private var gatt: BluetoothGatt? = null
    private var address: String? = null
    private var connected = false
    private var seated = false          // stato "bambino a bordo" (con antirimbalzo)
    private var zeroCount = 0
    private var batteryMv: Int? = null
    private var lastDataElapsed = 0L    // SystemClock.elapsedRealtime()
    private var lastDataWall = 0L
    private var alarmOn = false
    private var alarmReason: String? = null
    private var lowBatteryNotified = false
    private var reconnectDelay = 1_000L

    // ---------------------------------------------------------------- ciclo di vita

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        alarm = AlarmPlayer(this)
        createChannels()
        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        // Se il servizio e' stato riavviato (da Android o dopo un crash) mentre il bambino
        // era a bordo, lo stato viene ripristinato: se il tappetino non risponde entro 30 s, allarme.
        if (Prefs.seated(this)) {
            seated = true
            main.postDelayed({
                if (seated && !connected) startAlarm("Tappetino non raggiungibile con il bambino a bordo")
            }, 30_000)
        }
        persistedSeated = seated
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (!Perms.canConnect(this)) {
            Log.w(TAG, "permesso BLUETOOTH_CONNECT mancante")
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP_ALARM -> stopAlarm(byUser = true)
            ACTION_TEST_ALARM -> startAlarm("Prova allarme")
            ACTION_SHUTDOWN -> {
                shutdown()
                return START_NOT_STICKY
            }
        }
        val saved = Prefs.address(this)
        if (saved == null) {
            publish()
            return START_STICKY
        }
        if (saved != address || gatt == null) {
            address = saved
            connectNow()
        }
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
        publish()
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(btStateReceiver) }
        closeGatt()
        alarm.stop()
        status = Status(running = false)
        super.onDestroy()
    }

    private fun shutdown() {
        stopAlarm(byUser = true)
        Prefs.setSeated(this, false)
        closeGatt()
        address = null
        connected = false
        seated = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- BLE

    private fun adapter(): BluetoothAdapter? =
        getSystemService(BluetoothManager::class.java)?.adapter

    private fun connectNow() {
        main.removeCallbacks(reconnectRunnable)
        closeGatt()
        val addr = address ?: return
        val ad = adapter()
        if (ad == null || !ad.isEnabled) {
            publish()
            return
        }
        val dev: BluetoothDevice = try {
            ad.getRemoteDevice(addr)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "indirizzo non valido $addr")
            return
        }
        Log.i(TAG, "connessione a $addr")
        // autoConnect=true: Android si ricollega da solo appena il tappetino torna a portata
        gatt = dev.connectGatt(this, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun closeGatt() {
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    private fun scheduleReconnect() {
        main.removeCallbacks(reconnectRunnable)
        main.postDelayed(reconnectRunnable, reconnectDelay)
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(15_000L)
    }

    private val reconnectRunnable = Runnable { connectNow() }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            main.post { onConnState(g, st, newState) }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            main.post { onServices(g, st) }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = ch.value?.copyOf()
            main.post { onData(g, v) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            val v = value.copyOf()
            main.post { onData(g, v) }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, st: Int) {
            @Suppress("DEPRECATION") val v = ch.value?.copyOf()
            if (st == BluetoothGatt.GATT_SUCCESS) main.post { onData(g, v) }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, st: Int) {
            val v = value.copyOf()
            if (st == BluetoothGatt.GATT_SUCCESS) main.post { onData(g, v) }
        }
    }

    private fun onConnState(g: BluetoothGatt, st: Int, newState: Int) {
        if (g !== gatt) return
        Log.i(TAG, "stato connessione: status=$st newState=$newState")
        if (newState == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
            connected = true
            reconnectDelay = 1_000L
            // piccola attesa: alcuni telefoni falliscono la discovery se fatta subito
            main.postDelayed({ if (g === gatt) g.discoverServices() }, 600)
        } else {
            val wasConnected = connected
            connected = false
            if (seated) {
                startAlarm(if (wasConnected) "Ti sei allontanato con il bambino a bordo" else "Tappetino non raggiungibile con il bambino a bordo")
            }
            // Dopo un errore (es. 133) il gatt va chiuso e ricreato
            scheduleReconnect()
        }
        publish()
    }

    private fun onServices(g: BluetoothGatt, st: Int) {
        if (g !== gatt) return
        if (st != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "discovery fallita: $st")
            g.disconnect()
            return
        }
        val ch = g.getService(NidoProtocol.SERVICE_UUID)?.getCharacteristic(NidoProtocol.DATA_UUID)
            ?: g.services.flatMap { it.characteristics }.firstOrNull {
                it.uuid == NidoProtocol.DATA_UUID || it.uuid == NidoProtocol.DATA_UUID_ALT
            }
        if (ch == null) {
            Log.e(TAG, "caratteristica dati non trovata")
            g.disconnect()
            return
        }
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(NidoProtocol.CCCD_UUID)
        if (cccd != null) {
            val props = ch.properties
            val value = if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
        Log.i(TAG, "notifiche attivate su ${ch.uuid}")
    }

    private fun onData(g: BluetoothGatt, bytes: ByteArray?) {
        if (g !== gatt) return
        val r = NidoProtocol.parse(bytes) ?: return
        lastDataElapsed = SystemClock.elapsedRealtime()
        lastDataWall = System.currentTimeMillis()
        connected = true

        if (r.seated) {
            seated = true
            zeroCount = 0
        } else {
            zeroCount++
            if (zeroCount >= EMPTY_CONFIRMATIONS) seated = false
        }

        r.batteryMv?.let { mv ->
            batteryMv = mv
            val pct = NidoProtocol.batteryPercent(mv)
            if (pct <= LOW_BATTERY_PCT && !lowBatteryNotified) {
                lowBatteryNotified = true
                notifyLowBattery(pct)
            } else if (pct > LOW_BATTERY_PCT + 10) {
                lowBatteryNotified = false
            }
        }

        // Sono di nuovo vicino al tappetino: come l'app ufficiale, l'allarme si ferma.
        if (alarmOn && alarmReason != TEST_REASON) stopAlarm(byUser = false)
        publish()
    }

    /** Se il tappetino smette di trasmettere pur risultando connesso, la connessione e' persa. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (connected && lastDataElapsed > 0 &&
                SystemClock.elapsedRealtime() - lastDataElapsed > DATA_TIMEOUT_MS
            ) {
                Log.w(TAG, "nessun dato da ${DATA_TIMEOUT_MS / 1000}s: connessione considerata persa")
                connected = false
                if (seated) startAlarm("Nessun segnale dal tappetino con il bambino a bordo")
                connectNow()
                publish()
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    if (seated && !alarmOn) startAlarm("Bluetooth spento con il bambino a bordo")
                    connected = false
                    closeGatt()
                    publish()
                }
                BluetoothAdapter.STATE_ON -> {
                    reconnectDelay = 1_000L
                    connectNow()
                    publish()
                }
            }
        }
    }

    // ---------------------------------------------------------------- allarme

    private val TEST_REASON = "Prova allarme"

    private fun startAlarm(reason: String) {
        if (alarmOn) return
        Log.w(TAG, "ALLARME: $reason")
        alarmOn = true
        alarmReason = reason
        alarm.start()
        nm.notify(NOTIF_ALARM, buildAlarmNotification(reason))
        // Se l'app e' in primo piano o lo schermo e' acceso, prova anche ad aprire la schermata
        runCatching {
            startActivity(
                Intent(this, AlarmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(AlarmActivity.EXTRA_REASON, reason)
            )
        }
        publish()
    }

    private fun stopAlarm(byUser: Boolean) {
        if (!alarmOn) return
        alarmOn = false
        alarmReason = null
        alarm.stop()
        nm.cancel(NOTIF_ALARM)
        // Come l'app ufficiale: confermato l'allarme, il seggiolino e' considerato vuoto
        // finche' il tappetino non segnala di nuovo "1".
        if (byUser) {
            seated = false
            zeroCount = 0
        }
        sendBroadcast(Intent(AlarmActivity.ACTION_CLOSE).setPackage(packageName))
        publish()
    }

    // ---------------------------------------------------------------- notifiche

    private fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Stato tappetino", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Allarme bambino a bordo", NotificationManager.IMPORTANCE_HIGH).apply {
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)      // il suono lo gestisce AlarmPlayer, sul canale sveglia
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_INFO, "Avvisi (batteria)", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun goForeground() {
        val n = buildStatusNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_STATUS, n)
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun statusText(): String {
        val ad = adapter()
        return when {
            Prefs.address(this) == null -> "Nessun tappetino abbinato"
            ad == null || !ad.isEnabled -> "Bluetooth spento: il tappetino NON e' sorvegliato"
            !connected -> "In attesa del tappetino…"
            else -> buildString {
                append(if (seated) "Bambino a bordo" else "Seggiolino vuoto")
                batteryMv?.let { append(" · batteria ${NidoProtocol.batteryPercent(it)}%") }
            }
        }
    }

    private fun buildStatusNotification(): Notification =
        Notification.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun buildAlarmNotification(reason: String): Notification {
        val full = PendingIntent.getActivity(
            this, 1,
            Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AlarmActivity.EXTRA_REASON, reason),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, NidoService::class.java).setAction(ACTION_STOP_ALARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("BAMBINO A BORDO!")
            .setContentText(reason)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(Notification.Action.Builder(null, "Ho il bambino, ferma", stop).build())
            .build()
    }

    private fun notifyLowBattery(pct: Int) {
        nm.notify(
            NOTIF_BATTERY,
            Notification.Builder(this, CH_INFO)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Batteria del tappetino in esaurimento")
                .setContentText("Livello stimato: $pct%. Sostituisci la batteria.")
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
        )
    }

    private var persistedSeated: Boolean? = null

    private fun publish() {
        if (persistedSeated != seated) {
            persistedSeated = seated
            Prefs.setSeated(this, seated)
        }
        val ad = adapter()
        status = Status(
            running = true,
            bluetoothOn = ad?.isEnabled == true,
            connected = connected,
            receiving = connected && lastDataElapsed > 0 &&
                SystemClock.elapsedRealtime() - lastDataElapsed < DATA_TIMEOUT_MS,
            seated = seated,
            batteryMv = batteryMv,
            lastDataAt = lastDataWall,
            alarm = alarmOn,
            alarmReason = alarmReason,
        )
        if (Prefs.address(this) != null || status.running) {
            nm.notify(NOTIF_STATUS, buildStatusNotification())
        }
    }
}
