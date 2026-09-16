package com.hunter.btc

import org.json.JSONObject

/**
 * Datos de la cadena que hasta ahora se daban por supuestos.
 *
 * La comisión se calculaba con lo que el usuario escribiera en un campo, por
 * defecto 5 sat/vB — un número fijo que no sabe nada de cómo está la mempool en
 * ese momento: de más cuando está vacía, insuficiente cuando está llena, y en
 * ese caso la transacción se queda colgada.
 *
 * Y nLockTime iba siempre a 0. Las carteras modernas lo ponen en la altura
 * actual para desincentivar el *fee sniping*: con locktime 0 cualquiera puede
 * reminar el último bloque e incluir tu transacción; con la altura actual, sólo
 * sirve a partir del siguiente. Sólo tiene efecto si alguna entrada lleva
 * nSequence por debajo de 0xFFFFFFFF, cosa que ya hacemos por RBF.
 *
 * Todo hace red: nunca desde el hilo principal.
 */
object ChainInfo {

    /** Comisiones recomendadas, en sat/vB. */
    data class Fees(
        val fastest: Int,   // siguiente bloque
        val halfHour: Int,
        val hour: Int,
        val economy: Int,
        val minimum: Int
    )

    /**
     * @return null sólo si no respondió NI mempool.space NI ningún Electrum.
     *
     * Antes iba sólo a mempool.space, así que en una red donde ese dominio no
     * se alcanza —lo cual pasa: hay ISP que lo resuelven a una IP que no es
     * suya— las tres opciones de comisión se quedaban en "sin datos" y el envío
     * salía a ciegas.
     */
    fun fees(testnet: Boolean = false): Fees? =
        feesFromMempool(testnet) ?: feesFromElectrum(testnet)

    private fun feesFromMempool(testnet: Boolean): Fees? = try {
        // Sólo mempool.space tiene /v1/fees/recommended; Esplora a secas no.
        // Si ese host no va, se cae a Electrum, que estima por bloques.
        val o = JSONObject(ChainApi.get("/v1/fees/recommended", testnet)
            ?: throw java.io.IOException("sin respuesta"))
        Fees(
            fastest  = o.optInt("fastestFee", 0),
            halfHour = o.optInt("halfHourFee", 0),
            hour     = o.optInt("hourFee", 0),
            economy  = o.optInt("economyFee", 0),
            minimum  = o.optInt("minimumFee", 1)
        ).takeIf { it.fastest > 0 && it.hour > 0 }
    } catch (e: Exception) {
        android.util.Log.w("ChainInfo", "fees por mempool.space falló: ${e.message}"); null
    }

    /**
     * Electrum estima por número de bloques, no por franjas de tiempo: 1 bloque
     * son ~10 min, 3 ~30 min y 25 unas cuatro horas. Es la misma pregunta hecha
     * en la unidad del protocolo.
     */
    private fun feesFromElectrum(testnet: Boolean): Fees? = try {
        val rapida = ElectrumClient.estimateFee(1, testnet)
        val media  = ElectrumClient.estimateFee(3, testnet)
        val lenta  = ElectrumClient.estimateFee(25, testnet)
        if (rapida == null && media == null && lenta == null) null
        else {
            // Si alguna no viene, se rellena con la que sí, para no dejar una
            // opción muerta en la pantalla.
            val r = rapida ?: media ?: lenta!!
            val m = media  ?: r
            val l = lenta  ?: m
            Fees(fastest = r, halfHour = m, hour = m, economy = l, minimum = 1)
        }
    } catch (e: Exception) {
        android.util.Log.w("ChainInfo", "fees por Electrum falló: ${e.message}"); null
    }

    /**
     * Monedas sin gastar de una dirección, con el mismo respaldo.
     *
     * @return null si no respondió nadie; lista vacía si la dirección está
     *   realmente sin fondos. Confundir las dos cosas hace que el envío diga
     *   "no tienes saldo" cuando lo que pasa es que no hay red.
     */
    fun utxos(addr: String, testnet: Boolean = false): List<JSONObject>? {
        ChainApi.get("/address/$addr/utxo", testnet)?.let { txt ->
            try {
                val arr = org.json.JSONArray(txt)
                return (0 until arr.length()).map { arr.getJSONObject(it) }
            } catch (e: Exception) {
                android.util.Log.w("ChainInfo", "utxos: respuesta ilegible: ${e.message}")
            }
        }
        return ElectrumClient.listUnspent(addr, testnet)
    }

    /**
     * Difunde la transacción firmada.
     *
     * @return el txid si entró, o un texto que empieza por "ERROR" con el
     *   motivo del nodo. Ese motivo hay que enseñarlo tal cual:
     *   "bad-txns-inputs-missingorspent" dice qué ha pasado; "no se pudo
     *   enviar" no dice nada.
     */
    fun broadcast(rawHex: String, testnet: Boolean = false): String {
        when (val r = ChainApi.broadcast(rawHex, testnet)) {
            is ChainApi.Envio.Ok -> return r.txid
            // Un rechazo es definitivo: todos los nodos aplican las mismas
            // reglas, así que reintentarlo por Electrum daría lo mismo.
            is ChainApi.Envio.Rechazada -> return "ERROR: ${r.motivo}"
            ChainApi.Envio.SinRespuesta -> {}
        }
        val porElectrum = ElectrumClient.broadcast(rawHex, testnet)
        return porElectrum
            ?: "ERROR: no se pudo contactar con ningún nodo para difundirla. " +
               "Revisa la conexión: la transacción está firmada y puedes reintentarlo."
    }

    /**
     * Altura del último bloque.
     *
     * @return null si no se pudo consultar. En ese caso hay que firmar con
     *   locktime 0: poner una altura inventada dejaría la transacción sin
     *   validez hasta que la cadena la alcanzara.
     */
    fun tipHeight(testnet: Boolean = false): Int? {
        // Por encima de 500.000.000 el campo se interpreta como marca de tiempo.
        ChainApi.get("/blocks/tip/height", testnet)?.trim()?.toIntOrNull()
            ?.takeIf { it in 1..499_999_999 }?.let { return it }
        return ElectrumClient.tipHeight(testnet)?.takeIf { it in 1..499_999_999 }
    }
}
