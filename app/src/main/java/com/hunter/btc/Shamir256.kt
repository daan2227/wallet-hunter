package com.hunter.btc

/**
 * Reparto de secretos de Shamir byte a byte sobre GF(256) (polinomio 0x11b).
 * Pura: se prueba en la JVM (Shamir256Test).
 *
 * Una parte es "xx" + 32 bytes: x (1..255) y la evaluación del polinomio de
 * cada byte del secreto en x. El formato no lleva k, así que quien combina
 * tiene que dar al menos k partes distintas: con menos sale una clave
 * equivocada sin que haya forma de saberlo.
 */
object Shamir256 {
    private val EXP = IntArray(512).also { e ->
        var a = 1
        for (i in 0 until 255) { e[i] = a; var b = a shl 1; if (a and 0x80 != 0) b = b xor 0x11b; a = (b xor a) and 0xff }
        for (i in 255 until 512) e[i] = e[i - 255]
    }
    private val LOG = IntArray(256).also { l -> for (i in 0 until 255) l[EXP[i]] = i }

    private fun mul(x: Int, y: Int) = if (x == 0 || y == 0) 0 else EXP[LOG[x] + LOG[y]]
    private fun div(x: Int, y: Int) = if (x == 0) 0 else EXP[(LOG[x] + 255 - LOG[y]) % 255]

    /** Partes 1..n de un secreto, umbral k. */
    fun repartir(secreto: ByteArray, k: Int, n: Int, rnd: java.util.Random): List<Pair<Int, ByteArray>> {
        require(k in 2..n && n <= 255)
        val coef = Array(secreto.size) { bi -> IntArray(k) { c -> if (c == 0) secreto[bi].toInt() and 0xFF else rnd.nextInt(256) } }
        return (1..n).map { x ->
            x to ByteArray(secreto.size) { bi ->
                var acc = coef[bi][0]; var xp = 1
                for (c in 1 until k) { xp = mul(xp, x); acc = acc xor mul(coef[bi][c], xp) }
                acc.toByte()
            }
        }
    }

    sealed class Resultado {
        class Ok(val secreto: ByteArray) : Resultado()
        class Error(val motivo: String) : Resultado()
    }

    /**
     * Recompone el secreto. Antes, una parte repetida dejaba un denominador cero
     * que se cambiaba por 0 en silencio: salía una "clave recombinada" falsa.
     */
    fun combinar(partes: List<Pair<Int, ByteArray>>): Resultado {
        if (partes.size < 2) return Resultado.Error("At least 2 shares are needed.")
        val xs = partes.map { it.first }
        if (xs.any { it !in 1..255 }) return Resultado.Error("A share has an invalid index (x).")
        if (xs.toSet().size != xs.size) return Resultado.Error("The same share was pasted twice (repeated index).")
        val largo = partes[0].second.size
        if (partes.any { it.second.size != largo }) return Resultado.Error("The shares are of different lengths.")
        val out = ByteArray(largo)
        for (bi in 0 until largo) {
            var s = 0
            for (i in partes.indices) {
                var num = 1; var den = 1
                for (j in partes.indices) if (j != i) { num = mul(num, xs[j]); den = mul(den, xs[j] xor xs[i]) }
                s = s xor mul(partes[i].second[bi].toInt() and 0xFF, div(num, den))
            }
            out[bi] = s.toByte()
        }
        return Resultado.Ok(out)
    }
}
