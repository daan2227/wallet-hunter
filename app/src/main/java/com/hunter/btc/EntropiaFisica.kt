package com.hunter.btc

/**
 * Bits de entropía de una secuencia de dados (1-6) o de monedas (0/1).
 *
 * Antes cada '1' contaba como una tirada de dado (2,585 bits), también en una
 * secuencia de monedas: unas 50 monedas salían como "✓ 128+ bits" teniendo
 * unos 50. Ahora se mira la secuencia entera: sólo 0 y 1 son monedas (1 bit
 * cada una); sólo 1-6, dados; lo demás (un 7, un 9, mezcla con 0) no cuenta
 * como entropía física de esos tipos. Pura: EntropiaFisicaTest.
 */
object EntropiaFisica {
    fun bits(texto: String): Double {
        val s = texto.filter { it.isDigit() }
        if (s.isEmpty()) return 0.0
        return when {
            s.all { it == '0' || it == '1' } -> s.length * 1.0
            s.all { it in '1'..'6' } -> s.length * (Math.log(6.0) / Math.log(2.0))
            else -> 0.0
        }
    }
}
