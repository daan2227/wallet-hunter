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
        // Sólo dígitos ASCII: el motor sólo acepta ésos, y un dígito Unicode daría otra cartera.
        return if (digitos.length in 1..2 && digitos.all { it in '0'..'9' } && digitos.toInt() <= 99) frase.substring(0, h)
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
        // Un espacio sobrante cambia la cartera sin que se note: se rechaza en vez de recortarlo en silencio.
        p.isBlank() || p != p.trim() -> "The passphrase cannot start or end with a space."
        p.length > 100 -> "The passphrase is too long: 100 characters at most."
        p.any { it.code !in 0x20..0x7e || it == '|' || it == '"' || it == '\\' || it == '#' } ->
            "Use only plain letters, digits and symbols: no quotes, backslashes, '|' or '#'."
        else -> null
    }

    /**
     * La misma frase en su forma única: sin sufijo de cuenta 0, sin '|' vacío y
     * con la cuenta en dígitos normales. Dos escrituras de la misma cartera dan la
     * misma cadena, así que la deduplicación no las guarda dos veces.
     */
    fun canonica(frase: String): String {
        val c = cuenta(frase)
        return conCuenta(unir(palabras(frase), passphrase(frase)), c)
    }

    /** Palabras y passphrase juntas, en el formato que guarda la app (sin cuenta). */
    fun unir(palabras: String, passphrase: String): String =
        if (passphrase.isEmpty()) palabras else "$palabras|$passphrase"
}
