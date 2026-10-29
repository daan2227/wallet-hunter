package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReemplazoTest {
    @Test fun tasaMinimaMejoraLaVieja() {
        assertEquals(11L, Reemplazo.tasaMinima(1410, 141))   // 10 exacto → 11
        assertEquals(12L, Reemplazo.tasaMinima(1411, 141))   // 10,007 → 11 hacia arriba → 12
        assertEquals(2L, Reemplazo.tasaMinima(100, 141))     // por debajo de 1 → 1 → 2
    }

    @Test fun comisionCumpleBip125() {
        // Regla 3 (más en total) y 4 (su tamaño a 1 sat/vB por encima): con la misma tasa no bastaría.
        for ((tasa, vn, fv) in listOf(Triple(11L, 141L, 1410L), Triple(11L, 110L, 1410L), Triple(50L, 141L, 1410L), Triple(2L, 110L, 1000L))) {
            val f = Reemplazo.comision(tasa, vn, fv)
            assertTrue(f >= fv + vn); assertTrue(f >= tasa * vn)
        }
        // Una cancelación más pequeña: a 11 sat/vB × 110 = 1210 no llega a 1410 + 110.
        assertEquals(1520L, Reemplazo.comision(11, 110, 1410))
    }

    @Test fun tamanoDeLaCancelacion() {
        // P2WPKH 1 entrada → 2 salidas P2WPKH (22 bytes de script) = 141 vB; con una sola salida, 31 vB menos.
        assertEquals(110L, Reemplazo.vsizeCancelacion(141, listOf(22, 22), 22))
        // A una P2TR (34) y cambio P2WPKH, cancelada de vuelta a la P2WPKH de origen.
        assertEquals(153L - 43 - 31 + 31, Reemplazo.vsizeCancelacion(153, listOf(34, 22), 22))
    }

    @Test fun opcionesSinRepetirYNuncaPorDebajo() {
        val o = Reemplazo.opciones(12, 30, 12, 5)
        assertEquals(listOf(30L, 12L), o.map { it.second })
        assertTrue(o.all { it.second >= 12 })
        // Sin datos de la red: la mínima.
        assertEquals(listOf("Minimum to replace" to 7L), Reemplazo.opciones(7, null, null, null))
    }
}
