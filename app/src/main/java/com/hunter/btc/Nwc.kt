package com.hunter.btc

import org.json.JSONArray
import org.json.JSONObject

/**
 * Nostr Wallet Connect (NIP-47): manejar desde la app un nodo Lightning que está en otro sitio (Alby Hub,
 * LNbits, un nodo con un servicio NWC…). La conexión es una URI "nostr+walletconnect://<servicio>?relay=…
 * &secret=…": la app firma peticiones con ese secreto, las cifra para el servicio (NIP-44, o NIP-04 si el
 * servicio es antiguo) y las manda por el relay; la respuesta llega igual y se COMPRUEBA (firma del
 * servicio y que responde a nuestra petición) antes de creérsela.
 *
 * El secreto da permiso para gastar (lo que el servicio haya permitido): se guarda cifrado (NwcGuardado).
 */
class Nwc(val servicio: String, val relays: List<String>, private val secreto: String, private val curva: NostrEvento.Curva) {

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        /** Lee la URI; null si no es válida. */
        fun leer(uri: String, curva: NostrEvento.Curva): Nwc? {
            val t = uri.trim()
            val pre = listOf("nostr+walletconnect://", "nostrwalletconnect://").firstOrNull { t.startsWith(it, ignoreCase = true) } ?: return null
            val resto = t.substring(pre.length)
            val pk = resto.substringBefore('?').lowercase().trimEnd('/')
            val ps = resto.substringAfter('?', "").split('&').filter { it.contains('=') }
                .map { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val relays = ps.filter { it.first == "relay" }.map { it.second }.filter { it.startsWith("wss://") || it.startsWith("ws://") }
            val sec = ps.firstOrNull { it.first == "secret" }?.second?.lowercase() ?: return null
            if (!HEX64.matches(pk) || !HEX64.matches(sec) || relays.isEmpty()) return null
            return Nwc(pk, relays, sec, curva)
        }
    }

    class Error(val codigo: String, mensaje: String) : Exception("$codigo: $mensaje")

    private val compartida: ByteArray by lazy { Descriptor.bytes(curva.op("ecdh_x", secreto, servicio).also { require(it.length == 64) }) }
    private val conv: ByteArray by lazy { Nip44.claveConversacion(compartida) }
    @Volatile private var usarNip44: Boolean? = null

    /** El evento de información del servicio (kind 13194): métodos que permite y cifrados que entiende. */
    fun info(): Pair<List<String>, List<String>> {
        val ev = consulta(JSONObject().put("kinds", JSONArray().put(13194)).put("authors", JSONArray().put(servicio)).put("limit", 1))
            ?: return emptyList<String>() to listOf("nip04")
        val metodos = ev.content.split(' ').filter { it.isNotBlank() }
        val cifrados = ev.etiqueta("encryption")?.split(' ')?.filter { it.isNotBlank() } ?: listOf("nip04")
        return metodos to cifrados
    }

    /** Una petición: devuelve "result" o lanza [Error] con el error del servicio (o de red). */
    fun pedir(metodo: String, params: JSONObject = JSONObject(), timeoutMs: Int = 60_000): JSONObject {
        val nip44 = usarNip44 ?: (info().second.contains("nip44_v2")).also { usarNip44 = it }
        val plano = JSONObject().put("method", metodo).put("params", params).toString()
        val contenido = if (nip44) Nip44.cifrar(plano, conv) else Nip44.cifrar04(plano, compartida)
        val tags = mutableListOf(listOf("p", servicio)).apply { if (nip44) add(listOf("encryption", "nip44_v2")) }
        val ev = NostrEvento.firmar(curva, secreto, 23194, tags, contenido)
        var ultimo: Exception? = null
        for (r in relays) {
            try {
                WsCliente.conectar(r).use { ws ->
                    val sub = "nwc" + ev.id.take(8)
                    // Suscribirse ANTES de publicar: la respuesta puede llegar enseguida.
                    ws.enviar(JSONArray().put("REQ").put(sub).put(JSONObject().put("kinds", JSONArray().put(23195))
                        .put("authors", JSONArray().put(servicio)).put("#e", JSONArray().put(ev.id))).toString())
                    ws.enviar(JSONArray().put("EVENT").put(ev.json()).toString())
                    val fin = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < fin) {
                        ws.ponerTimeout(maxOf(1000, (fin - System.currentTimeMillis()).toInt()))
                        val m = JSONArray(ws.recibir() ?: break)
                        when (m.optString(0)) {
                            "OK" -> if (m.optString(1) == ev.id && !m.optBoolean(2, true)) throw Error("RELAY_REJECTED", m.optString(3))
                            "EVENT" -> {
                                if (m.optString(1) != sub) continue
                                val r2 = NostrEvento.leer(m.getJSONObject(2), curva) ?: continue      // firma o id malos: se ignora
                                if (r2.pubkey != servicio || r2.kind != 23195 || r2.etiqueta("e") != ev.id) continue
                                val texto = if (r2.etiqueta("encryption")?.contains("nip44") == true || !r2.content.contains("?iv="))
                                    Nip44.descifrar(r2.content, conv) else Nip44.descifrar04(r2.content, compartida)
                                val o = JSONObject(texto)
                                o.optJSONObject("error")?.let { e -> throw Error(e.optString("code", "ERROR"), e.optString("message")) }
                                ws.enviar(JSONArray().put("CLOSE").put(sub).toString())
                                return o.optJSONObject("result") ?: JSONObject()
                            }
                        }
                    }
                    throw Error("TIMEOUT", "no answer from the wallet service")
                }
            } catch (e: Error) { if (e.codigo != "TIMEOUT") throw e; ultimo = e } catch (e: Exception) { ultimo = e }
        }
        throw (ultimo as? Error) ?: Error("NETWORK", ultimo?.message ?: "no relay answered")
    }

    /** Un evento (el primero que cuadre) para un filtro; null si no hay. */
    private fun consulta(filtro: JSONObject): NostrEvento? {
        for (r in relays) try {
            WsCliente.conectar(r).use { ws ->
                ws.enviar(JSONArray().put("REQ").put("info").put(filtro).toString())
                ws.ponerTimeout(10_000)
                while (true) {
                    val m = JSONArray(ws.recibir() ?: return@use)
                    when (m.optString(0)) {
                        "EOSE" -> return@use
                        "EVENT" -> NostrEvento.leer(m.getJSONObject(2), curva)?.takeIf { it.pubkey == servicio }?.let { return it }
                    }
                }
            }
        } catch (e: Exception) {}
        return null
    }

    // ── Métodos de NIP-47 ─────────────────────────────────────────────────────────────────────────
    fun saldoSat(): Long = pedir("get_balance").optLong("balance") / 1000
    fun factura(sats: Long, descripcion: String): String =
        pedir("make_invoice", JSONObject().put("amount", sats * 1000).put("description", descripcion)).getString("invoice")
    fun pagar(factura: String): String = pedir("pay_invoice", JSONObject().put("invoice", factura)).optString("preimage")
    fun movimientos(n: Int = 20): JSONArray = pedir("list_transactions", JSONObject().put("limit", n)).optJSONArray("transactions") ?: JSONArray()
}
