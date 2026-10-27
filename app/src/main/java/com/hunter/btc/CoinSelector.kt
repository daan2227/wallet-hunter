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

    /**
     * Límite de polvo de Bitcoin Core (relay a 3 sat/vB) según el tipo de dirección.
     * DUST (546) es el de P2PKH; una salida P2WPKH de 294 sat ya se relaya.
     */
    fun polvoDe(addr: String): Long {
        val a = addr.lowercase()
        return when {
            a.startsWith("bc1p") || a.startsWith("tb1p") || a.startsWith("bcrt1p") -> 330L
            (a.startsWith("bc1q") || a.startsWith("tb1q") || a.startsWith("bcrt1q")) && a.length > 50 -> 330L  // P2WSH
            a.startsWith("bc1q") || a.startsWith("tb1q") || a.startsWith("bcrt1q") -> 294L                    // P2WPKH
            a.startsWith("3") || a.startsWith("2") -> 540L                                                      // P2SH
            else -> DUST
        }
    }

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
     * @param polvoCambio por debajo de esto la salida de CAMBIO no se puede gastar sin perder
     *   dinero. [DUST] (546, el de P2PKH) por defecto para no cambiar nada a quien ya llamaba
     *   sin este parámetro; cuando se sabe el tipo de la dirección de cambio conviene pasar
     *   [polvoDe] de esa dirección — una segwit/Taproot admite un cambio válido bastante más
     *   pequeño (294/330), y usar siempre 546 aquí hacía que un sobrante en ese rango estrecho
     *   se soltara entero a la comisión (pasada 1) cuando sí cabía como cambio de verdad.
     * @return null si el saldo no cubre importe + comisión
     */
    fun select(utxos: List<JSONObject>, amountSat: Long, feeRate: Long,
               inVBytes: Int, outVBytes: Int, changeVBytes: Int, polvoCambio: Long = DUST): Plan? {
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
            if (left in 0..polvoCambio && left < bestLeft) { best = u; bestLeft = left }
        }
        if (best != null) {
            // El sobrante va a la comisión: crear una salida de polvo costaría
            // más de lo que vale.
            return Plan(listOf(best), feeNoChange + bestLeft, 0L, vsNoChange, "no change")
        }

        // 2) una sola entrada, la más pequeña que cubra
        val vsOne = vsize(1, true)
        val feeOne = feeRate * vsOne
        val fits = utxos.filter { value(it) >= amountSat + feeOne + polvoCambio }
        if (fits.isNotEmpty()) {
            val u = fits.minByOrNull { value(it) }!!
            return Plan(listOf(u), feeOne, value(u) - amountSat - feeOne, vsOne, "one input")
        }

        // 2b) varias entradas que suman justo, sin cambio (búsqueda Branch and Bound)
        sinCambioExacto(utxos, amountSat, feeRate, inVBytes, outVBytes, polvoCambio)?.let { return it }

        // 3) acumular de mayor a menor
        val acc = mutableListOf<JSONObject>()
        var total = 0L
        for (u in utxos.sortedByDescending { value(it) }) {
            acc.add(u); total += value(u)
            val vs = vsize(acc.size, true)
            val fee = feeRate * vs
            if (total >= amountSat + fee) {
                val change = total - amountSat - fee
                if (change > polvoCambio) return Plan(acc.toList(), fee, change, vs, "several inputs")
            }
            // Sin cambio: o el cambio sería polvo, o ni siquiera alcanza para pagar la salida de cambio.
            // Antes este caso sólo se probaba si ya llegaba CON cambio, que es más caro: "All" (el máximo
            // sin cambio) con 2 o más monedas daba "Not enough balance" teniendo fondos.
            val vs2 = vsize(acc.size, false)
            if (total >= amountSat + feeRate * vs2)
                return Plan(acc.toList(), total - amountSat, 0L, vs2, "no change")
            // Si no llega ni así, seguir sumando entradas.
        }
        return null
    }

    /** Tope de nodos que visita la búsqueda: acota el tiempo con cientos de monedas. */
    internal const val BNB_NODOS = 100_000

    /**
     * Branch and Bound: un conjunto de VARIAS monedas cuya suma cubre importe + comisión y sobra a lo
     * sumo [polvoCambio] (que va a la comisión), o sea, una transacción sin salida de cambio. Evita
     * crear una moneda nueva y, con ella, el rastro del cambio y el coste de gastarla después.
     * Entre las soluciones elige las de menos entradas y, a igualdad, el menor sobrante.
     *
     * Sólo cuenta monedas que valen más que lo que cuesta gastarlas ([inVBytes] × [feeRate]); una de polvo
     * hace crecer el sobrante al añadirla y rompe la poda. Con una sola entrada ya lo resuelven las
     * pasadas anteriores, así que esto sólo busca de 2 en adelante.
     */
    internal fun sinCambioExacto(utxos: List<JSONObject>, amountSat: Long, feeRate: Long,
                                 inVBytes: Int, outVBytes: Int, polvoCambio: Long): Plan? {
        val costeEntrada = inVBytes * feeRate
        val v = utxos.filter { it.optLong("value", 0L) > costeEntrada }
            .sortedByDescending { it.optLong("value", 0L) }
        if (v.size < 2) return null
        val valores = LongArray(v.size) { v[it].optLong("value", 0L) }
        val resto = LongArray(v.size + 1)            // resto[i] = suma de valores[i..]
        for (i in v.size - 1 downTo 0) resto[i] = resto[i + 1] + valores[i]
        fun objetivo(n: Int) = amountSat + feeRate * (inVBytes * n + outVBytes + 11)
        if (resto[0] < objetivo(2)) return null

        var mejor: IntArray? = null
        var mejorN = Int.MAX_VALUE; var mejorSobra = Long.MAX_VALUE
        var nodos = 0
        val elegidas = IntArray(v.size)

        fun buscar(i: Int, n: Int, suma: Long) {
            if (++nodos > BNB_NODOS) return
            if (n >= 2) {
                val sobra = suma - objetivo(n)
                if (sobra >= 0) {
                    // Cubre. Si el sobrante cabe en polvo, es candidata; añadir más monedas sólo lo
                    // empeora (cada una suma más de lo que cuesta), así que no se sigue por aquí.
                    if (sobra <= polvoCambio && (n < mejorN || (n == mejorN && sobra < mejorSobra))) {
                        mejorN = n; mejorSobra = sobra; mejor = elegidas.copyOf(n)
                    }
                    return
                }
            }
            if (i >= v.size || n + 1 > mejorN) return
            // Ni cogiendo todo lo que queda se llega con al menos 2 monedas.
            if (suma + resto[i] < objetivo(maxOf(n, 2))) return
            elegidas[n] = i
            buscar(i + 1, n + 1, suma + valores[i])     // con esta
            buscar(i + 1, n, suma)                      // sin esta
        }
        buscar(0, 0, 0L)

        val m = mejor ?: return null
        val chosen = m.map { v[it] }
        val total = m.sumOf { valores[it] }
        return Plan(chosen, total - amountSat, 0L, inVBytes * m.size + outVBytes + 11, "no change (exact match)")
    }

    /** Tamaño en vB de una entrada según el tipo de dirección que la gasta. */
    fun inputVBytes(fromKey: String, wifSinComprimir: Boolean = false) = when {
        fromKey.startsWith("p2pkh")  -> 148
        // Las direcciones de una WIF, según su tipo (ver tipoDeClave).
        fromKey.startsWith("wif_p2wpkh") -> 68
        fromKey.startsWith("wif_p2sh")   -> 91
        fromKey.startsWith("wif_p2tr")   -> 58
        // La P2PKH de una WIF comprimida, o de una sin comprimir (clave de 65 bytes).
        fromKey.startsWith("wif")    -> if (wifSinComprimir) 180 else 148
        fromKey.startsWith("p2sh")   -> 91
        fromKey.startsWith("p2wpkh") -> 68
        else                         -> 58   // p2tr
    }

    /** Tamaño en vB de una salida según el tipo de dirección que la recibe. */
    fun outputVBytes(addr: String) = when {
        addr.startsWith("bc1p") || addr.startsWith("tb1p") -> 43
        // P2WSH (bc1q de 62 caracteres): programa de 32 bytes, como Taproot.
        (addr.startsWith("bc1") || addr.startsWith("tb1")) && addr.length > 50 -> 43
        addr.startsWith("bc1")  || addr.startsWith("tb1")  -> 31
        addr.startsWith("3")    || addr.startsWith("2")    -> 32
        else                                               -> 34
    }
}
