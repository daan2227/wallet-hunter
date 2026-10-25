package com.hunter.btc

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest
import javax.net.ssl.SSLSocketFactory

object ElectrumClient {

    data class Balance(val confirmed: Long, val unconfirmed: Long)
    data class TxEntry(val txid: String, val height: Int)

    private val SERVERS = listOf(
        "electrum.blockstream.info"   to 50002,
        "electrum.emzy.de"            to 50002,
        "bitcoin.aranguren.org"       to 50002,
        "electrum.bitaroo.net"        to 50002,
        "fortress.qtornado.com"       to 50002,
        "electrum.hodlister.co"       to 50002,
        "e2.keff.org"                 to 50002,
        "electrum1.bluewallet.io"     to 443,
        "electrum2.bluewallet.io"     to 443,
        "electrum3.bluewallet.io"     to 443
    )

    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS    = 6000

    /**
     * Usa BtcAddress, que sí verifica el checksum. Base58Check.decodePayload y
     * Bech32.decode no lo hacían, así que una dirección con un carácter mal
     * tecleado devolvía silenciosamente el scripthash de otra dirección y se
     * consultaba el saldo equivocado.
     */
    fun addrToScripthash(addr: String): String? {
        return try {
            val info = when (val r = BtcAddress.validate(addr, testnet = false)) {
                is BtcAddress.Result.Valid -> r.info
                is BtcAddress.Result.Invalid ->
                    when (val t = BtcAddress.validate(addr, testnet = true)) {
                        is BtcAddress.Result.Valid -> t.info
                        is BtcAddress.Result.Invalid -> {
                            Log.w("Electrum", "Invalid address: ${t.reason}")
                            return null
                        }
                    }
            }
            val script: ByteArray = when (info.type) {
                BtcAddress.Type.P2WPKH ->
                    byteArrayOf(0x00.toByte(), 0x14.toByte()) + info.program
                BtcAddress.Type.P2WSH ->
                    byteArrayOf(0x00.toByte(), 0x20.toByte()) + info.program
                BtcAddress.Type.P2TR ->
                    byteArrayOf(0x51.toByte(), 0x20.toByte()) + info.program
                BtcAddress.Type.P2SH ->
                    byteArrayOf(0xa9.toByte(), 0x14.toByte()) + info.program + byteArrayOf(0x87.toByte())
                BtcAddress.Type.P2PKH ->
                    byteArrayOf(0x76.toByte(), 0xa9.toByte(), 0x14.toByte()) + info.program +
                            byteArrayOf(0x88.toByte(), 0xac.toByte())
            }
            val sha = MessageDigest.getInstance("SHA-256").digest(script)
            sha.reversed().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            RegistroFallos.anotar("ElectrumClient.addrToScripthash", e)
            null
        }
    }

    fun getBalance(addr: String, testnet: Boolean = false): Balance? {
        val sh = addrToScripthash(addr) ?: return null
        val result = request("blockchain.scripthash.get_balance", listOf(sh), testnet)
        return if (result != null) {
            Balance(result.optLong("confirmed", 0), result.optLong("unconfirmed", 0))
        } else null
    }

    fun getHistory(addr: String, testnet: Boolean = false): List<TxEntry> {
        val sh = addrToScripthash(addr) ?: return emptyList()
        return try {
            // OrNull: una dirección sin historial responde [] y eso ES la respuesta.
            // Con requestArray se tomaba por fallo y la dirección se enviaba a los
            // diez servidores (privacidad) con sus esperas.
            val resp = requestArrayOrNull("blockchain.scripthash.get_history", listOf(sh), testnet)
                ?: emptyList()
            resp.map { TxEntry(it.getString("tx_hash"), it.getInt("height")) }
                .sortedByDescending { it.height }
        } catch (e: Exception) {
            RegistroFallos.anotar("ElectrumClient.getHistory: respuesta no parseable", e)
            emptyList()
        }
    }

    /**
     * Historial, o null si nadie respondió. Una lista vacía SÍ es respuesta
     * ("esta dirección no se ha usado"); getHistory no las distingue.
     */
    fun historialOrNull(addr: String, testnet: Boolean = false): List<TxEntry>? {
        val sh = addrToScripthash(addr) ?: return null
        return requestArrayOrNull("blockchain.scripthash.get_history", listOf(sh), testnet)
            ?.map { TxEntry(it.getString("tx_hash"), it.getInt("height")) }
            ?.sortedByDescending { it.height }
    }

