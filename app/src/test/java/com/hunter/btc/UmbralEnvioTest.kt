package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UmbralEnvioTest {

    @Test fun nuncaNoPidePin() = assertFalse(UmbralEnvio.exige(UmbralEnvio.NUNCA, 2_100_000_000_000_000L))

    @Test fun siemprePidePin() {
        assertTrue(UmbralEnvio.exige(0, 1))
        assertTrue(UmbralEnvio.exige(0, 0))
    }

    @Test fun elUmbralEsInclusivo() {
        assertFalse(UmbralEnvio.exige(1_000_000, 999_999))
        assertTrue(UmbralEnvio.exige(1_000_000, 1_000_000))
        assertTrue(UmbralEnvio.exige(1_000_000, 5_000_000))
    }

    @Test fun parsearBtcASatoshis() {
        assertEquals(1_000_000L, UmbralEnvio.parsear("0.01"))
        assertEquals(1_000_000L, UmbralEnvio.parsear(" 0,01 "))
        assertEquals(100_000_000L, UmbralEnvio.parsear("1"))
        assertEquals(0L, UmbralEnvio.parsear("0"))
        assertEquals(1L, UmbralEnvio.parsear("0.00000001"))
        assertEquals(UmbralEnvio.NUNCA, UmbralEnvio.parsear(""))
        assertEquals(UmbralEnvio.NUNCA, UmbralEnvio.parsear("   "))
    }

    @Test fun parsearRechazaLoInvalido() {
        assertNull(UmbralEnvio.parsear("abc"))
        assertNull(UmbralEnvio.parsear("-1"))
        assertNull(UmbralEnvio.parsear("1e400"))
        assertNull(UmbralEnvio.parsear("22000000"))
        assertNull(UmbralEnvio.parsear("1.2.3"))
    }

    @Test fun textoYBack() {
        assertEquals("0.01", UmbralEnvio.aTexto(1_000_000))
        assertEquals("1", UmbralEnvio.aTexto(100_000_000))
        assertEquals("0", UmbralEnvio.aTexto(0))
        assertEquals("", UmbralEnvio.aTexto(-1))
        assertEquals(1_234_567L, UmbralEnvio.parsear(UmbralEnvio.aTexto(1_234_567)))
    }
}
