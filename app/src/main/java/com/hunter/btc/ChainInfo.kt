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

    // 5 s era corto para una conexión móvil con latencia; y sobre todo, cuando
    // mempool.space no se alcanza —hay redes e ISP que lo resuelven a una IP
    // que no es suya— la espera entera se gastaba para acabar sin nada.
    private const val TIMEOUT_MS = 8000

    /** Comisiones recomendadas, en sat/vB. */
    data class Fees(
        val fastest: Int,   // siguiente bloque
        val halfHour: Int,
        val hour: Int,
        val economy: Int,
        val minimum: Int
    )

    private fun base(testnet: Boolean) =
        if (testnet) "https://mempool.space/testnet/api" else "https://mempool.space/api"

    private fun get(url: String): String {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS; conn.readTimeout = TIMEOUT_MS
        return try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
    }

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
        val o = JSONObject(get("${base(testnet)}/v1/fees/recommended"))
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
        try {
            val txt = get(
                if (testnet) "https://mempool.space/testnet/api/address/$addr/utxo"
                else "https://mempool.space/api/address/$addr/utxo")
            val arr = org.json.JSONArray(txt)
            return (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) {
            android.util.Log.w("ChainInfo", "utxos por mempool.space falló: ${e.message}")
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
        try {
            val url = java.net.URL(
                if (testnet) "https://mempool.space/testnet/api/tx"
                else "https://mempool.space/api/tx")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT_MS; conn.readTimeout = TIMEOUT_MS
            conn.outputStream.use { it.write(rawHex.toByteArray()) }
            val code = conn.responseCode
            val body = try {
                (if (code == 200) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
            } finally { conn.disconnect() }
            if (code == 200 && body.length == 64) return body
            // Un 400 de mempool.space es el nodo rechazando la transacción, no
            // un problema de red: no sirve reintentar por Electrum.
            if (code != 200 && body.isNotEmpty()) return "ERROR: $body"
        } catch (e: Exception) {
            android.util.Log.w("ChainInfo", "broadcast por mempool.space falló: ${e.message}")
        }
        return ElectrumClient.broadcast(rawHex, testnet)
            ?: "ERROR: no se pudo contactar con ningún nodo para difundirla"
    }

    /**
     * Altura del último bloque.
     *
     * @return null si no se pudo consultar. En ese caso hay que firmar con
     *   locktime 0: poner una altura inventada dejaría la transacción sin
     *   validez hasta que la cadena la alcanzara.
     */
    fun tipHeight(testnet: Boolean = false): Int? {
        try {
            val h = get("${base(testnet)}/blocks/tip/height").trim().toInt()
            // Por encima de 500.000.000 el campo se interpreta como marca de tiempo.
            if (h in 1..499_999_999) return h
        } catch (e: Exception) {
            android.util.Log.w("ChainInfo", "tipHeight por mempool.space falló: ${e.message}")
        }
        return ElectrumClient.tipHeight(testnet)?.takeIf { it in 1..499_999_999 }
    }
}
