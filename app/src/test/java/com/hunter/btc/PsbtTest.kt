package com.hunter.btc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PsbtTest {
    private fun h(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Transacción legacy de 1 entrada y 2 salidas (de los vectores de BIP143,
    // "unsigned" de P2WPKH nativo), y la misma con marcador segwit y un testigo.
    private val legacy = h("0100000002fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f0000000000eeffffffef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a0100000000ffffffff02202cb206000000001976a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac9093510d000000001976a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac11000000")
    private fun conTestigos(l: ByteArray): ByteArray {
        // version | 00 01 | resto sin locktime | testigos (2 entradas) | locktime
        val v = l.copyOfRange(0, 4); val cuerpo = l.copyOfRange(4, l.size - 4); val lt = l.copyOfRange(l.size - 4, l.size)
        val w1 = h("02" + "03" + "aabbcc" + "01" + "dd")   // 2 elementos
        val w2 = h("00")                                    // vacío
        return v + byteArrayOf(0, 1) + cuerpo + w1 + w2 + lt
    }

    @Test fun legacyYSegwitDanElMismoTxid() {
        val a = Psbt.parseTx(legacy); val b = Psbt.parseTx(conTestigos(legacy))
        assertEquals(2, a.ins.size); assertEquals(2, b.ins.size); assertEquals(2, b.outs.size)
        assertEquals(Psbt.txidHex(a), Psbt.txidHex(b))
        assertArrayEquals(legacy, Psbt.sinTestigos(b))
        assertEquals(112340000L, b.outs[0].value)
    }
}
