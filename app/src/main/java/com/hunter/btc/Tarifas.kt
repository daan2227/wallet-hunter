package com.hunter.btc

/**
 * Los cuatro tramos de comisión (en sat/vB) a partir de las estimaciones de Esplora.
 *
 * `/v1/fees/recommended` sólo existe en mempool.space. Si ese host no responde, la app
 * caía a Electrum y, si tampoco, a una tarifa fija de 5 sat/vB: el envío salía siempre
 * por la misma cantidad. blockstream.info sirve la misma información en `/fee-estimates`
 * (bloques objetivo -> sat/vB), que es lo que se traduce aquí. Sin Android, para probarlo.
 */
object Tarifas {

    data class Tramos(val fastest: Int, val halfHour: Int, val hour: Int, val economy: Int)

    /**
     * @param est objetivo en bloques -> sat/vB
     * @return null si no hay ninguna estimación utilizable o alguna pasa del tope.
     *   Los tramos salen siempre ordenados: la rápida nunca es menor que la lenta.
     */
    fun desdeEsplora(est: Map<Int, Double>, maximo: Int): Tramos? {
        fun tomar(vararg objetivos: Int): Int? =
            objetivos.asList().firstNotNullOfOrNull { est[it] }
                ?.takeIf { it.isFinite() && it > 0 }
                ?.let { Math.ceil(it).toInt() }
        val rapida = tomar(1, 2, 3) ?: return null
        val media = tomar(3, 4, 5, 6) ?: rapida
        val hora = tomar(6, 5, 4, 3) ?: media
        val eco = tomar(12, 24, 25, 144, 6) ?: hora
        if (listOf(rapida, media, hora, eco).any { it > maximo }) return null
        val m = minOf(media, rapida)
        val h = minOf(hora, m)
        val e = minOf(eco, h)
        return Tramos(rapida.coerceAtLeast(1), m.coerceAtLeast(1), h.coerceAtLeast(1), e.coerceAtLeast(1))
    }
}
