package com.hunter.btc

/**
 * Filtros de bloque BIP158 (Golomb-Rice, P = 19, M = 784931, SipHash-2-4 con los 16 primeros bytes del hash
 * del bloque en orden interno): ¿contiene el filtro alguno de estos elementos? Sin falsos negativos; falsos
 * positivos 1 de cada 784931 por elemento. Lo usa el escaneo de Silent Payments para no bajar las salidas de
 * cada bloque. Puro: GcsTest, con los vectores de BIP158.
 */
object Gcs {
    private const val P = 19
    private const val M = 784_931L

    private fun le64(b: ByteArray, o: Int): Long { var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xff); return v }

    fun siphash(k0: Long, k1: Long, m: ByteArray): Long {
        var v0 = k0 xor 0x736f6d6570736575L; var v1 = k1 xor 0x646f72616e646f6dL
        var v2 = k0 xor 0x6c7967656e657261L; var v3 = k1 xor 0x7465646279746573L
        fun ronda() {
            v0 += v1; v1 = java.lang.Long.rotateLeft(v1, 13); v1 = v1 xor v0; v0 = java.lang.Long.rotateLeft(v0, 32)
            v2 += v3; v3 = java.lang.Long.rotateLeft(v3, 16); v3 = v3 xor v2
            v0 += v3; v3 = java.lang.Long.rotateLeft(v3, 21); v3 = v3 xor v0
            v2 += v1; v1 = java.lang.Long.rotateLeft(v1, 17); v1 = v1 xor v2; v2 = java.lang.Long.rotateLeft(v2, 32)
        }
        val fin = m.size - m.size % 8
        var i = 0
        while (i < fin) { val x = le64(m, i); v3 = v3 xor x; ronda(); ronda(); v0 = v0 xor x; i += 8 }
        var b = (m.size.toLong() and 0xff) shl 56
        for (j in 0 until m.size % 8) b = b or ((m[fin + j].toLong() and 0xff) shl (8 * j))
        v3 = v3 xor b; ronda(); ronda(); v0 = v0 xor b
        v2 = v2 xor 0xff; ronda(); ronda(); ronda(); ronda()
        return v0 xor v1 xor v2 xor v3
    }

    /** (a · b) >> 64 sin signo: lleva el hash de 64 bits al rango [0, F). */
    private fun altoSinSigno(a: Long, b: Long): Long {
        val aL = a and 0xffffffffL; val aH = a ushr 32; val bL = b and 0xffffffffL; val bH = b ushr 32
        val ll = aL * bL; val lh = aL * bH; val hl = aH * bL; val hh = aH * bH
        val medio = (ll ushr 32) + (lh and 0xffffffffL) + (hl and 0xffffffffL)
        return hh + (lh ushr 32) + (hl ushr 32) + (medio ushr 32)
    }

    private class Bits(val b: ByteArray, var p: Int) {
        private var bit = 0
        fun uno(): Int { if (p >= b.size) throw IndexOutOfBoundsException(); val v = (b[p].toInt() ushr (7 - bit)) and 1; if (++bit == 8) { bit = 0; p++ }; return v }
        fun varios(n: Int): Long { var v = 0L; repeat(n) { v = (v shl 1) or uno().toLong() }; return v }
    }

    /**
     * [filtro]: N (CompactSize) y el conjunto codificado. [hashBloque]: el hash tal como se escribe (hex, el de
     * los exploradores). null si el filtro está mal formado.
     */
    fun contieneAlguno(filtro: ByteArray, hashBloque: String, elementos: List<ByteArray>): Boolean? {
        if (filtro.isEmpty()) return null
        val (n, ini) = when (val x = filtro[0].toInt() and 0xff) {
            0xfd -> (le64(filtro.copyOf(9).also { it.fill(0, 3, 9) }, 1)) to 3
            0xfe -> (le64(filtro.copyOf(9).also { it.fill(0, 5, 9) }, 1)) to 5
            0xff -> return null
            else -> x.toLong() to 1
        }
        if (n == 0L || elementos.isEmpty()) return false
        val clave = Descriptor.bytes(hashBloque).reversedArray()
        val k0 = le64(clave, 0); val k1 = le64(clave, 8)
        val f = n * M
        val buscados = elementos.map { altoSinSigno(siphash(k0, k1, it), f) }.sorted()
        val r = Bits(filtro, ini)
        var valor = 0L; var j = 0
        return try {
            for (i in 0 until n) {
                var q = 0L; while (r.uno() == 1) q++
                valor += (q shl P) + r.varios(P)
                while (j < buscados.size && buscados[j] < valor) j++
                if (j == buscados.size) return false
                if (buscados[j] == valor) return true
            }
            false
        } catch (e: IndexOutOfBoundsException) { null }
    }
}
