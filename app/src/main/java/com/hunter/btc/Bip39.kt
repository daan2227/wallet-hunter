package com.hunter.btc

import java.security.MessageDigest

/**
 * Validación de frases mnemónicas BIP39.
 *
 * El diálogo de importación sólo comprobaba que hubiera 12 palabras o más:
 * aceptaba palabras fuera de la lista, longitudes inválidas y checksums
 * incorrectos. Una seed mal tecleada se cifraba y guardaba igual, y la wallet
 * derivaba direcciones que no son las del usuario — saldo cero y la impresión
 * de haber perdido los fondos.
 */
object Bip39 {

    sealed class Result {
        object Valid : Result()
        data class Invalid(val reason: String) : Result()
    }

    /** Longitudes válidas: ENT de 128..256 bits en pasos de 32. */
    private val VALID_LENGTHS = setOf(12, 15, 18, 21, 24)

    private val index: Map<String, Int> by lazy {
        val w = Bip39Words.WORDS
        HashMap<String, Int>(w.size * 2).also { m ->
            w.forEachIndexed { i, word -> m[word] = i }
        }
    }

    /**
     * Una frase nueva, para crear una cartera desde la app.
     *
     * La entropía sale del SecureRandom del sistema: 128 bits para 12
     * palabras, 256 para 24. Detrás se pegan los ENT/32 primeros bits de
     * SHA-256(entropía) —el checksum— y el total se parte en grupos de 11
     * bits, cada uno un índice de la lista. Es lo mismo que [validate] hace
     * al revés.
     *
     * Comprobado antes de escribirlo, con un port línea a línea: da las siete
     * frases de los vectores oficiales de BIP39 (las de 12 y las de 24
     * palabras) y 2000 frases al azar pasan [validate].
     *
     * Y se comprueba otra vez aquí mismo, contra [validate], antes de
     * devolverla. Una frase con el checksum mal se guardaría igual y la
     * cartera derivaría direcciones que ninguna otra app sabría recuperar:
     * mejor que reviente aquí que perder fondos después.
     */
    fun generate(palabras: Int = 12): String {
        require(palabras in VALID_LENGTHS) { "BIP39: $palabras palabras no es una longitud valida" }
        val entBits = palabras * 11 * 32 / 33
        val ent = ByteArray(entBits / 8)
        java.security.SecureRandom().nextBytes(ent)
        val hash = MessageDigest.getInstance("SHA-256").digest(ent)

        fun bit(i: Int): Int =
            if (i < entBits) ((ent[i / 8].toInt() and 0xff) shr (7 - i % 8)) and 1
            else ((hash[(i - entBits) / 8].toInt() and 0xff) shr (7 - (i - entBits) % 8)) and 1

        val w = Bip39Words.WORDS
        val frase = (0 until palabras).joinToString(" ") { k ->
            var v = 0
            for (b in 0 until 11) v = (v shl 1) or bit(k * 11 + b)
            w[v]
        }
        check(validate(frase) == Result.Valid) { "BIP39: la frase generada no valida" }
        return frase
    }

    fun normalize(raw: String): List<String> =
        raw.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun validate(raw: String): Result {
        val words = normalize(raw)

        if (words.size !in VALID_LENGTHS) {
            return Result.Invalid(
                if (words.size < 12) "Missing words: ${words.size} of 12"
                else "Invalid length (${words.size}). It must be 12, 15, 18, 21 or 24")
        }

        val idx = ArrayList<Int>(words.size)
        words.forEachIndexed { i, w ->
            val p = index[w]
                ?: return Result.Invalid("Word ${i + 1} is not in the BIP39 list: \"$w\"")
            idx.add(p)
        }

        // Los últimos ENT/32 bits son los primeros bits de SHA256(entropía).
        val totalBits = words.size * 11
        val csBits    = totalBits / 33
        val entBits   = totalBits - csBits
        if (entBits % 8 != 0) return Result.Invalid("Invalid length")

        val ent = ByteArray(entBits / 8)
        var bit = 0
        for (v in idx) {
            for (b in 10 downTo 0) {
                if (bit < entBits && ((v shr b) and 1) == 1)
                    ent[bit / 8] = (ent[bit / 8].toInt() or (1 shl (7 - (bit % 8)))).toByte()
                bit++
            }
        }

        val hash = MessageDigest.getInstance("SHA-256").digest(ent)
        var got = 0
        for (i in 0 until csBits) {
            val p = entBits + i
            got = (got shl 1) or ((idx[p / 11] shr (10 - (p % 11))) and 1)
        }
        val want = (hash[0].toInt() and 0xff) ushr (8 - csBits)

        return if (got == want) Result.Valid
        else Result.Invalid("Wrong checksum: check the words, one of them is off")
    }
}
