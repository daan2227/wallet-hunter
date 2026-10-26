package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NombreSeguroTest {
    @Test fun normalSeQueda() {
        assertEquals("addresses.csv", NombreSeguro.limpiar("addresses.csv"))
        assertEquals("index-9.bin", NombreSeguro.limpiar("index-9.bin"))
    }
    @Test fun rutaMaliciosaNoSaleDelDirectorio() {
        for (n in listOf("../../../../data/user/0/com.hunter.btc/shared_prefs/hunter.xml",
                         "..\\..\\x.xml", "/etc/passwd", "a/../../b.csv")) {
            val r = NombreSeguro.limpiar(n)
            assertFalse(r, r.contains('/') || r.contains('\\') || r.startsWith("."))
        }
        assertEquals("hunter.xml", NombreSeguro.limpiar("../../shared_prefs/hunter.xml"))
    }
    @Test fun vaciosYPuntosDanElPorDefecto() {
        for (n in listOf(null, "", ".", "..", "...", "/", "../")) assertEquals("dataset.csv", NombreSeguro.limpiar(n))
    }
    @Test fun caracteresRarosSeSustituyen() {
        assertEquals("a_b_c.csv", NombreSeguro.limpiar("a b\u0000c.csv"))
        assertEquals(100, NombreSeguro.limpiar("x".repeat(500) + ".csv").length)
    }
    @Test fun recortarConservaLaExtension() {
        // El cargador nativo decide .bin o CSV por la extensión: cortarla cambiaba cómo se leía el fichero.
        val r = NombreSeguro.limpiar("a".repeat(120) + ".bin")
        assertEquals(100, r.length)
        assertTrue(r, r.endsWith(".bin"))
    }
}
