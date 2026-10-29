package com.hunter.btc

import org.json.JSONArray
import org.json.JSONObject

/**
 * LNURL: pagar a un QR estático o a una Lightning Address (usuario@dominio), y cobrar de un QR de retirada.
 *
 *   LUD-01  "LNURL1…" (bech32) o "lightning:LNURL1…": es una URL codificada.
 *   LUD-17  lnurlp:// lnurlw:// keyauth:// lnurlc:// en vez de https:// (http:// si es .onion).
 *   LUD-16  usuario@dominio → https://dominio/.well-known/lnurlp/usuario
 *   LUD-06  payRequest: límites en msat y metadata; el segundo paso devuelve la factura ("pr"), cuyo importe
 *           tiene que ser EXACTAMENTE el pedido (si no, no se paga).
 *   LUD-12  comentario opcional; LUD-09 successAction (mensaje o enlace del mismo dominio).
 *   LUD-03  withdrawRequest: la app hace una factura y se la manda al servicio, que la paga.
 *
 * Sólo https (o http a .onion): una URL http normal se rechaza. Puro: LnurlTest.
 */
object Lnurl {

    sealed class Destino {
        /** Una URL LNURL ya decodificada; [pista] el tipo si lo dice el esquema (LUD-17). */
        data class Url(val url: String, val pista: String? = null) : Destino()
        /** Una Lightning Address. */
        data class Direccion(val usuario: String, val dominio: String) : Destino() {
            override fun toString() = "$usuario@$dominio"
        }
    }

    /** Qué es [entrada]: LNURL bech32, esquema LUD-17, URL con "lightning=LNURL…" o usuario@dominio. null si nada de eso. */
    fun leer(entrada: String): Destino? {
        var t = entrada.trim()
        if (t.startsWith("lightning:", ignoreCase = true)) t = t.substring(10)
        if (t.startsWith("lnurl1", ignoreCase = true)) return bech32Url(t)?.let { Destino.Url(it) }
        val esquemas = mapOf("lnurlp://" to "payRequest", "lnurlw://" to "withdrawRequest", "keyauth://" to "login", "lnurlc://" to "channelRequest")
        for ((e, pista) in esquemas) if (t.startsWith(e, ignoreCase = true)) {
            val resto = t.substring(e.length)
            val host = resto.substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
            val url = (if (host.endsWith(".onion")) "http://" else "https://") + resto
            return if (urlValida(url)) Destino.Url(url, pista) else null
        }
        // LUD-01: una URL normal puede llevar el LNURL en "lightning=".
        Regex("[?&]lightning=(lnurl1[0-9a-z]+)", RegexOption.IGNORE_CASE).find(t)?.let { m -> return bech32Url(m.groupValues[1])?.let { Destino.Url(it) } }
        return direccion(t)
    }

    /** usuario@dominio (LUD-16: usuario a-z 0-9 - _ . +), o null. */
    fun direccion(t: String): Destino.Direccion? {
        val s = t.trim().removePrefix("₿")
        val i = s.lastIndexOf('@'); if (i < 1) return null
        val u = s.substring(0, i).lowercase(); val d = s.substring(i + 1).lowercase()
        if (!Regex("^[a-z0-9_.+-]{1,64}$").matches(u)) return null
        if (!Regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9-]{2,63}$").matches(d)) return null
        return Destino.Direccion(u, d)
    }

    fun urlDe(d: Destino.Direccion): String =
        (if (d.dominio.endsWith(".onion")) "http://" else "https://") + "${d.dominio}/.well-known/lnurlp/${d.usuario}"

    /** https, o http sólo a .onion; con host y sin usuario:clave. */
    fun urlValida(u: String): Boolean = try {
        val x = java.net.URI(u)
        val h = x.host?.lowercase() ?: ""
        h.isNotEmpty() && x.rawUserInfo == null &&
            (x.scheme.equals("https", true) || (x.scheme.equals("http", true) && h.endsWith(".onion")))
    } catch (e: Exception) { false }

