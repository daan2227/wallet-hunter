package com.hunter.btc

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recibir con Silent Payments: los casos de recepción sin etiquetas de los vectores oficiales de BIP352. Con
 * el tweak (lo que da un servidor de tweaks) y las claves, salen la dirección, las salidas nuestras, su ajuste
 * y la firma BIP340 con la privada de gasto + ajuste (mensaje sha256("message"), aux sha256("random auxiliary data")).
 */
class SpRecibirTest {
    private val casos by lazy { JSONArray(javaClass.classLoader!!.getResourceAsStream("sp_recibir.json")!!.bufferedReader().readText()) }
    private val ec = EcLentoOps.ec
    private fun sha(s: String) = Descriptor.hex(java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

    @Test fun vectoresBip352() {
        var salidas = 0
        for (i in 0 until casos.length()) {
            val c = casos.getJSONObject(i); val nombre = c.getString("comment")
            val scanPub = ec.op("pub", c.getString("scan"), ""); val spendPub = ec.op("pub", c.getString("spend"), "")
            assertEquals(nombre, c.getString("address"), SilentPayments.codificar(Descriptor.bytes(scanPub), Descriptor.bytes(spendPub), false))
            assertEquals(Descriptor.bytes(scanPub).toList(), SilentPayments.decodificar(c.getString("address"), false)!!.first.toList())
            val outs = c.getJSONArray("outputs").let { a -> List(a.length()) { a.getString(it) } }
            val r = SilentPayments.escanear(ec, c.getString("scan"), spendPub, c.getString("tweak"), outs)
            val esperadas = c.getJSONArray("found")
            assertEquals(nombre, esperadas.length(), r.size)
            for (k in 0 until esperadas.length()) {
                val e = esperadas.getJSONObject(k)
                val m = r.single { it.x == e.getString("x") }
                assertEquals(nombre, e.getString("t"), m.t)
                val d = SilentPayments.privada(ec, c.getString("spend"), m.t)!!
                assertEquals(nombre, m.x, ec.op("pub", d, "").substring(2))
                val firma = EcLentoNostr.curva.op("schnorr", d, sha("message") + "|" + sha("random auxiliary data"))
                assertEquals(nombre, e.getString("sig"), firma)
                salidas++
            }
        }
        assertEquals(21, salidas)
    }

    @Test fun ajenasNoCoinciden() {
        val c = casos.getJSONObject(0)
        val spendPub = ec.op("pub", c.getString("spend"), "")
        assertTrue(SilentPayments.escanear(ec, c.getString("scan"), spendPub, c.getString("tweak"), listOf("11".repeat(32))).isEmpty())
        // Con la clave de escaneo equivocada no se encuentra nada.
        assertTrue(SilentPayments.escanear(ec, c.getString("spend"), spendPub, c.getString("tweak"),
            c.getJSONArray("outputs").let { a -> List(a.length()) { a.getString(it) } }).isEmpty())
    }
}
