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

    fun addrToScripthash(addr: String): String? {
        return try {
            val script: ByteArray = when {
                addr.startsWith("bc1q") || addr.startsWith("tb1q") -> {
                    val h = Bech32.decode(addr) ?: return null
                    byteArrayOf(0x00.toByte(), 0x14.toByte()) + h
                }
                addr.startsWith("3") || addr.startsWith("2") -> {
                    val h = Base58Check.decodePayload(addr, 1) ?: return null
                    byteArrayOf(0xa9.toByte(), 0x14.toByte()) + h + byteArrayOf(0x87.toByte())
                }
                else -> {
                    val h = Base58Check.decodePayload(addr, 1) ?: return null
                    byteArrayOf(0x76.toByte(), 0xa9.toByte(), 0x14.toByte()) + h +
                            byteArrayOf(0x88.toByte(), 0xac.toByte())
                }
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
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val raw = factory.createSocket()
        raw.use {
            raw.connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = READ_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(raw.getInputStream()))
            val writer = BufferedWriter(OutputStreamWriter(raw.getOutputStream()))
            return block(reader, writer)
        }
    }

    private fun List<Any>.toJsonArray(): String {
        return "[" + joinToString(",") { if (it is String) "\"$it\"" else it.toString() } + "]"
    }
}