    fun dominio(u: String): String = try { java.net.URI(u).host?.lowercase() ?: "" } catch (e: Exception) { "" }

    // ── Respuestas del servicio ────────────────────────────────────────────────────────────────────

    data class Pago(val callback: String, val minMsat: Long, val maxMsat: Long, val metadata: String,
                    val descripcion: String, val comentarioMax: Int, val identificador: String?)
    data class Retiro(val callback: String, val k1: String, val minMsat: Long, val maxMsat: Long, val descripcion: String)

    sealed class Respuesta {
        data class EsPago(val p: Pago) : Respuesta()
        data class EsRetiro(val r: Retiro) : Respuesta()
        data class Error(val motivo: String) : Respuesta()
    }

    /** La primera respuesta (la de la URL LNURL). */
    fun leerRespuesta(json: String): Respuesta = try {
        val o = JSONObject(json)
        if (o.optString("status").equals("ERROR", true)) Respuesta.Error(o.optString("reason", "The service returned an error."))
        else when (o.optString("tag")) {
            "payRequest" -> leerPago(o)
            "withdrawRequest" -> leerRetiro(o)
            "login" -> Respuesta.Error("LNURL-auth (login) is not supported yet.")
            "channelRequest" -> Respuesta.Error("LNURL-channel is not supported.")
            else -> Respuesta.Error("Unknown LNURL type.")
        }
    } catch (e: Exception) { Respuesta.Error("The service did not answer with valid LNURL data.") }

    private fun leerPago(o: JSONObject): Respuesta {
        val cb = o.optString("callback"); val min = o.optLong("minSendable", -1); val max = o.optLong("maxSendable", -1)
        if (!urlValida(cb)) return Respuesta.Error("The service's callback is not a secure URL.")
        if (min < 1 || max < min) return Respuesta.Error("The service gave invalid amount limits.")
        val meta = o.optString("metadata")
        var texto: String? = null; var ident: String? = null
        try {
            val a = JSONArray(meta)
            for (i in 0 until a.length()) {
                val e = a.getJSONArray(i)
                when (e.optString(0)) {
                    "text/plain" -> texto = e.optString(1)
                    "text/identifier", "text/email" -> ident = e.optString(1)
                }
            }
        } catch (e: Exception) { return Respuesta.Error("The service's metadata is not valid.") }
        if (texto == null) return Respuesta.Error("The service's metadata has no description.")
        return Respuesta.EsPago(Pago(cb, min, max, meta, texto!!.take(500), o.optInt("commentAllowed", 0).coerceIn(0, 2000), ident))
    }

    private fun leerRetiro(o: JSONObject): Respuesta {
        val cb = o.optString("callback"); val k1 = o.optString("k1")
        val min = o.optLong("minWithdrawable", 0); val max = o.optLong("maxWithdrawable", -1)
        if (!urlValida(cb)) return Respuesta.Error("The service's callback is not a secure URL.")
        if (k1.isEmpty()) return Respuesta.Error("The service gave no withdrawal key (k1).")
        if (min < 0 || max < 1 || max < min) return Respuesta.Error("The service gave invalid amount limits.")
        return Respuesta.EsRetiro(Retiro(cb, k1, min, max, o.optString("defaultDescription").take(120)))
    }

    /** La URL del segundo paso del pago: callback con amount (msat) y, si se permite, comment. */
    fun urlFactura(p: Pago, msat: Long, comentario: String?): String {
        require(msat in p.minMsat..p.maxMsat) { "importe fuera de límites" }
        val sb = StringBuilder(p.callback).append(if ('?' in p.callback) '&' else '?').append("amount=").append(msat)
        if (!comentario.isNullOrBlank() && p.comentarioMax > 0)
            sb.append("&comment=").append(java.net.URLEncoder.encode(comentario.take(p.comentarioMax), "UTF-8"))
        return sb.toString()
    }

