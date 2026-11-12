package it.nido.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NidoProtocolTest {
    private fun hex(s: String) = s.split("-").map { it.toInt(16).toByte() }.toByteArray()

    @Test fun pacchettiRealiDalLog() {
        // valori catturati con nRF Connect dal tappetino Nido_ABCD
        assertEquals(NidoProtocol.Reading(false, 2900), NidoProtocol.parse(hex("30-2C-32-39-30-30-0A")))
        assertEquals(NidoProtocol.Reading(true, 2900), NidoProtocol.parse(hex("31-2C-32-39-30-30-0A")))
    }

    @Test fun formatoConDollaro() {
        assertEquals(NidoProtocol.Reading(true, 3050), NidoProtocol.parse("$,1,3050".toByteArray()))
    }

    @Test fun pacchettiNonValidi() {
        assertNull(NidoProtocol.parse(ByteArray(0)))
        assertNull(NidoProtocol.parse("x,1".toByteArray()))
        assertEquals(NidoProtocol.Reading(true, null), NidoProtocol.parse("1".toByteArray()))
    }

    @Test fun batteria() {
        assertEquals(70, NidoProtocol.batteryPercent(2900))
        assertEquals(100, NidoProtocol.batteryPercent(3200))
        assertEquals(20, NidoProtocol.batteryPercent(2302))
        assertEquals(0, NidoProtocol.batteryPercent(2200))
    }

    @Test fun nomi() {
        assertEquals(true, NidoProtocol.isNidoName("Nido_ABCD"))
        assertEquals(false, NidoProtocol.isNidoName("NidoPatch_1234"))
        assertEquals(false, NidoProtocol.isNidoName("TY"))
    }
}
