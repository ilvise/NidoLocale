package it.nido.locale

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/** Schermata a tutto schermo dell'allarme, visibile anche a telefono bloccato. */
class AlarmActivity : Activity() {
    companion object {
        const val EXTRA_REASON = "reason"
        const val ACTION_CLOSE = "it.nido.locale.CLOSE_ALARM"
    }

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)

        setContentView(R.layout.activity_alarm)
        showReason(intent)
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            NidoService.start(this, NidoService.ACTION_STOP_ALARM)
            finish()
        }
        val f = IntentFilter(ACTION_CLOSE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(closeReceiver, f, RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(closeReceiver, f)
        if (!NidoService.status.alarm) finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showReason(intent)
    }

    private fun showReason(i: Intent?) {
        findViewById<TextView>(R.id.txtReason).text =
            i?.getStringExtra(EXTRA_REASON) ?: NidoService.status.alarmReason ?: ""
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(closeReceiver) }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // il tasto indietro non ferma l'allarme: serve il pulsante
    }
}
