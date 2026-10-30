package com.hunter.btc

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los filtros básicos de los vectores de BIP158 (testnet): contienen cada script de salida y previo, y no otros. */
class GcsTest {
    private val v by lazy { JSONArray(javaClass.classLoader!!.getResourceAsStream("bip158_filtros.json")!!.bufferedReader().readText()) }

    @Test fun vectoresBip158() {
        var vistos = 0
        for (i in 0 until v.length()) {
            val o = v.getJSONObject(i); val f = Descriptor.bytes(o.getString("filter")); val h = o.getString("hash")
            val el = o.getJSONArray("elements")
            for (k in 0 until el.length()) {
                assertEquals(o.getString("notes"), true, Gcs.contieneAlguno(f, h, listOf(Descriptor.bytes(el.getString(k))))); vistos++
            }
            // Algo que no está (un falso positivo aquí sería 1 entre ~800 000 por elemento).
            assertEquals(false, Gcs.contieneAlguno(f, h, listOf("no está".toByteArray(), ByteArray(32) { 7 })))
            if (el.length() == 0) assertEquals(false, Gcs.contieneAlguno(f, h, listOf(ByteArray(1))))
        }
        assertEquals(40, vistos)
    }

    @Test fun sipHashDeReferencia() {
        // Vector de la implementación de referencia: clave 00..0f, mensaje 00..0e (15 bytes).
        val k = ByteArray(16) { it.toByte() }
        fun le(o: Int) = (0 until 8).fold(0L) { acc, i -> acc or ((k[o + i].toLong() and 0xff) shl (8 * i)) }
        assertEquals(0xa129ca6149be45e5uL.toLong(), Gcs.siphash(le(0), le(8), ByteArray(15) { it.toByte() }))
        assertTrue(Gcs.contieneAlguno(byteArrayOf(0xff.toByte()), "00".repeat(32), listOf(ByteArray(1))) == null)
    }
}
