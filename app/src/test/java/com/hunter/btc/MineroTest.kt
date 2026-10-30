package com.hunter.btc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bloque 1 de la cadena principal, con su coinbase partida como la mandaría un pool por Stratum. */
class MineroTest {
    private val coinbase = "01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff0704ffff001d0104ffffffff0100f2052a0100000043410496b538e853519c726a2c91e61ec11600ae1390813a627c66fb8be7947be63c52da7589379515d4e0a604f8141781e62294721166bf621e73a82cbf2342c858eeac00000000"
    // La coinbase es coinb1 + extranonce1 (4 bytes) + extranonce2 (4 bytes) + coinb2.
    private val i = coinbase.indexOf("04ffff001d0104") + 2
    private val trabajo = Minero.Trabajo("1", "0a8ce26f72b3f1b646a2a6c14ff763ae65831e939c085ae10019d66800000000",
        coinbase.substring(0, i), coinbase.substring(i + 16), emptyList(), "00000001", "1d00ffff", "4966bc61", true)

    @Test fun bloqueUno() {
        val c = Minero.cabecera(trabajo, coinbase.substring(i, i + 8), coinbase.substring(i + 8, i + 16))
        assertEquals("0e3e2357e806b6cdb1f70b54c3a3a17b6714ee1f0e68bebb44a74b1efd512098", Descriptor.hex(c.copyOfRange(36, 68).reversedArray()))
        val b = Minero.Buscador(c); val nonce = 2_573_394_689L.toInt()
        b.hash(nonce)
        assertEquals("00000000839a8e6886ab5951d76f411475428afc90947ee320161bbf18eb6048", b.hashTexto())
        assertEquals(Descriptor.hex(Minero.dsha(Minero.cabeceraCon(c, nonce)).reversedArray()), b.hashTexto())
        assertEquals(32, b.ceros()); assertTrue(b.cumple(Minero.objetivoDeBits(0x1d00ffff)))
        b.hash(nonce + 1); assertTrue(!b.cumple(Minero.objetivoDeBits(0x1d00ffff)))
        assertEquals("9962e301", Minero.nonceTexto(nonce))
    }

    @Test fun conRamasDeMerkle() {
        // Con una rama, la raíz es dsha(dsha(coinbase) ‖ rama): se comprueba contra la cuenta a mano.
        val rama = "11".repeat(32)
        val c = Minero.cabecera(Minero.Trabajo("2", trabajo.prevhash, trabajo.coinb1, trabajo.coinb2, listOf(rama),
            trabajo.version, trabajo.nbits, trabajo.ntime, false), coinbase.substring(i, i + 8), coinbase.substring(i + 8, i + 16))
        val esperada = Minero.dsha(Minero.dsha(Descriptor.bytes(coinbase)) + Descriptor.bytes(rama))
        assertEquals(Descriptor.hex(esperada), Descriptor.hex(c.copyOfRange(36, 68)))
        // Y el Buscador da lo mismo que MessageDigest con cualquier nonce.
        val b = Minero.Buscador(c)
        for (n in listOf(0, 1, -1, 123_456_789)) { b.hash(n); assertEquals(Descriptor.hex(Minero.dsha(Minero.cabeceraCon(c, n)).reversedArray()), b.hashTexto()) }
    }

    @Test fun objetivosYProbabilidad() {
        assertEquals(Minero.OBJETIVO_1, Minero.objetivoDeBits(0x1d00ffff))
        assertEquals(Minero.OBJETIVO_1.shiftRight(1), Minero.objetivoDeDificultad(2.0))
        // Con dificultad 1e14 y 1 MH/s: 1e14·2³² / 1e6 s ≈ 13 600 millones de años (más que la edad del universo).
        val s = Minero.segundosPorBloque(1e6, 1e14)
        assertEquals(4.294967296e17, s, 1e6); assertEquals("13.6 billion years", Minero.escala(s))
        assertEquals(1 - Math.exp(-1.0), Minero.probabilidad(1e6, 1e14, s), 1e-12)
    }
}
