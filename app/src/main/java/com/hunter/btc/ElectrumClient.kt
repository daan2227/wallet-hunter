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
                            Log.w("Electrum", "Dirección inválida: ${t.reason}")
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
        } catch (e: Exception) { null }
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
            val resp = requestArray("blockchain.scripthash.get_history", listOf(sh), testnet)
            resp.map { TxEntry(it.getString("tx_hash"), it.getInt("height")) }
                .sortedByDescending { it.height }
        } catch (e: Exception) { emptyList() }
    }

    private fun request(method: String, params: List<Any>, testnet: Boolean): JSONObject? {
        val servers = if (testnet) listOf("electrum.blockstream.info" to 60002) else SERVERS
        for ((host, port) in servers) {
            try {
                val result = connect(host, port) { reader, writer ->
                    val req = "{\"id\":1,\"method\":\"$method\",\"params\":${params.toJsonArray()}}\n"
                    writer.write(req); writer.flush()
                    val line = reader.readLine() ?: return@connect null
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

    private fun requestArray(method: String, params: List<Any>, testnet: Boolean): List<JSONObject> {
        val servers = if (testnet) listOf("electrum.blockstream.info" to 60002) else SERVERS
        for ((host, port) in servers) {
            try {
                val result = connect(host, port) { reader, writer ->
                    val req = "{\"id\":1,\"method\":\"$method\",\"params\":${params.toJsonArray()}}\n"
                    writer.write(req); writer.flush()
                    val line = reader.readLine() ?: return@connect emptyList<JSONObject>()
                    val arr = JSONObject(line).optJSONArray("result") ?: return@connect emptyList<JSONObject>()
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                }
                if (!result.isNullOrEmpty()) {
                    Log.d("Electrum", "OK: $host")
                    return result
                }
            } catch (e: Exception) {
                Log.w("Electrum", "$host:$port failed: ${e.message}")
            }
        }
        return emptyList()
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
