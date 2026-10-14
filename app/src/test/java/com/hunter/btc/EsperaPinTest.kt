package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EsperaPinTest {
    private val DIEZ_DIAS = 10L * 24 * 3600 * 1000

    @Test fun sinEsperaNoHayNada() = assertEquals(0L, EsperaPin.resto(0, 5, 1, 10, 1).first)

    @Test fun bajaConElTiempoEnElMismoArranque() {
        assertEquals(30_000L, EsperaPin.resto(30_000, DIEZ_DIAS, 7, DIEZ_DIAS, 7).first)
        assertEquals(20_000L, EsperaPin.resto(30_000, DIEZ_DIAS, 7, DIEZ_DIAS + 10_000, 7).first)
        assertEquals(0L, EsperaPin.resto(30_000, DIEZ_DIAS, 7, DIEZ_DIAS + 31_000, 7).first)
        assertNull(EsperaPin.resto(30_000, DIEZ_DIAS, 7, DIEZ_DIAS + 10_000, 7).second)
    }

    /** El fallo de antes: 10 días encendido, reinicio, y la espera congelada. */
    @Test fun tras10DiasYReinicioNoSeCongela() {
        val (r1, ancla) = EsperaPin.resto(30_000, DIEZ_DIAS, 7, 5_000, 8)
        assertEquals(30_000L, r1)          // vuelve a empezar entera…
        assertEquals(5_000L, ancla)        // …desde ahora
        // Y desde ese nuevo inicio baja con normalidad: 31 s después, libre.
        assertEquals(0L, EsperaPin.resto(30_000, ancla, 8, 5_000 + 31_000, 8).first)
    }

    @Test fun reinicioSinNumeroDeArranqueSeDetectaPorElReloj() {
        val (r, ancla) = EsperaPin.resto(30_000, DIEZ_DIAS, -1, 5_000, -1)
        assertEquals(30_000L, r); assertEquals(5_000L, ancla)
    }

    @Test fun nuncaDuraMasQueLaEsperaCompleta() {
        for (ahora in listOf(0L, 1L, 1_000L, DIEZ_DIAS, DIEZ_DIAS * 3))
            for (ini in listOf(0L, 999L, DIEZ_DIAS))
                for (b in listOf(-1, 1, 2))
                    assertTrue(EsperaPin.resto(3_600_000, ini, 1, ahora, b).first <= 3_600_000)
    }

    @Test fun inicioDesconocidoEmpiezaAhora() {
        val (r, ancla) = EsperaPin.resto(60_000, null, -1, 42, 3)
        assertEquals(60_000L, r); assertEquals(42L, ancla)
    }

    @Test fun segundosRedondeaHaciaArriba() {
        assertEquals(0L, EsperaPin.segundos(0)); assertEquals(1L, EsperaPin.segundos(1))
        assertEquals(30L, EsperaPin.segundos(30_000)); assertEquals(31L, EsperaPin.segundos(30_001))
    }
}
