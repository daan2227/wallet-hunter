package com.hunter.btc

/**
 * Resolver ₿usuario@dominio (BIP353). Las consultas van por DNS sobre HTTPS (RFC 8484, binario) a
 * resolvedores públicos, por RedPrivada (Tor si está activado); pero de ellos sólo se toman los DATOS: la
 * validez la decide Dnssec en el teléfono, desde el ancla de la raíz. Un resolvedor que mienta sólo puede
 * hacer que falle, nunca colar otra dirección.
 */
object ResolverNombre {
    private val DOH = listOf("https://cloudflare-dns.com/dns-query", "https://dns.google/dns-query", "https://dns.quad9.net/dns-query")
    private const val MAX = 64 * 1024

    /** Una respuesta DNS en binario para [nombre] y [tipo], del primer resolvedor que conteste. */
    fun pedir(nombre: String, tipo: Int): ByteArray? {
        val q = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(Dns.consulta(nombre, tipo))
        for (base in DOH) {
            val c = RedPrivada.abrir("$base?dns=$q")
            try {
                c.connectTimeout = 10_000; c.readTimeout = 15_000; c.instanceFollowRedirects = false
                c.setRequestProperty("Accept", "application/dns-message")
                if (c.responseCode != 200) continue
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var grande = false
                c.inputStream.use { while (true) { val n = it.read(buf); if (n < 0) break; if (out.size() + n > MAX) { grande = true; break }; out.write(buf, 0, n) } }
                if (!grande) return out.toByteArray()
            } catch (e: Exception) { RegistroFallos.anotar("ResolverNombre: $base", e) }
            finally { c.disconnect() }
        }
        return null
    }

    /** null si [texto] no es un nombre; si lo es, lo que dice su DNS (validado). Llamar desde un hilo. */
    fun resolver(texto: String): Bip353.Resultado? {
        val n = Bip353.leer(texto) ?: return null
        return try { Bip353.resolver(n, ::pedir, System.currentTimeMillis() / 1000) }
        catch (e: Exception) { RegistroFallos.anotar("ResolverNombre.resolver", e); Bip353.Resultado.Error("Could not resolve $n.") }
    }
}
