package com.hunter.btc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuzonCifradoTest {
    private val texto = "{\"addr\":\"1abc\",\"hex\":\"${"ab".repeat(32)}\"}".toByteArray()

    @Test fun cifraConLaPublicaYAbreConLaPrivada() {
        val (pub, priv) = BuzonCifrado.generarPar()
        val paq = BuzonCifrado.cifrar(pub, texto)
        assertArrayEquals(texto, BuzonCifrado.descifrar(priv, paq, pub))
    }

    @Test fun elPaqueteNoLlevaElTextoEnClaro() {
        val (pub, _) = BuzonCifrado.generarPar()
        val paq = BuzonCifrado.cifrar(pub, texto)
        assertFalse(String(paq, Charsets.ISO_8859_1).contains("abab"))
    }

    @Test fun cadaCifradoEsDistinto() {
        val (pub, _) = BuzonCifrado.generarPar()
        assertFalse(BuzonCifrado.cifrar(pub, texto).contentEquals(BuzonCifrado.cifrar(pub, texto)))
    }

    @Test fun otraPrivadaNoAbre() {
        val (pub, _) = BuzonCifrado.generarPar()
        val (_, otraPriv) = BuzonCifrado.generarPar()
        assertNull(BuzonCifrado.descifrar(otraPriv, BuzonCifrado.cifrar(pub, texto), pub))
    }

    @Test fun unPaqueteTocadoNoAbre() {
        val (pub, priv) = BuzonCifrado.generarPar()
        val paq = BuzonCifrado.cifrar(pub, texto)
        for (i in listOf(0, 5, paq.size / 2, paq.size - 1)) {
            val t = paq.copyOf(); t[i] = (t[i].toInt() xor 1).toByte()
            assertNull("byte $i", BuzonCifrado.descifrar(priv, t, pub))
        }
        assertNull(BuzonCifrado.descifrar(priv, paq.copyOf(10), pub))
        assertNull(BuzonCifrado.descifrar(priv, ByteArray(0), pub))
    }

    @Test fun variosPaquetesConLaMismaPublica() {
        val (pub, priv) = BuzonCifrado.generarPar()
        for (i in 1..5) {
            val d = "hallazgo $i".toByteArray()
            assertNotNull(BuzonCifrado.descifrar(priv, BuzonCifrado.cifrar(pub, d), pub))
            assertTrue(BuzonCifrado.descifrar(priv, BuzonCifrado.cifrar(pub, d), pub)!!.contentEquals(d))
        }
    }
}
