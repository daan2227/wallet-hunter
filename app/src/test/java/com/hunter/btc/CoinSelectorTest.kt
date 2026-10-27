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

class CoinSelectorBnbTest {
    private fun coins(vararg v: Long) = v.mapIndexed { i, x -> JSONObject().put("value", x).put("txid", "t$i").put("vout", 0) }
    private fun vs(n: Int) = 68 * n + 31 + 11

    @Test fun encuentraUnaCombinacionSinCambio() {
        // Con tasa 10: dos monedas de 40 000 y 30 000 pagan 60 000 + comisión de 2 entradas, sin cambio.
        val monto = 70_000L - 10 * vs(2)
        val p = CoinSelector.select(coins(45_000, 40_000, 30_000, 9_000), monto, 10, 68, 31, 31)!!
        assertEquals(0L, p.changeSat)
        assertEquals(2, p.chosen.size)
        assertEquals(70_000L - monto, p.feeSat)
    }

    @Test fun sinSolucionExactaSigueLaPasadaDeSiempre() {
        val p = CoinSelector.select(coins(50_000, 50_000), 80_000, 10, 68, 31, 31)!!
        assertTrue(p.changeSat > 0)
    }

    /** Contra fuerza bruta: si existe un subconjunto sin cambio de 2+ monedas, lo encuentra; si lo da, es válido. */
    @Test fun aleatorioContraFuerzaBruta() {
        val r = java.util.Random(7)
        repeat(3000) {
            val n = 2 + r.nextInt(9)
            val vals = LongArray(n) { 600L + r.nextInt(60_000) }
            val c = vals.mapIndexed { i, x -> JSONObject().put("value", x).put("txid", "t$i").put("vout", 0) }
            val tasa = 1L + r.nextInt(20)
            val monto = 1_000L + r.nextInt(80_000)
            val polvo = CoinSelector.DUST
            val coste = 68 * tasa
            val utiles = vals.filter { it > coste }
            var existe = false
            for (mask in 0 until (1 shl utiles.size)) {
                val k = Integer.bitCount(mask); if (k < 2) continue
                val suma = utiles.indices.filter { mask shr it and 1 == 1 }.sumOf { utiles[it] }
                val sobra = suma - (monto + tasa * vs(k))
                if (sobra in 0..polvo) { existe = true; break }
            }
            val p = CoinSelector.sinCambioExacto(c, monto, tasa, 68, 31, polvo)
            assertEquals("n=$n monto=$monto tasa=$tasa vals=${vals.toList()}", existe, p != null)
            if (p != null) {
                val suma = p.chosen.sumOf { it.getLong("value") }
                assertTrue(p.chosen.size >= 2 && p.chosen.toSet().size == p.chosen.size)
                assertEquals(0L, p.changeSat)
                val sobra = suma - (monto + tasa * vs(p.chosen.size))
                assertTrue(sobra in 0..polvo)
                assertEquals(suma - monto, p.feeSat)
            }
        }
    }

    @Test fun conMuchasMonedasTerminaRapido() {
        val r = java.util.Random(3)
        val c = (0 until 400).map { JSONObject().put("value", 5_000L + r.nextInt(500_000)).put("txid", "t$it").put("vout", 0) }
        val t = System.nanoTime()
        CoinSelector.sinCambioExacto(c, 1_234_567L, 5, 68, 31, 546)
        assertTrue((System.nanoTime() - t) / 1_000_000 < 3000)
    }
}
