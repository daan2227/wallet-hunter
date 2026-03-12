package com.hunter.btc

import android.util.Log
import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.security.MessageDigest
import javax.net.ssl.SSLSocketFactory

object ElectrumClient {

    data class Balance(val confirmed: Long, val unconfirmed: Long)
    data class TxEntry(val txid: String, val height: Int)

    private val SERVERS = listOf(
        "electrum.blockstream.info" to 50002,
        "electrum.emzy.de" to 50002,
        "bitcoin.aranguren.org" to 50002
    )

    // Compute Electrum scripthash from Bitcoin address
    fun addrToScripthash(addr: String): String? = try {
        val script: ByteArray = when {
            addr.startsWith("bc1q") || addr.startsWith("tb1q") -> {
                // P2WPKH: OP_0 <20-byte hash>
                val h = Bech32.decode(addr) ?: return null
                byteArrayOf(0x00.toByte(), 0x14.toByte()) + h
            }
            addr.startsWith("bc1p") -> return null // taproot not supported yet
            addr.startsWith("3") || addr.startsWith("2") -> {
                // P2SH: OP_HASH160 <20-byte hash> OP_EQUAL
                val h = Base58Check.decodePayload(addr, 1) ?: return null
                byteArrayOf(0xa9.toByte(), 0x14.toByte()) + h + byteArrayOf(0x87.toByte())
            }
            else -> {
                // P2PKH: OP_DUP OP_HASH160 <20-byte hash> OP_EQUALVERIFY OP_CHECKSIG
                val h = Base58Check.decodePayload(addr, 1) ?: return null
                byteArrayOf(0x76.toByte(), 0xa9.toByte(), 0x14.toByte()) + h +
                        byteArrayOf(0x88.toByte(), 0xac.toByte())
            }
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(script)
        // reverse for electrum
        sha.reversed().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { null }

    fun getBalance(addr: String, testnet: Boolean = false): Balance? {
        val sh = addrToScripthash(addr) ?: return null
        return request("blockchain.scripthash.get_balance", listOf(sh), testnet)?.let {
            Balance(
                it.optLong("confirmed", 0),
                it.optLong("unconfirmed", 0)
            )
        }
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
                return connect(host, port) { reader, writer ->
                    val req = """{"id":1,"method":"$method","params":${params.toJsonArray()}}""" + "\n"
                    writer.write(req); writer.flush()
                    val line = reader.readLine() ?: return@connect null
                    JSONObject(line).optJSONObject("result")
                }
            } catch (e: Exception) { Log.w("Electrum", "$host failed: ${e.message}") }
        }
        return null
    }

    private fun requestArray(method: String, params: List<Any>, testnet: Boolean): List<JSONObject> {
        val servers = if (testnet) listOf("electrum.blockstream.info" to 60002) else SERVERS
        for ((host, port) in servers) {
            try {
                return connect(host, port) { reader, writer ->
                    val req = """{"id":1,"method":"$method","params":${params.toJsonArray()}}""" + "\n"
                    writer.write(req); writer.flush()
                    val line = reader.readLine() ?: return@connect emptyList()
                    val arr = JSONObject(line).optJSONArray("result") ?: return@connect emptyList()
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                } ?: emptyList()
            } catch (e: Exception) { Log.w("Electrum", "$host failed: ${e.message}") }
        }
        return emptyList()
    }

    private fun <T> connect(host: String, port: Int, block: (BufferedReader, BufferedWriter) -> T): T? {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        factory.createSocket().use { raw ->
            val ssl = factory.createSocket(raw, host, port, true)
            ssl.soTimeout = 8000
            val reader = BufferedReader(InputStreamReader(ssl.getInputStream()))
            val writer = BufferedWriter(OutputStreamWriter(ssl.getOutputStream()))
            return block(reader, writer)
        }
    }

    private fun List<Any>.toJsonArray() = "[${joinToString(",") { if (it is String) "\"$it\"" else it.toString() }}]"
}
