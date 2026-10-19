package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpfpTest {
    /** El par tiene que llegar al objetivo, y una tasa menor no debe llegar. */
    private fun llega(vp: Long, fp: Long, objetivo: Long, vc: Long, t: Long) =
        fp + t * vc >= objetivo * (vp + vc)

    @Test fun laTasaEsLaMinimaQueLlega() {
        val casos = listOf(
            listOf(200L, 200L, 10L, 150L),
            listOf(140L, 700L, 25L, 110L),
            listOf(250L, 50L, 3L, 68L),
        )
        for ((vp, fp, obj, vc) in casos) {
            val t = Cpfp.tasaHija(vp, fp, obj, vc)
            assertTrue("llega con $t", llega(vp, fp, obj, vc, t))
            if (t > 1) assertTrue("con ${t - 1} no llega", !llega(vp, fp, obj, vc, t - 1))
        }
    }

    @Test fun padreQueYaPagaBastaConUnSatPorVbyte() {
        // El padre ya paga más que el objetivo: la hija no necesita más de 1 sat/vB.
        assertEquals(1L, Cpfp.tasaHija(100L, 10_000L, 5L, 100L))
    }

    @Test fun ejemploExacto() {
        // 10 * (200 + 150) - 200 = 3300; 3300 / 150 = 22 exacto.
        assertEquals(22L, Cpfp.tasaHija(200L, 200L, 10L, 150L))
    }
}
