package com.hunter.btc

/**
 * La parte con red de OpenTimestamps (Ots es la pura): mandar el compromiso a los calendarios, pedirles lo
 * que falta de una prueba pendiente, y comprobar cada atestación contra la cabecera del bloque. Todo por
 * RedPrivada (Tor si está activado). Al calendario sólo va un hash con nonce, nunca el archivo.
 */
object SelloTiempo {
    private const val MAX = 64 * 1024
    private const val CONNECT_MS = 15_000
    private const val READ_MS = 30_000
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun pedir(url: String, cuerpo: ByteArray?): ByteArray? {
        val c = RedPrivada.abrir(url)
        try {
            c.connectTimeout = CONNECT_MS; c.readTimeout = READ_MS
            c.setRequestProperty("Accept", "application/vnd.opentimestamps.v1")
            c.setRequestProperty("User-Agent", "hunter-suite")
            if (cuerpo != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(cuerpo) }
            }
            if (c.responseCode != 200) return null
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
            c.inputStream.use { while (true) { val n = it.read(buf); if (n < 0) break; if (out.size() + n > MAX) return null; out.write(buf, 0, n) } }
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    /**
     * Sella [digest] (SHA-256 del archivo): nonce aleatorio, compromiso a cada calendario y une las
     * respuestas. Devuelve el .ots y cuántos calendarios respondieron (0 = no sirve: no hay nada que guardar).
     */
    fun sellar(digest: ByteArray): Pair<Ots.Archivo, Int> {
        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val (arch, comp) = Ots.nuevo(digest, nonce)
        var ok = 0
        for (cal in Ots.CALENDARIOS) {
            try {
                val r = pedir("$cal/digest", comp.msg) ?: continue
                comp.fusionar(Ots.leerSello(r, comp.msg)); ok++
            } catch (e: Exception) { RegistroFallos.anotar("SelloTiempo.sellar: $cal", e) }
        }
        return arch to ok
    }

    /**
     * Pide a los calendarios (sólo a los conocidos) la rama que completa cada atestación pendiente.
     * Devuelve cuántas se completaron; 0 = aún no están en un bloque (tarda unas horas).
     */
    fun actualizar(a: Ots.Archivo): Int {
        var n = 0
        for ((nodo, uri) in a.sello.pendientes()) {
            if (!Ots.calendarioConocido(uri)) continue
            try {
                val r = pedir("$uri/timestamp/${hex(nodo.msg)}", null) ?: continue
                val s = Ots.leerSello(r, nodo.msg)
                if (s.todas().any { it.second is Ots.Atestacion.Bitcoin }) { nodo.fusionar(s); n++ }
            } catch (e: Exception) { RegistroFallos.anotar("SelloTiempo.actualizar: $uri", e) }
        }
        return n
    }

    sealed class Resultado {
        /** Existía, como muy tarde, en el bloque [altura] ([hora] en segundos Unix). */
        data class Anclado(val altura: Long, val hora: Long, val hash: String) : Resultado()
        data class Falla(val altura: Long, val motivo: String) : Resultado()
    }

    /**
     * Comprueba cada atestación de Bitcoin: la cabecera del bloque la dan todos los servidores que respondan
     * (tienen que coincidir), su hash tiene que ser el del bloque, tener su prueba de trabajo, y su raíz de
     * Merkle tiene que ser el mensaje de la prueba.
     */
    fun verificar(a: Ots.Archivo, testnet: Boolean = false): List<Resultado> =
        a.sello.todas().mapNotNull { (msg, at) ->
            val h = (at as? Ots.Atestacion.Bitcoin)?.altura ?: return@mapNotNull null
            try {
                val hashes = ChainApi.getDeTodos("/block-height/$h", testnet).map { it.trim() }.filter { it.length == 64 }.toSet()
                if (hashes.size != 1) return@mapNotNull Resultado.Falla(h, if (hashes.isEmpty()) "No server answered for block $h." else "Servers disagree on block $h.")
                val hash = hashes.single()
                val cabs = ChainApi.getDeTodos("/block/$hash/header", testnet).map { it.trim() }.toSet()
                val cab = cabs.singleOrNull()?.takeIf { it.length == 160 }
                    ?.let { s -> ByteArray(80) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
                    ?: return@mapNotNull Resultado.Falla(h, "Could not get a consistent header for block $h.")
                if (Ots.hashCabecera(cab) != hash.lowercase()) return@mapNotNull Resultado.Falla(h, "The header does not match block $h.")
                if (!testnet && !Ots.trabajoValido(cab)) return@mapNotNull Resultado.Falla(h, "The header of block $h has no valid proof of work.")
                val hora = Ots.horaSiAncla(msg, cab) ?: return@mapNotNull Resultado.Falla(h, "The proof does not match block $h: this file was NOT timestamped there.")
                Resultado.Anclado(h, hora, hash)
            } catch (e: Exception) { RegistroFallos.anotar("SelloTiempo.verificar", e); Resultado.Falla(h, "Network error checking block $h.") }
        }
}
