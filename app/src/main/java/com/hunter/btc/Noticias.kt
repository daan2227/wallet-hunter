package com.hunter.btc

import org.json.JSONArray

/**
 * Noticias cripto (RSS públicos de CoinDesk, Cointelegraph y Decrypt) y calendario
 * económico de la semana (Forex Factory, eventos de USD de impacto alto y medio).
 * Sin cuentas ni claves; va por Tor si la red privada está activa. El análisis
 * (RSS y JSON) es puro y se prueba en la JVM.
 */
object Noticias {
    class Noticia(val titulo: String, val enlace: String, val fuente: String, val t: Long)
    class Evento(val titulo: String, val pais: String, val t: Long, val impacto: String, val prevision: String, val anterior: String)

    val FUENTES = listOf(
        "CoinDesk" to "https://www.coindesk.com/arc/outboundfeeds/rss/",
        "Cointelegraph" to "https://cointelegraph.com/rss",
        "Decrypt" to "https://decrypt.co/feed",
    )
    const val CALENDARIO = "https://nfs.faireconomy.media/ff_calendar_thisweek.json"

    private val ENTIDADES = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&#8217;" to "’", "&#8216;" to "‘", "&#8220;" to "“", "&#8221;" to "”", "&#8211;" to "–", "&#8212;" to "—", "&nbsp;" to " ")

    private fun limpiar(x: String): String {
        var s = x.trim()
        if (s.startsWith("<![CDATA[")) s = s.removePrefix("<![CDATA[").removeSuffix("]]>")
        s = s.replace(Regex("<[^>]+>"), "")
        for ((k, v) in ENTIDADES) s = s.replace(k, v)
        s = s.replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun etiqueta(item: String, nombre: String): String? =
        Regex("<$nombre(?:\\s[^>]*)?>([\\s\\S]*?)</$nombre>", RegexOption.IGNORE_CASE).find(item)?.groupValues?.get(1)

    /** Fecha RFC 822 de RSS ("Mon, 23 Nov 2026 07:10:00 +0000" o con "GMT"). */
    fun fechaRss(s: String): Long = try {
        val f = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US)
        f.parse(s.trim().replace(Regex("\\s(GMT|UTC|UT)$"), " +0000"))!!.time / 1000
    } catch (e: Exception) { 0L }

    fun parsearRss(xml: String, fuente: String): List<Noticia> =
        Regex("<item[\\s>][\\s\\S]*?</item>", RegexOption.IGNORE_CASE).findAll(xml).mapNotNull { m ->
            val it = m.value
            val titulo = etiqueta(it, "title")?.let { limpiar(it) }?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val enlace = etiqueta(it, "link")?.let { limpiar(it) } ?: ""
            if (!enlace.startsWith("https://")) return@mapNotNull null              // solo enlaces seguros
            Noticia(titulo, enlace, fuente, etiqueta(it, "pubDate")?.let { fechaRss(limpiar(it)) } ?: 0L)
        }.toList()

    /** Fecha ISO con zona ("2026-11-23T08:30:00-05:00"). */
    fun fechaIso(s: String): Long = try {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).parse(s)!!.time / 1000
    } catch (e: Exception) { 0L }

    fun parsearCalendario(json: String, paises: Set<String> = setOf("USD"), impactos: Set<String> = setOf("High", "Medium")): List<Evento> = try {
        val a = JSONArray(json); val out = ArrayList<Evento>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val pais = o.optString("country"); val imp = o.optString("impact")
            if (pais !in paises || imp !in impactos) continue
            out.add(Evento(o.optString("title"), pais, fechaIso(o.optString("date")), imp, o.optString("forecast"), o.optString("previous")))
        }
        out.sortedBy { it.t }
    } catch (e: Exception) { emptyList() }

    /** Titulares que mencionan el activo (símbolo como palabra o su nombre). */
    fun filtrar(lista: List<Noticia>, simbolo: String, nombre: String): List<Noticia> {
        val s = Regex("\\b${Regex.escape(simbolo)}\\b", RegexOption.IGNORE_CASE)
        val n = nombre.trim().takeIf { it.length >= 3 && !it.equals(simbolo, true) }
        return lista.filter { s.containsMatchIn(it.titulo) || (n != null && it.titulo.contains(n, ignoreCase = true)) }
    }

    // ── red (hilo de fondo) ──
    fun cargarNoticias(): List<Noticia> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            val fut = FUENTES.map { (f, url) -> pool.submit<List<Noticia>> {
                Mercado.httpGet(url, "Mozilla/5.0 (Android) hunter", "application/rss+xml, application/xml, text/xml, */*")?.let { parsearRss(it, f) } ?: emptyList() } }
            return fut.flatMap { try { it.get(25, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { emptyList() } }
                .distinctBy { it.titulo.lowercase() }.sortedByDescending { it.t }
        } finally { pool.shutdown() }
    }

    fun cargarCalendario(): List<Evento> = Mercado.httpGet(CALENDARIO, "Mozilla/5.0 (Android) hunter")?.let { parsearCalendario(it) } ?: emptyList()
}
