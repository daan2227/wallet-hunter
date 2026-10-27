package com.hunter.btc

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectores generados con la implementación de referencia en JavaScript (@ngraveio/bc-ur 1.1.13). */
class UrTest {
    private fun h(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private val lineas by lazy {
        javaClass.classLoader!!.getResourceAsStream("ur_vectores.jsonl")!!.bufferedReader().readLines().map { JSONObject(it) }
    }

    @Test fun eleccionDeFragmentosIgualQueLaReferencia() {
        val cf = lineas.last().getJSONArray("cf")
        for (i in 0 until cf.length()) {
            val v = cf.getJSONArray(i)
            val esperado = (0 until v.getJSONArray(3).length()).map { v.getJSONArray(3).getInt(it) }
            assertEquals("seq=${v.getLong(0)} n=${v.getInt(1)}", esperado, Ur.elegirFragmentos(v.getLong(0), v.getInt(1), v.getLong(2)))
        }
    }

    @Test fun codificadorIgualQueLaReferencia() {
        for (o in lineas.dropLast(1)) {
            val msg = h(o.getString("msg"))
            val enc = Ur.Codificador("bytes", Ur.cborBytes(msg), o.getInt("maxf"))
            val partes = o.getJSONArray("parts")
            for (i in 0 until partes.length()) assertEquals("len=${msg.size} parte $i", partes.getString(i), enc.siguiente())
        }
    }

    @Test fun decodificaConPartesSueltasYMezcladas() {
        for (o in lineas.dropLast(1)) {
            val msg = h(o.getString("msg"))
            val partes = o.getJSONArray("parts")
            val n = partes.length()
            // Saltándose uno de cada tres (como una cámara que pierde fotogramas), en mayúsculas como un QR alfanumérico.
            val d = Ur.Decodificador()
            for (i in 0 until n) { if (i % 3 == 1 && n > 8) continue; d.recibir(partes.getString(i).uppercase()); if (d.listo) break }
            assertTrue("len=${msg.size}", d.listo)
            assertArrayEquals(msg, Ur.deCborBytes(d.resultado!!))
            assertEquals("bytes", d.tipo)
        }
    }

    @Test fun noAceptaUnLargoQueNoCabeEnLosFragmentos() {
        // Parte 1 de 2 con fragmentos de 10 bytes declarando un mensaje de 40 MB: antes se aceptaba y al
        // completar se reservaban 40 MB.
        val o = java.io.ByteArrayOutputStream()
        o.write(0x85); o.write(0x01); o.write(0x02); o.write(0x1a); o.write(byteArrayOf(0x02, 0x62, 0x5a, 0x00))
        o.write(0x1a); o.write(byteArrayOf(0, 0, 0, 1)); o.write(0x4a); o.write(ByteArray(10))
        val parte = "ur:bytes/1-2/" + Ur.bytewordsMin(o.toByteArray())
        assertEquals(false, Ur.Decodificador().recibir(parte))
    }

    @Test fun rechazaBasuraYChecksumMalo() {
        val d = Ur.Decodificador()
        assertEquals(false, d.recibir("hola"))
        assertEquals(false, d.recibir("ur:bytes/1-3/lpadaxcsencylobemohsgmoyadhdeynteelblrcygldwvarflojtcywyjytpdkfwprylienshnjnpluypmamtkmybsjkspvseesawmrltadlnnzmhfmd"))
    }
}
