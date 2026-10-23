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

        /** Las respuestas de una llamada por lotes, en el orden en que se pidieron. Lanza [CoreRpcError] si alguna falló. */
        fun interpretarLote(cuerpo: String, n: Int): List<Any?> {
            val t = cuerpo.trimStart()
            if (t.startsWith("{")) { interpretar(t); throw CoreRpcError(-32603, "unexpected answer to a batch") }
            val a = JSONArray(t)
            val salida = arrayOfNulls<Any>(n)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val err = o.optJSONObject("error")
                if (err != null) throw CoreRpcError(err.optInt("code", 0), err.optString("message", "error"))
                val id = o.optInt("id", -1)
                if (id in 0 until n) salida[id] = if (o.isNull("result")) null else o.get("result")
            }
            return salida.toList()
        }

        fun cabeceraBasic(cookie: String): String =
            "Basic " + java.util.Base64.getEncoder().encodeToString(cookie.trim().toByteArray(Charsets.UTF_8))
    }

    private fun enviar(cuerpo: String, lecturaMs: Int): String {
        // Sin cookie no hay nodo en marcha (Core la borra al parar y la escribe al arrancar). Antes esto se
        // lanzaba como «-28, cargando el índice», y la pantalla enseñaba «Starting…» con el nodo parado.
        val ck = try { cookie.readText() } catch (e: Exception) {
            throw java.io.FileNotFoundException("no cookie: the node is not running")
        }
        val c = URL("http://127.0.0.1:$puerto/").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 3_000; c.readTimeout = lecturaMs
            c.doOutput = true
            c.setRequestProperty("Authorization", cabeceraBasic(ck))
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            // Core contesta con HTTP 500 y el error en el cuerpo: se lee de donde toque.
            val flujo = if (c.responseCode in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            return flujo.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    fun llamar(metodo: String, params: JSONArray = JSONArray(), lecturaMs: Int = 15_000): Any? {
        val cuerpo = JSONObject().put("jsonrpc", "1.0").put("id", "wh")
            .put("method", metodo).put("params", params).toString()
        return interpretar(enviar(cuerpo, lecturaMs))
    }

    /** Varias llamadas en una sola petición (JSON-RPC por lotes): para miles de consultas pequeñas. */
    fun lote(llamadas: List<Pair<String, JSONArray>>, lecturaMs: Int = 120_000): List<Any?> {
        val cuerpo = JSONArray()
        for ((i, p) in llamadas.withIndex())
            cuerpo.put(JSONObject().put("jsonrpc", "1.0").put("id", i).put("method", p.first).put("params", p.second))
        return interpretarLote(enviar(cuerpo.toString(), lecturaMs), llamadas.size)
    }

    fun objeto(metodo: String, params: JSONArray = JSONArray(), lecturaMs: Int = 15_000): JSONObject =
        llamar(metodo, params, lecturaMs) as JSONObject
}
