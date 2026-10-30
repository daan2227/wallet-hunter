package com.hunter.btc

import java.math.BigInteger

/**
 * Lo que necesita un minero de Bitcoin, sin Android: la cabecera de 80 bytes, el doble SHA-256 con el
 * "midstate" (los primeros 64 bytes no cambian al probar nonces, así que su compresión se hace una vez), el
 * trabajo que manda un pool por Stratum v1 (mining.notify) y las cuentas de probabilidad. Puro: MineroTest,
 * con la cabecera del bloque 1 y su coinbase partida como la partiría un pool.
 *
 * Un teléfono hace unos pocos millones de hashes por segundo; la red, cientos de trillones por segundo
 * (cientos de EH/s). Encontrar un bloque solo es una lotería con premio casi imposible: las cuentas lo dicen.
 */
object Minero {
    private val K = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e)
    private val H0 = intArrayOf(0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6, 0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19)

    /** Una compresión de SHA-256: [w] trae los 16 primeros (big-endian) y tiene sitio para 64. */
    private fun comprimir(estado: IntArray, w: IntArray, salida: IntArray) {
        for (t in 16 until 64) {
            val a = w[t - 15]; val b = w[t - 2]
            val s0 = Integer.rotateRight(a, 7) xor Integer.rotateRight(a, 18) xor (a ushr 3)
            val s1 = Integer.rotateRight(b, 17) xor Integer.rotateRight(b, 19) xor (b ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = estado[0]; var b = estado[1]; var c = estado[2]; var d = estado[3]
        var e = estado[4]; var f = estado[5]; var g = estado[6]; var h = estado[7]
        for (t in 0 until 64) {
            val t1 = h + (Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)) +
                ((e and f) xor (e.inv() and g)) + K[t] + w[t]
            val t2 = (Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)) +
                ((a and b) xor (a and c) xor (b and c))
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        salida[0] = estado[0] + a; salida[1] = estado[1] + b; salida[2] = estado[2] + c; salida[3] = estado[3] + d
        salida[4] = estado[4] + e; salida[5] = estado[5] + f; salida[6] = estado[6] + g; salida[7] = estado[7] + h
    }

    private fun be(b: ByteArray, o: Int) =
        ((b[o].toInt() and 255) shl 24) or ((b[o + 1].toInt() and 255) shl 16) or ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255)

    /**
     * Prueba nonces sobre una cabecera de 80 bytes (los de [cabecera] 76..79 se ignoran). Reutiliza sus
     * arrays: uno por hilo. [hash] devuelve la palabra más alta del hash (la que lleva los ceros).
     */
    class Buscador(cabecera: ByteArray) {
        private val mid = IntArray(8)
        private val w = IntArray(64); private val w2 = IntArray(64)
        private val h1 = IntArray(8); val h2 = IntArray(8)
        private val cola = IntArray(3)

        init {
            require(cabecera.size >= 76)
            for (i in 0 until 16) w[i] = be(cabecera, i * 4)
            comprimir(H0, w, mid)
            for (i in 0 until 3) cola[i] = be(cabecera, 64 + i * 4)
        }

        /** Doble SHA-256 con este nonce (como entero; en la cabecera va en little-endian). Deja el resultado en [h2]. */
        fun hash(nonce: Int) {
            w[0] = cola[0]; w[1] = cola[1]; w[2] = cola[2]; w[3] = Integer.reverseBytes(nonce)
            w[4] = Int.MIN_VALUE; for (i in 5 until 15) w[i] = 0; w[15] = 640
            comprimir(mid, w, h1)
            for (i in 0 until 8) w2[i] = h1[i]
            w2[8] = Int.MIN_VALUE; for (i in 9 until 15) w2[i] = 0; w2[15] = 256
            comprimir(H0, w2, h2)
        }

        /** El hash en bytes, en el orden en que se escribe (el de los exploradores, con los ceros delante). */
        fun hashTexto(): String = (7 downTo 0).joinToString("") { "%08x".format(Integer.reverseBytes(h2[it])) }

        /** Ceros a la izquierda del hash tal como se escribe. */
        fun ceros(): Int {
            var n = 0
            for (i in 7 downTo 0) { val v = Integer.reverseBytes(h2[i]); if (v == 0) n += 32 else return n + Integer.numberOfLeadingZeros(v) }
            return n
        }

        /** ¿hash ≤ objetivo? (objetivo como número de 256 bits). */
        fun cumple(objetivo: BigInteger): Boolean = BigInteger(1, Descriptor.bytes(hashTexto())) <= objetivo
    }

    fun dsha(b: ByteArray): ByteArray {
        val m = java.security.MessageDigest.getInstance("SHA-256")
        return m.digest(m.digest(b))
    }

    private fun le32(x: Long) = byteArrayOf(x.toByte(), (x shr 8).toByte(), (x shr 16).toByte(), (x shr 24).toByte())

    /** El trabajo de mining.notify. Los campos de texto son los del pool, tal cual. */
    class Trabajo(val id: String, val prevhash: String, val coinb1: String, val coinb2: String, val ramas: List<String>,
                  val version: String, val nbits: String, val ntime: String, val limpiar: Boolean)

    /** Cabecera de 80 bytes (nonce 0) para un trabajo de Stratum con estos extranonces. */
    fun cabecera(t: Trabajo, extranonce1: String, extranonce2: String, ntime: String = t.ntime): ByteArray {
        var raiz = dsha(Descriptor.bytes(t.coinb1 + extranonce1 + extranonce2 + t.coinb2))
        for (r in t.ramas) raiz = dsha(raiz + Descriptor.bytes(r))
        // Stratum manda prevhash con cada palabra de 4 bytes dada la vuelta; versión, bits y hora en big-endian.
        val p = Descriptor.bytes(t.prevhash)
        val prev = ByteArray(32) { p[(it / 4) * 4 + 3 - it % 4] }
        return Descriptor.bytes(t.version).reversedArray() + prev + raiz +
            Descriptor.bytes(ntime).reversedArray() + Descriptor.bytes(t.nbits).reversedArray() + ByteArray(4)
    }

    /** El objetivo de dificultad 1 (bits 1d00ffff). */
    val OBJETIVO_1: BigInteger = BigInteger("00000000FFFF0000000000000000000000000000000000000000000000000000", 16)

    /** Objetivo de los "bits" compactos de la cabecera. */
    fun objetivoDeBits(bits: Long): BigInteger {
        val exp = (bits ushr 24).toInt(); val man = BigInteger.valueOf(bits and 0x7fffff)
        return if (exp <= 3) man.shiftRight(8 * (3 - exp)) else man.shiftLeft(8 * (exp - 3))
    }

    /** Objetivo para una dificultad de pool (mining.set_difficulty), que puede ser fraccionaria. */
    fun objetivoDeDificultad(d: Double): BigInteger =
        java.math.BigDecimal(OBJETIVO_1).divide(java.math.BigDecimal(d), java.math.MathContext(90)).toBigInteger()

    /** Segundos esperados para encontrar un bloque con [hashrate] H/s y la dificultad de la red. */
    fun segundosPorBloque(hashrate: Double, dificultadRed: Double) = dificultadRed * 4_294_967_296.0 / hashrate

    /** Probabilidad de encontrar al menos un bloque en [segundos]. */
    fun probabilidad(hashrate: Double, dificultadRed: Double, segundos: Double): Double =
        -Math.expm1(-segundos / segundosPorBloque(hashrate, dificultadRed))

    /** "una vez cada 12 millones de años" y similares, para dar una idea de la escala. */
    fun escala(segundos: Double): String {
        val anos = segundos / 31_557_600
        return when {
            segundos < 3600 -> "%.0f minutes".format(java.util.Locale.US, segundos / 60)
            segundos < 86_400 * 2 -> "%.1f hours".format(java.util.Locale.US, segundos / 3600)
            anos < 2 -> "%.0f days".format(java.util.Locale.US, segundos / 86_400)
            anos < 1e3 -> "%.0f years".format(java.util.Locale.US, anos)
            anos < 1e6 -> "%.0f thousand years".format(java.util.Locale.US, anos / 1e3)
            anos < 1e9 -> "%.1f million years".format(java.util.Locale.US, anos / 1e6)
            else -> "%.1f billion years".format(java.util.Locale.US, anos / 1e9)
        }
    }

    /** Nonce para mining.submit: el entero en hexadecimal big-endian (en la cabecera va al revés). */
    fun nonceTexto(n: Int) = "%08x".format(n)

    fun cabeceraCon(c: ByteArray, nonce: Int) = c.copyOf(76) + le32(nonce.toLong() and 0xffffffffL)

    // ── Tandas de nonces (nativo o Kotlin) ──

    /**
     * Prueba [cuantos] nonces desde [desde] (sin pasar de 0xffffffff) con [objetivo] (32 bytes, big-endian).
     * [salida] = {nonce que cumple o -1, ceros del mejor hash, nonce del mejor}. Devuelve los hashes hechos.
     * Es la firma de HunterEngine.minar (con las instrucciones SHA-256 del procesador).
     */
    fun interface Tanda { fun hacer(cab: ByteArray, desde: Int, cuantos: Int, objetivo: ByteArray, salida: LongArray): Long }

    /** La misma tanda en Kotlin: el respaldo si el motor nativo no pasa la autoprueba. */
    val tandaKotlin = Tanda { cab, desde, cuantos, objetivo, salida ->
        val b = Buscador(cab); val obj = BigInteger(1, objetivo)
        salida[0] = -1; salida[1] = -1; salida[2] = desde.toLong() and 0xffffffffL
        var n = desde.toLong() and 0xffffffffL; var hechos = 0L
        while (hechos < cuantos && n <= 0xffffffffL) {
            b.hash(n.toInt()); hechos++
            val alta = Integer.reverseBytes(b.h2[7])
            val z = if (alta != 0) Integer.numberOfLeadingZeros(alta) else b.ceros()
            if (z > salida[1]) { salida[1] = z.toLong(); salida[2] = n }
            if (b.cumple(obj)) { salida[0] = n; break }
            n++
        }
        hechos
    }

    /** Cabecera del bloque 1 con nonce 0, y su nonce: para comprobar que una Tanda mina bien. */
    const val CABECERA_BLOQUE_1 = "010000006fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000982051fd1e4ba744bbbe680e1fee14677ba1a3c3540bf7b1cdb606e857233e0e61bc6649ffff001d00000000"
    const val NONCE_BLOQUE_1 = 2_573_394_689L

    /** ¿La tanda encuentra el nonce del bloque 1 (y no antes) en una ventana a su alrededor? */
    fun autoprueba(t: Tanda): Boolean = try {
        val sal = LongArray(3)
        val desde = (NONCE_BLOQUE_1 - 3000).toInt()
        val h = t.hacer(Descriptor.bytes(CABECERA_BLOQUE_1), desde, 6000, objetivoBytes(objetivoDeBits(0x1d00ffff)), sal)
        sal[0] == NONCE_BLOQUE_1 && h == 3001L && sal[1] >= 32
    } catch (e: Throwable) { false }

    fun objetivoBytes(o: BigInteger): ByteArray {
        val b = o.toByteArray(); val r = ByteArray(32)
        val n = minOf(32, b.size); System.arraycopy(b, b.size - n, r, 32 - n, n)
        return if (o.bitLength() > 256) ByteArray(32) { -1 } else r
    }

    /** Objetivo de "N ceros delante": 2^(256−N) − 1. */
    fun objetivoCeros(n: Int): BigInteger = BigInteger.ONE.shiftLeft(256 - n).subtract(BigInteger.ONE)

    /** Dificultad de un hash (como la de un share): objetivo de dificultad 1 / hash. */
    fun dificultadDe(hashTexto: String): Double {
        val h = BigInteger(1, Descriptor.bytes(hashTexto)); if (h.signum() == 0) return Double.MAX_VALUE
        return java.math.BigDecimal(OBJETIVO_1).divide(java.math.BigDecimal(h), java.math.MathContext.DECIMAL64).toDouble()
    }

    /** Dificultad del hash de [cab] con [nonce]. */
    fun dificultadDe(cab: ByteArray, nonce: Long): Double = Buscador(cab).run { hash(nonce.toInt()); dificultadDe(hashTexto()) }

    /** 1.23 k, 4.5 M, 150 T… */
    fun corto(x: Double): String {
        val u = listOf("" to 1.0, "k" to 1e3, "M" to 1e6, "G" to 1e9, "T" to 1e12, "P" to 1e15, "E" to 1e18)
        val (s, v) = u.lastOrNull { x >= it.second } ?: u[0]
        return "%.3g%s".format(java.util.Locale.US, x / v, if (s.isEmpty()) "" else " $s")
    }
}
