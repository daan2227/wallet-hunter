package com.hunter.btc

/**
 * Datos de mercado para el Home y el tool de precio. Fetch nativo por el proxy de
 * la app (respeta Tor si está activo); nada de WebView. Llamar desde un hilo de fondo.
 *
 * Un [Activo] es cripto o bolsa. Velas:
 *   - BTC: Binance, respaldo Bitstamp.
 *   - Otra cripto: Binance (SÍMBOLO+USDT); si no cotiza allí, OHLC de CoinGecko
 *     (sin volumen y con resolución fija por rango).
 *   - Bolsa (acciones, índices, materias primas, divisas): Yahoo Finance, con
 *     intervalos equivalentes (no hay 1 s; 4 h se agrega de 1 h; 12 h = diario).
 * Listas: top cripto paginado de CoinGecko (sin stablecoins) y una lista fija de
 * valores tradicionales cotizados en Yahoo.
 */
object Mercado {

    enum class Tipo { CRYPTO, BOLSA }

    /** [yahoo]: símbolo en Yahoo (bolsa). [cgId]: id de CoinGecko (cripto). */
    data class Activo(val tipo: Tipo, val simbolo: String, val nombre: String,
                      val cgId: String = "", val yahoo: String = "") {
        val clave get() = "${tipo.name}:$simbolo"
        val par get() = if (tipo == Tipo.CRYPTO) "$simbolo / USD" else "$simbolo · $nombre"
        /** Índices (^...) y divisas (=X) no se expresan en dólares. */
        val sinDolar get() = yahoo.startsWith("^") || yahoo.endsWith("=X")
        fun aJson(): String = org.json.JSONObject().put("t", tipo.name).put("s", simbolo).put("n", nombre)
            .put("id", cgId).put("y", yahoo).toString()
        companion object {
            fun deJson(s: String?): Activo? = try {
                if (s.isNullOrEmpty()) null else org.json.JSONObject(s).let {
                    Activo(Tipo.valueOf(it.getString("t")), it.getString("s"), it.optString("n"), it.optString("id"), it.optString("y")) }
            } catch (e: Exception) { null }
        }
    }
    val BTC = Activo(Tipo.CRYPTO, "BTC", "Bitcoin", "bitcoin")

    data class Moneda(val activo: Activo, val precio: Double, val cambio24h: Double, val spark: FloatArray) {
        val simbolo get() = activo.simbolo
        val nombre get() = activo.nombre
    }

    /** De dónde salieron las últimas velas de cada activo ("binance", "bitstamp", "coingecko", "yahoo"). */
    private val fuentes = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun fuente(a: Activo): String = fuentes[a.clave] ?: ""
    /** Las fuentes rápidas aguantan refresco de 1 s; las demás se limitan para no saturarlas. */
    fun refrescoMinimoMs(a: Activo): Long = if (fuente(a) == "binance" || fuente(a) == "bitstamp") 1_000L else 15_000L

    private val STABLES = setOf("usdt", "usdc", "dai", "busd", "tusd", "usdd", "fdusd",
        "pyusd", "usde", "frax", "gusd", "usdp", "lusd", "usdl", "susds", "usdy", "usds", "usd1", "rlusd", "bsc-usd")

