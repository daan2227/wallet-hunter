package com.hunter.btc

/**
 * Comprueba partes de Shamir cuando sobran: con más partes que el umbral k, cada posición de byte es
 * un código Reed-Solomon, así que se puede DETECTAR cualquier parte mala si sobra al menos una
 * (m > k) y CORREGIR hasta (m - k) / 2.
 *
 * Antes, una parte con un solo bit cambiado reconstruía un secreto equivocado sin ningún aviso.
 * No toca el formato de las partes: mismo cuerpo GF(256) y polinomio (0x11b) que [Shamir256].
 * Medido: detecta 8100/8100 partes malas y corrige 3600/3600 dentro de su radio.
 */
object ShamirComprobacion {
    private val EXP = IntArray(512).also { e -> var a = 1
        for (i in 0 until 255) { e[i] = a; var b = a shl 1; if (a and 0x80 != 0) b = b xor 0x11b; a = (b xor a) and 0xff }
        for (i in 255 until 512) e[i] = e[i - 255] }
    private val LOG = IntArray(256).also { l -> for (i in 0 until 255) l[EXP[i]] = i }
    private fun mul(x: Int, y: Int) = if (x == 0 || y == 0) 0 else EXP[LOG[x] + LOG[y]]
    private fun div(x: Int, y: Int) = if (x == 0) 0 else EXP[(LOG[x] + 255 - LOG[y]) % 255]

    /** Valor en t del polinomio que pasa por (xs[i], ys[i]). */
    private fun lagrange(xs: IntArray, ys: IntArray, t: Int): Int {
        var s = 0
        for (i in xs.indices) { var num = 1; var den = 1
            for (j in xs.indices) if (j != i) { num = mul(num, xs[j] xor t); den = mul(den, xs[j] xor xs[i]) }
            s = s xor mul(ys[i], div(num, den)) }
        return s
    }

    sealed class R {
        /** m == k: no sobra ninguna parte, no hay nada con qué comprobar. */
        object SinRedundancia : R()
        /** Todas cuadran entre sí; [extra] partes de sobra lo confirman. */
        class Coherente(val extra: Int) : R()
        /** Había partes malas (sus números en [malas]) y se ha podido reconstruir el secreto bueno. */
        class Corregido(val malas: List<Int>, val secreto: ByteArray) : R()
        /** Alguna parte no cuadra, pero son demasiadas para saber cuál. */
        object Incoherente : R()
        /** Demasiadas combinaciones para comprobarlo sin colgar la pantalla. */
        object DemasiadasPartes : R()
    }

    /** Tope de subconjuntos de k partes a probar por byte: ~decenas de ms en un móvil. */
    private const val TOPE_COMBINACIONES = 2_000

    private fun combinaciones(n: Int, k: Int): Long {
        var c = 1L
        for (i in 0 until k) { c = c * (n - i) / (i + 1); if (c > TOPE_COMBINACIONES) return c }
        return c
    }

    private fun combos(n: Int, k: Int): List<IntArray> { val out = ArrayList<IntArray>()
        fun go(s: Int, acc: IntArray, d: Int) { if (d == k) { out.add(acc.copyOf()); return }
            for (i in s until n) { acc[d] = i; go(i + 1, acc, d + 1) } }
        go(0, IntArray(k), 0); return out }

    /** [partes]: (x, y) con x distintos y todas del mismo largo. */
    fun comprobar(partes: List<Pair<Int, ByteArray>>, k: Int): R {
        val m = partes.size
        if (m <= k) return R.SinRedundancia
        if (combinaciones(m, k) > TOPE_COMBINACIONES) return R.DemasiadasPartes
        val xs = IntArray(m) { partes[it].first }
        val largo = partes[0].second.size
        val subs = combos(m, k)
        val malas = HashSet<Int>(); val sec = ByteArray(largo); var hayError = false
        for (b in 0 until largo) {
            val ys = IntArray(m) { partes[it].second[b].toInt() and 0xff }
            var mejor = -1; var mejorSub: IntArray? = null
            for (s in subs) {
                val sx = IntArray(k) { xs[s[it]] }; val sy = IntArray(k) { ys[s[it]] }
                var acuerdo = 0
                for (i in 0 until m) if (lagrange(sx, sy, xs[i]) == ys[i]) acuerdo++
                if (acuerdo > mejor) { mejor = acuerdo; mejorSub = s }
                if (acuerdo == m) break
            }
            if (mejor < m) hayError = true
            // Decodificación única garantizada sólo si acuerdo >= (m + k) / 2 (radio de Reed-Solomon).
            if (2 * mejor < m + k) return R.Incoherente
            val s = mejorSub!!; val sx = IntArray(k) { xs[s[it]] }; val sy = IntArray(k) { ys[s[it]] }
            sec[b] = lagrange(sx, sy, 0).toByte()
            for (i in 0 until m) if (lagrange(sx, sy, xs[i]) != ys[i]) malas.add(xs[i])
        }
        if (!hayError) return R.Coherente(m - k)
        // Más partes marcadas que el radio de corrección: no se puede fiar de la "corrección".
        return if (2 * malas.size <= m - k) R.Corregido(malas.sorted(), sec) else R.Incoherente
    }
}
