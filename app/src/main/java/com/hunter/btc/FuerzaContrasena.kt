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
    const val MIN_BITS = 60.0

    // Palabras y filas de teclado que un atacante prueba primero. Se ordenan de
    // la más larga a la más corta para que "qwertyuiop" no se cuente como "qwerty".
    private val COMUNES = listOf(
        "password", "passw0rd", "qwertyuiop", "asdfghjkl", "zxcvbnm", "qwerty", "123456789",
        "12345678", "123456", "abcdef", "letmein", "admin", "welcome", "bitcoin", "wallet",
        "hunter", "satoshi", "iloveyou", "trustno1", "monkey", "dragon", "master", "login", "1234"
    ).sortedByDescending { it.length }

    /** Lo que cuesta, a un atacante, saber que usaste una de esas palabras. */
    private const val BITS_POR_PALABRA_COMUN = 12.0

    /** Lo que cuesta, a un atacante, adivinar una palabra corriente: ~2,6 bits por letra (un diccionario de
     *  decenas de miles de palabras da 15-17 bits para una de 6-8 letras), no los 4,7 de una letra al azar. */
    private const val BITS_POR_LETRA_DE_PALABRA = 2.6
    /** Un año (19xx / 20xx) son ~7 bits, no los 13 de cuatro dígitos al azar. */
    private const val BITS_ANIO = 7.0
    private const val BITS_SEPARADOR = 3.0

    private fun esVocal(c: Char) = c.lowercaseChar() in "aeiouáéíóú"

    /** ¿Parece una palabra? Minúsculas, MAYÚSCULAS o Capitalizada, con una proporción normal de vocales. */
    private fun parecePalabra(w: String): Boolean {
        if (w.length < 3) return false
        val forma = w.all { it.isLowerCase() } || w.all { it.isUpperCase() } ||
                    (w[0].isUpperCase() && w.drop(1).all { it.isLowerCase() })
        if (!forma) return false
        val v = w.count { esVocal(it) }.toDouble() / w.length
        return v in 0.2..0.6
    }

    /** Parte «MiPerroLoro» en «Mi», «Perro», «Loro»: cada trozo se juzga por separado. */
    private fun palabras(run: String): List<String> {
        val out = ArrayList<String>(); var ini = 0
        for (i in 1 until run.length)
            if (run[i - 1].isLowerCase() && run[i].isUpperCase()) { out.add(run.substring(ini, i)); ini = i }
        out.add(run.substring(ini))
        return out
    }

    /**
     * Antes: caracteres distintos × log2(alfabeto). Eso daba 84 bits a «Barcelona2024!!» y 91 a «purple monkey
     * dishwasher» (reales: ~30 y ~40), y las dos pasaban el mínimo: el fichero de copia sale del móvil y lo
     * protege sólo esta contraseña. Ahora se trocea en palabras, años y símbolos, y lo que parece palabra o
     * año cuesta lo que le cuesta a un atacante con diccionario; lo que no, cuenta por carácter al azar.
     */
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
            val porCaracter = ln(pool.toDouble()) / ln(2.0)
            // Al azar: los repetidos aportan poco, cuentan un cuarto.
            fun alAzar(t: String) = (t.toSet().size + (t.length - t.toSet().size) * 0.25) * porCaracter

            var i = 0
            while (i < resto.length) {
                val c = resto[i]
                var j = i + 1
                val tipo = when { c.isLetter() -> 0; c.isDigit() -> 1; else -> 2 }
                while (j < resto.length) {
                    val d = resto[j]
                    val t2 = when { d.isLetter() -> 0; d.isDigit() -> 1; else -> 2 }
                    if (t2 != tipo) break
                    j++
                }
                val run = resto.substring(i, j)
                bits += when (tipo) {
                    0 -> palabras(run).sumOf { w -> if (parecePalabra(w)) w.length * BITS_POR_LETRA_DE_PALABRA else alAzar(w) }
                    1 -> if (run.length == 4 && (run.startsWith("19") || run.startsWith("20"))) BITS_ANIO else alAzar(run)
                    else -> if (run.all { it == ' ' }) run.length * BITS_SEPARADOR else alAzar(run)
                }
                i = j
            }
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
