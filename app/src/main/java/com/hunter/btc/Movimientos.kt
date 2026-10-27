package com.hunter.btc

import org.json.JSONObject

/**
 * Qué ha cambiado en una dirección entre dos consultas: el estado es (nº de transacciones, saldo
 * confirmado + pendiente). Puro, para probarlo; la consulta y el aviso están en AvisosMovimientos.
 */
object Movimientos {
    data class Estado(val txs: Int, val saldo: Long) {
        fun texto() = "$txs|$saldo"
        companion object {
            fun de(t: String?): Estado? = t?.split('|')?.takeIf { it.size == 2 }?.let { p ->
                val a = p[0].toIntOrNull(); val b = p[1].toLongOrNull(); if (a == null || b == null) null else Estado(a, b) }
        }
    }

    /** De la respuesta de /address/{addr} (Esplora). null si no tiene la forma esperada. */
    fun deEsplora(json: String): Estado? = try {
        val o = JSONObject(json)
        val c = o.getJSONObject("chain_stats"); val m = o.getJSONObject("mempool_stats")
        Estado(c.getInt("tx_count") + m.getInt("tx_count"),
               c.getLong("funded_txo_sum") - c.getLong("spent_txo_sum") + m.getLong("funded_txo_sum") - m.getLong("spent_txo_sum"))
    } catch (e: Exception) { null }

    sealed class Cambio {
        data class Entra(val sat: Long) : Cambio()
        data class Sale(val sat: Long) : Cambio()
        /** Hubo transacciones pero el saldo no cambió (p. ej. una que entra y sale, o una pendiente que se confirma con otra). */
        object Otro : Cambio()
    }

    /** null si no hay nada que avisar. Sin estado anterior tampoco: la primera vez sólo se apunta. */
    fun comparar(antes: Estado?, ahora: Estado): Cambio? {
        if (antes == null || ahora.txs <= antes.txs && ahora.saldo == antes.saldo) return null
        val d = ahora.saldo - antes.saldo
        return when { d > 0 -> Cambio.Entra(d); d < 0 -> Cambio.Sale(-d); else -> if (ahora.txs != antes.txs) Cambio.Otro else null }
    }
}
