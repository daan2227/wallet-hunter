package com.hunter.btc

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Monedas reales de un monedero de Bitcoin Core 30 en regtest (pkh, sh(wpkh), wpkh y tr), con sus
 * descriptores públicos. La PSBT que arma la app desde la xpub la firmó Core por completo y su mempool
 * aceptó la transacción (comprobado a mano con walletprocesspsbt + finalizepsbt + testmempoolaccept).
 * Aquí se comprueba lo que no necesita a Core: los scripts y los campos que un firmador necesita.
 */
class MotorVigiladaTest {
    private val casos by lazy { JSONArray(javaClass.classLoader!!.getResourceAsStream("vigilada_core.json")!!.bufferedReader().readText()) }

    @Test fun scriptsYPsbtCompleta() {
        for (k in 0 until casos.length()) {
            val o = casos.getJSONObject(k)
            val r = Descriptor.leer(o.getString("desc")) as Descriptor.R.Ok
            val m = MotorVigilada(r.v, EcLento.derivador, EcLento.tweaker)
            val c = o.getInt("c"); val i = o.getInt("i")
            assertEquals(o.getString("desc"), o.getString("spk"), Descriptor.hex(m.spk(c, i)!!))
            val v = o.getLong("valor")
            val p = m.psbt(listOf(Moneda(c, i, o.getString("txid"), o.getInt("vout"), v)),
                Descriptor.bytes(o.getString("destspk")), v / 2, v - v / 2 - 3000, 5, 0) { Descriptor.bytes(o.getString("prev")) }
            assertNotNull(p)
            val d = Psbt.parse(p!!)!!
            val tr = r.v.tipo == Descriptor.Tipo.TR
            // Entrada: UTXO y derivación (0x06, o 0x16/0x17 en Taproot); legacy con la transacción anterior.
            val ent = d.inputs[0].map { it.key[0].toInt() }
            assertTrue(ent.toString(), if (tr) 0x16 in ent && 0x17 in ent else 0x06 in ent)
            assertTrue(if (r.v.tipo == Descriptor.Tipo.PKH) 0x00 in ent else 0x01 in ent)
            // Cambio con su derivación (0x02, o 0x05/0x07 en Taproot).
            val sal = d.outputs[1].map { it.key[0].toInt() }
            assertTrue(sal.toString(), if (tr) 0x05 in sal && 0x07 in sal else 0x02 in sal)
            assertEquals(v - 3000, d.tx.outs.sumOf { it.value })
            assertTrue(Psbt.revisar(d).problemas.isEmpty())
        }
    }
}
