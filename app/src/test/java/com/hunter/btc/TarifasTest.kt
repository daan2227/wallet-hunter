package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TarifasTest {
    @Test fun tramosDistintos() {
        val t = Tarifas.desdeEsplora(mapOf(1 to 87.3, 2 to 60.0, 3 to 41.2, 6 to 20.1, 12 to 9.5, 24 to 5.0, 144 to 2.0), 2000)!!
        assertEquals(Tarifas.Tramos(88, 42, 21, 10), t)
    }

    @Test fun redTranquilaDaUnoEnTodos() {
        assertEquals(Tarifas.Tramos(1, 1, 1, 1), Tarifas.desdeEsplora(mapOf(1 to 1.0, 3 to 1.0, 6 to 1.0, 144 to 1.0), 2000))
    }

    @Test fun siempreOrdenados() {
        val d = Tarifas.desdeEsplora(mapOf(1 to 5.0, 3 to 9.0, 6 to 12.0, 12 to 30.0), 2000)!!
        assertTrue(d.fastest >= d.halfHour && d.halfHour >= d.hour && d.hour >= d.economy)
    }

    @Test fun datosInutilizables() {
        assertNull(Tarifas.desdeEsplora(emptyMap(), 2000))
        assertNull(Tarifas.desdeEsplora(mapOf(1 to 5000.0), 2000))
        assertNull(Tarifas.desdeEsplora(mapOf(1 to Double.NaN, 3 to -1.0), 2000))
    }

    @Test fun unaSolaEstimacionSeReparte() {
        assertEquals(Tarifas.Tramos(5, 5, 5, 5), Tarifas.desdeEsplora(mapOf(2 to 4.2), 2000))
    }
}
