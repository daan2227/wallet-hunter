package com.btcseedrecovery

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
    private const val GLOBAL_TIMEOUT_MS  = 12_000L

    private val pool: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "electrum").apply { isDaemon = true }
        }

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

    /**
     * Queries every server concurrently and returns the first usable answer,
     * bounded by GLOBAL_TIMEOUT_MS. Sequential fallback over 10 servers could
     * otherwise block for CONNECT+READ (10s) x 10 = 100s.
     */
    private fun <T : Any> race(servers: List<Pair<String, Int>>, call: (String, Int) -> T?): T? {
        val ecs = java.util.concurrent.ExecutorCompletionService<T?>(pool)
        val futures = servers.map { (host, port) ->
            ecs.submit(java.util.concurrent.Callable<T?> {
                try { call(host, port) }
                catch (e: Exception) { Log.w("Electrum", "$host:$port failed: ${e.message}"); null }
            })
        }
        val deadlineNs = System.nanoTime() + GLOBAL_TIMEOUT_MS * 1_000_000
        try {
            repeat(servers.size) {
                val remaining = deadlineNs - System.nanoTime()
                if (remaining <= 0) return null
                val done = ecs.poll(remaining, java.util.concurrent.TimeUnit.NANOSECONDS) ?: return null
                val result = try { done.get() } catch (e: Exception) { null }
                if (result != null) return result
            }
            Log.e("Electrum", "All ${servers.size} servers failed")
            return null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            futures.forEach { it.cancel(true) }
        }
    }

    private fun serversFor(testnet: Boolean) =
        if (testnet) listOf("electrum.blockstream.info" to 60002) else SERVERS

    /** Builds the JSON-RPC frame with org.json so params are escaped properly. */
    private fun buildRequest(method: String, params: List<Any>): String {
        val arr = org.json.JSONArray()
        params.forEach { arr.put(it) }
        return JSONObject()
            .put("id", 1)
            .put("method", method)
            .put("params", arr)
            .toString() + "\n"
    }

    private fun request(method: String, params: List<Any>, testnet: Boolean): JSONObject? =
        race(serversFor(testnet)) { host, port ->
            connect(host, port) { reader, writer ->
                writer.write(buildRequest(method, params)); writer.flush()
                val line = reader.readLine() ?: return@connect null
                JSONObject(line).optJSONObject("result")
            }
        }

    private fun requestArray(method: String, params: List<Any>, testnet: Boolean): List<JSONObject> =
        race(serversFor(testnet)) { host, port ->
            connect(host, port) { reader, writer ->
                writer.write(buildRequest(method, params)); writer.flush()
                val line = reader.readLine() ?: return@connect null
                val arr = JSONObject(line).optJSONArray("result") ?: return@connect null
                val out = (0 until arr.length()).map { arr.getJSONObject(it) }
                out.ifEmpty { null }
            }
        } ?: emptyList()

    private fun <T> connect(host: String, port: Int, block: (BufferedReader, BufferedWriter) -> T): T {
        // Connect a plain socket first so CONNECT_TIMEOUT_MS applies, then layer
        // TLS over it. createSocket(socket, host, port, autoClose=true) makes the
        // SSLSocket own the plain socket, so closing it closes both.
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
            // An SSLSocket validates the chain but does NOT check that the cert
            // matches `host` unless endpoint identification is requested. Without
            // this, any cert from any trusted CA would be accepted -> MITM.
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
}