    /**
     * Monedas sin gastar de una dirección, en el mismo formato que devuelve
     * mempool.space —{txid, vout, value}— para que quien las consuma no tenga
     * que saber de dónde vinieron.
     *
     * Electrum las llama tx_hash, tx_pos y value.
     *
     * @return null si ningún servidor respondió. Lista vacía SÍ significa "esta
     *   dirección no tiene nada": distinguirlo importa, porque tratar un fallo
     *   de red como "sin fondos" haría que el envío dijera que no hay saldo.
     */
    fun listUnspent(addr: String, testnet: Boolean = false): List<JSONObject>? {
        val sh = addrToScripthash(addr) ?: return null
        val arr = requestArrayOrNull("blockchain.scripthash.listunspent", listOf(sh), testnet)
            ?: return null
        // Un servidor ajeno puede devolver cualquier cosa: una entrada sin tx_hash o con un valor que no es
        // número lanzaba JSONException y tumbaba la app (el hilo de «All» no lo recogía). Se trata como si
        // el servidor no hubiera respondido.
        return try {
            arr.map {
                JSONObject().apply {
                    put("txid",  it.getString("tx_hash"))
                    put("vout",  it.getInt("tx_pos"))
                    put("value", it.getLong("value"))
                    // Fuente ajena: el importe no se puede comprobar (ver ChainInfo.utxos).
                    put("src", "electrum")
                    put("confirmed", it.optInt("height", 0) > 0)
                }
            }
        } catch (e: Exception) {
            RegistroFallos.anotar("ElectrumClient.listUnspent: respuesta no parseable", e)
            null
        }
    }

    /**
     * Comisión recomendada en sat/vB para confirmar en [bloques] bloques.
     *
     * blockchain.estimatefee devuelve BTC por kilobyte, o -1 cuando el servidor
     * no tiene una estimación. Aquí sale en sat/vB, que es en lo que piensa el
     * resto de la app.
     */
    fun estimateFee(bloques: Int, testnet: Boolean = false): Int? {
        val btcPerKb = (requestRaw("blockchain.estimatefee", listOf(bloques), testnet)
            as? Number)?.toDouble() ?: return null
        if (btcPerKb <= 0) return null
        // BTC/kB -> sat/vB: x1e8 para pasar a satoshis, /1000 por el kilobyte.
        val satVb = Math.ceil(btcPerKb * 1e8 / 1000.0)
        // Un servidor ajeno puede decir cualquier cosa: por encima del tope no se
        // cree (ver ChainInfo.TARIFA_MAX).
        if (satVb > ChainInfo.TARIFA_MAX) return null
        return satVb.toInt().coerceAtLeast(1)
    }

    /** Altura del último bloque. */
    fun tipHeight(testnet: Boolean = false): Int? =
        request("blockchain.headers.subscribe", emptyList(), testnet)
            ?.optInt("height", 0)?.takeIf { it > 0 }

    /**
     * Difunde una transacción firmada.
     *
     * @return el txid, o el mensaje de error del nodo si lo rechaza — que hay
     *   que enseñar tal cual: "bad-txns-inputs-missingorspent" dice qué pasó,
     *   y un "no se pudo enviar" genérico no dice nada.
     */
    fun broadcast(rawHex: String, testnet: Boolean = false): String? =
        // Sólo vale como éxito un txid de verdad (64 hex): un servidor que
        // devolviera cualquier otra cosa hacía que la app dijera "enviado" sin
        // que nada se hubiera difundido. Si no lo es, se prueba el siguiente.
        requestRaw("blockchain.transaction.broadcast", listOf(rawHex), testnet) { r ->
            r is String && TXID.matches(r)
        } as? String

    private val TXID = Regex("^[0-9a-fA-F]{64}$")

    /** El nodo rechazó la petición (p. ej. una transacción inválida): no es un fallo de red. */
    private class RechazoNodo(msg: String) : Exception(msg)

    /** Tope de una respuesta: una línea de varios cientos de MB agotaba la memoria. */
    private const val MAX_LINEA = 4 * 1024 * 1024
    /** Tope de tiempo de una respuesta entera: el de lectura sólo cuenta entre bytes. */
    private const val MAX_RESPUESTA_MS = 30_000L

    /** readLine con tope de tamaño y de tiempo total. null si se cerró sin datos. */
    private fun leerLinea(r: BufferedReader): String? {
        val fin = System.currentTimeMillis() + MAX_RESPUESTA_MS
        val sb = StringBuilder()
        while (true) {
            val c = r.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (sb.length >= MAX_LINEA) throw java.io.IOException("response too long")
            if (System.currentTimeMillis() > fin) throw java.io.IOException("response too slow")
            sb.append(c.toChar())
        }
    }

