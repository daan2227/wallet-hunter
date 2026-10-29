package com.hunter.btc

/**
 * Las cuentas de un reemplazo por comisión (RBF, BIP125): acelerar un envío propio sin confirmar o
 * cancelarlo (mismas entradas, todo de vuelta a la dirección de origen). Puro: ReemplazoTest.
 */
object Reemplazo {

    /** La menor tasa entera (sat/vB) que mejora la de la vieja: su tasa redondeada hacia arriba, más 1. */
    fun tasaMinima(feeViejo: Long, vsizeViejo: Long): Long {
        require(vsizeViejo > 0) { "tamaño" }
        return (feeViejo + vsizeViejo - 1) / vsizeViejo + 1
    }

    /**
     * La comisión del reemplazo a [tasa]: BIP125 pide además que pague MÁS en total que la vieja, y al menos
     * su propio tamaño a 1 sat/vB por encima (regla 4). Una cancelación ocupa menos, así que a veces manda esto.
     */
    fun comision(tasa: Long, vsizeNuevo: Long, feeViejo: Long): Long = maxOf(tasa * vsizeNuevo, feeViejo + vsizeNuevo)

    /**
     * El tamaño de la cancelación, a partir de la vieja: las mismas entradas, y en vez de sus salidas (de
     * scripts de [scriptsViejos] bytes) una sola de [scriptNuevo] bytes. Cada salida son 8 de importe, el
     * largo del script (1 byte hasta 252) y el script; el número de salidas cabe en 1 byte en ambas.
     */
    fun vsizeCancelacion(vsizeViejo: Long, scriptsViejos: List<Int>, scriptNuevo: Int): Long {
        require(scriptsViejos.isNotEmpty() && scriptsViejos.size < 253) { "salidas" }
        fun salida(n: Int) = 8L + (if (n < 253) 1 else 3) + n
        return vsizeViejo - scriptsViejos.sumOf { salida(it) } + salida(scriptNuevo)
    }

    /**
     * Las tasas para elegir: las recomendadas que mejoran la vieja (si no, la mínima), sin repetir y de
     * mayor a menor, con su nombre. Siempre hay al menos una.
     */
    fun opciones(minima: Long, siguiente: Long?, mediaHora: Long?, hora: Long?): List<Pair<String, Long>> {
        val r = ArrayList<Pair<String, Long>>()
        for ((n, t) in listOf("Next block" to siguiente, "About 30 minutes" to mediaHora, "About 1 hour" to hora)) {
            val v = maxOf(t ?: continue, minima)
            if (r.none { it.second == v }) r.add(n to v)
        }
        if (r.none { it.second == minima }) r.add("Minimum to replace" to minima)
        return r.sortedByDescending { it.second }
    }
}
