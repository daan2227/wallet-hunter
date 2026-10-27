package com.hunter.btc

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Herencia con older(10) en Bitcoin Core 30 (regtest). Comprobado además a mano: el gasto del dueño
 * (firmado y finalizado por la app) lo aceptó la mempool; el del heredero se rechazó con "non-BIP68-final"
 * hasta la confirmación 10 y se aceptó en ella; y la finalización de la app es la de finalizepsbt.
 */
class HerenciaTest {
    private val o by lazy { JSONObject(javaClass.classLoader!!.getResourceAsStream("herencia_core.json")!!.bufferedReader().readText()) }
    private fun motor() = MotorHerencia.leer(o.getString("pub0"), EcLento.derivador)!!.first!!

    @Test fun direccionesComoCore() {
        val m = motor(); assertEquals(10, m.bloques)
        for (c in 0..1) { val s = o.getJSONArray("spks$c"); for (i in 0 until s.length()) assertEquals(s.getString(i), Descriptor.hex(m.spk(c, i)!!)) }
        assertEquals(m.texto(), MotorHerencia.leer(m.texto(), EcLento.derivador)!!.first!!.texto())
    }

    @Test fun dueno() {
        val m = motor(); val v = o.getLong("valor")
        val d = Psbt.parse(m.psbt(listOf(Moneda(0, 0, o.getString("t1"), o.getInt("v1"), v)), Descriptor.bytes(o.getString("destspk")),
            50_000_000L, v - 50_005_000L, 1, 0) { null }!!)!!
        assertEquals(1L, d.tx.version); assertEquals(Psbt.SEQUENCIA, d.tx.ins[0].seq)
        val priv = EcLentoPriv.derivar(o.getString("tprv1"), "48h/1h/1h/2h/0/0")
        val pub = Descriptor.bytes(EcLentoOps.ec.op("pub", priv, ""))
        assertEquals(1, Psbt.firmarWsh(d, pub) { z -> EcLentoPriv.firmar(priv, z) })
        assertEquals(1, Psbt.finalizar(d) { s, f -> m.testigoWsh(s, f) })
        // Testigo del dueño: [firma, script]
        val w = d.inputs[0].first { it.key.size == 1 && it.key[0].toInt() == 0x08 }.value
        assertEquals(2, w[0].toInt())
    }

    @Test fun herederoConVersion2YSecuencia() {
        val m = motor(); m.modoHeredero = true; val v = o.getLong("valor")
        val d = Psbt.parse(m.psbt(listOf(Moneda(0, 1, o.getString("t2"), o.getInt("v2"), v)), Descriptor.bytes(o.getString("destspk")),
            50_000_000L, v - 50_005_000L, 2, 0) { null }!!)!!
        assertEquals(2L, d.tx.version); assertEquals(10L, d.tx.ins[0].seq)
        val f = Psbt.parse(java.util.Base64.getDecoder().decode(o.getString("heredero_firmada")))!!
        assertEquals(1, Psbt.finalizar(f) { s, x -> m.testigoWsh(s, x) })
        assertEquals(o.getString("heredero_final"), Descriptor.hex(Psbt.extraer(f)!!))
    }

    @Test fun numerosDeScript() {
        assertArrayEquals(byteArrayOf(0x5a), MotorHerencia.numero(10))
        assertArrayEquals(byteArrayOf(1, 0x11), MotorHerencia.numero(17))
        assertArrayEquals(byteArrayOf(2, 0x80.toByte(), 0), MotorHerencia.numero(128))
        assertArrayEquals(byteArrayOf(3, 0xff.toByte(), 0xff.toByte(), 0), MotorHerencia.numero(65535))
        assertNull(MotorHerencia.leer("wpkh(x)", EcLento.derivador))
        assertTrue(MotorHerencia.leer(o.getString("pub0").substringBefore('#').replace("older(10)", "older(70000)"), EcLento.derivador)!!.first == null)
    }
}
