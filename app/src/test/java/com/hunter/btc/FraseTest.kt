package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FraseTest {
    private val palabras = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test fun sinPassphraseLaFraseEsLaDeSiempre() {
        assertEquals(palabras, Frase.palabras(palabras))
        assertFalse(Frase.tienePassphrase(palabras))
        assertEquals("", Frase.passphrase(palabras))
        assertEquals(palabras, Frase.unir(palabras, ""))
    }

    @Test fun conPassphraseSeSeparaYSeUne() {
        val f = Frase.unir(palabras, "TREZOR")
        assertEquals("$palabras|TREZOR", f)
        assertTrue(Frase.tienePassphrase(f))
        assertEquals(palabras, Frase.palabras(f))
        assertEquals("TREZOR", Frase.passphrase(f))
    }

    @Test fun passphraseValidaYErronea() {
        assertNull(Frase.errorPassphrase(""))
        assertNull(Frase.errorPassphrase("TREZOR 2026!"))
        assertNotNull(Frase.errorPassphrase("con\"comillas"))
        assertNotNull(Frase.errorPassphrase("con\\barra"))
        assertNotNull(Frase.errorPassphrase("con|barra"))
        assertNotNull(Frase.errorPassphrase("ñandú"))
        assertNotNull(Frase.errorPassphrase("x".repeat(101)))
        assertNull(Frase.errorPassphrase("x".repeat(100)))
    }

    @Test fun cuentaSeLeeYSeQuitaSiEsLaCero() {
        assertEquals(0, Frase.cuenta(palabras))
        assertEquals(3, Frase.cuenta("$palabras#3"))
        assertEquals(palabras, Frase.base("$palabras#3"))
        assertEquals("$palabras#7", Frase.conCuenta(palabras, 7))
        assertEquals(palabras, Frase.conCuenta("$palabras#7", 0))
        assertEquals("$palabras|TREZOR#2", Frase.conCuenta("$palabras|TREZOR", 2))
        assertEquals("TREZOR", Frase.passphrase("$palabras|TREZOR#2"))
        assertEquals("abandon", Frase.palabras("$palabras#2").substringBefore(' '))
    }

    @Test fun sufijoQueNoEsCuentaSeQuedaComoEsta() {
        assertEquals(0, Frase.cuenta("$palabras#100"))
        assertEquals("$palabras#100", Frase.base("$palabras#100"))
        assertEquals(0, Frase.cuenta("$palabras#"))
    }

    @Test fun passphraseSinAlmohadilla() {
        assertNotNull(Frase.errorPassphrase("con#almohadilla"))
    }
}
