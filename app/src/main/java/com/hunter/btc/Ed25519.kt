package com.hunter.btc

import java.math.BigInteger

/**
 * Verificación de firmas Ed25519 (RFC 8032), para DNSSEC (algoritmo 15). Android sólo la trae desde la API 33
 * y la app arranca en la 26, así que va aquí, con BigInteger: lento, pero se usa unas pocas veces por consulta.
 * Sólo VERIFICA (no hay claves privadas en juego). Puro: Ed25519Test con los vectores de la RFC.
 */
object Ed25519 {
    private val P = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
    private val L = BigInteger.ONE.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
    private val D = BigInteger.valueOf(-121665).mod(P) * BigInteger.valueOf(121666).modInverse(P) % P
    private val I = BigInteger.TWO.modPow((P - BigInteger.ONE).shiftRight(2), P)   // raíz de -1
    private val BY = BigInteger.valueOf(4) * BigInteger.valueOf(5).modInverse(P) % P
    private val B = Punto(recuperarX(BY, false)!!, BY)

    private data class Punto(val x: BigInteger, val y: BigInteger)

    private fun sumar(a: Punto, b: Punto): Punto {
        val xx = a.x * b.x % P; val yy = a.y * b.y % P
        val t = D * xx % P * yy % P
        val x = (a.x * b.y + b.x * a.y) % P * (BigInteger.ONE + t).modInverse(P) % P
        val y = (yy + xx) % P * (BigInteger.ONE - t).mod(P).modInverse(P) % P
        return Punto(x.mod(P), y.mod(P))
    }

    private fun multiplicar(k: BigInteger, p0: Punto): Punto {
        var r = Punto(BigInteger.ZERO, BigInteger.ONE); var p = p0; var e = k
        while (e.signum() > 0) { if (e.testBit(0)) r = sumar(r, p); p = sumar(p, p); e = e.shiftRight(1) }
        return r
    }

    private fun recuperarX(y: BigInteger, impar: Boolean): BigInteger? {
        if (y >= P) return null
        val u = (y * y - BigInteger.ONE).mod(P); val v = (D * y * y + BigInteger.ONE).mod(P)
        val x2 = u * v.modInverse(P) % P
        if (x2.signum() == 0) return if (impar) null else BigInteger.ZERO
        var x = x2.modPow((P + BigInteger.valueOf(3)).shiftRight(3), P)
        if ((x * x - x2).mod(P).signum() != 0) x = x * I % P
        if ((x * x - x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != impar) x = P - x
        return x
    }

    private fun leEntero(b: ByteArray) = BigInteger(1, b.reversedArray())

    private fun decodificar(b: ByteArray): Punto? {
        if (b.size != 32) return null
        val c = b.copyOf(); val impar = (c[31].toInt() and 0x80) != 0
        c[31] = (c[31].toInt() and 0x7f).toByte()
        val y = leEntero(c)
        return recuperarX(y, impar)?.let { Punto(it, y) }
    }

    private fun codificar(p: Punto): ByteArray {
        val b = ByteArray(32); val y = p.y.toByteArray().reversedArray()
        System.arraycopy(y, 0, b, 0, minOf(32, y.size))
        if (p.x.testBit(0)) b[31] = (b[31].toInt() or 0x80).toByte()
        return b
    }

    /** ¿Es [firma] (64 bytes) la firma Ed25519 de [mensaje] por la pública [pub] (32 bytes)? */
    fun verificar(pub: ByteArray, mensaje: ByteArray, firma: ByteArray): Boolean {
        if (pub.size != 32 || firma.size != 64) return false
        val a = decodificar(pub) ?: return false
        val r = decodificar(firma.copyOfRange(0, 32)) ?: return false
        val s = leEntero(firma.copyOfRange(32, 64))
        if (s >= L) return false
        val h = leEntero(java.security.MessageDigest.getInstance("SHA-512").digest(firma.copyOfRange(0, 32) + pub + mensaje)).mod(L)
        // [S]B = R + [h]A, comparado por su codificación.
        return codificar(multiplicar(s, B)).contentEquals(codificar(sumar(r, multiplicar(h, a))))
    }
}
