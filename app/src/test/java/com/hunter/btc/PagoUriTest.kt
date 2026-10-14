package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PagoUriTest {
    private val A = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"

    @Test fun direccionSuelta() {
        val p = PagoUri.leer(" $A ")
        assertEquals(A, p.direccion); assertNull(p.importeBtc); assertTrue(!p.esPeticion); assertNull(p.error)
    }
    @Test fun peticionConImporteYMayusculas() {
        val p = PagoUri.leer("BITCOIN:${A.uppercase()}?amount=0.0015&label=Cafe")
        assertEquals(A, p.direccion); assertEquals("0.0015", p.importeBtc); assertTrue(p.esPeticion)
    }
    @Test fun peticionSinImporteVaciaElCampo() {
        val p = PagoUri.leer("bitcoin:$A?label=x")
        assertTrue(p.esPeticion); assertNull(p.importeBtc); assertNull(p.error)
    }
    @Test fun importesInvalidos() {
        for (a in listOf("Infinity", "NaN", "1e9", "-1", "0", "0.000000001", "21000000.00000001", "1,5", "0x10", ""))
            assertNotNull(a, PagoUri.leer("bitcoin:$A?amount=$a").error)
    }
    @Test fun reqObligatorioSeRechaza() {
        assertNotNull(PagoUri.leer("bitcoin:$A?amount=1&req-somethingnew=1").error)
    }
    @Test fun mezclaDeMayusculasNoSeToca() {
        assertEquals("bC1qAbc", PagoUri.leer("bC1qAbc").direccion)
    }
}
