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

    /** @param source "" = una API web, "electrum" = se usó el respaldo. */
    data class Result(val sat: Long, val source: String)

    /**
     * Saldo por la API web (mempool.space o blockstream.info, lo que responda).
     * Lanza si no responde ninguna o si el JSON no se entiende.
     */
    fun fromMempool(addr: String, testnet: Boolean = false): Long {
        val js = ChainApi.get("/address/$addr", testnet)
            ?: throw java.io.IOException("no web API answered")
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

    /**
     * ¿Se ha usado alguna vez esta dirección?
     *
     * No vale mirar el saldo: una dirección que recibió y se vació tiene saldo
     * 0 pero está usada, y reutilizarla es justo lo que se quiere evitar. Lo que
     * cuenta es el número de transacciones.
     *
     * @return null si no se pudo averiguar — quien llame debe tratarlo como
     *   "no lo sé" y no como "sin usar", o acabaría reutilizando direcciones.
     */
    fun isUsed(addr: String, testnet: Boolean = false): Boolean? {
        if (addr.isEmpty()) return null
        try {
            val js = ChainApi.get("/address/$addr", testnet)
            if (js != null) {
                val o = JSONObject(js)
                fun txs(b: String) = o.optJSONObject(b)?.optInt("tx_count", 0) ?: 0
                return txs("chain_stats") + txs("mempool_stats") > 0
            }
        } catch (e: Exception) {
            android.util.Log.w("BalanceLookup", "isUsed via web failed on $addr: ${e.message}")
        }
        // Electrum no da el número de transacciones directamente, pero su
        // historial sí: si tiene alguna entrada, la dirección se ha usado.
        return try {
            ElectrumClient.getHistory(addr, testnet).takeIf { it.isNotEmpty() }?.let { true }
                ?: if (ElectrumClient.getBalance(addr, testnet) != null) false else null
        } catch (e: Exception) {
            android.util.Log.w("BalanceLookup", "isUsed via Electrum failed on $addr: ${e.message}")
            null
        }
    }

    /**
     * Saldo con respaldo, en tres niveles: mempool.space, blockstream.info y
     * Electrum. null sólo si no respondió ninguno de los tres.
     */
    fun query(addr: String, testnet: Boolean = false): Result? {
        if (addr.isEmpty()) return null
        try {
            return Result(fromMempool(addr, testnet), "")
        } catch (e: Exception) {
            android.util.Log.w("BalanceLookup", "web APIs failed on $addr: ${e.message}")
        }
        return try {
            val eb = ElectrumClient.getBalance(addr, testnet)
            if (eb != null) Result(eb.confirmed + eb.unconfirmed, "electrum") else null
        } catch (e: Exception) {
            android.util.Log.w("BalanceLookup", "electrum failed on $addr: ${e.message}")
            null
        }
    }
}
