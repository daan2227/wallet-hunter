package com.hunter.btc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShamirComprobacionTest {
    private val secreto = ByteArray(32) { (it * 7 + 3).toByte() }
    private fun partes(k: Int, n: Int) = Shamir256.repartir(secreto, k, n, java.util.Random(42))
    private fun conBitCambiado(p: Pair<Int, ByteArray>) =
        p.first to p.second.copyOf().also { it[5] = (it[5].toInt() xor 0x01).toByte() }

    @Test fun sinPartesDeSobraNoComprueba() {
        assertTrue(ShamirComprobacion.comprobar(partes(3, 3), 3) is ShamirComprobacion.R.SinRedundancia)
    }

    @Test fun todasBuenasSonCoherentes() {
        val r = ShamirComprobacion.comprobar(partes(3, 5), 3)
        assertTrue(r is ShamirComprobacion.R.Coherente)
        assertEquals(2, (r as ShamirComprobacion.R.Coherente).extra)
    }

    @Test fun unaMalaConUnaDeSobraSeDetecta() {
        // m = k + 1: se detecta pero no se puede corregir (radio 0).
        val p = partes(3, 4).toMutableList(); p[1] = conBitCambiado(p[1])
        assertTrue(ShamirComprobacion.comprobar(p, 3) is ShamirComprobacion.R.Incoherente)
    }

    @Test fun unaMalaConDosDeSobraSeCorrige() {
        val p = partes(3, 5).toMutableList(); p[2] = conBitCambiado(p[2])
        val r = ShamirComprobacion.comprobar(p, 3)
        assertTrue(r is ShamirComprobacion.R.Corregido)
        r as ShamirComprobacion.R.Corregido
        assertEquals(listOf(p[2].first), r.malas)
        assertArrayEquals(secreto, r.secreto)
    }

    @Test fun demasiadasPartesNoSeCuelga() {
        // C(40, 20) es enorme: se niega en vez de probarlas todas.
        assertTrue(ShamirComprobacion.comprobar(partes(20, 40), 20) is ShamirComprobacion.R.DemasiadasPartes)
    }
}
