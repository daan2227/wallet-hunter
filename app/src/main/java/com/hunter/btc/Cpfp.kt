package com.hunter.btc

/**
 * Cálculo de la tasa de una transacción hija (CPFP). Puro: sin red ni Android,
 * para poder probarlo en la JVM.
 */
object Cpfp {
    /**
     * La menor tasa entera (sat/vB) de la hija tal que el par (padre + hija)
     * pague [objetivo] sat/vB. [vp] y [fp] son el tamaño y la comisión del
     * padre; [vc] el tamaño de la hija. Nunca menos de 1 sat/vB.
     */
    fun tasaHija(vp: Long, fp: Long, objetivo: Long, vc: Long): Long {
        require(vc > 0) { "la hija tiene que tener tamaño" }
        val necesario = objetivo * (vp + vc) - fp
        if (necesario <= 0) return 1L
        return maxOf(1L, (necesario + vc - 1) / vc)
    }
}
