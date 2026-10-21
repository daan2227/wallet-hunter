package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuerzaContrasenaTest {

    @Test fun vaciaEsDebil() = assertEquals(FuerzaContrasena.Nivel.DEBIL, FuerzaContrasena.evaluar("").nivel)

    @Test fun repetidaEsDebilYNoVale() {
        assertEquals(FuerzaContrasena.Nivel.DEBIL, FuerzaContrasena.evaluar("aaaaaaaaaaaa").nivel)
        assertFalse(FuerzaContrasena.aceptable("aaaaaaaaaaaa"))
    }

    @Test fun lasTrampasCorrientesNoValen() {
        assertFalse(FuerzaContrasena.aceptable("Password1234!"))
        assertFalse(FuerzaContrasena.aceptable("1234567890123456"))
        assertFalse(FuerzaContrasena.aceptable("abcdefghijkl"))
        assertFalse(FuerzaContrasena.aceptable("qwertyuiop12"))
    }

    @Test fun cortaNoVale() = assertFalse(FuerzaContrasena.aceptable("k9#Lm2\$vQx"))

    @Test fun fraseLargaYAleatoriaValen() {
        assertTrue(FuerzaContrasena.aceptable("correct horse battery staple"))
        assertTrue(FuerzaContrasena.aceptable("k9#Lm2\$vQx8!"))
        assertEquals(FuerzaContrasena.Nivel.FUERTE, FuerzaContrasena.evaluar("correct horse battery staple").nivel)
    }

    @Test fun masVarianoPuntuaMas() {
        val a = FuerzaContrasena.evaluar("kxmvqzrtbwnl").bits
        val b = FuerzaContrasena.evaluar("kXm9qZ#tbWn!").bits
        assertTrue(b > a)
    }

    /** Las contraseñas que el medidor anterior daba por buenas (82-91 bits) y que se rompen con un diccionario. */
    @Test fun palabraMasAnioMasSimboloNoVale() {
        assertFalse(FuerzaContrasena.aceptable("Barcelona2024!!"))
        assertFalse(FuerzaContrasena.aceptable("MyDogName2020!"))
        assertFalse(FuerzaContrasena.aceptable("purple monkey dishwasher"))
        assertTrue(FuerzaContrasena.evaluar("Barcelona2024!!").bits < 45)
    }

    @Test fun unaFraseDeCincoPalabrasSiVale() {
        assertTrue(FuerzaContrasena.aceptable("tiger window orange river candle"))
    }

    @Test fun aleatoriaLargaVale() {
        assertTrue(FuerzaContrasena.aceptable("vQ7#nT2!xLp9@wRz"))
    }
}
