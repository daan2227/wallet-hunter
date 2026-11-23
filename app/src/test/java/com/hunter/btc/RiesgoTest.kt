package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RiesgoTest {
    private val eps = 1e-6
    private val T0 = 1_700_000_000L - 1_700_000_000L % (8 * 3600)

    private fun cuenta() = Simulador().apply {
        assertNull(abrirFuturo("B", "", "BTC", true, 100.0, 10, 100.0, sl = 95.0, t = T0))   // 10 BTC
        assertNull(abrirFuturo("E", "", "ETH", false, 50.0, 2, 10.0, t = T0))                 // 10 ETH corto, sin stop
        procesar("B", 100.0, 100.0, 100.0, T0); procesar("E", 10.0, 10.0, 10.0, T0)
    }

    @Test fun exposicion() {
        val e = Riesgo.exposicion(cuenta())
        assertEquals(1000.0, e.larga, eps); assertEquals(100.0, e.corta, eps)
        assertEquals(900.0, e.neta, eps); assertEquals(1100.0, e.bruta, eps)
    }

    @Test fun peorCaso() {
        val pc = Riesgo.peorCaso(cuenta())
        // BTC: (95−100)·10 − comisión 95·10·0,05 % = −50,475 ; ETH sin stop: se pierde el margen (50)
        assertEquals(-50.475 - 50.0, pc.perdida, eps)
        assertEquals(1, pc.sinStop)
    }

    @Test fun repartoSumaElPatrimonio() {
        val s = cuenta()
        val r = Riesgo.reparto(s)
        assertEquals(s.patrimonio(), r.sumOf { it.valor }, eps)
        assertEquals("VUSD cash", r.first().nombre)
    }

    @Test fun correlacion() {
        val a = doubleArrayOf(100.0, 102.0, 101.0, 105.0, 104.0, 108.0, 107.0)
        assertEquals(1.0, Riesgo.correlacion(a, a.map { it * 3 }.toDoubleArray())!!, 1e-9)
        val inv = DoubleArray(a.size - 1) { a[it + 1] / a[it] - 1 }.let { r ->
            val out = DoubleArray(a.size); out[0] = 50.0; for (i in r.indices) out[i + 1] = out[i] * (1 - r[i]); out }
        assertEquals(-1.0, Riesgo.correlacion(a, inv)!!, 1e-9)
        assertNull(Riesgo.correlacion(doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0, 2.0)))
        assertTrue(Riesgo.correlacion(a, DoubleArray(7) { 5.0 }) == null)              // sin variación
    }
}
