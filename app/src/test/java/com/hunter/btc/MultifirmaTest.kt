package com.hunter.btc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2-de-3 con tres monederos de Bitcoin Core 30 (regtest). Comprobado además a mano: la PSBT que arma la
 * app, con la firma de la clave 3 puesta por la app y la de la clave 1 por Core, la aceptó la mempool de
 * Core, y la finalización de la app dio la misma transacción que finalizepsbt.
 */
class MultifirmaTest {
    private val o by lazy { JSONObject(javaClass.classLoader!!.getResourceAsStream("multifirma_core.json")!!.bufferedReader().readText()) }
    private fun motor() = MotorMultifirma.leer(o.getString("pub0"), EcLento.derivador)!!.first!!

    @Test fun direccionesYDescriptorComoCore() {
        val m = motor()
        assertEquals(2, m.k); assertEquals(3, m.claves.size)
        for (c in 0..1) { val s = o.getJSONArray("spks$c"); for (i in 0 until s.length()) assertEquals("$c/$i", s.getString(i), Descriptor.hex(m.spk(c, i)!!)) }
        // El canónico se vuelve a leer igual.
        assertEquals(m.texto(), MotorMultifirma.leer(m.texto(), EcLento.derivador)!!.first!!.texto())
    }

    @Test fun psbtYFirmaPropia() {
        val m = motor(); val v = o.getLong("valor")
        val p = m.psbt(listOf(Moneda(0, 0, o.getString("txid"), o.getInt("vout"), v)), Descriptor.bytes(o.getString("destspk")),
            100_000_000L, v - 100_005_000L, 2, 0) { null }!!
        val d = Psbt.parse(p)!!
        assertTrue(d.inputs[0].any { it.key.size == 1 && it.key[0].toInt() == 0x05 })               // script de testigo
        assertEquals(3, d.inputs[0].count { it.key.size == 34 && it.key[0].toInt() == 0x06 })      // las 3 derivaciones
        assertTrue(d.outputs[1].any { it.key.size == 1 && it.key[0].toInt() == 0x01 })              // el cambio, con su script
        val priv = EcLentoPriv.derivar(o.getString("tprv3"), "48h/1h/0h/2h/0/0")
        val pub = Descriptor.bytes(EcLentoOps.ec.op("pub", priv, ""))
        assertEquals(1, Psbt.firmarWsh(d, pub) { z -> EcLentoPriv.firmar(priv, z) })
        assertEquals(0, Psbt.firmarWsh(d, pub) { z -> EcLentoPriv.firmar(priv, z) })                 // no repite
        // Con una sola firma no se puede finalizar.
        assertEquals(0, Psbt.finalizar(d) { s, f -> m.testigoWsh(s, f) }); assertNull(Psbt.extraer(d))
    }

    @Test fun finalizaIgualQueCore() {
        val m = motor()
        val d = Psbt.parse(java.util.Base64.getDecoder().decode(o.getString("psbt2")))!!
        assertEquals(1, Psbt.finalizar(d) { s, f -> m.testigoWsh(s, f) })
        assertEquals(o.getString("final"), Descriptor.hex(Psbt.extraer(d)!!))
    }

    @Test fun rechazaLoQueNoEsMultifirma() {
        assertNull(MotorMultifirma.leer("wpkh(xpub)", EcLento.derivador))
        assertNotNull(MotorMultifirma.leer("wsh(sortedmulti(2,xpub1,xpub2))", EcLento.derivador)!!.second)
    }
}
