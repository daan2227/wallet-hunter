package com.hunter.btc

/**
 * La única puerta de salida a la cadena, con tres niveles de contingencia.
 *
 * El problema que arregla: TODAS las llamadas de la app iban a mempool.space y
 * a nada más. Hay redes donde ese dominio no se alcanza —lo resuelven a una IP
 * que no es suya— y entonces no funcionaba nada que necesitara la cadena: ni
 * saldos, ni comisiones, ni enviar. Y lo peor es que cada llamada volvía a
 * intentarlo y volvía a gastarse la espera entera antes de rendirse.
 *
 * Los tres niveles:
 *
 *  1. **mempool.space** — la API Esplora.
 *  2. **blockstream.info** — la MISMA API. Esplora es el servidor que usan los
 *     dos, así que las rutas y las respuestas son idénticas: sirve de repuesto
 *     sin escribir un segundo parseo.
 *  3. **Electrum** — otro protocolo, sobre TCP con TLS y contra diez
 *     servidores distintos. Es el que salva el caso de arriba, porque no
 *     depende de que un dominio de web resuelva bien.
 *
 * Los dos primeros viven aquí; el tercero lo pone quien llama, porque no todas
 * las preguntas se pueden hacer en Electrum.
 *
 * **Se recuerda cuál funcionó.** Sin esto, en una red donde el primer host
 * está muerto, cada consulta se gasta el tiempo de espera entero antes de
 * pasar al segundo — con doce direcciones en la cartera son casi dos minutos
 * de reloj tirados. Al acertar, ese host pasa a ser el primero que se prueba.
 *
 * Todo hace red: nunca desde el hilo principal.
 */
object ChainApi {

    /** Descubrir que un host está muerto tiene que ser rápido. */
    private const val CONNECT_MS = 4000
    /** Leer la respuesta de un host que sí responde puede tardar más. */
    private const val READ_MS = 9000

    private val HOSTS = listOf(
        "https://mempool.space",
        "https://blockstream.info"
    )

    /**
     * Índice del host que funcionó la última vez.
     *
     * @Volatile y no un lock: lo escriben varios hilos de consulta a la vez y
     * lo peor que puede pasar es que dos acierten el mismo valor.
     */
    @Volatile private var preferido = 0

    private fun base(host: String, testnet: Boolean) =
        if (testnet) "$host/testnet/api" else "$host/api"

    /** Los hosts empezando por el que respondió la última vez. */
    private fun enOrden(): List<Pair<Int, String>> {
        val p = preferido.coerceIn(0, HOSTS.size - 1)
        return (HOSTS.indices).map { (p + it) % HOSTS.size }.map { it to HOSTS[it] }
    }

    /**
     * GET contra la ruta Esplora dada, probando los hosts por orden.
     *
     * @param ruta empieza por "/", sin el "/api": "/address/1A2b.../utxo"
     * @return el cuerpo de la respuesta, o null si ninguno respondió
     */
    fun get(ruta: String, testnet: Boolean = false): String? {
        for ((idx, host) in enOrden()) {
            try {
                val conn = java.net.URL(base(host, testnet) + ruta)
                    .openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = CONNECT_MS
                conn.readTimeout = READ_MS
                val cuerpo = try {
                    if (conn.responseCode != 200)
                        throw java.io.IOException("HTTP ${conn.responseCode}")
                    conn.inputStream.bufferedReader().readText()
                } finally { conn.disconnect() }
                preferido = idx
                return cuerpo
            } catch (e: Exception) {
                android.util.Log.w("ChainApi", "$host$ruta falló: ${e.message}")
            }
        }
        return null
    }

    /** Lo que devuelve un intento de difundir. */
    sealed class Envio {
        /** Entró. */
        data class Ok(val txid: String) : Envio()
        /**
         * El nodo la rechazó y dijo por qué. No sirve reintentar en otro
         * servidor: todos aplican las mismas reglas de consenso.
         */
        data class Rechazada(val motivo: String) : Envio()
        /** No respondió nadie. Esto SÍ se puede reintentar. */
        object SinRespuesta : Envio()
    }

    /**
     * POST de una transacción firmada.
     *
     * Distinguir "rechazada" de "sin respuesta" no es un detalle: con la
     * primera hay que cambiar algo, con la segunda basta con volver a
     * intentarlo, y enseñar la una como la otra manda al usuario a hacer lo
     * que no toca.
     */
    fun broadcast(rawHex: String, testnet: Boolean = false): Envio {
        var huboRechazo: String? = null
        for ((idx, host) in enOrden()) {
            try {
                val conn = java.net.URL(base(host, testnet) + "/tx")
                    .openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/plain")
                conn.connectTimeout = CONNECT_MS
                conn.readTimeout = READ_MS
                conn.outputStream.use { it.write(rawHex.toByteArray()) }
                val code = conn.responseCode
                val cuerpo = try {
                    (if (code == 200) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.readText()?.trim().orEmpty()
                } finally { conn.disconnect() }
                preferido = idx
                // Esplora devuelve el txid en texto plano: 64 caracteres hex.
                if (code == 200 && cuerpo.length == 64) return Envio.Ok(cuerpo)
                if (cuerpo.isNotEmpty()) { huboRechazo = cuerpo; break }
            } catch (e: Exception) {
                android.util.Log.w("ChainApi", "broadcast por $host falló: ${e.message}")
            }
        }
        huboRechazo?.let { return Envio.Rechazada(it) }
        return Envio.SinRespuesta
    }

    /**
     * ¿Hay alguna forma de llegar a la cadena ahora mismo?
     *
     * Sirve para poder decirle al usuario "no hay red" una vez, en vez de
     * repetir el mismo fallo en cada fila de una lista de doce direcciones.
     */
    fun hayRed(testnet: Boolean = false): Boolean =
        get("/blocks/tip/height", testnet) != null || ElectrumClient.tipHeight(testnet) != null
}
