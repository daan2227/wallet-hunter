package com.hunter.btc

/**
 * Datos de mercado para los gráficos del Home y del tool de precio. Fetch nativo
 * por el proxy de la app (respeta Tor si está activo); nada de WebView.
 *   - velasBtc(): velas OHLC+volumen de BTC en USD (Binance, fallback Bitstamp).
 *   - topMonedas(): top por capitalización SIN stablecoins (CoinGecko).
 * Llamar siempre desde un hilo de fondo.
 */
object Mercado {

    data class Moneda(val simbolo: String, val nombre: String, val precio: Double,
                      val cambio24h: Double, val spark: FloatArray)

    private val STABLES = setOf("usdt", "usdc", "dai", "busd", "tusd", "usdd", "fdusd",
        "pyusd", "usde", "frax", "gusd", "usdp", "lusd", "usdl", "susds", "usdy", "usds")

    private fun httpGet(url: String): String? = try {
        val conn = RedPrivada.abrir(url)
        conn.connectTimeout = 4000; conn.readTimeout = 9000
        conn.setRequestProperty("User-Agent", "hunter")
        try {
            if (conn.responseCode != 200) null
            else {
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
                conn.inputStream.use { while (true) { val n = it.read(buf); if (n < 0) break; if (out.size() + n > 8 * 1024 * 1024) break; out.write(buf, 0, n) } }
                out.toString("UTF-8")
            }
        } finally { conn.disconnect() }
    } catch (e: Throwable) { null }

    /** Velas OHLC+volumen en USD. intervalo: "1m"/"1h"/"1d". */
    fun velasBtc(intervalo: String): List<GraficoPrecio.Vela> {
        try {
            val js = httpGet("https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=$intervalo&limit=180")
            if (js != null && js.trimStart().startsWith("[")) {
                val arr = org.json.JSONArray(js); val out = ArrayList<GraficoPrecio.Vela>()
                for (i in 0 until arr.length()) {
                    val k = arr.getJSONArray(i)
                    out.add(GraficoPrecio.Vela(k.getLong(0) / 1000, k.getString(1).toFloat(), k.getString(2).toFloat(),
                        k.getString(3).toFloat(), k.getString(4).toFloat(), k.optString(5, "0").toFloatOrNull() ?: 0f))
                }
                if (out.isNotEmpty()) return out
            }
        } catch (e: Throwable) { }
        try {
            val step = when (intervalo) { "1m" -> 60; "1h" -> 3600; else -> 86400 }
            val js = httpGet("https://www.bitstamp.net/api/v2/ohlc/btcusd/?step=$step&limit=180")
            if (js != null) {
                val ohlc = org.json.JSONObject(js).getJSONObject("data").getJSONArray("ohlc"); val out = ArrayList<GraficoPrecio.Vela>()
                for (i in 0 until ohlc.length()) {
                    val o = ohlc.getJSONObject(i)
                    out.add(GraficoPrecio.Vela(o.getString("timestamp").toLong(), o.getString("open").toFloat(), o.getString("high").toFloat(),
                        o.getString("low").toFloat(), o.getString("close").toFloat(), o.optString("volume", "0").toFloatOrNull() ?: 0f))
                }
                if (out.isNotEmpty()) return out
            }
        } catch (e: Throwable) { }
        return emptyList()
    }

    /** Top [n] monedas por capitalización, SIN stablecoins, de CoinGecko. */
    fun topMonedas(n: Int = 10): List<Moneda> {
        val js = httpGet("https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc" +
                "&per_page=25&page=1&sparkline=true&price_change_percentage=24h") ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(js); val out = ArrayList<Moneda>()
            for (i in 0 until arr.length()) {
                if (out.size >= n) break
                val o = arr.getJSONObject(i)
                val sim = o.optString("symbol", "").lowercase()
                if (sim.isEmpty() || sim in STABLES) continue
                val sparkArr = o.optJSONObject("sparkline_in_7d")?.optJSONArray("price")
                val spark = if (sparkArr != null) FloatArray(sparkArr.length()) { sparkArr.optDouble(it, 0.0).toFloat() } else FloatArray(0)
                out.add(Moneda(sim.uppercase(), o.optString("name", sim.uppercase()),
                    o.optDouble("current_price", 0.0), o.optDouble("price_change_percentage_24h", 0.0), spark))
            }
            out
        } catch (e: Throwable) { emptyList() }
    }
}
