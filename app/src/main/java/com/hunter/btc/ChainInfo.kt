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

    /**
     * Tarifa máxima creíble, en sat/vB. Las fuentes son ajenas (y en el respaldo,
     * diez servidores Electrum de cualquiera): una que dijera 50 000 sat/vB haría
     * pagar de comisión una parte grande del envío. Por encima de esto la fuente
     * no se cree y se pasa a la siguiente.
     */
    const val TARIFA_MAX = 2000

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
            ?: throw java.io.IOException("no answer"))
        Fees(
            fastest  = o.optInt("fastestFee", 0),
            halfHour = o.optInt("halfHourFee", 0),
            hour     = o.optInt("hourFee", 0),
            economy  = o.optInt("economyFee", 0),
            minimum  = o.optInt("minimumFee", 1)
        ).takeIf { it.fastest > 0 && it.hour > 0 && it.fastest <= TARIFA_MAX && it.halfHour <= TARIFA_MAX }
    } catch (e: Exception) {
        android.util.Log.w("ChainInfo", "fees via mempool.space failed: ${e.message}"); null
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
        android.util.Log.w("ChainInfo", "fees via Electrum failed: ${e.message}"); null
    }

    /**
     * Monedas sin gastar de una dirección, con el mismo respaldo.
     *
     * @return null si no respondió nadie; lista vacía si la dirección está
     *   realmente sin fondos. Confundir las dos cosas hace que el envío diga
     *   "no tienes saldo" cuando lo que pasa es que no hay red.
     */
    fun utxos(addr: String, testnet: Boolean = false): List<JSONObject>? {
        // Cada moneda lleva de dónde salió ("src") y si está confirmada
        // ("confirmed"): Enviar lo necesita para no fiarse de un importe que no
        // puede comprobar (ver fuenteFiable) ni gastar lo que aún no es firme.
        ChainApi.get("/address/$addr/utxo", testnet)?.let { txt ->
            try {
                val arr = org.json.JSONArray(txt)
                return (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    o.put("src", "esplora")
                    o.put("confirmed", o.optJSONObject("status")?.optBoolean("confirmed", false) ?: false)
                }
            } catch (e: Exception) {
                android.util.Log.w("ChainInfo", "utxos: unreadable answer: ${e.message}")
            }
        }
        return ElectrumClient.listUnspent(addr, testnet)
    }

    /** ¿El importe de esta moneda viene de una fuente en la que se confía (Esplora)? */
    fun fuenteFiable(u: JSONObject): Boolean = u.optString("src") == "esplora"

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
            is ChainApi.Envio.Ok -> {
                // El txid que se muestra tiene que ser el de los bytes que se
                // firmaron, no el que devuelva el servidor sin comprobar.
                val calculado = Psbt.txidDeRaw(rawHex)
                // Un txid distinto no prueba que no se haya difundido: puede estar en la red y
                // la respuesta venir mal. Se avisa de que se mire en el explorador antes de reintentar.
                return if (calculado == null || calculado == r.txid.lowercase()) r.txid
                       else "ERROR: the server answered with a different transaction id. Check the explorer " +
                            "before trying again: the transaction may already have been relayed."
            }
            is ChainApi.Envio.Rechazada -> {
                // Un servidor que ya la tiene (el envío anterior llegó pero la
                // respuesta se perdió por tiempo) dice "ya está": eso es un éxito,
                // y mostrarlo como error hacía reenviar y confundía al usuario.
                val yaEsta = Regex("already|txn-already|in mempool|in block", RegexOption.IGNORE_CASE)
                    .containsMatchIn(r.motivo)
                if (yaEsta) Psbt.txidDeRaw(rawHex)?.let { return it }
                // Un rechazo es definitivo: todos los nodos aplican las mismas
                // reglas, así que reintentarlo por Electrum daría lo mismo.
                return "ERROR: ${r.motivo}"
            }
            ChainApi.Envio.SinRespuesta -> {}
        }
        val porElectrum = ElectrumClient.broadcast(rawHex, testnet)
        if (porElectrum != null) return porElectrum
        // Ni la API ni Electrum respondieron: se prueba directamente contra nodos de la
        // red por P2P (sólo mainnet). No depende de ningún servidor concreto.
        if (!testnet) {
            val ctx = HunterEngine.contexto()
            if (ctx != null) P2pBroadcast.difundir(ctx, rawHex)?.let { return it }
        }
        return "ERROR: no node could be reached to broadcast it. " +
               "Check your connection: the transaction is signed and you can try again."
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
            ?.takeIf { it in 1..499_999_999 && creible(it, testnet) }?.let { return it }
        return ElectrumClient.tipHeight(testnet)?.takeIf { it in 1..499_999_999 && creible(it, testnet) }
    }

    /**
     * ¿Es creíble esa altura? Con nSequence 0xFFFFFFFD el locktime se aplica: una
     * altura inflada por una fuente mala dejaba la transacción sin validez durante
     * meses. En mainnet se compara con la esperada por la hora (un bloque cada
     * ~10 min desde el bloque 840 000); una muy por encima no se cree, y se firma
     * con locktime 0. En testnet no hay ritmo fiable y no se comprueba.
     */
    private fun creible(altura: Int, testnet: Boolean): Boolean {
        if (testnet) return true
        val esperada = 840_000 + (System.currentTimeMillis() - 1_713_571_767_000L) / 600_000L
        return altura <= esperada + 2_000
    }
}
