package com.hunter.btc

/**
 * La frase de una semilla tal como la guarda la app: "palabras" o
 * "palabras|passphrase". La passphrase es la palabra 25 de BIP39: con ella, las
 * mismas palabras dan OTRA cartera. Las palabras son lo que se apunta en papel;
 * la passphrase, lo que hay que apuntar aparte.
 *
 * Se guarda así para que copias, cifrado y motor la lleven sin cambios.
 */
object Frase {
    /** La frase sin el sufijo de cuenta ("#n"). */
    fun base(frase: String): String {
        val h = frase.lastIndexOf('#')
        if (h < 0) return frase
        val digitos = frase.substring(h + 1)
        return if (digitos.length in 1..2 && digitos.all { it.isDigit() } && digitos.toInt() <= 99) frase.substring(0, h)
               else frase
    }

    /** Cuenta BIP44/49/84/86 de la frase (0 si no tiene sufijo). */
    fun cuenta(frase: String): Int {
        val b = base(frase)
        return if (b.length == frase.length) 0 else frase.substring(b.length + 1).toInt()
    }

    /** La frase con la cuenta [n]: la 0 se escribe sin sufijo, así las carteras de siempre no cambian. */
    fun conCuenta(frase: String, n: Int): String {
        require(n in 0..99) { "la cuenta va de 0 a 99" }
        val b = base(frase)
        return if (n == 0) b else "$b#$n"
    }

    /** Las palabras, sin la passphrase ni la cuenta. */
    fun palabras(frase: String): String = base(frase).substringBefore('|')

    fun tienePassphrase(frase: String): Boolean = base(frase).contains('|')

    fun passphrase(frase: String): String =
        if (tienePassphrase(frase)) base(frase).substringAfter('|') else ""

    /**
     * null si vale. La passphrase se limita a ASCII imprimible sin comillas,
     * barras invertidas ni '|': así no hace falta normalizarla y no rompe el JSON
     * ni la separación de la frase.
     */
    fun errorPassphrase(p: String): String? = when {
        p.isEmpty() -> null
        p.length > 100 -> "The passphrase is too long: 100 characters at most."
        p.any { it.code !in 0x20..0x7e || it == '|' || it == '"' || it == '\\' || it == '#' } ->
            "Use only plain letters, digits and symbols: no quotes, backslashes, '|' or '#'."
        else -> null
    }

    /** Palabras y passphrase juntas, en el formato que guarda la app (sin cuenta). */
    fun unir(palabras: String, passphrase: String): String =
        if (passphrase.isEmpty()) palabras else "$palabras|$passphrase"
}
