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

    private const val TIMEOUT_MS = 5000

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

    /** @return null si no se pudo consultar; quien llame decide el respaldo. */
    fun fees(testnet: Boolean = false): Fees? = try {
        val o = JSONObject(get("${base(testnet)}/v1/fees/recommended"))
        Fees(
            fastest  = o.optInt("fastestFee", 0),
            halfHour = o.optInt("halfHourFee", 0),
            hour     = o.optInt("hourFee", 0),
            economy  = o.optInt("economyFee", 0),
            minimum  = o.optInt("minimumFee", 1)
        ).takeIf { it.fastest > 0 && it.hour > 0 }
    } catch (e: Exception) {
        android.util.Log.w("ChainInfo", "fees falló: ${e.message}"); null
    }

    /**
     * Altura del último bloque.
     *
     * @return null si no se pudo consultar. En ese caso hay que firmar con
     *   locktime 0: poner una altura inventada dejaría la transacción sin
     *   validez hasta que la cadena la alcanzara.
     */
    fun tipHeight(testnet: Boolean = false): Int? = try {
        val h = get("${base(testnet)}/blocks/tip/height").trim().toInt()
        // Por encima de 500.000.000 el campo se interpreta como marca de tiempo.
        if (h in 1..499_999_999) h else null
    } catch (e: Exception) {
        android.util.Log.w("ChainInfo", "tipHeight falló: ${e.message}"); null
    }
}
