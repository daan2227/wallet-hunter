package com.hunter.btc

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Un error que devuelve el nodo (code -28 = aún arrancando, cargando el índice de bloques). */
class CoreRpcError(val code: Int, mensaje: String) : Exception(mensaje) {
    val arrancando get() = code == -28
}

/**
 * RPC contra el bitcoind del propio teléfono, por 127.0.0.1 y con la cookie que escribe el nodo
 * (rpccookiefile, en el almacén privado de la app): no hay contraseña guardada en ninguna parte.
 */
class CoreRpc(private val cookie: File, private val puerto: Int = CoreConfig.RPC_PORT) {

    companion object {
        /** Saca `result` de una respuesta JSON-RPC o lanza [CoreRpcError]. Público para probarlo. */
        fun interpretar(cuerpo: String): Any? {
            val o = JSONObject(cuerpo)
            val err = o.optJSONObject("error")
            if (err != null) throw CoreRpcError(err.optInt("code", 0), err.optString("message", "error"))
            return if (o.isNull("result")) null else o.get("result")
        }

        fun cabeceraBasic(cookie: String): String =
            "Basic " + java.util.Base64.getEncoder().encodeToString(cookie.trim().toByteArray(Charsets.UTF_8))
    }

    fun llamar(metodo: String, params: JSONArray = JSONArray(), lecturaMs: Int = 15_000): Any? {
        val ck = try { cookie.readText() } catch (e: Exception) {
            throw CoreRpcError(-28, "El nodo todavía no ha escrito su cookie")
        }
        val c = URL("http://127.0.0.1:$puerto/").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 3_000; c.readTimeout = lecturaMs
            c.doOutput = true
            c.setRequestProperty("Authorization", cabeceraBasic(ck))
            c.setRequestProperty("Content-Type", "application/json")
            val cuerpo = JSONObject().put("jsonrpc", "1.0").put("id", "wh")
                .put("method", metodo).put("params", params).toString()
            c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            // Core contesta con HTTP 500 y el error en el cuerpo: se lee de donde toque.
            val flujo = if (c.responseCode in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            return interpretar(flujo.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    fun objeto(metodo: String, params: JSONArray = JSONArray(), lecturaMs: Int = 15_000): JSONObject =
        llamar(metodo, params, lecturaMs) as JSONObject
}