    private fun request(method: String, params: List<Any>, testnet: Boolean): JSONObject? {
        val servers = if (testnet) listOf("electrum.blockstream.info" to 60002) else SERVERS
        for ((host, port) in servers) {
            try {
                val result = connect(host, port) { reader, writer ->
                    val req = "{\"id\":1,\"method\":\"$method\",\"params\":${params.toJsonArray()}}\n"
                    writer.write(req); writer.flush()
                    val line = leerLinea(reader) ?: return@connect null
                    JSONObject(line).optJSONObject("result")
                }
                if (result != null) {
                    Log.d("Electrum", "OK: $host")
                    return result
                }
            } catch (e: Exception) {
                Log.w("Electrum", "$host:$port failed: ${e.message}")
            }
        }
        Log.e("Electrum", "All ${servers.size} servers failed")
        return null
    }

    /**
     * Como [request], pero devuelve el "result" crudo: estimatefee da un número
     * y broadcast una cadena, ninguno de los dos es un objeto JSON.
     */
    private fun requestRaw(method: String, params: List<Any>, testnet: Boolean,
                           acepta: (Any) -> Boolean = { true }): Any? {
        val servers = if (testnet) listOf("electrum.blockstream.info" to 60002) else SERVERS
        for ((host, port) in servers) {
            try {
                val result = connect(host, port) { reader, writer ->
                    val req = "{\"id\":1,\"method\":\"$method\",\"params\":${params.toJsonArray()}}\n"
                    writer.write(req); writer.flush()
                    val line = leerLinea(reader) ?: return@connect null
                    val o = JSONObject(line)
                    // Un nodo que rechaza la transacción responde con "error",
                    // no con "result": devolverlo como null diría "no hay red"
                    // cuando lo que hay es un motivo concreto.
                    o.opt("error")?.takeIf { it != JSONObject.NULL }?.let { err ->
                        val msg = (err as? JSONObject)?.optString("message") ?: err.toString()
                        throw RechazoNodo(msg)
                    }
                    o.opt("result")?.takeIf { it != JSONObject.NULL }
                }
                if (result != null && acepta(result)) { Log.d("Electrum", "OK: $host"); return result }
                if (result != null) Log.w("Electrum", "$host: unexpected answer, trying the next one")
            } catch (e: RechazoNodo) {
                // Rechazo del nodo: no sirve reintentar en otro servidor, porque
                // todos van a decir lo mismo. Antes caía aquí también cualquier
                // fallo de red (un timeout es IOException), y el primer servidor
                // caído se daba por "rechazo": no se probaba ningún otro.
                Log.w("Electrum", "$host rejected it: ${e.message}")
                return "ERROR: ${e.message}"
            } catch (e: Exception) {
                Log.w("Electrum", "$host:$port failed: ${e.message}")
            }
        }
        return null
    }

    /** Lista de resultados; null si nadie respondió (lista vacía SÍ es una respuesta). */
    private fun requestArrayOrNull(method: String, params: List<Any>, testnet: Boolean):
            List<JSONObject>? {
        val servers = if (testnet) listOf("electrum.blockstream.info" to 60002) else SERVERS
        for ((host, port) in servers) {
            try {
                val result = connect(host, port) { reader, writer ->
                    val req = "{\"id\":1,\"method\":\"$method\",\"params\":${params.toJsonArray()}}\n"
                    writer.write(req); writer.flush()
                    val line = leerLinea(reader) ?: return@connect null
                    val arr = JSONObject(line).optJSONArray("result") ?: return@connect null
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                }
                if (result != null) { Log.d("Electrum", "OK: $host"); return result }
            } catch (e: Exception) {
                Log.w("Electrum", "$host:$port failed: ${e.message}")
            }
        }
        return null
    }

    private fun <T> connect(host: String, port: Int, block: (BufferedReader, BufferedWriter) -> T): T {
        // Conectamos primero un socket plano para que aplique CONNECT_TIMEOUT_MS y
        // luego montamos TLS encima. createSocket(socket, host, port, autoClose=true)
        // hace que el SSLSocket sea dueño del plano, así cerrarlo cierra ambos.
        val plain = java.net.Socket()
        val ssl = try {
            plain.connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            factory.createSocket(plain, host, port, true) as javax.net.ssl.SSLSocket
        } catch (e: Throwable) {
            try { plain.close() } catch (_: Exception) {}
            throw e
        }
        ssl.use {
            // Un SSLSocket valida la cadena de certificados pero NO comprueba que
            // el certificado corresponda a `host` salvo que se pida explícitamente.
            // Sin esto, cualquier certificado de cualquier CA de confianza sirve
            // para un ataque MITM.
            ssl.sslParameters = ssl.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            ssl.soTimeout = READ_TIMEOUT_MS
            ssl.startHandshake()
            val reader = BufferedReader(InputStreamReader(ssl.inputStream))
            val writer = BufferedWriter(OutputStreamWriter(ssl.outputStream))
            return block(reader, writer)
        }
    }

    private fun List<Any>.toJsonArray(): String {
        return "[" + joinToString(",") { if (it is String) "\"$it\"" else it.toString() } + "]"
    }
}
