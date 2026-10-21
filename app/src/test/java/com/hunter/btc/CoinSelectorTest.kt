package com.hunter.btc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoinSelectorTest {
    private fun coins(vararg v: Long) = v.mapIndexed { i, x -> JSONObject().put("value", x).put("txid", "t$i").put("vout", 0) }

    // P2WPKH: entrada 68 vB, salida 31 vB, cambio 31 vB.
    private fun elegir(c: List<JSONObject>, monto: Long, tasa: Long = 10) =
        CoinSelector.select(c, monto, tasa, 68, 31, 31)

    @Test fun unaMonedaQueAlcanza() {
        val p = elegir(coins(100_000), 50_000)!!
        assertEquals(1, p.chosen.size)
        assertTrue(p.changeSat > 0)
        assertEquals(100_000L, p.feeSat + p.changeSat + 50_000)
    }

    /** "All" con dos monedas: el máximo sin cambio tiene que poder enviarse. */
    @Test fun maximoSinCambioConDosMonedas() {
        val c = coins(30_000, 30_000)
        val vsSinCambio = 68 * 2 + 31 + 11
        val maximo = 60_000L - 10 * vsSinCambio
        val p = elegir(c, maximo)
        assertNotNull("el máximo sin cambio debe poder enviarse", p)
        assertEquals(2, p!!.chosen.size)
        assertEquals(0L, p.changeSat)
        assertEquals(60_000L, p.feeSat + maximo)
    }

    @Test fun unSatMasQueElMaximoNoAlcanza() {
        val c = coins(30_000, 30_000)
        val vsSinCambio = 68 * 2 + 31 + 11
        assertNull(elegir(c, 60_000L - 10 * vsSinCambio + 1))
    }

    @Test fun conCambioSiHaceFalta() {
        val p = elegir(coins(40_000, 40_000, 40_000), 70_000)!!
        assertEquals(2, p.chosen.size)
        assertTrue(p.changeSat > CoinSelector.DUST)
    }

    @Test fun sinMonedasONiImporte() {
        assertNull(elegir(emptyList(), 1000))
        assertNull(elegir(coins(1000), 0))
        assertNull(elegir(coins(1000), 5000))
    }
}
