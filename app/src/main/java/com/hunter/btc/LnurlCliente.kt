package com.hunter.btc

/**
 * Las peticiones de LNURL (Lnurl es la parte pura): GET por RedPrivada (Tor si está activado), sólo a URLs
 * https (o .onion), con tope de tamaño. Muchos servicios responden el error en JSON con un código 4xx: se lee
 * también, para enseñar su motivo.
 */
object LnurlCliente {
    private const val MAX = 256 * 1024

    fun get(url: String): String? {
        if (!Lnurl.urlValida(url)) return null
        val c = RedPrivada.abrir(url)
        return try {
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.instanceFollowRedirects = false          // una redirección podría llevar a http o a otro sitio
            c.setRequestProperty("Accept", "application/json")
            val codigo = c.responseCode
            val ent = (if (codigo in 200..299) c.inputStream else c.errorStream) ?: return null
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
            ent.use { while (true) { val n = it.read(buf); if (n < 0) break; if (out.size() + n > MAX) return null; out.write(buf, 0, n) } }
            out.toString("UTF-8")
        } catch (e: Exception) { RegistroFallos.anotar("LnurlCliente", e); null }
        finally { c.disconnect() }
    }
}
