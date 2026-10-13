package com.hunter.btc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SeedEnvelopeTest {

    private fun clave(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun sellarYAbrirDevuelveLoMismo() {
        val k = clave()
        val datos = "abandon abandon abandon abandon abandon about".toByteArray()
        val (enc, iv) = SeedEnvelope.sellar(k, datos)
        assertArrayEquals(datos, SeedEnvelope.abrir(k, enc, iv))
    }

    @Test
    fun conOtraClaveNoSeAbre() {
        val (enc, iv) = SeedEnvelope.sellar(clave(), "semilla".toByteArray())
        assertNull(SeedEnvelope.abrir(clave(), enc, iv))
    }

    @Test
    fun undatoTocadoNoSeAbre() {
        val k = clave()
        val (enc, iv) = SeedEnvelope.sellar(k, "semilla".toByteArray())
        enc[0] = (enc[0].toInt() xor 1).toByte()
        assertNull(SeedEnvelope.abrir(k, enc, iv))
    }

    @Test
    fun empaquetarYDesempaquetarEsIdentidad() {
        val iv = ByteArray(12) { it.toByte() }
        val enc = ByteArray(48) { (it * 3).toByte() }
        val (iv2, enc2) = SeedEnvelope.desempaquetar(SeedEnvelope.empaquetar(iv, enc))!!
        assertArrayEquals(iv, iv2); assertArrayEquals(enc, enc2)
    }

    @Test
    fun blobMalFormadoSeRechaza() {
        assertNull(SeedEnvelope.desempaquetar(ByteArray(0)))
        assertNull(SeedEnvelope.desempaquetar(byteArrayOf(0x00, 1, 2)))
        assertNull(SeedEnvelope.desempaquetar(byteArrayOf(0x0c, 1, 2)))   // dice 12 bytes de iv y no hay tantos
        assertNotNull(SeedEnvelope.desempaquetar(byteArrayOf(0x02, 1, 2, 3)))
    }
}
