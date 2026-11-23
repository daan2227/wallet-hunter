package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestTest {
    /** Plano en 100, baja a 81 y sube de 2 en 2: un cruce de medias al alza claro. */
    private fun serie(): MutableList<GraficoPrecio.Vela> {
        val out = ArrayList<GraficoPrecio.Vela>(); var prev = 100f
        for (i in 0 until 60) {
            val c = when { i < 20 -> 100f; i < 40 -> 100f - (i - 19); else -> 81f + (i - 39) * 2 }
            out.add(GraficoPrecio.Vela(i * 3600L, prev, maxOf(prev, c) + 0.5f, minOf(prev, c) - 0.5f, c, 10f)); prev = c
        }
        return out
    }
    private val p = Backtest.Params(Backtest.Estrategia.CRUCE_MA, rapida = 3, lenta = 8, soloLargos = true, comision = 0.001)

    @Test fun sinMirarElFuturo() {
        val v = serie(); val r = Backtest.ejecutar(v, p)
        assertTrue(r.trades.isNotEmpty())
        val c = FloatArray(v.size) { v[it].c }
        val rapida = Indicadores.ema(c, 3); val lenta = Indicadores.ema(c, 8)
        for (t in r.trades) {
            assertTrue(t.iEntrada >= 1)
            assertEquals(v[t.iEntrada].o.toDouble(), t.entrada, 1e-6)                // se entra en la apertura siguiente
            assertTrue(rapida[t.iEntrada - 1] > lenta[t.iEntrada - 1])               // con la señal ya visible al cierre anterior
        }
    }

    @Test fun comisionYResultado() {
        val v = serie(); val r = Backtest.ejecutar(v, p)
        val t = r.trades.first()
        assertEquals((t.salida / t.entrada - 1) - 2 * 0.001, t.resultado, 1e-9)
        assertEquals(r.trades.fold(1.0) { k, x -> k * (1 + x.resultado) }, 1 + r.retornoPct / 100, 1e-9)
        assertEquals((v.last().c / v.first().o - 1) * 100.0, r.comprarYMantenerPct, 1e-6)
    }

    @Test fun stopLoss() {
        val v = serie(); val e = Backtest.ejecutar(v, p).trades.first()
        val i = e.iEntrada + 1; val k = v[i]
        v[i] = GraficoPrecio.Vela(k.t, k.o, k.h, (e.entrada * 0.9).toFloat(), k.c, k.vol)      // mecha hasta −10 %
        val t = Backtest.ejecutar(v, Backtest.Params(Backtest.Estrategia.CRUCE_MA, 3, 8, soloLargos = true, comision = 0.001, slPct = 5.0)).trades.first()
        assertEquals("Stop-loss", t.motivo)
        assertEquals(e.entrada * 0.95, t.salida, 1e-6)
        assertEquals(i, t.iSalida)
    }

    @Test fun apalancamientoLiquida() {
        val v = serie(); val e = Backtest.ejecutar(v, p).trades.first()
        val i = e.iEntrada + 1; val k = v[i]
        v[i] = GraficoPrecio.Vela(k.t, k.o, k.h, (e.entrada * 0.9).toFloat(), k.c, k.vol)
        val r = Backtest.ejecutar(v, Backtest.Params(Backtest.Estrategia.CRUCE_MA, 3, 8, soloLargos = true, comision = 0.001, apalancamiento = 50))
        assertTrue(r.liquidado)
        assertEquals("Liquidated", r.trades.first().motivo)
        assertEquals(-100.0, r.retornoPct, 1e-6)
        assertEquals(1, r.trades.size)                                               // liquidado, ya no opera
    }

    @Test fun curvaYDrawdown() {
        val r = Backtest.ejecutar(serie(), p)
        assertEquals(60, r.curva.size)
        assertEquals(1.0, r.curva[0], 1e-12)
        assertTrue(r.maxDrawdownPct >= 0)
        assertFalse(r.liquidado)
    }

    @Test fun otrasEstrategiasNoFallan() {
        for (e in listOf(Backtest.Estrategia.RSI, Backtest.Estrategia.BOLLINGER)) {
            val r = Backtest.ejecutar(serie(), Backtest.Params(e, rsiN = 5, bbN = 10))
            assertEquals(60, r.curva.size)
            for (t in r.trades) assertTrue(t.iSalida >= t.iEntrada)
        }
    }
}