    sealed class Accion {
        data class Mensaje(val texto: String) : Accion()
        data class Enlace(val descripcion: String, val url: String) : Accion()
    }

    /** El segundo paso: la factura ([pr]) o el [error]; y la [accion] tras pagar, si es de un tipo conocido y seguro. */
    data class Factura(val pr: String?, val error: String?, val accion: Accion? = null)

    fun leerFactura(json: String, p: Pago): Factura = try {
        val o = JSONObject(json)
        if (o.optString("status").equals("ERROR", true)) Factura(null, o.optString("reason", "The service returned an error."))
        else {
            val pr = o.optString("pr")
            if (pr.isEmpty()) Factura(null, "The service did not return an invoice.")
            else Factura(pr, null, o.optJSONObject("successAction")?.let { a ->
                when (a.optString("tag")) {
                    "message" -> Accion.Mensaje(a.optString("message").take(144))
                    // LUD-09: la URL tiene que ser del mismo dominio que el callback.
                    "url" -> a.optString("url").takeIf { urlValida(it) && dominio(it) == dominio(p.callback) }
                        ?.let { Accion.Enlace(a.optString("description").take(144), it) }
                    else -> null
                }
            })
        }
    } catch (e: Exception) { Factura(null, "The service did not answer with a valid invoice.") }

    /** LUD-06 paso 7: la factura tiene que pedir EXACTAMENTE lo que se pidió. null si vale; si no, el motivo. */
    fun comprobarImporte(pedidoMsat: Long, facturaMsat: Long?): String? = when {
        facturaMsat == null -> "The invoice has no amount; it should ask for exactly what you chose."
        facturaMsat != pedidoMsat -> "The invoice asks for ${facturaMsat / 1000} sat, not the ${pedidoMsat / 1000} sat you chose. Not paying it."
        else -> null
    }

    /** La URL para mandar la factura de una retirada. */
    fun urlRetiro(r: Retiro, factura: String): String =
        StringBuilder(r.callback).append(if ('?' in r.callback) '&' else '?')
            .append("k1=").append(java.net.URLEncoder.encode(r.k1, "UTF-8"))
            .append("&pr=").append(java.net.URLEncoder.encode(factura, "UTF-8")).toString()

    /** La respuesta a la retirada: null si OK, o el motivo. */
    fun leerOk(json: String): String? = try {
        val o = JSONObject(json)
        if (o.optString("status").equals("OK", true)) null else o.optString("reason", "The service did not accept it.")
    } catch (e: Exception) { "The service did not answer." }

    // ── bech32 (sin el límite de 90 caracteres de las direcciones) ─────────────────────────────────

    private const val CS = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    private fun bech32Url(s0: String): String? {
        val s = s0.trim()
        if (s != s.lowercase() && s != s.uppercase()) return null
        val t = s.lowercase(); val sep = t.lastIndexOf('1')
        if (sep < 1 || t.length - sep < 7 || t.substring(0, sep) != "lnurl") return null
        val datos = t.substring(sep + 1).map { CS.indexOf(it) }
        if (datos.any { it < 0 }) return null
        val hrp = t.substring(0, sep)
        val exp = hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 }
        if (polymod(exp + datos) != 1) return null
        var acc = 0; var bits = 0; val out = java.io.ByteArrayOutputStream()
        for (v in datos.dropLast(6)) { acc = (acc shl 5) or v; bits += 5; while (bits >= 8) { bits -= 8; out.write((acc shr bits) and 0xff) } }
        if (bits >= 5 || (acc and ((1 shl bits) - 1)) != 0) return null
        val url = String(out.toByteArray(), Charsets.UTF_8)
        return url.takeIf { urlValida(it) }
    }

    private fun polymod(v: List<Int>): Int {
        val g = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (x in v) { val b = chk ushr 25; chk = ((chk and 0x1ffffff) shl 5) xor x; for (i in 0 until 5) if ((b ushr i) and 1 == 1) chk = chk xor g[i] }
        return chk
    }
}
