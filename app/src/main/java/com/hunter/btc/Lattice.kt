package com.hunter.btc

import java.math.BigInteger

/**
 * Ataque de retícula a nonces ECDSA sesgados/cortos (Hidden Number Problem + LLL).
 *
 * Si varias firmas de la MISMA clave usaron nonces pequeños (los bits altos a
 * cero, p. ej. un RNG de 128 bits en una curva de 256) o sesgados, la clave
 * privada se recupera resolviendo un HNP con reducción de retícula, sin fuerza
 * bruta. Es el ataque "Biased Nonce Sense" (Breitner–Heninger, 2019), que sacó
 * cientos de claves BTC reales.
 *
 * Cada firma da  k = s⁻¹·z + s⁻¹·r·d (mod n)  con 0 ≤ k < 2^bits. Se monta la
 * retícula entera (validada en Python contra firmas fabricadas, 18/18 con 3–5
 * firmas y nonces de 110–160 bits) y LLL devuelve el vector corto que lleva d.
 * El resultado se COMPRUEBA (d·G == pub) antes de darlo por bueno: un fallo hace
 * que no encuentre, nunca que devuelva una clave falsa.
 */
object Lattice {
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

    /** Racional exacto (BigInteger / BigInteger, reducido, denominador > 0). */
    private class Rat {
        val n: BigInteger; val d: BigInteger
        constructor(num: BigInteger, den: BigInteger = BigInteger.ONE) {
            var a = num; var b = den
            if (b.signum() == 0) { n = BigInteger.ZERO; d = BigInteger.ONE; return }
            if (b.signum() < 0) { a = a.negate(); b = b.negate() }
            val g = a.gcd(b).let { if (it.signum() == 0) BigInteger.ONE else it }
            n = a / g; d = b / g
        }
        operator fun plus(o: Rat) = Rat(n * o.d + o.n * d, d * o.d)
        operator fun minus(o: Rat) = Rat(n * o.d - o.n * d, d * o.d)
        operator fun times(o: Rat) = Rat(n * o.n, d * o.d)
        operator fun div(o: Rat) = Rat(n * o.d, d * o.n)
        fun cmp(o: Rat) = (n * o.d).compareTo(o.n * d)
        fun absHalfLe() = (n.abs() * TWO).compareTo(d) <= 0   // |x| <= 1/2
        /** Redondeo al entero más cercano (half-up). */
        fun round(): BigInteger {
            val twoN = n * TWO + d          // (2n + d)
            val twoD = d * TWO
            return floorDiv(twoN, twoD)
        }
        companion object {
            val ZERO = Rat(BigInteger.ZERO)
            val TWO: BigInteger = BigInteger.valueOf(2)
            fun floorDiv(a: BigInteger, b: BigInteger): BigInteger {
                val qr = a.divideAndRemainder(b)
                return if (qr[1].signum() != 0 && (qr[1].signum() != b.signum())) qr[0] - BigInteger.ONE else qr[0]
            }
        }
    }

    private fun dot(u: Array<BigInteger>, v: Array<BigInteger>): BigInteger {
        var s = BigInteger.ZERO; for (i in u.indices) s += u[i] * v[i]; return s
    }

    /** LLL (Cohen 2.6.3, incremental) sobre una base entera. Modifica y devuelve B. */
    private fun lll(B: Array<Array<BigInteger>>) {
        val nn = B.size; val dim = B[0].size
        val mu = Array(nn) { Array(nn) { Rat.ZERO } }
        val Bn = Array(nn) { Rat.ZERO }
        // GSO inicial (vectores b* racionales).
        val bs = Array(nn) { Array(dim) { Rat.ZERO } }
        for (i in 0 until nn) {
            for (t in 0 until dim) bs[i][t] = Rat(B[i][t])
            for (j in 0 until i) {
                var num = Rat.ZERO
                for (t in 0 until dim) num += Rat(B[i][t]) * bs[j][t]
                mu[i][j] = num / Bn[j]
                for (t in 0 until dim) bs[i][t] = bs[i][t] - mu[i][j] * bs[j][t]
            }
            var nb = Rat.ZERO; for (t in 0 until dim) nb += bs[i][t] * bs[i][t]; Bn[i] = nb
        }
        val delta = Rat(BigInteger.valueOf(99), BigInteger.valueOf(100))
        fun red(k: Int, l: Int) {
            if (mu[k][l].absHalfLe()) return
            val q = mu[k][l].round()
            if (q.signum() == 0) return
            for (t in 0 until dim) B[k][t] -= q * B[l][t]
            for (j in 0 until l) mu[k][j] = mu[k][j] - Rat(q) * mu[l][j]
            mu[k][l] = mu[k][l] - Rat(q)
        }
        var k = 1
        var guard = 0; val maxIter = 200000
        while (k < nn && guard++ < maxIter) {
            red(k, k - 1)
            val lhs = Bn[k]
            val rhs = (delta - mu[k][k - 1] * mu[k][k - 1]) * Bn[k - 1]
            if (lhs.cmp(rhs) >= 0) {
                for (l in k - 2 downTo 0) red(k, l)
                k++
            } else {
                val m = mu[k][k - 1]
                val Bb = Bn[k] + m * m * Bn[k - 1]
                mu[k][k - 1] = m * Bn[k - 1] / Bb
                Bn[k] = Bn[k - 1] * Bn[k] / Bb
                Bn[k - 1] = Bb
                val tmp = B[k]; B[k] = B[k - 1]; B[k - 1] = tmp
                for (j in 0 until k - 1) { val t = mu[k][j]; mu[k][j] = mu[k - 1][j]; mu[k - 1][j] = t }
                for (i in k + 1 until nn) {
                    val t = mu[i][k]
                    mu[i][k] = mu[i][k - 1] - m * t
                    mu[i][k - 1] = t + mu[k][k - 1] * mu[i][k]
                }
                k = maxOf(k - 1, 1)
            }
        }
    }

    /**
     * Recupera la clave privada (hex de 64) o null. sigs = (r, s, z); bbits = cota
     * superior del nonce (se asume 0 ≤ k < 2^bbits); verifica contra pubComprimida.
     */
    fun recover(sigs: List<Triple<BigInteger, BigInteger, BigInteger>>, bbits: Int, verify: (BigInteger) -> Boolean): BigInteger? {
        val m = sigs.size
        if (m < 2 || bbits !in 1..255) return null
        val bnd = BigInteger.ONE.shiftLeft(bbits)
        val t = ArrayList<BigInteger>(m); val u = ArrayList<BigInteger>(m)
        for ((r, s, z) in sigs) {
            val si = s.modInverse(N)
            t.add(r.multiply(si).mod(N)); u.add(z.multiply(si).mod(N))
        }
        val dim = m + 2
        val M = Array(dim) { Array(dim) { BigInteger.ZERO } }
        val nn2 = N * N
        for (i in 0 until m) M[i][i] = nn2
        for (i in 0 until m) M[m][i] = t[i] * N
        M[m][m] = bnd
        for (i in 0 until m) M[m + 1][i] = u[i] * N
        M[m + 1][m + 1] = bnd * N
        lll(M)
        for (row in M) {
            val col = row[m]
            if (col.signum() == 0) continue
            for (cand in listOf(col / bnd, col.negate() / bnd)) {
                val d = cand.mod(N)
                if (d.signum() != 0 && verify(d)) return d
            }
        }
        return null
    }
}
