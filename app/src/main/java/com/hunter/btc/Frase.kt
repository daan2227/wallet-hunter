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
    /** Las palabras, sin la passphrase. */
    fun palabras(frase: String): String = frase.substringBefore('|')

    fun tienePassphrase(frase: String): Boolean = frase.contains('|')

    fun passphrase(frase: String): String =
        if (tienePassphrase(frase)) frase.substringAfter('|') else ""

    /**
     * null si vale. La passphrase se limita a ASCII imprimible sin comillas,
     * barras invertidas ni '|': así no hace falta normalizarla y no rompe el JSON
     * ni la separación de la frase.
     */
    fun errorPassphrase(p: String): String? = when {
        p.isEmpty() -> null
        p.length > 100 -> "The passphrase is too long: 100 characters at most."
        p.any { it.code !in 0x20..0x7e || it == '|' || it == '"' || it == '\\' } ->
            "Use only plain letters, digits and symbols: no quotes, backslashes or '|'."
        else -> null
    }

    /** Palabras y passphrase juntas, en el formato que guarda la app. */
    fun unir(palabras: String, passphrase: String): String =
        if (passphrase.isEmpty()) palabras else "$palabras|$passphrase"
}
