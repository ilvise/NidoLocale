package it.nido.locale

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.UUID

/** Protocollo del tappetino Nido (ricavato dall'app ufficiale 2.2.74). */
object NidoProtocol {
    /** Servizio "Transparent UART" Microchip/ISSC. */
    val SERVICE_UUID: UUID = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")
    /** Caratteristica da cui arrivano i dati (notify/indicate). */
    val DATA_UUID: UUID = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")
    /** Variante Telink usata da alcuni firmware, accettata anche dall'app ufficiale. */
    val DATA_UUID_ALT: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d2b10")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Filtro sul nome usato dall'app ufficiale per riconoscere il tappetino. */
    fun isNidoName(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return n.contains("nido_") && !n.contains("nidopatch")
    }

    data class Reading(val seated: Boolean, val batteryMv: Int?)

    /**
     * Pacchetto ASCII: "<seduto>,<batteria_mV>\n", eventualmente preceduto da "$,".
     * Esempi: "1,2900\n" = bambino seduto, "0,2900\n" = vuoto.
     */
    fun parse(bytes: ByteArray?): Reading? {
        if (bytes == null || bytes.isEmpty()) return null
        val text = String(bytes, Charsets.US_ASCII).replace("$,", "").trim()
        val parts = text.split(",")
        if (parts.isEmpty() || parts[0].isBlank()) return null
        val flag = parts[0].trim()
        if (flag != "0" && flag != "1") return null
        val mv = parts.getOrNull(1)?.trim()?.toIntOrNull()
        return Reading(flag == "1", mv)
    }

    /** Stessa tabella dell'app ufficiale. */
    fun batteryPercent(mv: Int): Int = when {
        mv >= 3102 -> 100
        mv >= 3002 -> 90
        mv >= 2902 -> 80
        mv >= 2802 -> 70
        mv >= 2702 -> 60
        mv >= 2602 -> 50
        mv >= 2502 -> 40
        mv >= 2402 -> 30
        mv >= 2302 -> 20
        else -> 0
    }
}

object Prefs {
    private const val FILE = "nido"
    private const val KEY_ADDR = "address"
    private const val KEY_NAME = "name"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun address(c: Context): String? = sp(c).getString(KEY_ADDR, null)
    fun name(c: Context): String? = sp(c).getString(KEY_NAME, null)

    fun save(c: Context, address: String, name: String?) =
        sp(c).edit().putString(KEY_ADDR, address).putString(KEY_NAME, name).apply()

    fun clear(c: Context) = sp(c).edit().clear().apply()

    /** Ultimo stato "bambino a bordo", per sopravvivere a un riavvio del servizio. */
    fun seated(c: Context): Boolean = sp(c).getBoolean("seated", false)
    fun setSeated(c: Context, v: Boolean) = sp(c).edit().putBoolean("seated", v).apply()
}

object Perms {
    fun required(): Array<String> {
        val l = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            l += Manifest.permission.BLUETOOTH_SCAN
            l += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            l += Manifest.permission.ACCESS_COARSE_LOCATION
            l += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
        return l.toTypedArray()
    }

    fun canConnect(c: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun missing(c: Context): List<String> =
        required().filter { c.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}
