package com.hunter.btc

import org.json.JSONObject

/**
 * Recoge (hilo de fondo) lo que el modo IA analiza del par: velas del intervalo y
 * diarias; mercado (funding, ratios long/short, taker); social (Fear & Greed,
 * tendencias y votos de CoinGecko, titulares de Reddit). Todo son fuentes públicas
 * sin cuenta, por la red privada (Tor) si está activa. Lo que falla se deja vacío.
 */
object RecolectorIA {
    fun recolectar(a: Mercado.Activo, intervalo: String): AnalisisIA.Datos {
        val velas = try { Mercado.velas(a, intervalo, 300) } catch (e: Throwable) { emptyList() }
        val diarias = if (intervalo == "1d") velas else try { Mercado.velas(a, "1d", 120) } catch (e: Throwable) { emptyList() }
        val cripto = a.tipo == Mercado.Tipo.CRYPTO
        val prem = if (cripto) Mercado.premium(a) else null
        val fut = if (cripto) Mercado.datosFut(a) else null
        val fg = if (cripto) try { Mercado.fearGreed()?.valor } catch (e: Throwable) { null } else null
        // CoinGecko: ¿está en tendencia? votos de la comunidad y su subreddit
        var tendencia: Boolean? = null; var votos: Double? = null; var subreddit: String? = null
        if (cripto) try {
            Mercado.httpGet("https://api.coingecko.com/api/v3/search/trending")?.let { js ->
                val arr = JSONObject(js).optJSONArray("coins")
                tendencia = arr != null && (0 until arr.length()).any { arr.optJSONObject(it)?.optJSONObject("item")?.optString("symbol").equals(a.simbolo, true) }
            }
        } catch (e: Throwable) { }
        if (cripto && a.cgId.isNotEmpty()) try {
            Mercado.httpGet("https://api.coingecko.com/api/v3/coins/${a.cgId}?localization=false&tickers=false&market_data=false&community_data=false&developer_data=false&sparkline=false")?.let { js ->
                val o = JSONObject(js)
                votos = o.optDouble("sentiment_votes_up_percentage").takeIf { !it.isNaN() && it > 0 }
                subreddit = o.optJSONObject("links")?.optString("subreddit_url")?.trimEnd('/')?.substringAfterLast("/r/", "")?.takeIf { it.isNotBlank() && !it.contains('/') }
            }
        } catch (e: Throwable) { }
        // Reddit: titulares recientes del subreddit del par, o búsqueda en r/CryptoCurrency
        val titulares = ArrayList<Pair<String, Int>>()
        try {
            val url = if (subreddit != null) "https://www.reddit.com/r/$subreddit/hot.json?limit=25&raw_json=1"
                else "https://www.reddit.com/r/${if (cripto) "CryptoCurrency" else "stocks"}/search.json?q=" +
                    java.net.URLEncoder.encode(if (cripto) a.simbolo else a.nombre, "UTF-8") + "&restrict_sr=1&sort=new&t=week&limit=25&raw_json=1"
            Mercado.httpGet(url, "android:hunter-dex:1.0 (paper trading)")?.let { js ->
                val hijos = JSONObject(js).optJSONObject("data")?.optJSONArray("children")
                if (hijos != null) for (i in 0 until hijos.length()) {
                    val d = hijos.optJSONObject(i)?.optJSONObject("data") ?: continue
                    if (d.optBoolean("stickied")) continue
                    val t = d.optString("title").trim(); if (t.isNotEmpty()) titulares.add(t to d.optInt("score"))
                }
            }
        } catch (e: Throwable) { }
        val tono = if (titulares.size >= 5) titulares.map { AnalisisIA.tono(it.first) }.average() else null
        return AnalisisIA.Datos(velas, diarias, prem?.tasa, fut?.ratioCuentas, fut?.pctLong, fut?.ratioTaker, fg, tendencia, votos, tono,
            titulares.sortedByDescending { it.second }.take(8), cripto)
    }

    /** Resumen compacto (texto) de datos y resultado para el informe de Claude. */
    fun resumen(a: Mercado.Activo, intervalo: String, d: AnalisisIA.Datos, r: AnalisisIA.Resultado): String {
        val o = JSONObject()
        o.put("pair", a.par); o.put("interval", intervalo); o.put("asset_type", if (d.esCripto) "crypto" else "stock/market")
        val v = d.velas
        if (v.isNotEmpty()) {
            o.put("last_price", v.last().c.toDouble())
            o.put("last_40_candles_ohlcv", org.json.JSONArray().apply { v.takeLast(40).forEach { k -> put(org.json.JSONArray().put(k.t).put(k.o.toDouble()).put(k.h.toDouble()).put(k.l.toDouble()).put(k.c.toDouble()).put(k.vol.toDouble())) } })
            o.put("change_over_loaded_candles_pct", (v.last().c / v.first().o - 1) * 100.0)
        }
        if (d.diarias.size > 31) o.put("daily_change_30d_pct", (d.diarias.last().c / d.diarias[d.diarias.size - 31].c - 1) * 100.0)
        o.put("atr_pct", r.atrPct); o.put("supports", org.json.JSONArray(r.soportes)); o.put("resistances", org.json.JSONArray(r.resistencias))
        o.put("factors", org.json.JSONArray().apply { r.factores.forEach { put(JSONObject().put("group", it.grupo.nombre).put("name", it.nombre).put("score", it.puntos).put("note", it.texto)) } })
        o.put("candlestick_patterns", org.json.JSONArray(r.patrones.map { it.nombre }))
        o.put("local_engine", JSONObject().put("verdict", r.veredicto.nombre).put("score", r.puntuacion).put("confidence_pct", r.confianza))
        r.plan?.let { p -> o.put("local_plan", JSONObject().put("side", if (p.largo) "long" else "short").put("entry", p.entrada).put("stop", p.stop)
            .put("target1", p.objetivo1).put("target2", p.objetivo2).put("max_leverage", p.apalancamientoMax)) }
        if (d.titularesReddit.isNotEmpty()) o.put("reddit_headlines", org.json.JSONArray(d.titularesReddit.map { "${it.first} (score ${it.second})" }))
        return o.toString(1)
    }
}
