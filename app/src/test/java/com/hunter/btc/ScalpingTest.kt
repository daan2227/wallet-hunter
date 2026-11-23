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
}
