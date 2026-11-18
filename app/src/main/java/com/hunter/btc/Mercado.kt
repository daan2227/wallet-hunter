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
            "1s", "1m" -> 60; "1h" -> 3600; "4h" -> 14400; "12h" -> 43200; "3d", "1w", "1M" -> 259200; else -> 86400
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
        val dias = when (intervalo) { "1s", "1m", "1h" -> 1; "4h", "12h" -> 30; else -> 365 }
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
        "1h" -> arrayOf<Any>("60m", "60d", 1, ""); "4h" -> arrayOf<Any>("60m", "730d", 4, "4h built from 1h")
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
                if (sim.isEmpty() || sim in STABLES) continue
                val sparkArr = o.optJSONObject("sparkline_in_7d")?.optJSONArray("price")
                val spark = if (sparkArr != null) FloatArray(sparkArr.length()) { sparkArr.optDouble(it, 0.0).toFloat() } else FloatArray(0)
                out.add(Moneda(Activo(Tipo.CRYPTO, sim.uppercase(), o.optString("name", sim.uppercase()), o.optString("id", "")),
                    o.optDouble("current_price", 0.0), o.optDouble("price_change_percentage_24h", 0.0), spark))
            }
            out
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
}
