package com.hunter.btc

/**
 * Quién está usando el motor Kangaroo, que es uno solo en el proceso.
 *
 * La auditoría de claves débiles y la búsqueda de Puzzle lo compartían sin
 * saberlo: empezar una auditoría paraba la búsqueda del puzzle sin avisar, y el
 * refresco de la pantalla del puzzle leía el resultado de la auditoría — lo
 * guardaba como "Puzzle #71 resuelto" (falso) o, si el puzzle tenía clave
 * conocida, lo daba por erróneo y lo tiraba, y la auditoría apuntaba "no
 * encontrada". Ahora quien arranca el motor lo marca, y el otro ni lo para, ni
 * arranca encima, ni lee sus resultados.
 */
object KangarooDueno {
    const val PUZZLE = "puzzle"
    const val WEAK = "weak"

    @Volatile var actual: String? = null

    /** ¿Hay un Kangaroo en marcha que NO es de [yo]? (Sin marca cuenta como ajeno.) */
    fun ocupadoPorOtro(yo: String): Boolean {
        val corre = try { HunterEngine.kangarooRunning() } catch (e: Throwable) { false }
        return corre && actual != yo
    }
}