    private fun httpGet(url: String, ua: String = "hunter"): String? = try {
        val conn = RedPrivada.abrir(url)
        conn.connectTimeout = 5000; conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", ua)
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode != 200) null
            else {
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
                conn.inputStream.use { while (true) { val n = it.read(buf); if (n < 0) break; if (out.size() + n > 8 * 1024 * 1024) break; out.write(buf, 0, n) } }
                out.toString("UTF-8")
            }
        } finally { conn.disconnect() }
    } catch (e: Throwable) { null }
    private const val UA_NAV = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private fun f(s: String?) = s?.toFloatOrNull() ?: 0f

    private fun binance(simbolo: String, intervalo: String, lim: Int): List<GraficoPrecio.Vela> = try {
        val js = httpGet("https://api.binance.com/api/v3/klines?symbol=$simbolo&interval=$intervalo&limit=$lim")
        if (js == null || !js.trimStart().startsWith("[")) emptyList() else {
            val arr = org.json.JSONArray(js)
            List(arr.length()) { i -> val k = arr.getJSONArray(i)
                GraficoPrecio.Vela(k.getLong(0) / 1000, f(k.getString(1)), f(k.getString(2)), f(k.getString(3)), f(k.getString(4)), f(k.optString(5, "0"))) }
        }
    } catch (e: Throwable) { emptyList() }

    private fun bitstampBtc(intervalo: String, lim: Int): List<GraficoPrecio.Vela> = try {
        val step = when (intervalo) {
            "1s", "1m" -> 60; "5m" -> 300; "15m" -> 900; "30m" -> 1800; "1h" -> 3600; "2h" -> 7200; "4h" -> 14400
            "6h" -> 21600; "12h" -> 43200; "3d", "1w", "1M" -> 259200; else -> 86400
        }
        val js = httpGet("https://www.bitstamp.net/api/v2/ohlc/btcusd/?step=$step&limit=${lim.coerceAtMost(1000)}")
        if (js == null) emptyList() else {
            val ohlc = org.json.JSONObject(js).getJSONObject("data").getJSONArray("ohlc")
            List(ohlc.length()) { i -> val o = ohlc.getJSONObject(i)
                GraficoPrecio.Vela(o.getString("timestamp").toLong(), f(o.getString("open")), f(o.getString("high")),
                    f(o.getString("low")), f(o.getString("close")), f(o.optString("volume", "0"))) }
        }
    } catch (e: Throwable) { emptyList() }

    /** OHLC de CoinGecko (sin volumen). Resolución según días: ≤2 → 30 min, ≤30 → 4 h, más → 4 días. */
    private fun coingeckoOhlc(id: String, intervalo: String): List<GraficoPrecio.Vela> = try {
        val dias = when (intervalo) { "1s", "1m", "5m", "15m", "30m", "1h" -> 1; "2h", "4h", "6h", "12h" -> 30; else -> 365 }
        val js = httpGet("https://api.coingecko.com/api/v3/coins/$id/ohlc?vs_currency=usd&days=$dias")
        if (js == null || !js.trimStart().startsWith("[")) emptyList() else {
            val arr = org.json.JSONArray(js)
            List(arr.length()) { i -> val k = arr.getJSONArray(i)
                GraficoPrecio.Vela(k.getLong(0) / 1000, k.getDouble(1).toFloat(), k.getDouble(2).toFloat(), k.getDouble(3).toFloat(), k.getDouble(4).toFloat()) }
        }
    } catch (e: Throwable) { emptyList() }

    /** Agrupa [n] velas consecutivas en una (OHLC + suma de volumen). */
    fun agrupar(v: List<GraficoPrecio.Vela>, n: Int): List<GraficoPrecio.Vela> =
        if (n <= 1) v else v.chunked(n).map { g ->
            GraficoPrecio.Vela(g[0].t, g[0].o, g.maxOf { it.h }, g.minOf { it.l }, g.last().c, g.fold(0f) { s, k -> s + k.vol })
        }

    /** Equivalencia del intervalo pedido en Yahoo: (intervalo, rango, agrupar, aviso). */
    private fun yahooMapa(iv: String): Array<Any> = when (iv) {
        "1s", "1m" -> arrayOf<Any>("1m", "5d", 1, if (iv == "1s") "1s n/a → 1m" else "")
        "5m", "15m", "30m" -> arrayOf<Any>(iv, "60d", 1, "")
        "1h" -> arrayOf<Any>("60m", "60d", 1, ""); "2h" -> arrayOf<Any>("60m", "730d", 2, "2h built from 1h")
        "4h" -> arrayOf<Any>("60m", "730d", 4, "4h built from 1h"); "6h" -> arrayOf<Any>("60m", "730d", 6, "6h built from 1h")
        "12h" -> arrayOf<Any>("1d", "2y", 1, "12h → daily"); "1d" -> arrayOf<Any>("1d", "2y", 1, "")
        "3d" -> arrayOf<Any>("1d", "10y", 3, ""); "1w" -> arrayOf<Any>("1wk", "10y", 1, ""); "1M" -> arrayOf<Any>("1mo", "max", 1, "")
        else -> arrayOf<Any>("1d", "2y", 1, "")
    }
    fun avisoIntervalo(a: Activo, iv: String): String =
        if (a.tipo == Tipo.BOLSA) yahooMapa(iv)[3] as String
        else if (fuente(a) == "coingecko") "CoinGecko candles, no volume" else ""

    private fun yahooVelas(sym: String, iv: String): List<GraficoPrecio.Vela> = try {
        val m = yahooMapa(iv)
        val js = httpGet("https://query1.finance.yahoo.com/v8/finance/chart/" + java.net.URLEncoder.encode(sym, "UTF-8") +
                "?interval=${m[0]}&range=${m[1]}&includePrePost=false", UA_NAV)
        if (js == null) emptyList() else {
            val r = org.json.JSONObject(js).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val ts = r.getJSONArray("timestamp"); val q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
            val o = q.getJSONArray("open"); val h = q.getJSONArray("high"); val l = q.getJSONArray("low"); val c = q.getJSONArray("close")
            val vo = q.optJSONArray("volume")
            val out = ArrayList<GraficoPrecio.Vela>()
            for (i in 0 until ts.length()) {
                if (c.isNull(i) || o.isNull(i) || h.isNull(i) || l.isNull(i)) continue
                out.add(GraficoPrecio.Vela(ts.getLong(i), o.getDouble(i).toFloat(), h.getDouble(i).toFloat(), l.getDouble(i).toFloat(),
                    c.getDouble(i).toFloat(), if (vo == null || vo.isNull(i)) 0f else vo.getDouble(i).toFloat()))
            }
            agrupar(out, m[2] as Int)
        }
    } catch (e: Throwable) { emptyList() }

    /** Velas del activo, como mucho las últimas [limite]. Vacío si ninguna fuente responde. */
    fun velas(a: Activo, intervalo: String, limite: Int = 500): List<GraficoPrecio.Vela> {
        val lim = limite.coerceIn(10, 1000)
        fun ok(v: List<GraficoPrecio.Vela>, src: String): List<GraficoPrecio.Vela>? =
            if (v.isEmpty()) null else { fuentes[a.clave] = src; v.takeLast(limite.coerceAtLeast(1)) }
        return when (a.tipo) {
            Tipo.CRYPTO -> {
                ok(binance(a.simbolo.uppercase() + "USDT", intervalo, lim), "binance")
                    ?: (if (a.simbolo.equals("BTC", true)) ok(bitstampBtc(intervalo, lim), "bitstamp") else null)
                    ?: (if (a.cgId.isNotEmpty()) ok(coingeckoOhlc(a.cgId, intervalo), "coingecko") else null)
                    ?: emptyList()
            }
            Tipo.BOLSA -> ok(yahooVelas(a.yahoo.ifEmpty { a.simbolo }, intervalo), "yahoo") ?: emptyList()
        }
    }

    // ── Libro de órdenes y operaciones en vivo (solo cripto; la bolsa no los publica gratis) ──
    /** Niveles (precio, cantidad): bids de mayor a menor, asks de menor a mayor. */
    class Libro(val bids: List<Pair<Double, Double>>, val asks: List<Pair<Double, Double>>, val fuente: String)
    /** Operación ejecutada: [t] en ms, [compra] = el que tomó liquidez compró. */
    class Trade(val t: Long, val precio: Double, val qty: Double, val compra: Boolean)

    private fun niveles(a: org.json.JSONArray?, max: Int): List<Pair<Double, Double>> {
        if (a == null) return emptyList()
        val out = ArrayList<Pair<Double, Double>>()
        for (i in 0 until minOf(a.length(), max)) { val l = a.getJSONArray(i)
            val p = l.getString(0).toDoubleOrNull() ?: continue; val q = l.getString(1).toDoubleOrNull() ?: continue
            if (q > 0) out.add(p to q) }
        return out
    }

    fun libro(a: Activo, limite: Int = 100): Libro? {
        if (a.tipo != Tipo.CRYPTO) return null
        try {
            val js = httpGet("https://api.binance.com/api/v3/depth?symbol=${a.simbolo.uppercase()}USDT&limit=$limite")
            if (js != null && js.trimStart().startsWith("{")) { val o = org.json.JSONObject(js)
                if (o.has("bids")) return Libro(niveles(o.optJSONArray("bids"), limite), niveles(o.optJSONArray("asks"), limite), "Binance") }
        } catch (e: Throwable) { }
        if (a.simbolo.equals("BTC", true)) try {
            val js = httpGet("https://www.bitstamp.net/api/v2/order_book/btcusd/")
            if (js != null) { val o = org.json.JSONObject(js)
                return Libro(niveles(o.optJSONArray("bids"), limite), niveles(o.optJSONArray("asks"), limite), "Bitstamp") }
        } catch (e: Throwable) { }
        return null
    }

    fun trades(a: Activo, n: Int = 40): List<Trade> {
        if (a.tipo != Tipo.CRYPTO) return emptyList()
        try {
            val js = httpGet("https://api.binance.com/api/v3/trades?symbol=${a.simbolo.uppercase()}USDT&limit=$n")
            if (js != null && js.trimStart().startsWith("[")) { val arr = org.json.JSONArray(js)
                return List(arr.length()) { i -> val o = arr.getJSONObject(i)
                    Trade(o.getLong("time"), o.getString("price").toDouble(), o.getString("qty").toDouble(), !o.getBoolean("isBuyerMaker")) }.reversed() }
        } catch (e: Throwable) { }
        if (a.simbolo.equals("BTC", true)) try {
            val js = httpGet("https://www.bitstamp.net/api/v2/transactions/btcusd/?time=minute")
            if (js != null && js.trimStart().startsWith("[")) { val arr = org.json.JSONArray(js)
                return List(minOf(arr.length(), n)) { i -> val o = arr.getJSONObject(i)
                    Trade(o.getString("date").toLong() * 1000, o.getString("price").toDouble(), o.getString("amount").toDouble(), o.getString("type") == "0") } }
        } catch (e: Throwable) { }
        return emptyList()
    }

    /** Compatibilidad con el tool de precio: velas de BTC. */
    fun velasBtc(intervalo: String, limite: Int = 180): List<GraficoPrecio.Vela> = velas(BTC, intervalo, limite)

    data class Global(val mcap: Double, val vol24h: Double, val btcDom: Double, val cambio24h: Double)
    /** Mercado general: capitalización total, volumen 24h, dominancia BTC y cambio 24h. */
    fun global(): Global? {
        val js = httpGet("https://api.coingecko.com/api/v3/global") ?: return null
        return try {
            val d = org.json.JSONObject(js).getJSONObject("data")
            Global(d.getJSONObject("total_market_cap").optDouble("usd", 0.0),
                   d.getJSONObject("total_volume").optDouble("usd", 0.0),
                   d.getJSONObject("market_cap_percentage").optDouble("btc", 0.0),
                   d.optDouble("market_cap_change_percentage_24h_usd", 0.0))
        } catch (e: Throwable) { null }
    }

    data class Miedo(val valor: Int, val clasif: String)
    /** Índice Fear & Greed (0..100) de alternative.me. */
    fun fearGreed(): Miedo? {
        val js = httpGet("https://api.alternative.me/fng/?limit=1") ?: return null
        return try {
            val o = org.json.JSONObject(js).getJSONArray("data").getJSONObject(0)
            Miedo(o.optString("value", "0").toIntOrNull() ?: 0, o.optString("value_classification", ""))
        } catch (e: Throwable) { null }
    }

    /** Una página del top cripto por capitalización, SIN stablecoins. */
    fun topCripto(pagina: Int, porPagina: Int = 100): List<Moneda> {
        val js = httpGet("https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc" +
                "&per_page=$porPagina&page=$pagina&sparkline=true&price_change_percentage=24h") ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(js); val out = ArrayList<Moneda>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sim = o.optString("symbol", "").lowercase()
                // fuera stablecoins y tokens que no son pares negociables (p. ej. "FIGR_HELOC", préstamos tokenizados)
                if (sim.isEmpty() || sim in STABLES || sim.contains('_')) continue
                val sparkArr = o.optJSONObject("sparkline_in_7d")?.optJSONArray("price")
                val spark = if (sparkArr != null) FloatArray(sparkArr.length()) { sparkArr.optDouble(it, 0.0).toFloat() } else FloatArray(0)
                out.add(Moneda(Activo(Tipo.CRYPTO, sim.uppercase(), o.optString("name", sim.uppercase()), o.optString("id", "")),
                    o.optDouble("current_price", 0.0), o.optDouble("price_change_percentage_24h", 0.0), spark))
            }
            out
        } catch (e: Throwable) { emptyList() }
    }
    /** Búsqueda de criptos por nombre o símbolo (CoinGecko), más allá del top. */
    fun buscarCripto(q: String): List<Activo> {
        val js = httpGet("https://api.coingecko.com/api/v3/search?query=" + java.net.URLEncoder.encode(q, "UTF-8")) ?: return emptyList()
        return try {
            val arr = org.json.JSONObject(js).getJSONArray("coins")
            (0 until minOf(arr.length(), 30)).mapNotNull { i -> val o = arr.getJSONObject(i)
                val sim = o.optString("symbol").lowercase()
                if (sim.isEmpty() || sim in STABLES || sim.contains('_')) null
                else Activo(Tipo.CRYPTO, sim.uppercase(), o.optString("name", sim.uppercase()), o.optString("id")) }
        } catch (e: Throwable) { emptyList() }
    }

    /** Compatibilidad: primeras [n] del top cripto. */
    fun topMonedas(n: Int = 10): List<Moneda> = topCripto(1, 50).take(n)

    /** Mercado tradicional: índices, materias primas, divisas, grandes valores y valores ligados a BTC. */
    val BOLSA: List<Activo> = listOf(
        "^GSPC" to "S&P 500", "^IXIC" to "Nasdaq", "^DJI" to "Dow Jones", "^RUT" to "Russell 2000", "^VIX" to "VIX",
        "GC=F" to "Gold", "SI=F" to "Silver", "CL=F" to "Crude oil", "EURUSD=X" to "EUR / USD",
        "AAPL" to "Apple", "MSFT" to "Microsoft", "NVDA" to "Nvidia", "GOOGL" to "Alphabet", "AMZN" to "Amazon",
        "META" to "Meta", "TSLA" to "Tesla", "BRK-B" to "Berkshire Hathaway", "AVGO" to "Broadcom", "JPM" to "JPMorgan",
        "LLY" to "Eli Lilly", "V" to "Visa", "WMT" to "Walmart", "XOM" to "Exxon Mobil", "MA" to "Mastercard",
        "UNH" to "UnitedHealth", "ORCL" to "Oracle", "COST" to "Costco", "HD" to "Home Depot", "PG" to "Procter & Gamble",
        "JNJ" to "Johnson & Johnson", "NFLX" to "Netflix", "BAC" to "Bank of America", "KO" to "Coca-Cola", "AMD" to "AMD",
        "CRM" to "Salesforce", "PEP" to "PepsiCo", "MCD" to "McDonald's", "ADBE" to "Adobe", "CSCO" to "Cisco",
        "INTC" to "Intel", "DIS" to "Disney", "NKE" to "Nike", "PYPL" to "PayPal",
        "MSTR" to "Strategy", "COIN" to "Coinbase", "IBIT" to "iShares Bitcoin ETF", "MARA" to "MARA Holdings"
    ).map { (y, n) -> Activo(Tipo.BOLSA, y.removePrefix("^").removeSuffix("=X").removeSuffix("=F"), n, yahoo = y) }

    /** Precio, cambio diario y sparkline (1 mes) de un valor de bolsa. null si Yahoo no responde. */
    fun cotizacion(a: Activo): Moneda? = try {
        val js = httpGet("https://query1.finance.yahoo.com/v8/finance/chart/" + java.net.URLEncoder.encode(a.yahoo, "UTF-8") +
                "?interval=1d&range=1mo", UA_NAV)
        if (js == null) null else {
            val r = org.json.JSONObject(js).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val c = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close")
            val cierres = (0 until c.length()).filter { !c.isNull(it) }.map { c.getDouble(it) }
            val meta = r.getJSONObject("meta")
            val precio = meta.optDouble("regularMarketPrice", cierres.lastOrNull() ?: 0.0)
            val prev = if (cierres.size >= 2) cierres[cierres.size - 2] else meta.optDouble("chartPreviousClose", precio)
            val cambio = if (prev > 0) (precio - prev) / prev * 100 else 0.0
            Moneda(a, precio, cambio, FloatArray(cierres.size) { cierres[it].toFloat() })
        }
    } catch (e: Throwable) { null }

    // ── Ficha completa de un activo ──
    /** Secciones de filas (etiqueta, valor), descripción, su fuente y enlaces (texto, url). */
    data class Info(val titulo: String, val secciones: List<Pair<String, List<Pair<String, String>>>>,
                    val descripcion: String, val fuenteDesc: String, val enlaces: List<Pair<String, String>>)

    private fun usd(x: Double?): String? = when {
        x == null || x.isNaN() || x <= 0 -> null
        x >= 1e12 -> "$%.2fT".format(x / 1e12); x >= 1e9 -> "$%.2fB".format(x / 1e9); x >= 1e6 -> "$%.2fM".format(x / 1e6)
        x >= 1 -> "$%,.2f".format(x); else -> "$%.6g".format(x)
    }
    private fun num(x: Double?): String? = when {
        x == null || x.isNaN() || x <= 0 -> null
        x >= 1e12 -> "%.2fT".format(x / 1e12); x >= 1e9 -> "%.2fB".format(x / 1e9); x >= 1e6 -> "%.2fM".format(x / 1e6)
        else -> "%,.0f".format(x)
    }
    private fun pct(x: Double?): String? = if (x == null || x.isNaN()) null else "%s%.2f%%".format(if (x >= 0) "+" else "", x)
    private fun org.json.JSONObject.d(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
    private fun org.json.JSONObject.usdDe(k: String): Double? = optJSONObject(k)?.d("usd")
    private fun org.json.JSONObject.raw(k: String): Double? = optJSONObject(k)?.d("raw")
    private fun fecha(iso: String?): String? = iso?.takeIf { it.length >= 10 }?.substring(0, 10)
    private fun MutableList<Pair<String, String>>.fila(k: String, v: String?) { if (!v.isNullOrBlank()) add(k to v) }

    /** Rendimientos, volatilidad y caída máxima a partir de cierres diarios (más antiguo primero). */
    private fun metricas(t: List<Long>, c: List<Double>, diasAnio: Int): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>(); if (c.size < 3) return out
        val ult = c.last()
        fun hace(dias: Int): Double? { val lim = t.last() - dias * 86_400L; val i = t.indexOfLast { it <= lim }; return if (i >= 0) c[i] else null }
        fun ret(base: Double?) = if (base == null || base <= 0) null else (ult - base) / base * 100
        out.fila("7 days", pct(ret(hace(7)))); out.fila("30 days", pct(ret(hace(30)))); out.fila("90 days", pct(ret(hace(90))))
        out.fila("6 months", pct(ret(hace(182)))); out.fila("1 year", pct(ret(hace(365))))
        val anio = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).get(java.util.Calendar.YEAR)
        val iYtd = t.indexOfFirst { val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")); cal.timeInMillis = it * 1000; cal.get(java.util.Calendar.YEAR) == anio }
        if (iYtd > 0) out.fila("Year to date", pct(ret(c[iYtd - 1])))
        val r = (1 until c.size).mapNotNull { if (c[it - 1] > 0 && c[it] > 0) Math.log(c[it] / c[it - 1]) else null }
        if (r.size > 5) { val m = r.average(); val sd = Math.sqrt(r.sumOf { (it - m) * (it - m) } / (r.size - 1))
            out.fila("Volatility (annualized)", "%.1f%%".format(sd * Math.sqrt(diasAnio.toDouble()) * 100)) }
        var pico = c[0]; var dd = 0.0
        for (x in c) { if (x > pico) pico = x; if (pico > 0) dd = minOf(dd, (x - pico) / pico) }
        out.fila("Max drawdown (1y)", "%.1f%%".format(dd * 100))
        val hi = c.maxOrNull() ?: 0.0; val lo = c.minOrNull() ?: 0.0
        if (hi > 0) out.fila("From 1y high", pct((ult - hi) / hi * 100)); if (lo > 0) out.fila("From 1y low", pct((ult - lo) / lo * 100))
        return out
    }

    private fun textoPlano(html: String): String =
        android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\n{3,}"), "\n\n").trim()

    /** Resumen de Wikipedia (inglés) buscando por [consulta]; vacío si no hay nada claro. */
    private fun wikipedia(consulta: String): String = try {
        val q = java.net.URLEncoder.encode(consulta, "UTF-8")
        val bus = httpGet("https://en.wikipedia.org/w/api.php?action=query&list=search&srlimit=1&format=json&srsearch=$q")
        val titulo = bus?.let { org.json.JSONObject(it).getJSONObject("query").getJSONArray("search").optJSONObject(0)?.optString("title") }
        if (titulo.isNullOrEmpty()) "" else {
            val js = httpGet("https://en.wikipedia.org/api/rest_v1/page/summary/" + java.net.URLEncoder.encode(titulo.replace(' ', '_'), "UTF-8").replace("+", "%20"))
            js?.let { org.json.JSONObject(it).optString("extract") } ?: ""
        }
    } catch (e: Throwable) { "" }

    fun info(a: Activo): Info = if (a.tipo == Tipo.CRYPTO) infoCripto(a) else infoBolsa(a)

    private fun infoCripto(a: Activo): Info {
        val secs = mutableListOf<Pair<String, List<Pair<String, String>>>>()
        var desc = ""; val enlaces = mutableListOf<Pair<String, String>>(); var titulo = a.nombre
        val js = if (a.cgId.isNotEmpty()) httpGet("https://api.coingecko.com/api/v3/coins/${a.cgId}?localization=false&tickers=false" +
                "&community_data=false&developer_data=false&sparkline=false") else null
        if (js != null) try {
            val o = org.json.JSONObject(js); val md = o.optJSONObject("market_data") ?: org.json.JSONObject()
            titulo = "${o.optString("name", a.nombre)} (${o.optString("symbol", a.simbolo).uppercase()})"
            val gen = mutableListOf<Pair<String, String>>()
            gen.fila("Market cap rank", o.optInt("market_cap_rank", 0).takeIf { it > 0 }?.let { "#$it" })
            gen.fila("Categories", o.optJSONArray("categories")?.let { ar -> (0 until ar.length()).mapNotNull { ar.optString(it).takeIf { s -> s.isNotBlank() } }.take(4).joinToString(", ") })
            gen.fila("Hashing algorithm", o.optString("hashing_algorithm").takeIf { it != "null" })
            gen.fila("Genesis date", fecha(o.optString("genesis_date").takeIf { it != "null" }))
            secs.add("Overview" to gen)
            val pr = mutableListOf<Pair<String, String>>()
            pr.fila("Price", usd(md.usdDe("current_price"))); pr.fila("24h high", usd(md.usdDe("high_24h"))); pr.fila("24h low", usd(md.usdDe("low_24h")))
            pr.fila("24h change", pct(md.d("price_change_percentage_24h"))); pr.fila("7d change", pct(md.d("price_change_percentage_7d")))
            pr.fila("30d change", pct(md.d("price_change_percentage_30d"))); pr.fila("1y change", pct(md.d("price_change_percentage_1y")))
            secs.add("Price" to pr)
            val mk = mutableListOf<Pair<String, String>>()
            mk.fila("Market cap", usd(md.usdDe("market_cap"))); mk.fila("Fully diluted valuation", usd(md.usdDe("fully_diluted_valuation")))
            mk.fila("Volume 24h", usd(md.usdDe("total_volume")))
            val mc = md.usdDe("market_cap"); val vol = md.usdDe("total_volume")
            if (mc != null && vol != null && mc > 0) mk.fila("Volume / market cap", "%.2f%%".format(vol / mc * 100))
            secs.add("Market" to mk)
            val su = mutableListOf<Pair<String, String>>()
            val circ = md.d("circulating_supply"); val tot = md.d("total_supply"); val max = md.d("max_supply")
            su.fila("Circulating", num(circ)); su.fila("Total", num(tot)); su.fila("Max supply", num(max) ?: "unlimited / not set")
            if (circ != null && max != null && max > 0) su.fila("Issued of max", "%.2f%%".format(circ / max * 100))
            secs.add("Supply" to su)
            val ex = mutableListOf<Pair<String, String>>()
            ex.fila("All-time high", usd(md.usdDe("ath"))); ex.fila("ATH date", fecha(md.optJSONObject("ath_date")?.optString("usd")))
            ex.fila("From ATH", pct(md.usdDe("ath_change_percentage")))
            ex.fila("All-time low", usd(md.usdDe("atl"))); ex.fila("ATL date", fecha(md.optJSONObject("atl_date")?.optString("usd")))
            ex.fila("From ATL", pct(md.usdDe("atl_change_percentage")))
            secs.add("All-time" to ex)
            desc = textoPlano(o.optJSONObject("description")?.optString("en") ?: "")
            o.optJSONObject("links")?.let { l ->
                l.optJSONArray("homepage")?.optString(0)?.takeIf { it.startsWith("http") }?.let { enlaces.add("Website" to it) }
                l.optString("whitepaper").takeIf { it.startsWith("http") }?.let { enlaces.add("Whitepaper" to it) }
                l.optJSONArray("blockchain_site")?.optString(0)?.takeIf { it.startsWith("http") }?.let { enlaces.add("Explorer" to it) }
                l.optJSONObject("repos_url")?.optJSONArray("github")?.optString(0)?.takeIf { it.startsWith("http") }?.let { enlaces.add("Source code" to it) }
                l.optString("subreddit_url").takeIf { it.startsWith("http") }?.let { enlaces.add("Reddit" to it) }
            }
            enlaces.add("CoinGecko" to "https://www.coingecko.com/en/coins/${a.cgId}")
        } catch (e: Throwable) { }
        val diarias = velas(a, "1d", 400)
        if (diarias.size > 2) secs.add("Performance & risk (computed)" to metricas(diarias.map { it.t }, diarias.map { it.c.toDouble() }, 365))
        if (secs.isEmpty()) secs.add("Info" to listOf("Status" to "Details unavailable (CoinGecko unreachable)"))
        return Info(titulo, secs, desc, if (desc.isNotEmpty()) "CoinGecko" else "", enlaces)
    }

    private fun infoBolsa(a: Activo): Info {
        val secs = mutableListOf<Pair<String, List<Pair<String, String>>>>()
        var titulo = a.nombre; var desc = ""; var fuente = ""; val enlaces = mutableListOf<Pair<String, String>>()
        val sym = java.net.URLEncoder.encode(a.yahoo, "UTF-8")
        // 1) Gráfico de 1 año: meta + cierres diarios (sin "crumb", suele funcionar).
        val js = httpGet("https://query1.finance.yahoo.com/v8/finance/chart/$sym?interval=1d&range=1y", UA_NAV)
        var t = emptyList<Long>(); var c = emptyList<Double>()
        if (js != null) try {
            val r = org.json.JSONObject(js).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val m = r.getJSONObject("meta")
            titulo = m.optString("longName").ifEmpty { m.optString("shortName").ifEmpty { a.nombre } } + " (${a.yahoo})"
            val ts = r.optJSONArray("timestamp"); val cl = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).optJSONArray("close")
            if (ts != null && cl != null) { val idx = (0 until minOf(ts.length(), cl.length())).filter { !cl.isNull(it) }
                t = idx.map { ts.getLong(it) }; c = idx.map { cl.getDouble(it) } }
            val cur = m.optString("currency")
            fun px(x: Double?) = x?.takeIf { it > 0 }?.let { (if (a.sinDolar) "%,.4g" else "%,.2f").format(it) + if (cur.isNotEmpty() && !a.sinDolar) " $cur" else "" }
            val gen = mutableListOf<Pair<String, String>>()
            gen.fila("Exchange", m.optString("fullExchangeName").ifEmpty { m.optString("exchangeName") })
            gen.fila("Type", m.optString("instrumentType").lowercase().replaceFirstChar { it.uppercase() })
            gen.fila("Currency", cur); gen.fila("Time zone", m.optString("exchangeTimezoneName"))
            gen.fila("Trading since", m.d("firstTradeDate")?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(it.toLong() * 1000)) })
            secs.add("Overview" to gen)
            val pr = mutableListOf<Pair<String, String>>()
            pr.fila("Price", px(m.d("regularMarketPrice"))); pr.fila("Previous close", px(c.getOrNull(c.size - 2)))
            pr.fila("Day high", px(m.d("regularMarketDayHigh"))); pr.fila("Day low", px(m.d("regularMarketDayLow")))
            pr.fila("52-week high", px(m.d("fiftyTwoWeekHigh"))); pr.fila("52-week low", px(m.d("fiftyTwoWeekLow")))
            pr.fila("Volume (today)", num(m.d("regularMarketVolume")))
            secs.add("Price" to pr)
        } catch (e: Throwable) { }
        // 2) Perfil y fundamentales (puede pedir "crumb" y fallar: es opcional).
        val qs = httpGet("https://query2.finance.yahoo.com/v10/finance/quoteSummary/$sym?modules=assetProfile,summaryDetail,defaultKeyStatistics", UA_NAV)
        if (qs != null) try {
            val r = org.json.JSONObject(qs).getJSONObject("quoteSummary").getJSONArray("result").getJSONObject(0)
            r.optJSONObject("assetProfile")?.let { p ->
                val co = mutableListOf<Pair<String, String>>()
                co.fila("Sector", p.optString("sector")); co.fila("Industry", p.optString("industry"))
                co.fila("Country", p.optString("country")); co.fila("Employees", p.optInt("fullTimeEmployees", 0).takeIf { it > 0 }?.let { "%,d".format(it) })
                if (co.isNotEmpty()) secs.add("Company" to co)
                p.optString("website").takeIf { it.startsWith("http") }?.let { enlaces.add("Website" to it) }
                p.optString("longBusinessSummary").takeIf { it.isNotBlank() }?.let { desc = it; fuente = "Yahoo Finance" }
            }
            val sd = r.optJSONObject("summaryDetail"); val ks = r.optJSONObject("defaultKeyStatistics")
            val fu = mutableListOf<Pair<String, String>>()
            fu.fila("Market cap", usd(sd?.raw("marketCap"))); fu.fila("P/E (trailing)", sd?.raw("trailingPE")?.let { "%.2f".format(it) })
            fu.fila("P/E (forward)", sd?.raw("forwardPE")?.let { "%.2f".format(it) }); fu.fila("Beta", sd?.raw("beta")?.let { "%.2f".format(it) })
            fu.fila("Dividend yield", sd?.raw("dividendYield")?.let { "%.2f%%".format(it * 100) })
            fu.fila("Avg volume", num(sd?.raw("averageVolume"))); fu.fila("Shares outstanding", num(ks?.raw("sharesOutstanding")))
            fu.fila("Profit margin", ks?.raw("profitMargins")?.let { "%.2f%%".format(it * 100) })
            if (fu.isNotEmpty()) secs.add("Fundamentals" to fu)
        } catch (e: Throwable) { }
        if (c.size > 2) secs.add("Performance & risk (computed)" to metricas(t, c, 252))
        if (desc.isEmpty()) { desc = wikipedia(a.nombre + if (a.yahoo.startsWith("^") || a.yahoo.contains('=')) "" else " company"); if (desc.isNotEmpty()) fuente = "Wikipedia" }
        enlaces.add("Yahoo Finance" to "https://finance.yahoo.com/quote/$sym")
        if (secs.isEmpty()) secs.add("Info" to listOf("Status" to "Details unavailable (Yahoo Finance unreachable)"))
        return Info(titulo, secs, desc, fuente, enlaces)
    }
}
