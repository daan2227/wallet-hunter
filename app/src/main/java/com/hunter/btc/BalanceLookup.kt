package com.hunter.btc

import org.json.JSONObject

/**
 * Consulta de saldo de una dirección, con respaldo.
 *
 * Primero mempool.space; si no responde o devuelve un error, Electrum. La
 * lógica vivía dentro de loadBalancesTab() en WalletActivity, así que el baúl
 * de hallazgos no podía reutilizarla sin copiarla —y dos copias del mismo
 * parseo acaban divergiendo—.
 *
 * Todas las llamadas hacen red: nunca desde el hilo principal.
 *
 * Aviso de privacidad: preguntar por una dirección se la revela al servidor
 * consultado. En un hallazgo eso delata que este dispositivo tiene la clave,
 * y con qué antelación. Para las direcciones de los puzzles da casi igual
 * —están vigiladas por medio mundo—, pero conviene tenerlo presente.
 */
object BalanceLookup {

    /** @param source "" = mempool.space, "electrum" = se usó el respaldo. */
    data class Result(val sat: Long, val source: String)

    private const val TIMEOUT_MS = 5000

    /** Saldo vía mempool.space. Lanza si la consulta o el JSON fallan. */
    fun fromMempool(addr: String, testnet: Boolean = false): Long {
        val conn = java.net.URL(
            if (testnet) "https://mempool.space/testnet/api/address/$addr"
            else "https://mempool.space/api/address/$addr"
        ).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS; conn.readTimeout = TIMEOUT_MS
        val js = try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
        // Se leía con Regex().find(), que devuelve la PRIMERA coincidencia. La
        // respuesta trae esos campos en chain_stats y en mempool_stats, así que
        // el resultado dependía del orden que emitiera la API.
        val o = JSONObject(js)
        fun sumOf(block: String): Long {
            val b = o.optJSONObject(block) ?: return 0L
            return b.optLong("funded_txo_sum", 0L) - b.optLong("spent_txo_sum", 0L)
        }
        return sumOf("chain_stats") + sumOf("mempool_stats")
    }

    /** Saldo con respaldo. null si ninguna fuente respondió. */
    fun query(addr: String, testnet: Boolean = false): Result? {
        if (addr.isEmpty()) return null
        try {
            return Result(fromMempool(addr, testnet), "")
        } catch (e: Exception) {
            android.util.Log.w("BalanceLookup", "mempool falló en $addr: ${e.message}")
        }
        return try {
            val eb = ElectrumClient.getBalance(addr, testnet)
            if (eb != null) Result(eb.confirmed + eb.unconfirmed, "electrum") else null
        } catch (e: Exception) {
            android.util.Log.w("BalanceLookup", "electrum falló en $addr: ${e.message}")
            null
        }
    }
}
