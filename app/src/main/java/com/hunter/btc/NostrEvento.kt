package com.hunter.btc

import org.json.JSONArray
import org.json.JSONObject

/**
 * Un evento de Nostr (NIP-01): id = SHA-256 de la serialización canónica, firma Schnorr (BIP340) del id.
 * Las operaciones de curva se inyectan (en la app, HunterEngine.ec "xonly"/"schnorr"/"schnorr_ok").
 */
class NostrEvento(val id: String, val pubkey: String, val creado: Long, val kind: Int,
                  val tags: List<List<String>>, val content: String, val sig: String) {

    fun etiqueta(nombre: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == nombre }?.get(1)

    fun json(): JSONObject = JSONObject().put("id", id).put("pubkey", pubkey).put("created_at", creado).put("kind", kind)
        .put("tags", JSONArray().apply { tags.forEach { t -> put(JSONArray(t)) } }).put("content", content).put("sig", sig)

    fun interface Curva { fun op(op: String, a: String, b: String): String }

    companion object {
        /** Cadena JSON con el escapado exacto de NIP-01 (sin escapar "/", ni lo que no sea de control). */
        fun cadena(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r")
                '\t' -> append("\\t"); '\b' -> append("\\b"); '\u000c' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }

        fun serializar(pubkey: String, creado: Long, kind: Int, tags: List<List<String>>, content: String): String =
            "[0," + cadena(pubkey) + "," + creado + "," + kind + ",[" +
            tags.joinToString(",") { t -> "[" + t.joinToString(",") { cadena(it) } + "]" } + "]," + cadena(content) + "]"

        fun idDe(pubkey: String, creado: Long, kind: Int, tags: List<List<String>>, content: String): String =
            Descriptor.hex(java.security.MessageDigest.getInstance("SHA-256").digest(serializar(pubkey, creado, kind, tags, content).toByteArray(Charsets.UTF_8)))

        /** Crea y firma un evento con la privada [priv] (hex). */
        fun firmar(curva: Curva, priv: String, kind: Int, tags: List<List<String>>, content: String,
                   creado: Long = System.currentTimeMillis() / 1000): NostrEvento {
            val pub = curva.op("xonly", priv, "").also { require(it.length == 64) { "bad key" } }
            val id = idDe(pub, creado, kind, tags, content)
            val aux = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val sig = curva.op("schnorr", priv, "$id|${Descriptor.hex(aux)}").also { require(it.length == 128) { "sign failed" } }
            return NostrEvento(id, pub, creado, kind, tags, content, sig)
        }

        /** Lee un evento y comprueba id y firma; null si algo no cuadra. */
        fun leer(o: JSONObject, curva: Curva): NostrEvento? = try {
            val tags = o.getJSONArray("tags").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { t -> (0 until t.length()).map { t.getString(it) } } } }
            val e = NostrEvento(o.getString("id"), o.getString("pubkey"), o.getLong("created_at"), o.getInt("kind"), tags, o.getString("content"), o.getString("sig"))
            val ok = e.id == idDe(e.pubkey, e.creado, e.kind, e.tags, e.content) && curva.op("schnorr_ok", e.pubkey, "${e.id}|${e.sig}") == "1"
            if (ok) e else null
        } catch (e: Exception) { null }
    }
}
