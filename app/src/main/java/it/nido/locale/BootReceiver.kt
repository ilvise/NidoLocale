package it.nido.locale

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Riavvia la sorveglianza dopo il riavvio del telefono o un aggiornamento dell'app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.address(c) != null && Perms.canConnect(c)) {
            runCatching { NidoService.start(c) }
        }
    }
}
