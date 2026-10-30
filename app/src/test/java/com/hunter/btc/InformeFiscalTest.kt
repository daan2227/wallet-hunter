package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InformeFiscalTest {
    private val ENE23 = 1_673_000_000L; private val JUN23 = 1_686_000_000L; private val ENE24 = 1_705_000_000L

    @Test fun fifoConDosLotes() {
        val r = InformeFiscal.calcular(listOf(
            InformeFiscal.Mov(JUN23, "b", 50_000_000, 0, 30_000.0),     // 0,5 BTC a 30 000
            InformeFiscal.Mov(ENE23, "a", 100_000_000, 0, 20_000.0),    // 1 BTC a 20 000 (va primero aunque llegue después)
            InformeFiscal.Mov(ENE24, "c", -120_010_000, 10_000, 40_000.0)))
        val v = r.ventas.single()
        // Sale 1,2001 BTC: 1 del lote de enero (20 000) y 0,2001 del de junio (6 003). Se cobra 1,2 · 40 000.
        assertEquals(48_000.0, v.ingreso, 1e-6); assertEquals(26_003.0, v.coste, 1e-6); assertEquals(21_997.0, v.ganancia, 1e-6)
        assertEquals(0L, v.sinCoste); assertEquals(29_990_000L, r.quedan.single().sats)
        assertEquals(listOf(2023, 2024), r.anos()); assertEquals(0.0, r.ganancia(2023), 0.0); assertEquals(21_997.0, r.ganancia(2024), 1e-6)
    }

    @Test fun ventaSinLoteConocido() {
        val r = InformeFiscal.calcular(listOf(InformeFiscal.Mov(JUN23, "x", 10_000_000, 0, 30_000.0),
            InformeFiscal.Mov(ENE24, "y", -30_000_000, 0, 40_000.0)))
        val v = r.ventas.single(); assertEquals(20_000_000L, v.sinCoste); assertEquals(3_000.0, v.coste, 1e-6)
        assertEquals(20_000_000L, r.sinCoste); assertTrue(r.quedan.isEmpty())
        val csv = InformeFiscal.csv(r, "EUR")
        assertTrue(csv.contains("2024-01-11,disposal,y,0.30000000,12000.00,3000.00,9000.00,0.20000000"))
        assertTrue(csv.contains("2023-06-05,acquisition,x,0.10000000,,3000.00,,"))
        assertTrue(csv.contains("\n2024,9000.00\n"))
    }
}
