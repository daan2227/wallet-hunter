package com.hunter.btc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Shamir256Test {
    private val sec = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test fun cualquierSubconjuntoDeKRecompone() {
        val partes = Shamir256.repartir(sec, 3, 5, java.util.Random(1))
        for (a in 0 until 5) for (b in a + 1 until 5) for (c in b + 1 until 5) {
            val r = Shamir256.combinar(listOf(partes[a], partes[b], partes[c]))
            assertTrue(r is Shamir256.Resultado.Ok)
            assertArrayEquals(sec, (r as Shamir256.Resultado.Ok).secreto)
        }
    }
    @Test fun parteRepetidaEsError() {
        val p = Shamir256.repartir(sec, 2, 3, java.util.Random(2))
        assertTrue(Shamir256.combinar(listOf(p[0], p[0])) is Shamir256.Resultado.Error)
    }
    @Test fun indiceCeroEsError() {
        assertTrue(Shamir256.combinar(listOf(0 to ByteArray(32), 1 to ByteArray(32))) is Shamir256.Resultado.Error)
    }
    @Test fun menosDeKNoDaElSecreto() {
        val p = Shamir256.repartir(sec, 3, 5, java.util.Random(3))
        val r = Shamir256.combinar(listOf(p[0], p[1])) as Shamir256.Resultado.Ok
        assertFalse(r.secreto.contentEquals(sec))
    }

    @Test fun parteNuevaLlevaKYCuentaElUmbral() {
        val rnd = java.util.Random(7)
        val sec = ByteArray(32) { (it * 7 + 3).toByte() }
        val partes = Shamir256.repartir(sec, 3, 5, rnd)
        val hex = partes.map { (x, y) -> "%02x".format(3) + "%02x".format(x) + y.joinToString("") { "%02x".format(it.toInt() and 0xFF) } }
        val lectura = Shamir256.leerParte(hex[0])!!
        assertTrue(lectura.k == 3 && lectura.x == partes[0].first)
        assertArrayEquals(partes[0].second, lectura.y)
    }

    @Test fun parteAntiguaSinKSeLeeSinK() {
        val y = ByteArray(32) { 9 }
        val hex = "05" + y.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val lectura = Shamir256.leerParte(hex)!!
        assertTrue(lectura.k == null && lectura.x == 5)
    }

    @Test fun basuraNoEsParte() {
        assertTrue(Shamir256.leerParte("zz") == null)
        assertTrue(Shamir256.leerParte("00" + "11".repeat(32)) == null)   // x = 0
        assertTrue(Shamir256.leerParte("01" + "11".repeat(31)) == null)   // largo 64
        assertTrue(Shamir256.leerParte("01" + "11".repeat(32)) != null)   // antigua válida
    }
}
