package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsolidacionTest {
    // P2WPKH: entrada 68 vB, salida 31 vB.
    @Test fun diezMonedasABajoCoste() {
        val p = Consolidacion.evaluar(List(10) { 50_000L }, tasa = 2, vbEntrada = 68, vbSalida = 31)!!
        assertEquals(10, p.usar.size); assertEquals(68L * 10 + 31 + 11, p.vsize)
        assertEquals(2L * p.vsize, p.comision); assertEquals(500_000L - p.comision, p.recibe)
        // Luego, a 20 sat/vB, 9 entradas menos = 9·68·20 = 12 240, menos lo de ahora (1 444).
        assertEquals(9L * 68 * 20 - p.comision, p.ahorroLuego); assertTrue(p.compensa)
    }

    @Test fun conLaRedCaraNoCompensa() {
        val p = Consolidacion.evaluar(List(3) { 500_000L }, tasa = 40, vbEntrada = 68, vbSalida = 31)!!
        assertTrue(!p.compensa && p.ahorroLuego < 0)
    }

    @Test fun lasQueNoValenSuComisionSeDejan() {
        val p = Consolidacion.evaluar(listOf(100_000, 90_000, 200, 400), tasa = 3, vbEntrada = 68, vbSalida = 31)!!
        assertEquals(listOf(100_000L, 90_000L), p.usar); assertEquals(listOf(200L, 400L), p.dejar)   // a 3 sat/vB, gastar una cuesta 204 sat: se deja lo que no vale el doble
        assertNull(Consolidacion.evaluar(listOf(100_000, 300), 3, 68, 31))          // queda una sola
    }
}
