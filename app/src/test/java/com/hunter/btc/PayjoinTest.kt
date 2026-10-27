package com.hunter.btc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Una original y la propuesta de un cobrador Payjoin de prueba hecho con Bitcoin Core 30 (regtest).
 * Comprobado además a mano: la app volvió a firmar su entrada y la mempool aceptó la transacción.
 * Aquí, la comprobación de BIP78 con la buena y con variantes tramposas.
 */
class PayjoinTest {
    private val o by lazy { JSONObject(javaClass.classLoader!!.getResourceAsStream("payjoin_core.json")!!.bufferedReader().readText()) }
    private val us by lazy { o.getJSONArray("utxos").let { a -> (0 until a.length()).map { a.getJSONArray(it).getLong(0) to Descriptor.bytes(a.getJSONArray(it).getString(1)) } } }
    private val propios by lazy { o.getJSONArray("propios").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }
    private fun original() = Psbt.parse(Payjoin.psbtOriginal(Descriptor.bytes(o.getString("raw")), us))!!
    private fun propuesta() = Psbt.parse(java.util.Base64.getDecoder().decode(o.getString("propuesta")))!!

    @Test fun originalBienFormada() {
        val d = original()
        assertEquals(1, d.tx.ins.size)
        assertTrue(d.inputs[0].any { it.key[0].toInt() == 0x08 })
        assertEquals(0, d.tx.ins[0].scriptSig.size)
    }

    @Test fun laBuenaPasa() { assertNull(Payjoin.comprobar(original(), propuesta(), us, propios, 136)) }

    private fun conSalidas(p: Psbt.Doc, f: (List<Psbt.TxOut>) -> List<Psbt.TxOut>) =
        Psbt.Doc(p.globals, p.inputs, p.outputs, Psbt.Tx(p.tx.version, p.tx.ins, f(p.tx.outs), p.tx.locktime))

    @Test fun lasTramposasNo() {
        val p = propuesta(); val orig = original()
        val pago = orig.tx.outs.first { Descriptor.hex(it.spk) !in propios }
        assertNotNull(Payjoin.comprobar(orig, conSalidas(p) { os -> os.map { if (it.spk.contentEquals(pago.spk)) Psbt.TxOut(pago.value - 1, it.spk) else it } }, us, propios, 136))
        assertNotNull(Payjoin.comprobar(orig, conSalidas(p) { os -> os.map { if (Descriptor.hex(it.spk) in propios) Psbt.TxOut(it.value - 1000, it.spk) else it } }, us, propios, 136))
        assertNotNull(Payjoin.comprobar(orig, conSalidas(p) { os -> os.filter { Descriptor.hex(it.spk) !in propios } }, us, propios, 136))
        assertNotNull(Payjoin.comprobar(orig, orig, us, propios, 136))
        assertNotNull(Payjoin.comprobar(orig, Psbt.Doc(p.globals, p.inputs, p.outputs, Psbt.Tx(1, p.tx.ins, p.tx.outs, p.tx.locktime)), us, propios, 136))
    }

    @Test fun endpoint() {
        assertEquals("https://pj.example.com/p?x=1", Payjoin.endpoint("bitcoin:bc1qabc?amount=1&pj=https%3A%2F%2Fpj.example.com%2Fp%3Fx%3D1"))
        assertNull(Payjoin.endpoint("bitcoin:bc1qabc?pj=http://example.com/p"))
        assertNotNull(Payjoin.endpoint("bitcoin:bc1qabc?pj=http://abcdefghijklmnop.onion/p"))
        assertTrue(Payjoin.url("https://x/p", 1, 136, 2).contains("additionalfeeoutputindex=1&maxadditionalfeecontribution=136"))
    }
}
