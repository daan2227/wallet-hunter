package com.hunter.btc

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PSBT firmadas por Bitcoin Core (walletprocesspsbt sin finalizar) de los cuatro tipos y una con las
 * cuatro entradas a la vez: finalizar y extraer tiene que dar EXACTAMENTE la transacción de finalizepsbt
 * (la mixta, además, la aceptó testmempoolaccept).
 */
class PsbtFinalizarTest {
    private val vec by lazy { JSONArray(javaClass.classLoader!!.getResourceAsStream("psbt_core.json")!!.bufferedReader().readText()) }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun igualQueFinalizepsbtDeCore() {
        for (i in 0 until vec.length()) {
            val o = vec.getJSONObject(i)
            val d = Psbt.parse(java.util.Base64.getDecoder().decode(o.getString("parcial")))!!
            assertNull("aún sin finalizar no se extrae", Psbt.extraer(d))
            assertEquals(o.getString("tipo"), d.tx.ins.size, Psbt.finalizar(d))
            assertEquals(o.getString("tipo"), o.getString("final"), hex(Psbt.extraer(d)!!))
            // Se puede serializar y volver a leer finalizada.
            assertEquals(o.getString("final"), hex(Psbt.extraer(Psbt.parse(Psbt.serialize(d))!!)!!))
        }
    }
}
