package com.hunter.btc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MonedasCongeladasTest {
    private fun u(txid: String, vout: Int, sat: Long) = JSONObject().put("txid", txid).put("vout", vout).put("value", sat)

    @Test fun elEnvioAutomaticoNoUsaLasCongeladas() {
        val l = listOf(u("AA", 0, 50_000), u("bb", 1, 20_000), u("cc", 0, 700))
        assertEquals(listOf("bb"), MonedasCongeladas.utilizables(l, setOf("aa:0", "cc:0")).map { it.optString("txid") })
        assertEquals(3, MonedasCongeladas.utilizables(l, emptySet()).size)
    }

    @Test fun polvoSoloLaPrimeraVez() {
        val l = listOf(u("aa", 0, 546), u("bb", 0, 1_000), u("cc", 0, 1_001), u("dd", 2, 0))
        assertEquals(listOf("aa:0", "bb:0"), MonedasCongeladas.polvoNuevo(l, emptySet()))
        // Ya vista (p. ej. el usuario la descongeló): no se vuelve a congelar.
        assertEquals(listOf("bb:0"), MonedasCongeladas.polvoNuevo(l, setOf("aa:0")))
    }
}
