package com.hunter.btc

/**
 * Argon2id (RFC 9106, versión 0x13) en Kotlin puro, y el BLAKE2b que necesita.
 *
 * Para derivar la clave del código y de las copias con un coste en MEMORIA, que es lo que encarece
 * probar claves en GPU o ASIC (PBKDF2 sólo cuesta cálculo). Sin clave secreta ni datos asociados: no
 * hacen falta aquí. Se comprobó byte a byte contra la implementación de referencia (ver Argon2idTest).
 *
 * Los carriles se calculan uno tras otro: el resultado es el mismo que en paralelo (el estándar define
 * así los segmentos), sólo que sin hilos. Con p = 1, que es lo que usa la app, no hay diferencia.
 */
object Argon2id {

    /** [mKiB] en KiB. Lanza IllegalArgumentException si los parámetros no valen. */
    fun derivar(password: ByteArray, salt: ByteArray, t: Int, mKiB: Int, p: Int, longitud: Int): ByteArray {
        require(t >= 1 && p in 1..255 && longitud in 4..1024 && salt.size >= 8) { "parámetros de Argon2" }
        require(mKiB >= 8 * p && mKiB <= 1 shl 22) { "memoria de Argon2" }
        val bloques = 4 * p * (mKiB / (4 * p))            // m', múltiplo de 4p
        val largoCarril = bloques / p
        val largoSegmento = largoCarril / 4

        val h0 = h0(password, salt, t, mKiB, p, longitud)
        val mem = LongArray(bloques * 128)
        val buf = ByteArray(1024)
        for (l in 0 until p) {
            for (i in 0..1) {
                val ini = h0.copyOf(72)
                putLe32(ini, 64, i); putLe32(ini, 68, l)
                hPrima(ini, 1024, buf)
                val off = (l * largoCarril + i) * 128
                for (w in 0 until 128) mem[off + w] = getLe64(buf, w * 8)
            }
        }
        java.util.Arrays.fill(h0, 0)

        val tmp = LongArray(128); val dir = LongArray(128); val entrada = LongArray(128); val r = LongArray(128)
        for (pasada in 0 until t) {
            for (corte in 0 until 4) {
                for (l in 0 until p) {
                    val independiente = pasada == 0 && corte < 2          // Argon2id: las dos primeras mitades de la 1.ª pasada
                    var inicio = 0
                    if (independiente) {
                        java.util.Arrays.fill(entrada, 0L); java.util.Arrays.fill(dir, 0L)
                        entrada[0] = pasada.toLong(); entrada[1] = l.toLong(); entrada[2] = corte.toLong()
                        entrada[3] = bloques.toLong(); entrada[4] = t.toLong(); entrada[5] = 2L
                    }
                    if (pasada == 0 && corte == 0) {
                        inicio = 2
                        if (independiente) siguientesDirecciones(entrada, dir, tmp)
                    }
                    for (i in inicio until largoSegmento) {
                        val actual = l * largoCarril + corte * largoSegmento + i
                        val previo = if (actual % largoCarril == 0) actual + largoCarril - 1 else actual - 1
                        val aleatorio: Long
                        if (independiente) {
                            if (i % 128 == 0) siguientesDirecciones(entrada, dir, tmp)
                            aleatorio = dir[i % 128]
                        } else aleatorio = mem[previo * 128]

                        val refCarril = if (pasada == 0 && corte == 0) l else ((aleatorio ushr 32) % p).toInt()
                        val mismo = refCarril == l
                        val area: Int = if (pasada == 0) {
                            if (corte == 0) i - 1
                            else if (mismo) corte * largoSegmento + i - 1
                            else corte * largoSegmento + (if (i == 0) -1 else 0)
                        } else {
                            if (mismo) largoCarril - largoSegmento + i - 1
                            else largoCarril - largoSegmento + (if (i == 0) -1 else 0)
                        }
                        var rel = aleatorio and 0xFFFFFFFFL
                        rel = (rel * rel) ushr 32
                        rel = area.toLong() - 1 - ((area.toLong() * rel) ushr 32)
                        val partida = if (pasada == 0) 0 else if (corte == 3) 0 else (corte + 1) * largoSegmento
                        val abs = ((partida + rel) % largoCarril).toInt()
                        val ref = refCarril * largoCarril + abs
                        comprimir(mem, previo * 128, ref * 128, actual * 128, pasada > 0, r, tmp)
                    }
                }
            }
        }

        val fin = LongArray(128)
        for (l in 0 until p) {
            val off = (l * largoCarril + largoCarril - 1) * 128
            for (w in 0 until 128) fin[w] = fin[w] xor mem[off + w]
        }
        for (w in 0 until 128) putLe64(buf, w * 8, fin[w])
        val salida = ByteArray(longitud)
        hPrima(buf.copyOf(1024), longitud, salida)
        java.util.Arrays.fill(mem, 0L); java.util.Arrays.fill(buf, 0); java.util.Arrays.fill(fin, 0L)
        return salida
    }

