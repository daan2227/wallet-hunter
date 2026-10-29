package com.hunter.btc

/**
 * BIP353: pagar a un nombre legible, ₿usuario@dominio. Las instrucciones son un TXT DNS en
 * usuario.user._bitcoin-payment.dominio con una URI "bitcoin:…" (dirección, oferta BOLT12 "lno=", factura
 * "lightning=", Silent Payments "sp="…), y TIENEN que estar firmadas con DNSSEC: la validación la hace el
 * teléfono desde la raíz (Dnssec), no el resolvedor.
 *
 * Reglas de BIP353 aplicadas: se ignoran los TXT que no empiezan por "bitcoin:" (sin mirar mayúsculas); si
 * hay más de uno así, no vale ninguno; las cadenas de un TXT se juntan en orden, sin separador, y nunca se
 * juntan TXT distintos. Puro: Bip353Test.
 */
object Bip353 {

    data class Nombre(val usuario: String, val dominio: String) {
        val consulta: String get() = "$usuario.user._bitcoin-payment.$dominio."
        override fun toString() = "₿$usuario@$dominio"
    }

    sealed class Resultado {
        data class Instrucciones(val nombre: Nombre, val uri: String) : Resultado()
        /** No hay instrucciones BIP353 para ese nombre (puede ser una Lightning Address normal). */
        object NoExiste : Resultado()
        data class Error(val motivo: String) : Resultado()
    }

    /** "₿ana@dominio.com" o "ana@dominio.com" (sólo ASCII: BIP353 pide evitar lo demás). null si no lo es. */
    fun leer(s: String): Nombre? {
        val t = s.trim().removePrefix("₿").trim().lowercase()
        val i = t.lastIndexOf('@'); if (i < 1) return null
        val u = t.substring(0, i); val d = t.substring(i + 1)
        val etiqueta = Regex("^[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?$")
        if (!u.split('.').all { etiqueta.matches(it) }) return null
        if (!Regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9-]{2,63}$").matches(d)) return null
        if (u.length + d.length + 30 > 253) return null
        return Nombre(u, d)
    }

    /** De los TXT validados, la URI: exactamente uno que empiece por "bitcoin:". */
    fun interpretar(n: Nombre, registros: List<Dns.Rr>): Resultado {
        val uris = registros.mapNotNull { r ->
            Dns.cadenasTxt(r.rdata)?.fold(ByteArray(0)) { a, c -> a + c }?.let { String(it, Charsets.UTF_8) }
        }.filter { it.startsWith("bitcoin:", ignoreCase = true) }
        return when (uris.size) {
            0 -> Resultado.NoExiste
            1 -> Resultado.Instrucciones(n, uris[0])
            else -> Resultado.Error("$n has more than one payment record; by the standard none of them can be used.")
        }
    }

    /** Resuelve [n] con [pedir] (una respuesta DNS en binario por consulta) validando DNSSEC desde la raíz. */
    fun resolver(n: Nombre, pedir: (String, Int) -> ByteArray?, ahora: Long, anclas: List<Dnssec.Ds> = Dnssec.ANCLAS_RAIZ): Resultado {
        val v = Dnssec(pedir, ahora, anclas).txt(n.consulta)
        return when {
            v.noExiste -> Resultado.NoExiste
            v.registros == null -> Resultado.Error("$n could not be verified with DNSSEC: ${v.error}")
            else -> interpretar(n, v.registros)
        }
    }

    /** Un parámetro de la URI ("lno", "lightning", "sp", "amount"…), o null. */
    fun parametro(uri: String, clave: String): String? =
        uri.substringAfter('?', "").split('&').firstOrNull { it.substringBefore('=').equals(clave, ignoreCase = true) }
            ?.substringAfter('=', "")?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotEmpty() }

    /** La dirección en cadena de la URI ("bitcoin:<dirección>?…"), o null si no trae. */
    fun direccion(uri: String): String? = uri.substring(8).substringBefore('?').trim().takeIf { it.isNotEmpty() }
}
