package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiarioTest {
    private fun op(t: Long, pnl: Double, cerrada: Boolean = true, nota: String = "") =
        Simulador.Operacion(t, Simulador.Mercado.FUT, "BTC", "Long 10x", 100.0, if (cerrada) 101.0 else 0.0, 1.0, pnl, 0.05, "Close", nota)

    @Test fun metricasBasicas() {
        // +100, −50, −30, +20, +10 → pico 100, valle 20 → drawdown 80
        val ops = listOf(op(1, 100.0), op(2, -50.0), op(3, -30.0), op(4, 20.0), op(5, 10.0), op(6, 0.0, cerrada = false))
        val m = Diario.metricas(ops)
        assertEquals(5, m.operaciones); assertEquals(3, m.ganadoras); assertEquals(2, m.perdedoras)
        assertEquals(60.0, m.tasaAcierto, 1e-9)
        assertEquals(130.0 / 80.0, m.factorBeneficio, 1e-9)
        assertEquals(130.0 / 3, m.mediaGanadora, 1e-9); assertEquals(-40.0, m.mediaPerdedora, 1e-9)
        assertEquals(10.0, m.esperanza, 1e-9)
        assertEquals(80.0, m.maxDrawdown, 1e-9)
        assertEquals(2, m.rachaGanadora); assertEquals(2, m.rachaPerdedora)
    }

    @Test fun sinPerdidasFactorInfinito() {
        assertTrue(Diario.metricas(listOf(op(1, 5.0))).factorBeneficio.isInfinite())
        assertEquals(0, Diario.metricas(emptyList()).operaciones)
    }

    @Test fun pnlPorDiaSuma() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val d = Diario.pnlPorDia(listOf(op(86_400L * 20_000 + 10, 5.0), op(86_400L * 20_000 + 500, -2.0), op(86_400L * 20_001, 7.0)), utc)
        assertEquals(2, d.size)
        assertEquals(3.0, d.values.minOrNull()!!, 1e-9); assertEquals(7.0, d.values.maxOrNull()!!, 1e-9)
    }

    @Test fun csvEscapaYTieneCabeceras() {
        val s = Simulador()
        s.abrirFuturo("B", "", "BTC", true, 100.0, 10, 100.0)
        s.historial.last().nota = "breakout, \"clean\" #setupA"
        val csv = Diario.csv(s)
        assertTrue(csv.startsWith("time_utc,market,symbol,side,entry,exit,quantity,pnl_vusd,fee_vusd,reason,note\n"))
        assertTrue(csv.contains("\"breakout, \"\"clean\"\" #setupA\""))
        assertTrue(csv.contains("time_utc,movement,account,amount_vusd,note"))
        assertTrue(csv.contains("Welcome test funds"))
    }

    @Test fun notaSeGuardaEnJson() {
        val s = Simulador()
        s.comprarSpot("E", "", "ETH", 100.0, 50.0)
        s.historial.last().nota = "#dca"
        assertEquals("#dca", Simulador.deJson(s.aJson()).historial.last().nota)
    }
}