    // ── Bloques ──────────────────────────────────────────────────────────────

    /** Siguiente bloque de direcciones (modo independiente): dir = G(0, G(0, entrada)), con el contador en entrada[6]. */
    private fun siguientesDirecciones(entrada: LongArray, dir: LongArray, tmp: LongArray) {
        entrada[6]++
        System.arraycopy(entrada, 0, dir, 0, 128)
        permutarYMezclar(dir, tmp)        // G(0, entrada)
        permutarYMezclar(dir, tmp)        // G(0, ·) otra vez
    }

    /** x = P(x) ^ x, la «compresión» G con el primer argumento a cero. */
    private fun permutarYMezclar(x: LongArray, tmp: LongArray) {
        System.arraycopy(x, 0, tmp, 0, 128)
        permutar(tmp)
        for (w in 0 until 128) x[w] = x[w] xor tmp[w]
    }

    /** mem[destino] = P(mem[a] ^ mem[b]) ^ mem[a] ^ mem[b], y con [xor] también ^ lo que hubiera (pasadas 2+). */
    private fun comprimir(mem: LongArray, a: Int, b: Int, destino: Int, xor: Boolean, r: LongArray, tmp: LongArray) {
        for (w in 0 until 128) r[w] = mem[a + w] xor mem[b + w]
        System.arraycopy(r, 0, tmp, 0, 128)
        permutar(tmp)
        if (xor) for (w in 0 until 128) mem[destino + w] = mem[destino + w] xor r[w] xor tmp[w]
        else for (w in 0 until 128) mem[destino + w] = r[w] xor tmp[w]
    }

    private fun permutar(v: LongArray) {
        for (i in 0 until 8) ronda(v, 16 * i, 16 * i + 1, 16 * i + 2, 16 * i + 3, 16 * i + 4, 16 * i + 5, 16 * i + 6, 16 * i + 7,
            16 * i + 8, 16 * i + 9, 16 * i + 10, 16 * i + 11, 16 * i + 12, 16 * i + 13, 16 * i + 14, 16 * i + 15)
        for (i in 0 until 8) ronda(v, 2 * i, 2 * i + 1, 2 * i + 16, 2 * i + 17, 2 * i + 32, 2 * i + 33, 2 * i + 48, 2 * i + 49,
            2 * i + 64, 2 * i + 65, 2 * i + 80, 2 * i + 81, 2 * i + 96, 2 * i + 97, 2 * i + 112, 2 * i + 113)
    }

    private fun gb(v: LongArray, a: Int, b: Int, c: Int, d: Int) {
        var va = v[a]; var vb = v[b]; var vc = v[c]; var vd = v[d]
        va = va + vb + 2 * (va and M32) * (vb and M32)
        vd = (vd xor va).let { (it ushr 32) or (it shl 32) }
        vc = vc + vd + 2 * (vc and M32) * (vd and M32)
        vb = (vb xor vc).let { (it ushr 24) or (it shl 40) }
        va = va + vb + 2 * (va and M32) * (vb and M32)
        vd = (vd xor va).let { (it ushr 16) or (it shl 48) }
        vc = vc + vd + 2 * (vc and M32) * (vd and M32)
        vb = (vb xor vc).let { (it ushr 63) or (it shl 1) }
        v[a] = va; v[b] = vb; v[c] = vc; v[d] = vd
    }
    private const val M32 = 0xFFFFFFFFL

    private fun ronda(v: LongArray, i0: Int, i1: Int, i2: Int, i3: Int, i4: Int, i5: Int, i6: Int, i7: Int,
                      i8: Int, i9: Int, i10: Int, i11: Int, i12: Int, i13: Int, i14: Int, i15: Int) {
        gb(v, i0, i4, i8, i12); gb(v, i1, i5, i9, i13); gb(v, i2, i6, i10, i14); gb(v, i3, i7, i11, i15)
        gb(v, i0, i5, i10, i15); gb(v, i1, i6, i11, i12); gb(v, i2, i7, i8, i13); gb(v, i3, i4, i9, i14)
    }

    // ── H0 y H' ──────────────────────────────────────────────────────────────

