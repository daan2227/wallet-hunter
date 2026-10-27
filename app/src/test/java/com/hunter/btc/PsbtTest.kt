package com.hunter.btc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // PSBT mínima sobre la transacción de arriba: un único UTXO en la entrada 0,
    // con witness_utxo y sin la transacción anterior.
    private fun psbtConUtxo(spk: ByteArray): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        fun rec(k: ByteArray, v: ByteArray) { o.write(k.size); o.write(k); o.write(v.size); o.write(v) }
        o.write(h("70736274ff"))
        rec(h("00"), legacy); o.write(0)
        val amt = ByteArray(8).also { it[1] = 0x10 }
        rec(h("01"), amt + byteArrayOf(spk.size.toByte()) + spk); o.write(0)
        o.write(0)                       // entrada 1 sin datos
        o.write(0); o.write(0)           // dos salidas sin datos
        return o.toByteArray()
    }

    @Test fun legacySinTransaccionAnteriorNoSePuedeRevisar() {
        val p2pkh = h("76a914" + "11".repeat(20) + "88ac")
        val doc = Psbt.parse(psbtConUtxo(p2pkh))!!
        val rev = Psbt.revisar(doc)
        assertTrue(rev.problemas.any { it.contains("legacy") })
    }

    @Test fun segwitConWitnessUtxoSiSeRevisa() {
        val p2wpkh = h("0014" + "22".repeat(20))
        val doc = Psbt.parse(psbtConUtxo(p2wpkh))!!
        assertTrue(Psbt.revisar(doc).problemas.none { it.contains("legacy") })
    }

    @Test fun txidDeRawCoincideConElParseadoConYSinTestigos() {
        val hexLegacy = legacy.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val hexTestigos = conTestigos(legacy).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val esperado = Psbt.txidHex(Psbt.parseTx(legacy))
        assertEquals(esperado, Psbt.txidDeRaw(hexLegacy))
        assertEquals(esperado, Psbt.txidDeRaw(hexTestigos))
        assertEquals(null, Psbt.txidDeRaw("zz"))
    }

    @Test fun crearPsbtYLeerla() {
        val spkWit = h("0014751e76e8199196d454941c45d1b3a323f1433bd6")   // BIP173: bc1qw508...
        val spkLeg = h("76a914" + "11".repeat(20) + "88ac")
        val legHex = legacy.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val bin = Psbt.crear(legHex, listOf(
            Psbt.EntradaPsbt(100_000L, spkWit, null),
            Psbt.EntradaPsbt(50_000L, spkLeg, legacy)))
        val doc = Psbt.parse(bin)!!
        assertEquals(2, doc.tx.ins.size)
        assertEquals(Psbt.txidHex(Psbt.parseTx(legacy)), Psbt.txidHex(doc.tx))
        // La entrada con witness_utxo lleva el importe y el script; la legacy, la transacción anterior.
        assertEquals(0x01, doc.inputs[0][0].key[0].toInt())
        assertEquals(0x00, doc.inputs[1][0].key[0].toInt())
        assertArrayEquals(legacy, doc.inputs[1][0].value)
        assertArrayEquals(bin, Psbt.serialize(doc))
    }

    @Test fun derivacionBIP32YRescateEnLasEntradas() {
        val pub = h("02" + "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
        val huella = h("01020304")
        val ruta = Psbt.rutaAIndices("m/84'/0'/0'/0/3")
        assertEquals(listOf(0x80000054L, 0x80000000L, 0x80000000L, 0L, 3L), ruta)
        val legHex = legacy.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val spk = h("0014751e76e8199196d454941c45d1b3a323f1433bd6")
        val bin = Psbt.crear(legHex, listOf(
            Psbt.EntradaPsbt(1L, spk, null, Psbt.Derivacion(pub, huella, ruta)),
            Psbt.EntradaPsbt(2L, spk, null, Psbt.Derivacion(pub, huella, ruta,
                rescate = h("0014" + "11".repeat(20)))),
            ))
        val doc = Psbt.parse(bin)!!
        // Entrada 0: clave 0x06 + pubkey, valor = huella + 5 índices de 4 bytes.
        val bip32 = doc.inputs[0].first { it.key[0].toInt() == 0x06 }
        assertArrayEquals(pub, bip32.key.copyOfRange(1, bip32.key.size))
        assertEquals(4 + 5 * 4, bip32.value.size)
        assertArrayEquals(huella, bip32.value.copyOfRange(0, 4))
        // Entrada 1: script de rescate (0x04).
        assertTrue(doc.inputs[1].any { it.key[0].toInt() == 0x04 })
        // Taproot: clave interna (0x17) y derivación taproot (0x16) con 0 hojas.
        val tapBin = Psbt.crear(legHex, listOf(
            Psbt.EntradaPsbt(1L, h("5120" + "22".repeat(32)), null, Psbt.Derivacion(pub, huella, ruta, tapInterna = pub.copyOfRange(1, 33))),
            Psbt.EntradaPsbt(2L, h("5120" + "22".repeat(32)), null, Psbt.Derivacion(pub, huella, ruta, tapInterna = pub.copyOfRange(1, 33)))))
        val tdoc = Psbt.parse(tapBin)!!
        assertTrue(tdoc.inputs[0].any { it.key[0].toInt() == 0x17 })
        val tap = tdoc.inputs[0].first { it.key[0].toInt() == 0x16 }
        assertEquals(0, tap.value[0].toInt())
    }
}

class PsbtSinFirmarTest {
    private fun h(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun construyeLoQueSeLeeDeVuelta() {
        val txid = "aa".repeat(31) + "01"
        val spkA = h("0014" + "11".repeat(20)); val spkB = h("5120" + "22".repeat(32))
        val hex = Psbt.sinFirmar(listOf(txid to 3), listOf(spkA to 50_000L, spkB to 1234L), 840_000L)
        val tx = Psbt.parseTx(h(hex))
        assertEquals(1L, tx.version); assertEquals(1, tx.ins.size); assertEquals(2, tx.outs.size)
        assertEquals(3L, tx.ins[0].vout); assertEquals(Psbt.SEQUENCIA, tx.ins[0].seq)
        assertEquals(0, tx.ins[0].scriptSig.size)
        // el txid se escribe al revés en la transacción
        assertEquals(1, tx.ins[0].txid[0].toInt()); assertEquals(0xaa, tx.ins[0].txid[31].toInt() and 0xff)
        assertEquals(50_000L, tx.outs[0].value); assertArrayEquals(spkB, tx.outs[1].spk)
        assertEquals(840_000L, tx.locktime)
    }

    @Test fun alRevesDeUnaPsbtCoincide() {
        val hex = Psbt.sinFirmar(listOf("bb".repeat(32) to 0), listOf(h("0014" + "33".repeat(20)) to 1000L), 0)
        val psbt = Psbt.crear(hex, listOf(Psbt.EntradaPsbt(5000, h("0014" + "44".repeat(20)), null)))
        val doc = Psbt.parse(psbt)!!
        assertEquals(1, doc.tx.ins.size); assertEquals(1000L, doc.tx.outs[0].value)
    }

    @Test fun opReturn() {
        assertArrayEquals(h("6a03616263"), Psbt.scriptDatos("616263"))
        val largo = "ab".repeat(76)
        assertArrayEquals(h("6a4c4c") + h(largo), Psbt.scriptDatos(largo))
    }

    @Test(expected = IllegalArgumentException::class) fun sinSalidasNo() {
        Psbt.sinFirmar(listOf("cc".repeat(32) to 0), emptyList(), 0)
    }
}
