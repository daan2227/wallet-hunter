package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Prueba de la fuga BIP32 (Bip32Fuga.kt): se fabrica una madre con una clave y un chain code conocidos,
 * se deriva una hija NO endurecida, y se comprueba que desde el xpub + la hija se recupera la madre.
 * JVM pura (curva en BigInteger, EcLento), igual camino que en el móvil (allí con la curva nativa).
 */
class Bip32FugaTest {

    private val N = Bip32Fuga.N
    private val rnd = SecureRandom()

    private fun hmac512(k: ByteArray, d: ByteArray) =
        javax.crypto.Mac.getInstance("HmacSHA512").apply {
            init(javax.crypto.spec.SecretKeySpec(k, "HmacSHA512")) }.doFinal(d)

    private fun b58check(datos: ByteArray): String {
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        val b = datos + sha.digest(sha.digest(datos)).copyOfRange(0, 4)
        val a = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var n = BigInteger(1, b); val base = BigInteger.valueOf(58); val sb = StringBuilder()
        while (n.signum() > 0) { val qr = n.divideAndRemainder(base); sb.append(a[qr[1].toInt()]); n = qr[0] }
        for (x in b) { if (x.toInt() != 0) break; sb.append('1') }
        return sb.reverse().toString()
    }

    private fun pubDe(hex: String): String = Descriptor.hex(EcLento.ser(EcLento.mul(BigInteger(hex, 16))!!))

    /** Construye el xpub (mainnet) de una madre (clave d, chain code c). */
    private fun xpubDe(d: BigInteger, c: ByteArray): String {
        val pub = EcLento.ser(EcLento.mul(d)!!)                  // 33 bytes comprimidos
        val raw = byteArrayOf(0x04, 0x88.toByte(), 0xB2.toByte(), 0x1E) + byteArrayOf(0) +
                  ByteArray(4) + ByteArray(4) + c + pub
        return b58check(raw)
    }

    @Test fun recuperaLaMadre() {
        val d = BigInteger(256, rnd).mod(N)
        val c = ByteArray(32).also { rnd.nextBytes(it) }
        val indice = 7L
        val pub = EcLento.ser(EcLento.mul(d)!!)
        val ser = byteArrayOf((indice ushr 24).toByte(), (indice ushr 16).toByte(), (indice ushr 8).toByte(), indice.toByte())
        val il = BigInteger(1, hmac512(c, pub + ser).copyOfRange(0, 32)).mod(N)
        val hija = d.add(il).mod(N)                              // k_hija = (k_madre + IL) mod n

        val xpub = xpubDe(d, c)
        val got = Bip32Fuga.recupera(xpub, hija) { pubDe(it) }
        assertNotNull("debía recuperar la madre", got)
        assertEquals(indice, got!!.first)
        assertEquals(d, got.second)
    }

    @Test fun hijaDeOtraCarteraNoEncaja() {
        // Una hija que no pertenece a este xpub no debe dar un falso positivo.
        val d = BigInteger(256, rnd).mod(N)
        val c = ByteArray(32).also { rnd.nextBytes(it) }
        val xpub = xpubDe(d, c)
        val ajena = BigInteger(256, rnd).mod(N)
        val got = Bip32Fuga.recupera(xpub, ajena, maxIndice = 200) { pubDe(it) }
        assertNull("una hija ajena no debe recuperar nada", got)
    }
}
