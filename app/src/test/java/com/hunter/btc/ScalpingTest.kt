package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalpingTest {
    private val eps = 1e-6
    private val T0 = 1_700_000_000L - 1_700_000_000L % 86_400          // medianoche UTC

    private fun sube(n: Int = 80): List<GraficoPrecio.Vela> {
        val out = ArrayList<GraficoPrecio.Vela>(); var p = 100f
        for (i in 0 until n) { val c = p * 1.001f; out.add(GraficoPrecio.Vela(T0 + i * 60L, p, c * 1.0005f, p * 0.9995f, c, 10f)); p = c }
        return out
    }

    @Test fun desequilibrioYDelta() {
        val l = Mercado.Libro(listOf(100.0 to 3.0, 99.0 to 1.0, 90.0 to 50.0), listOf(100.2 to 1.0, 101.0 to 1.0), "t")
        // ±0,5 % de 100,1: bids solo 100 (300), asks solo 100,2 (99 y 101 quedan fuera)
        assertEquals((300.0 - 100.2) / (300.0 + 100.2), Scalping.desequilibrio(l)!!, eps)
        assertNull(Scalping.desequilibrio(Mercado.Libro(emptyList(), emptyList(), "t")))
        val tr = listOf(Mercado.Trade(0, 100.0, 3.0, true), Mercado.Trade(0, 100.0, 1.0, false))
        assertEquals(0.5, Scalping.delta(tr)!!, eps)
        assertNull(Scalping.delta(emptyList()))
    }

    @Test fun vwapDeSesionSeReiniciaCadaDia() {
        val t = longArrayOf(T0, T0 + 60, T0 + 86_400)
        val h = floatArrayOf(10f, 20f, 50f); val l = floatArrayOf(10f, 20f, 50f); val c = floatArrayOf(10f, 20f, 50f)
        val v = floatArrayOf(1f, 3f, 2f)
        val r = Indicadores.vwapSesion(t, h, l, c, v)
        assertEquals(17.5f, r.media[1], 1e-4f)                      // (10 + 60) / 4
        assertEquals(50f, r.media[2], 1e-4f)                        // día nuevo
        assertEquals(Math.sqrt(18.75).toFloat(), r.desv[1], 1e-3f)        // (100·1 + 400·3)/4 − 17,5²
    }

    @Test fun senalAlcista() {
        val s = Scalping.senal(sube(), libro = 0.3)
        assertNotNull(s); s!!
        assertEquals(1, s.dir); assertTrue(s.confianza >= 76)
        val p = sube().last().c.toDouble()
        assertTrue(s.stop < p && s.objetivo > p)
        assertEquals(1.5, (s.objetivo - p) / (p - s.stop), 1e-6)
        assertTrue(p - s.stop >= p * 0.0015 - 1e-9)
        assertEquals(0, Scalping.senal(sube().take(30)) ?.dir ?: 0)
    }

    @Test fun limiteDiarioBloquea() {
        val s = Simulador(); s.limiteDia = 50.0
        val t = T0 + 3600
        assertNull(s.abrirFuturo("B", "", "BTC", true, 100.0, 10, 100.0, t = t))
        s.cerrarFuturo(s.posiciones[0].id, 94.0, t = t + 60)                  // −60 − comisiones
        assertTrue(s.resultadoHoy(t + 120) < -50)
        assertTrue(s.bloqueadoHoy(t + 120))
        assertTrue(s.abrirFuturo("B", "", "BTC", true, 100.0, 10, 100.0, t = t + 120)!!.startsWith("Daily loss limit"))
        assertTrue(!s.bloqueadoHoy(T0 + 86_400 + 10))                         // día nuevo
        assertEquals(50.0, Simulador.deJson(s.aJson()).limiteDia, eps)
        val ses = Scalping.sesion(s.historial, T0, t + 120)
        assertEquals(1, ses.operaciones); assertEquals(0, ses.ganadas); assertEquals(s.resultadoHoy(t + 120), ses.resultado, eps)
    }

    @Test fun botScalpBreakEven() {
        val s = Simulador()
        assertNull(s.crearBotIA("B", "", "BTC", "1m", 100.0, 20, 60, T0))
        val b = s.bots[0]
        s.senalIA(b.id, 1, 80, 99.0, 101.5, 20, 100.0, T0, T0)
        val p = s.posiciones.single(); assertEquals(99.0, p.sl, eps)
        s.procesar("B", 100.5, 101.1, 101.0, T0 + 60)                        // +1R: stop a break-even
        assertEquals(100.0 * (1 + 2 * Simulador.COM_TAKER), p.sl, eps)
        assertTrue(s.posiciones.isNotEmpty())
    }

    // ── TP/SL del scalping: cálculo y cierre ──
    private fun vela(t: Long, o: Double, h: Double, l: Double, c: Double) = GraficoPrecio.Vela(t, o.toFloat(), h.toFloat(), l.toFloat(), c.toFloat(), 1f)

    @Test fun tpYSlEnPorcentajeDelPrecio() {
        val s = Simulador()
        val (tpL, slL) = s.nivelesPct(100.0, true, 0.4, 0.25)
        assertEquals(100.4, tpL, eps); assertEquals(99.75, slL, eps)
        val (tpS, slS) = s.nivelesPct(100.0, false, 0.4, 0.25)
        assertEquals(99.6, tpS, eps); assertEquals(100.25, slS, eps)
        assertEquals(0.0 to 0.0, s.nivelesPct(100.0, true, 0.0, 0.0))
    }

    @Test fun cierraJustoEnElTp() {
        val s = Simulador(); val t = T0 + 600
        assertNull(s.abrirConPct("B", "", "BTC", true, 100.0, 20, 100.0, 0.4, 0.25, t))
        val p = s.posiciones.single(); assertEquals(100.4, p.tp, eps); assertEquals(99.75, p.sl, eps)
        s.procesar("B", 99.9, 100.39, 100.2, t + 10)                     // se queda a 0,01: sigue abierta
        assertEquals(1, s.posiciones.size)
        s.procesar("B", 100.1, 100.41, 100.3, t + 20)                    // toca el TP
        assertTrue(s.posiciones.isEmpty())
        val h = s.historial.last()
        assertEquals("Take-profit", h.motivo); assertEquals(100.4, h.salida, eps)
        // 20 unidades × 0,4 = +8 de PnL; comisión de cierre 100,4 × 20 × 0,05 %
        assertEquals(8.0 - 100.4 * 20 * Simulador.COM_TAKER, h.pnl, eps)
    }

    @Test fun cortoCierraEnTpYLargoEnSl() {
        val s = Simulador(); val t = T0 + 600
        s.abrirConPct("B", "", "BTC", false, 100.0, 20, 100.0, 0.4, 0.25, t)
        s.procesar("B", 99.59, 99.8, 99.7, t + 10)
        assertEquals("Take-profit", s.historial.last().motivo); assertEquals(99.6, s.historial.last().salida, eps)
        s.abrirConPct("E", "", "ETH", true, 100.0, 20, 100.0, 0.4, 0.25, t)
        s.procesar("E", 99.7, 100.1, 99.9, t + 10)
        assertEquals("Stop-loss", s.historial.last().motivo); assertEquals(99.75, s.historial.last().salida, eps)
    }

    @Test fun alSumarseRecalculaSobreLaEntradaMedia() {
        val s = Simulador(); val t = T0 + 600
        s.abrirConPct("B", "", "BTC", true, 100.0, 20, 100.0, 0.4, 0.25, t)
        s.abrirConPct("B", "", "BTC", true, 100.0, 20, 102.0, 0.4, 0.25, t + 5)
        val p = s.posiciones.single()
        assertEquals(4000.0 / (20.0 + 2000.0 / 102.0), p.entrada, eps)           // entrada media
        assertEquals(p.entrada * 1.004, p.tp, eps); assertEquals(p.entrada * 0.9975, p.sl, eps)
    }

    @Test fun noSePierdeUnToqueDentroDelMinutoEntreRevisiones() {
        val s = Simulador(); val t = T0 + 600                             // 00:10:00
        s.abrirConPct("B", "", "BTC", true, 100.0, 20, 100.0, 0.4, 0.25, t - 120)
        // revisión a las 00:10:30 con la vela en curso aún tranquila
        s.procesarVelas("B", listOf(vela(t, 100.0, 100.1, 99.9, 100.0)), t + 30)
        assertEquals(1, s.posiciones.size)
        // a las 00:10:40 sube al TP y vuelve; la siguiente revisión (00:10:50) ve el máximo de la vela
        s.procesarVelas("B", listOf(vela(t, 100.0, 100.5, 99.9, 100.1)), t + 50)
        assertTrue(s.posiciones.isEmpty())
        assertEquals("Take-profit", s.historial.last().motivo)
    }

    // ── escalones, trailing, límites, riesgo, reglas y métricas ──
    @Test fun tpEscalonadoYBreakEven() {
        val s = Simulador(); val t = T0 + 600
        val plan = Simulador.Plan(0.6, 0.3, tp1Pct = 0.2, tp1Frac = 0.5, tp2Pct = 0.4, tp2Frac = 0.3, be = true)
        assertNull(s.abrirConPlan("B", "", "BTC", true, 100.0, 20, 100.0, plan, t))       // 20 unidades
        val p = s.posiciones.single()
        assertEquals(2, p.escalones.size); assertEquals(100.2, p.escalones[0][0], eps); assertEquals(10.0, p.escalones[0][1], eps)
        s.procesar("B", 100.0, 100.25, 100.2, t + 10)                                        // TP1: cierra la mitad
        assertEquals(10.0, p.qty, eps); assertEquals("TP partial", s.historial.last().motivo)
        assertEquals(100.0 * (1 + 2 * Simulador.COM_TAKER), p.sl, eps)                       // stop a break-even
        s.procesar("B", 100.2, 100.45, 100.4, t + 20)                                        // TP2: 6 más
        assertEquals(4.0, p.qty, eps)
        s.procesar("B", 100.3, 100.65, 100.6, t + 30)                                        // TP final: el resto
        assertTrue(s.posiciones.isEmpty()); assertEquals("Take-profit", s.historial.last().motivo)
    }

    @Test fun trailingSigueAlPrecio() {
        val s = Simulador(); val t = T0 + 600
        s.abrirConPlan("B", "", "BTC", true, 100.0, 10, 100.0, Simulador.Plan(0.0, 1.0, trailPct = 0.5), t)
        val p = s.posiciones.single(); assertEquals(99.0, p.sl, eps)
        s.procesar("B", 100.0, 100.3, 100.2, t + 10); assertEquals(99.0, p.sl, eps)           // aún no se activa
        s.procesar("B", 100.2, 101.0, 100.9, t + 20); assertEquals(101.0 * 0.995, p.sl, eps) // se activa y sube
        s.procesar("B", 100.6, 100.9, 100.7, t + 30); assertEquals(101.0 * 0.995, p.sl, eps) // no baja
        s.procesar("B", 100.4, 100.6, 100.4, t + 40)
        assertTrue(s.posiciones.isEmpty())
        assertEquals("Trailing stop", s.historial.last().motivo); assertEquals(100.495, s.historial.last().salida, eps)
    }

    @Test fun limiteConPlanCaducaOSeLlena() {
        val s = Simulador(); val t = T0 + 600
        assertNull(s.limiteConPlan("B", "", "BTC", true, 100.0, 20, 99.9, Simulador.Plan(0.4, 0.25, trailPct = 0.2), 30, t))
        val reservado = s.ordenes.single().reservado
        assertEquals(100.0 + 100.0 * 20 * Simulador.COM_MAKER, reservado, eps)
        s.procesar("B", 99.95, 100.1, 100.0, t + 40)                                        // no llega y caduca
        assertTrue(s.ordenes.isEmpty()); assertEquals(Simulador.BIENVENIDA, s.fut, eps)
        s.limiteConPlan("B", "", "BTC", true, 100.0, 20, 99.9, Simulador.Plan(0.4, 0.25, trailPct = 0.2), 30, t + 50)
        s.procesar("B", 99.85, 100.0, 99.9, t + 60)                                         // se llena: plan aplicado
        val p = s.posiciones.single()
        assertEquals(99.9 * 1.004, p.tp, eps); assertEquals(99.9 * 0.9975, p.sl, eps); assertEquals(0.2, p.trailPct, eps)
    }

    @Test fun tamanoPorRiesgo() {
        val m = ScalpUtil.margenPorRiesgo(10.0, 0.25, 20)
        val noc = m * 20
        assertEquals(10.0, noc * 0.0025 + noc * 2 * Simulador.COM_TAKER, eps)               // pierde justo 10 en el SL
        assertEquals(0.0, ScalpUtil.margenPorRiesgo(10.0, 0.0, 20), eps)
    }

    @Test fun reglasDeRiesgo() {
        val t = T0 + 3600
        val s = Simulador().apply { maxOpsDia = 2 }
        s.abrirFuturo("B", "", "BTC", true, 10.0, 10, 100.0, t = t); s.abrirFuturo("E", "", "ETH", true, 10.0, 10, 100.0, t = t)
        assertTrue(s.abrirFuturo("S", "", "SOL", true, 10.0, 10, 100.0, t = t)!!.startsWith("Max 2 trades"))
        assertTrue(s.nuevaOrden(Simulador.Mercado.FUT, "S", "", "SOL", true, Simulador.TipoOrden.LIMIT, 99.0, 0.0, 0.0, 10.0, 0.0, 10, 0.0, 0.0, t = t)!!.startsWith("Max 2"))
        val r = Simulador().apply { perdidasSeguidas = 2; pausaMin = 30 }
        repeat(2) { r.abrirFuturo("B", "", "BTC", true, 10.0, 10, 100.0, t = t); r.cerrarFuturo(r.posiciones[0].id, 99.0, t = t + 60) }
        assertTrue(r.motivoBloqueo(t + 120)!!.contains("losses in a row"))
        assertNull(r.motivoBloqueo(t + 60 + 30 * 60 + 1))
        val o = Simulador().apply { objetivoDia = 5.0 }
        o.abrirFuturo("B", "", "BTC", true, 100.0, 10, 100.0, t = t); o.cerrarFuturo(o.posiciones[0].id, 101.0, t = t + 60)
        assertTrue(o.motivoBloqueo(t + 120)!!.startsWith("Daily target"))
        val j = Simulador.deJson(s.aJson()); assertEquals(2, j.maxOpsDia)
    }

    @Test fun maeMfeYDuracion() {
        val s = Simulador(); val t = T0 + 600
        s.abrirFuturo("B", "", "BTC", true, 100.0, 10, 100.0, t = t)
        s.procesar("B", 99.0, 102.0, 101.0, t + 60)
        s.cerrarFuturo(s.posiciones[0].id, 101.0, t = t + 300)
        val h = s.historial.last()
        assertEquals(300L, h.dur); assertEquals(-1.0, h.mae, 1e-6); assertEquals(2.0, h.mfe, 1e-6)
        val r = Simulador.deJson(s.aJson()).historial.last(); assertEquals(2.0, r.mfe, 1e-6)
        val an = ScalpUtil.analizar(s.historial, java.util.TimeZone.getTimeZone("UTC"))
        assertEquals(1, an.porLado.single().operaciones); assertEquals("BTC", an.porPar.single().clave); assertEquals(300.0, an.duracionMedia, eps)
    }

    @Test fun murosYCvd() {
        val l = Mercado.Libro(listOf(100.0 to 50.0, 99.9 to 1.0), listOf(100.1 to 2.0, 100.2 to 80.0), "t")
        val m = ScalpUtil.muros(l, 2)
        assertEquals(2, m.size); assertTrue(!m[0].compra); assertEquals(1f, m[0].fuerza, 1e-6f); assertTrue(m[1].compra)
        val c = Indicadores.cvd(floatArrayOf(2f, 2f), floatArrayOf(1f, 1f), floatArrayOf(2f, 1f), floatArrayOf(10f, 10f), floatArrayOf(7f, Float.NaN))
        assertEquals(4f, c[0], 1e-6f); assertEquals(-6f, c[1], 1e-6f)
    }
}
