package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalisisIATest {
    private fun vela(i: Int, o: Float, h: Float, l: Float, c: Float, v: Float = 100f) = GraficoPrecio.Vela(i * 3600L, o, h, l, c, v)

    /** Serie que sube con retrocesos: tendencia alcista clara (acaba en una vela a favor de la tendencia). */
    private fun alcista(n: Int = 259): List<GraficoPrecio.Vela> {
        val out = ArrayList<GraficoPrecio.Vela>(); var p = 100f
        for (i in 0 until n) { val c = p * (1 + if (i % 5 == 4) -0.004f else 0.006f)
            out.add(vela(i, p, maxOf(p, c) * 1.002f, minOf(p, c) * 0.998f, c)); p = c }
        return out
    }

    @Test fun tendenciaAlcistaDaLong() {
        val r = AnalisisIA.evaluar(AnalisisIA.Datos(alcista()))
        assertTrue(r.puntuacion > 0)
        assertTrue(r.veredicto == AnalisisIA.Veredicto.LONG || r.veredicto == AnalisisIA.Veredicto.SPOT)
        val p = r.plan; assertNotNull(p)
        assertTrue(p!!.largo); assertTrue(p.stop < p.entrada); assertTrue(p.objetivo1 > p.entrada); assertTrue(p.objetivo2 > p.objetivo1)
        assertTrue(p.apalancamientoMax in 1..10)
    }

    @Test fun tendenciaBajistaDaShort() {
        val sube = alcista()
        // espejo: el mismo camino hacia abajo
        val baja = sube.map { k -> vela((k.t / 3600).toInt(), 20000f / k.o, 20000f / k.l, 20000f / k.h, 20000f / k.c) }
        val r = AnalisisIA.evaluar(AnalisisIA.Datos(baja))
        assertTrue(r.puntuacion < 0)
        assertEquals(AnalisisIA.Veredicto.SHORT, r.veredicto)
        assertTrue(r.plan!!.stop > r.plan!!.entrada)
    }

    @Test fun derivadosCargadosPasanASpot() {
        val r = AnalisisIA.evaluar(AnalisisIA.Datos(alcista(), funding = 0.001, pctLong = 78.0))
        assertTrue(r.veredicto == AnalisisIA.Veredicto.SPOT || r.veredicto == AnalisisIA.Veredicto.ESPERAR)
    }

    @Test fun bolsaNoDaCortos() {
        val sube = alcista()
        val baja = sube.map { k -> vela((k.t / 3600).toInt(), 20000f / k.o, 20000f / k.l, 20000f / k.h, 20000f / k.c) }
        val r = AnalisisIA.evaluar(AnalisisIA.Datos(baja, esCripto = false))
        assertTrue(r.veredicto != AnalisisIA.Veredicto.SHORT && r.veredicto != AnalisisIA.Veredicto.LONG)
    }

    @Test fun patronEnvolventeAlcista() {
        val v = ArrayList<GraficoPrecio.Vela>()
        for (i in 0 until 8) v.add(vela(i, 110f - i, 111f - i, 108f - i, 109f - i))      // bajada
        v.add(vela(8, 102f, 102.5f, 100f, 100.5f))                                          // bajista
        v.add(vela(9, 100f, 104f, 99.8f, 103.5f))                                           // envuelve (última cerrada)
        v.add(vela(10, 103.5f, 104f, 103f, 103.8f))                                         // en curso
        assertTrue(AnalisisIA.patronesVelas(v).any { it.nombre == "Bullish engulfing" && it.alcista == true })
    }

    @Test fun nivelesYApalancamiento() {
        val (s, r) = AnalisisIA.niveles(alcista())
        val ult = alcista().last().c
        assertTrue(s.all { it < ult }); assertTrue(r.all { it > ult })
        assertEquals(10, AnalisisIA.apalancamiento(0.01)); assertEquals(4, AnalisisIA.apalancamiento(0.1)); assertEquals(1, AnalisisIA.apalancamiento(0.5))
    }

    @Test fun tonoDeTitulares() {
        assertEquals(1, AnalisisIA.tono("Bitcoin surges to new all-time high"))
        assertEquals(-1, AnalisisIA.tono("Exchange hack leads to crash"))
        assertEquals(0, AnalisisIA.tono("Weekly discussion thread"))
    }
}
