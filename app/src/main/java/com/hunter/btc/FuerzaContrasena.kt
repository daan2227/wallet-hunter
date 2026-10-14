package com.hunter.btc

import kotlin.math.abs
import kotlin.math.ln

/**
 * Estimación de lo difícil que es adivinar una contraseña, para la de las copias
 * de seguridad.
 *
 * Es una ESTIMACIÓN, no una garantía: cuenta caracteres y variedad, y resta por
 * las trampas más corrientes (palabras sabidas, secuencias, repetición). Una
 * frase larga de palabras sueltas sale fuerte; "Password1234!" sale débil aunque
 * tenga de todo. El fichero de la copia sale del móvil, así que lo que hay que
 * evitar es que alguien lo pruebe contra contraseñas fáciles.
 *
 * Pura (sin Android): se prueba en la JVM.
 */
object FuerzaContrasena {

    enum class Nivel(val texto: String) {
        DEBIL("Weak"), JUSTA("Fair"), BUENA("Good"), FUERTE("Strong")
    }

    class Resultado(val bits: Double, val nivel: Nivel)

    /** Mínimo de longitud y de bits estimados para aceptar una contraseña de copia. */
    const val MIN_LARGO = 12
    const val MIN_BITS = 45.0

    // Palabras y filas de teclado que un atacante prueba primero. Se ordenan de
    // la más larga a la más corta para que "qwertyuiop" no se cuente como "qwerty".
    private val COMUNES = listOf(
        "password", "passw0rd", "qwertyuiop", "asdfghjkl", "zxcvbnm", "qwerty", "123456789",
        "12345678", "123456", "abcdef", "letmein", "admin", "welcome", "bitcoin", "wallet",
        "hunter", "satoshi", "iloveyou", "trustno1", "monkey", "dragon", "master", "login", "1234"
    ).sortedByDescending { it.length }

    /** Lo que cuesta, a un atacante, saber que usaste una de esas palabras. */
    private const val BITS_POR_PALABRA_COMUN = 12.0

    fun evaluar(pw: String): Resultado {
        if (pw.isEmpty()) return Resultado(0.0, Nivel.DEBIL)
        // Las palabras sabidas no aportan el largo que parecen: se quitan del
        // cálculo y cada una cuenta como una sola elección.
        var resto = pw
        var comunes = 0
        for (c in COMUNES) {
            val re = Regex(Regex.escape(c), RegexOption.IGNORE_CASE)
            if (re.containsMatchIn(resto)) { comunes++; resto = re.replace(resto, "") }
        }
        var bits = comunes * BITS_POR_PALABRA_COMUN
        if (resto.isNotEmpty()) {
            var pool = 0
            if (resto.any { it in '0'..'9' }) pool += 10
            if (resto.any { it in 'a'..'z' }) pool += 26
            if (resto.any { it in 'A'..'Z' }) pool += 26
            if (resto.any { !it.isLetterOrDigit() && it.code < 128 }) pool += 33
            if (resto.any { it.code > 127 }) pool += 30
            if (pool == 0) pool = 10
            // Los caracteres repetidos aportan poco: cuentan un cuarto.
            val distintos = resto.toSet().size
            val repetidos = resto.length - distintos
            bits += (distintos + repetidos * 0.25) * (ln(pool.toDouble()) / ln(2.0))
            if (tieneSecuencia(resto)) bits -= 10
        }
        bits = bits.coerceAtLeast(0.0)
        val nivel = when {
            bits < 40 -> Nivel.DEBIL
            bits < 55 -> Nivel.JUSTA
            bits < 70 -> Nivel.BUENA
            else      -> Nivel.FUERTE
        }
        return Resultado(bits, nivel)
    }

    /** Cuatro o más caracteres seguidos en orden (abcd, 1234, dcba). */
    private fun tieneSecuencia(p: String): Boolean {
        var run = 1; var prev = 0
        for (i in 1 until p.length) {
            val d = p[i].code - p[i - 1].code
            run = if (abs(d) == 1 && d == prev) run + 1 else if (abs(d) == 1) 2 else 1
            prev = d
            if (run >= 4) return true
        }
        return false
    }

    /** ¿Vale como contraseña de una copia? */
    fun aceptable(pw: String): Boolean = pw.length >= MIN_LARGO && evaluar(pw).bits >= MIN_BITS
}
