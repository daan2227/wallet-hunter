package com.hunter.btc

/**
 * Otras formas de guardar una frase BIP39, compatibles con hardware conocido. Puro: CopiasSemillaTest con los
 * vectores de SeedSigner y de Coldcard.
 *
 *   SeedQR (SeedSigner): la frase como QR. "Standard": el índice de cada palabra en 4 cifras, todo seguido (QR
 *   numérico). "Compact": los 16/32 bytes de entropía tal cual (QR binario, más pequeño).
 *   Seed XOR (Coldcard): varias frases BIP39 válidas cuyo XOR (de la entropía) es la frase de verdad. Cada parte
 *   es una cartera normal (sirve de señuelo) y hacen falta TODAS para la buena.
 */
object SeedQr {
    /** Los índices de las palabras en 4 cifras cada uno; null si la frase no es BIP39 válida. */
    fun estandar(frase: String): String? {
        if (Bip39.validate(frase) !is Bip39.Result.Valid) return null
        return Bip39.normalize(frase).joinToString("") { "%04d".format(Bip39Words.WORDS.indexOf(it)) }
    }

    /** La frase de un SeedQR estándar (12 o 24 palabras); null si no es uno válido. */
    fun deEstandar(cifras: String): String? {
        val t = cifras.trim()
        if (t.length != 48 && t.length != 96 || !t.all { it in '0'..'9' }) return null
        val idx = t.chunked(4).map { it.toInt() }
        if (idx.any { it > 2047 }) return null
        return idx.joinToString(" ") { Bip39Words.WORDS[it] }.takeIf { Bip39.validate(it) is Bip39.Result.Valid }
    }

    /** CompactSeedQR: la entropía (16 o 32 bytes). null si la frase no es de 12 o 24 palabras válida. */
    fun compacto(frase: String): ByteArray? = Bip39.entropia(frase)?.takeIf { it.size == 16 || it.size == 32 }

    fun deCompacto(b: ByteArray): String? = if (b.size == 16 || b.size == 32) Bip39.deEntropia(b) else null
}

object SeedXor {
    /** El XOR de varias frases del mismo largo; null si alguna no es válida o los largos no coinciden. */
    fun juntar(frases: List<String>): String? {
        val ents = frases.map { Bip39.entropia(it) ?: return null }
        if (ents.size < 2 || ents.any { it.size != ents[0].size }) return null
        val r = ByteArray(ents[0].size)
        for (e in ents) for (i in r.indices) r[i] = (r[i].toInt() xor e[i].toInt()).toByte()
        return Bip39.deEntropia(r)
    }

    /**
     * Parte [frase] en [n] frases: n−1 al azar ([azar] da la entropía de cada una) y la última tal que el XOR de
     * todas sea la original. Cada parte es una frase BIP39 válida por sí misma.
     */
    fun partir(frase: String, n: Int, azar: (Int) -> ByteArray = { t -> ByteArray(t).also { java.security.SecureRandom().nextBytes(it) } }): List<String>? {
        require(n in 2..4) { "Seed XOR: de 2 a 4 partes" }
        val e = Bip39.entropia(frase) ?: return null
        val partes = (1 until n).map { Bip39.deEntropia(azar(e.size)) }
        val ultima = ByteArray(e.size) { i -> partes.fold(e[i].toInt()) { a, p -> a xor Bip39.entropia(p)!![i].toInt() }.toByte() }
        return partes + Bip39.deEntropia(ultima)
    }
}
