package com.hunter.btc

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectores de la librería oficial de Coinkite (npm "bbqr" 1.2.0), en H, 2 y Z. */
class BbqrTest {
    private fun h(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private val vec by lazy {
        javaClass.classLoader!!.getResourceAsStream("bbqr_vectores.jsonl")!!.bufferedReader().readLines().map { JSONObject(it) }
    }

    @Test fun leeLoQueEscribeLaLibreriaOficial() {
        for (o in vec) {
            val j = Bbqr.Juntador()
            val partes = o.getJSONArray("parts")
            // En orden inverso y con una repetida: la cámara no las lee en orden.
            for (i in partes.length() - 1 downTo 0) assertTrue(j.recibir(partes.getString(i)))
            j.recibir(partes.getString(0))
            assertTrue(j.listo); assertEquals('P', j.tipo)
            assertArrayEquals("enc=${o.getString("enc")}", h(o.getString("raw")), j.resultado())
        }
    }

    @Test fun idaYVuelta() {
        val r = java.util.Random(1)
        for (n in listOf(1, 4, 5, 6, 299, 300, 301, 4000)) {
            val d = ByteArray(n).also { r.nextBytes(it) }
            val partes = Bbqr.partir(d, 'P', 120)
            assertTrue(partes.all { it.startsWith("B$2P") && it.all { c -> c in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:" } })
            val j = Bbqr.Juntador(); partes.forEach { j.recibir(it) }
            assertArrayEquals(d, j.resultado())
        }
    }

    @Test fun noMezclaFicheros() {
        val a = Bbqr.partir(ByteArray(500) { 1 }, 'P', 200); val b = Bbqr.partir(ByteArray(900) { 2 }, 'P', 200)
        val j = Bbqr.Juntador(); j.recibir(a[0]); assertFalse(j.recibir(b[1]))
        assertNull(j.resultado())
    }
}
