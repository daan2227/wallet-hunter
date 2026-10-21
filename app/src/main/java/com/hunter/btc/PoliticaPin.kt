package com.hunter.btc

/**
 * Qué se acepta como código nuevo: de 8 a 24 caracteres con letras, números y símbolos.
 *
 * El código de seis dígitos tenía un millón de posibilidades. Quien llegara a los ficheros de la
 * app lo probaba entero contra la comprobación del PIN en minutos, sin pasar por el contador de
 * intentos. Con 8 caracteres de tres clases y una derivación más cara (ver WalletManager) ese
 * camino pasa a ser inviable.
 *
 * Sólo se exige al ELEGIR un código. Al desbloquear se acepta lo que haya guardado, para que quien
 * ya tenía uno de seis dígitos pueda entrar y cambiarlo.
 *
 * Pura (sin Android): se prueba en la JVM.
 */
object PoliticaPin {
    const val MIN = 8
    const val MAX = 24

    /** Bits mínimos según [FuerzaContrasena]: descarta «Abcd1234!» y parecidos. */
    private const val MIN_BITS = 35.0

    private fun esSimbolo(c: Char) = !c.isLetterOrDigit() && !c.isWhitespace() && !c.isISOControl()

    /** Por qué no vale, o null si vale. */
    fun error(pin: String): String? {
        if (pin.length < MIN) return "Use at least $MIN characters."
        if (pin.length > MAX) return "Use $MAX characters at most."
        if (pin.any { it.isISOControl() }) return "That character is not allowed."
        if (pin.none { it.isLetter() }) return "Add at least one letter."
        if (pin.none { it.isDigit() }) return "Add at least one number."
        if (pin.none { esSimbolo(it) }) return "Add at least one symbol, like ! # \$ % & or *."
        if (FuerzaContrasena.evaluar(pin).bits < MIN_BITS)
            return "That code is too easy to guess. Avoid words, years and sequences."
        return null
    }

    fun vale(pin: String) = error(pin) == null
}
