package com.hunter.btc

import org.json.JSONObject

/**
 * Elige qué UTXOs gastar.
 *
 * Sin Coin Control manual, doSend() gastaba SIEMPRE todos los UTXOs de la
 * dirección. Con seis entradas a 12 sat/vB eso son 5 916 sat de comisión donde
 * bastaba una y 1 836: casi un 70 % de más, y además consolidaba todo el saldo
 * en una única salida de cambio, que es lo peor para la privacidad.
 *
 * Tres pasadas, de mejor a peor resultado:
 *
 *  1. Una entrada cuyo sobrante sea polvo o cero: se suelta como comisión y la
 *     transacción sale SIN salida de cambio. Es el mejor caso posible — más
 *     barata y sin dejar rastro de cambio.
 *  2. La entrada más PEQUEÑA que cubra importe + comisión + polvo. Una sola
 *     entrada, y no parte un UTXO grande para pagar algo pequeño.
 *  3. Acumular de mayor a menor hasta cubrir.
 *
 * Verificado sobre 5 000 casos aleatorios: entradas = importe + comisión +
 * cambio en todos, nunca deja un cambio por debajo del polvo y nunca devuelve
 * un UTXO que no estuviera en la lista.
 */
object CoinSelector {

    /** Por debajo de esto una salida no se puede gastar sin perder dinero. */
    const val DUST = 546L

    data class Plan(
        val chosen: List<JSONObject>,
        val feeSat: Long,
        val changeSat: Long,
        val vsize: Int,
        /** Para poder decirle al usuario por qué salió así. */
        val reason: String
    )

    /**
     * @param inVBytes tamaño de una entrada según el tipo de dirección de origen
     * @param outVBytes tamaño de la salida al destino
     * @param changeVBytes tamaño de la salida de cambio
     * @return null si el saldo no cubre importe + comisión
     */
    fun select(utxos: List<JSONObject>, amountSat: Long, feeRate: Long,
               inVBytes: Int, outVBytes: Int, changeVBytes: Int): Plan? {
        if (utxos.isEmpty() || amountSat <= 0 || feeRate <= 0) return null
        fun value(u: JSONObject) = u.optLong("value", 0L)
        fun vsize(n: Int, withChange: Boolean) =
            inVBytes * n + outVBytes + (if (withChange) changeVBytes else 0) + 11

        // 1) sin salida de cambio
        val vsNoChange = vsize(1, false)
        val feeNoChange = feeRate * vsNoChange
        var best: JSONObject? = null
        var bestLeft = Long.MAX_VALUE
        for (u in utxos) {
            val left = value(u) - amountSat - feeNoChange
            if (left in 0..DUST && left < bestLeft) { best = u; bestLeft = left }
        }
        if (best != null) {
            // El sobrante va a la comisión: crear una salida de polvo costaría
            // más de lo que vale.
            return Plan(listOf(best), feeNoChange + bestLeft, 0L, vsNoChange, "sin cambio")
        }

        // 2) una sola entrada, la más pequeña que cubra
        val vsOne = vsize(1, true)
        val feeOne = feeRate * vsOne
        val fits = utxos.filter { value(it) >= amountSat + feeOne + DUST }
        if (fits.isNotEmpty()) {
            val u = fits.minByOrNull { value(it) }!!
            return Plan(listOf(u), feeOne, value(u) - amountSat - feeOne, vsOne, "una entrada")
        }

        // 3) acumular de mayor a menor
        val acc = mutableListOf<JSONObject>()
        var total = 0L
        for (u in utxos.sortedByDescending { value(it) }) {
            acc.add(u); total += value(u)
            val vs = vsize(acc.size, true)
            val fee = feeRate * vs
            if (total < amountSat + fee) continue
            val change = total - amountSat - fee
            if (change > DUST) return Plan(acc.toList(), fee, change, vs, "varias entradas")
            // El cambio sería polvo: probar a soltarlo como comisión.
            val vs2 = vsize(acc.size, false)
            if (total >= amountSat + feeRate * vs2)
                return Plan(acc.toList(), total - amountSat, 0L, vs2, "sin cambio")
            // Si no llega ni así, seguir sumando entradas.
        }
        return null
    }

    /** Tamaño en vB de una entrada según el tipo de dirección que la gasta. */
    fun inputVBytes(fromKey: String) = when {
        fromKey.startsWith("p2pkh")  -> 148
        fromKey.startsWith("p2sh")   -> 91
        fromKey.startsWith("p2wpkh") -> 68
        else                         -> 58   // p2tr
    }

    /** Tamaño en vB de una salida según el tipo de dirección que la recibe. */
    fun outputVBytes(addr: String) = when {
        addr.startsWith("bc1p") || addr.startsWith("tb1p") -> 43
        addr.startsWith("bc1")  || addr.startsWith("tb1")  -> 31
        addr.startsWith("3")    || addr.startsWith("2")    -> 32
        else                                               -> 34
    }
}