    private fun h0(pw: ByteArray, salt: ByteArray, t: Int, m: Int, p: Int, tag: Int): ByteArray {
        val b = Blake2b(64)
        val le = ByteArray(4)
        fun u32(x: Int) { putLe32(le, 0, x); b.update(le) }
        u32(p); u32(tag); u32(m); u32(t); u32(0x13); u32(2)
        u32(pw.size); b.update(pw)
        u32(salt.size); b.update(salt)
        u32(0); u32(0)                                  // sin clave secreta ni datos asociados
        return b.digest()
    }

    /** H': hash de longitud variable de RFC 9106 §3.3. [entrada] se copia entera; escribe [longitud] bytes en [out]. */
    private fun hPrima(entrada: ByteArray, longitud: Int, out: ByteArray) {
        val pref = ByteArray(4); putLe32(pref, 0, longitud)
        if (longitud <= 64) {
            val b = Blake2b(longitud); b.update(pref); b.update(entrada)
            System.arraycopy(b.digest(), 0, out, 0, longitud); return
        }
        val r = (longitud + 31) / 32 - 2
        var v = Blake2b(64).also { it.update(pref); it.update(entrada) }.digest()
        System.arraycopy(v, 0, out, 0, 32)
        var pos = 32
        for (i in 2..r) {
            v = Blake2b(64).also { it.update(v) }.digest()
            System.arraycopy(v, 0, out, pos, 32); pos += 32
        }
        val ultimo = Blake2b(longitud - 32 * r).also { it.update(v) }.digest()
        System.arraycopy(ultimo, 0, out, pos, longitud - 32 * r)
    }

    private fun putLe32(b: ByteArray, o: Int, x: Int) { for (i in 0 until 4) b[o + i] = (x ushr (8 * i)).toByte() }
    private fun putLe64(b: ByteArray, o: Int, x: Long) { for (i in 0 until 8) b[o + i] = (x ushr (8 * i)).toByte() }
    private fun getLe64(b: ByteArray, o: Int): Long { var x = 0L; for (i in 7 downTo 0) x = (x shl 8) or (b[o + i].toLong() and 0xFF); return x }

    // ── BLAKE2b (RFC 7693), sin clave ────────────────────────────────────────

    class Blake2b(private val salida: Int) {
        private val h = LongArray(8)
        private val buf = ByteArray(128)
        private var n = 0
        private var total = 0L

        init {
            require(salida in 1..64)
            System.arraycopy(IV, 0, h, 0, 8)
            h[0] = h[0] xor 0x01010000L xor salida.toLong()
        }

        fun update(d: ByteArray) {
            for (x in d) {
                if (n == 128) { total += 128; compress(false); n = 0 }
                buf[n++] = x
            }
        }

        fun digest(): ByteArray {
            total += n
            java.util.Arrays.fill(buf, n, 128, 0)
            compress(true)
            val o = ByteArray(salida)
            for (i in 0 until salida) o[i] = (h[i / 8] ushr (8 * (i % 8))).toByte()
            return o
        }

        private fun compress(ultimo: Boolean) {
            val m = LongArray(16) { getLe64(buf, it * 8) }
            val v = LongArray(16)
            System.arraycopy(h, 0, v, 0, 8); System.arraycopy(IV, 0, v, 8, 8)
            v[12] = v[12] xor total
            if (ultimo) v[14] = v[14].inv()
            for (r in 0 until 12) {
                val s = SIGMA[r % 10]
                g(v, 0, 4, 8, 12, m[s[0]], m[s[1]]); g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
                g(v, 2, 6, 10, 14, m[s[4]], m[s[5]]); g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
                g(v, 0, 5, 10, 15, m[s[8]], m[s[9]]); g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
                g(v, 2, 7, 8, 13, m[s[12]], m[s[13]]); g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
            }
            for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
        }

        private fun g(v: LongArray, a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
            v[a] = v[a] + v[b] + x; v[d] = (v[d] xor v[a]).let { (it ushr 32) or (it shl 32) }
            v[c] = v[c] + v[d]; v[b] = (v[b] xor v[c]).let { (it ushr 24) or (it shl 40) }
            v[a] = v[a] + v[b] + y; v[d] = (v[d] xor v[a]).let { (it ushr 16) or (it shl 48) }
            v[c] = v[c] + v[d]; v[b] = (v[b] xor v[c]).let { (it ushr 63) or (it shl 1) }
        }

        companion object {
            private val IV = longArrayOf(
                0x6a09e667f3bcc908L, -0x4498517a7b3558c5L, 0x3c6ef372fe94f82bL, -0x5ab00ac5a0e2c90fL,
                0x510e527fade682d1L, -0x64fa9773d4c193e1L, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L)
            private val SIGMA = arrayOf(
                intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
                intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
                intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
                intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
                intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
                intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
                intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
                intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
                intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
                intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0))
        }
    }
}
