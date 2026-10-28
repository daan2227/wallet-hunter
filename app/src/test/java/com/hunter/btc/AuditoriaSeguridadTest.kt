package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditoriaSeguridadTest {
    private val buena = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")
    private fun A(tienePin: Boolean = true, bits: Double? = 60.0, antiguo: Boolean = false, huella: Boolean = true,
                  copia: Boolean = true, capturas: Boolean = true, n: Int = 1) =
        AuditoriaSeguridad.Ajustes(tienePin, bits, antiguo, huella, copia, capturas, n)
    private fun E(palabras: List<String>? = buena, pass: String? = null, reutil: Int = 0, nonce: Boolean = false, sinComp: Boolean = false) =
        AuditoriaSeguridad.Entrada("w", palabras, pass, null, reutil, 20, nonce, sinComp)
    private fun tiene(h: List<AuditoriaSeguridad.Hallazgo>, t: String) = h.any { it.titulo.contains(t) }

    @Test fun carteraLimpiaPuntuaAlto() {
        val h = AuditoriaSeguridad.revisar(A(), listOf(E()))
        assertTrue(AuditoriaSeguridad.nota(h) >= 90)
        assertEquals("Strong", AuditoriaSeguridad.veredicto(AuditoriaSeguridad.nota(h)).first)
    }

    @Test fun fraseHechaAManoEsCritica() {
        // 12 veces "abandon" (checksum válido de esa frase conocida) → patrón de poca entropía.
        val mala = List(11) { "abandon" } + "about"
        val fr = AuditoriaSeguridad.analizarFrase(mala)
        assertTrue(fr.checksumOk); assertTrue(fr.banderas.isNotEmpty())
        val h = AuditoriaSeguridad.revisar(A(), listOf(E(palabras = mala)))
        assertTrue(tiene(h, "Guessable seed phrase"))
        assertTrue(AuditoriaSeguridad.nota(h) < 60)
    }

    @Test fun checksumMaloEsCritico() {
        val mala = "legal winner thank year wave sausage worth useful legal winner thank zebra".split(" ")
        assertFalse(AuditoriaSeguridad.analizarFrase(mala).checksumOk)
        assertTrue(tiene(AuditoriaSeguridad.revisar(A(), listOf(E(palabras = mala))), "checksum fails"))
    }

    @Test fun nonceExpuestoEsCriticoYMandaMover() {
        val h = AuditoriaSeguridad.revisar(A(), listOf(E(nonce = true)))
        val f = h.first { it.titulo.contains("reused signature nonce") }
        assertEquals(AuditoriaSeguridad.Gravedad.CRITICO, f.gravedad)
        assertTrue(f.consejo.contains("Move ALL funds"))
    }

    @Test fun ajustesFlojosSuman() {
        val h = AuditoriaSeguridad.revisar(A(tienePin = false, copia = false, capturas = false), listOf(E()))
        assertTrue(tiene(h, "No app code set")); assertTrue(tiene(h, "No backup made")); assertTrue(tiene(h, "Screenshots allowed"))
        assertTrue(AuditoriaSeguridad.nota(h) < 70)
    }

    @Test fun reutilizacionYSinComprimirYPassDebil() {
        val h = AuditoriaSeguridad.revisar(A(), listOf(E(pass = "1234", reutil = 3, sinComp = true)))
        assertTrue(tiene(h, "Address reuse")); assertTrue(tiene(h, "Uncompressed key")); assertTrue(tiene(h, "Weak passphrase"))
    }

    @Test fun ordenadosPorGravedad() {
        val h = AuditoriaSeguridad.revisar(A(tienePin = false), listOf(E(nonce = true, reutil = 1)))
        assertTrue(h.first().gravedad.penaliza >= h.last().gravedad.penaliza)
    }
}
