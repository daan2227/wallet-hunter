package com.hunter.btc

/**
 * Modo IA del DEx, parte local: detecta patrones y puntúa el par con datos de
 * mercado, del gráfico y sociales, y propone Long, Short, Spot o Esperar con un
 * plan (entrada, stop, objetivos, apalancamiento máximo prudente). Todo es cálculo
 * en el móvil, sin enviar nada; es una ayuda para pensar, no una señal ni consejo.
 * La parte de recolección (red) está en [Recolector]; [evaluar] es puro y se prueba
 * en la JVM.
 */
object AnalisisIA {

    enum class Grupo(val nombre: String) { MERCADO("Market"), GRAFICO("Chart"), SOCIAL("Social") }
    enum class Veredicto(val nombre: String) { LONG("Long"), SHORT("Short"), SPOT("Spot buy"), ESPERAR("Wait") }

    /** Un factor: puntuación de −2 (bajista) a +2 (alcista) con su explicación. */
    class Factor(val grupo: Grupo, val nombre: String, val puntos: Double, val texto: String)

    class Patron(val nombre: String, val alcista: Boolean?, val indice: Int)

    class Datos(
        val velas: List<GraficoPrecio.Vela>,               // del intervalo del gráfico
        val diarias: List<GraficoPrecio.Vela> = emptyList(),
        val funding: Double? = null, val ratioCuentas: Double? = null, val pctLong: Double? = null, val ratioTaker: Double? = null,
        val miedoCodicia: Int? = null, val enTendencia: Boolean? = null, val votosPositivos: Double? = null,
        val tonoReddit: Double? = null, val titularesReddit: List<Pair<String, Int>> = emptyList(),
        val esCripto: Boolean = true,
    )

    class Plan(val largo: Boolean, val entrada: Double, val stop: Double, val objetivo1: Double, val objetivo2: Double, val apalancamientoMax: Int)

    class Resultado(
        val veredicto: Veredicto, val puntuacion: Double, val confianza: Int,
        val factores: List<Factor>, val patrones: List<Patron>,
        val soportes: List<Double>, val resistencias: List<Double>, val plan: Plan?, val atrPct: Double,
    )

    // ── patrones de velas (sobre velas CERRADAS: la última del gráfico aún se mueve) ──
    fun patronesVelas(v: List<GraficoPrecio.Vela>): List<Patron> {
        val out = ArrayList<Patron>(); val n = v.size
        if (n < 4) return out
        val i = n - 2                      // última cerrada
        fun cuerpo(k: GraficoPrecio.Vela) = Math.abs(k.c - k.o)
        fun rango(k: GraficoPrecio.Vela) = (k.h - k.l).coerceAtLeast(1e-9f)
        fun sube(k: GraficoPrecio.Vela) = k.c > k.o
        val a = v[i]; val b = v[i - 1]; val c0 = v[i - 2]
        val sombraInf = minOf(a.o, a.c) - a.l; val sombraSup = a.h - maxOf(a.o, a.c)
        // tendencia corta previa (5 velas) para dar sentido al martillo / estrella
        val previa = v[maxOf(0, i - 6)].c; val bajando = b.c < previa; val subiendo = b.c > previa
        if (cuerpo(a) <= rango(a) * 0.1f) out.add(Patron("Doji (indecision)", null, i))
        if (sombraInf >= cuerpo(a) * 2 && sombraSup <= cuerpo(a) * 0.6f && cuerpo(a) > 0)
            out.add(if (bajando) Patron("Hammer (possible bottom)", true, i) else Patron("Hanging man (weakness after a rise)", false, i))
        if (sombraSup >= cuerpo(a) * 2 && sombraInf <= cuerpo(a) * 0.6f && cuerpo(a) > 0)
            out.add(if (subiendo) Patron("Shooting star (rejection at the top)", false, i) else Patron("Inverted hammer", true, i))
        if (sube(a) && !sube(b) && a.c >= b.o && a.o <= b.c && cuerpo(a) > cuerpo(b)) out.add(Patron("Bullish engulfing", true, i))
        if (!sube(a) && sube(b) && a.o >= b.c && a.c <= b.o && cuerpo(a) > cuerpo(b)) out.add(Patron("Bearish engulfing", false, i))
        if (!sube(c0) && cuerpo(b) <= rango(b) * 0.3f && sube(a) && a.c > (c0.o + c0.c) / 2) out.add(Patron("Morning star", true, i))
        if (sube(c0) && cuerpo(b) <= rango(b) * 0.3f && !sube(a) && a.c < (c0.o + c0.c) / 2) out.add(Patron("Evening star", false, i))
        if (cuerpo(a) >= rango(a) * 0.9f && cuerpo(a) > 0) out.add(Patron(if (sube(a)) "Bullish marubozu" else "Bearish marubozu", sube(a), i))
        return out
    }

    /** Pivotes: máximos y mínimos locales (k velas a cada lado) → soportes y resistencias cercanos. */
    fun niveles(v: List<GraficoPrecio.Vela>, k: Int = 3): Pair<List<Double>, List<Double>> {
        if (v.size < 2 * k + 2) return emptyList<Double>() to emptyList()
        val precio = v.last().c.toDouble()
        val maximos = ArrayList<Double>(); val minimos = ArrayList<Double>()
        for (i in k until v.size - k - 1) {
            val h = v[i].h; val l = v[i].l
            if ((i - k..i + k).all { it == i || v[it].h < h }) maximos.add(h.toDouble())
            if ((i - k..i + k).all { it == i || v[it].l > l }) minimos.add(l.toDouble())
        }
        // agrupa niveles a menos de 0,5 % y se queda con los 3 más cercanos a cada lado
        fun agrupar(xs: List<Double>): List<Double> { val o = ArrayList<Double>()
            for (x in xs.sorted()) if (o.isEmpty() || (x - o.last()) / o.last() > 0.005) o.add(x) else o[o.size - 1] = (o.last() + x) / 2
            return o }
        val todos = agrupar(maximos + minimos)
        return todos.filter { it < precio }.sortedDescending().take(3) to todos.filter { it > precio }.sorted().take(3)
    }

    private fun ultimo(a: FloatArray, desde: Int = 0): Float { for (i in a.size - 1 - desde downTo 0) if (!a[i].isNaN()) return a[i]; return Float.NaN }

    fun evaluar(d: Datos): Resultado {
        val v = d.velas
        val f = ArrayList<Factor>()
        val n = v.size
        val c = FloatArray(n) { v[it].c }; val h = FloatArray(n) { v[it].h }; val l = FloatArray(n) { v[it].l }; val vol = FloatArray(n) { v[it].vol }
        val precio = v.lastOrNull()?.c?.toDouble() ?: 0.0
        // ── GRÁFICO: tendencia ──
        val e20 = ultimo(Indicadores.ema(c, 20)); val e50 = ultimo(Indicadores.ema(c, 50)); val e200 = ultimo(Indicadores.ema(c, 200))
        if (!e20.isNaN() && !e50.isNaN()) {
            val p = c.last()
            val pts = when {
                p > e20 && e20 > e50 && (e200.isNaN() || e50 > e200) -> 2.0
                p > e50 -> 1.0
                p < e20 && e20 < e50 && (e200.isNaN() || e50 < e200) -> -2.0
                p < e50 -> -1.0
                else -> 0.0
            }
            f.add(Factor(Grupo.GRAFICO, "Trend (EMA 20/50/200)", pts, when {
                pts >= 2 -> "Price above rising EMAs stacked 20 > 50${if (!e200.isNaN()) " > 200" else ""}: uptrend"
                pts <= -2 -> "Price below EMAs stacked 20 < 50${if (!e200.isNaN()) " < 200" else ""}: downtrend"
                pts > 0 -> "Price above the EMA 50, trend still mixed"
                pts < 0 -> "Price below the EMA 50, trend still mixed"
                else -> "Price tangled in the averages: no clear trend" }))
        }
        // estructura: máximos y mínimos crecientes en las últimas 40 velas
        if (n >= 40) {
            val m1 = (n - 40 until n - 20).maxOf { h[it] }; val m2 = (n - 20 until n).maxOf { h[it] }
            val n1 = (n - 40 until n - 20).minOf { l[it] }; val n2 = (n - 20 until n).minOf { l[it] }
            val pts = when { m2 > m1 && n2 > n1 -> 1.0; m2 < m1 && n2 < n1 -> -1.0; else -> 0.0 }
            f.add(Factor(Grupo.GRAFICO, "Market structure", pts, when { pts > 0 -> "Higher highs and higher lows"; pts < 0 -> "Lower highs and lower lows"; else -> "Range: no higher/lower sequence" }))
        }
        // ── GRÁFICO: momentum ──
        val rsiS = Indicadores.rsi(c, 14); val rsi = ultimo(rsiS)
        if (!rsi.isNaN()) {
            val tendencia = f.firstOrNull { it.nombre.startsWith("Trend") }?.puntos ?: 0.0
            val pts = when { rsi >= 75 -> if (tendencia >= 2) -0.5 else -1.0; rsi >= 55 -> 1.0; rsi <= 25 -> if (tendencia <= -2) 0.5 else 1.0; rsi <= 45 -> -1.0; else -> 0.0 }
            f.add(Factor(Grupo.GRAFICO, "RSI(14) %.0f".format(rsi), pts, when {
                rsi >= 75 -> "Overbought: pullback risk"; rsi >= 55 -> "Bullish momentum"; rsi <= 25 -> "Oversold: bounce potential"
                rsi <= 45 -> "Bearish momentum"; else -> "Neutral momentum" }))
        }
        val macd = Indicadores.macd(c); val hist = ultimo(macd.hist); val histPrev = ultimo(macd.hist, 1)
        if (!hist.isNaN() && !histPrev.isNaN()) {
            val pts = when { hist > 0 && hist >= histPrev -> 1.0; hist > 0 -> 0.5; hist < 0 && hist <= histPrev -> -1.0; else -> -0.5 }
            f.add(Factor(Grupo.GRAFICO, "MACD", pts, if (hist > 0) (if (hist >= histPrev) "Above signal and widening" else "Above signal but fading")
                else (if (hist <= histPrev) "Below signal and widening" else "Below signal but recovering")))
        }
        // divergencia RSI (últimas 30 velas): precio hace nuevo mínimo y el RSI no (alcista) o al revés
        if (n >= 35 && !rsi.isNaN()) {
            val i1 = (n - 30 until n - 10).minByOrNull { l[it] }!!; val i2 = (n - 10 until n).minByOrNull { l[it] }!!
            val j1 = (n - 30 until n - 10).maxByOrNull { h[it] }!!; val j2 = (n - 10 until n).maxByOrNull { h[it] }!!
            if (l[i2] < l[i1] && !rsiS[i1].isNaN() && rsiS[i2] > rsiS[i1] + 3) f.add(Factor(Grupo.GRAFICO, "Bullish RSI divergence", 1.5, "New low in price without a new low in RSI: selling pressure fading"))
            else if (h[j2] > h[j1] && !rsiS[j1].isNaN() && rsiS[j2] < rsiS[j1] - 3) f.add(Factor(Grupo.GRAFICO, "Bearish RSI divergence", -1.5, "New high in price without a new high in RSI: buying pressure fading"))
        }
        // ruptura del rango de 20 velas y compresión de Bollinger
        if (n >= 25) {
            val maxR = (n - 21 until n - 1).maxOf { h[it] }; val minR = (n - 21 until n - 1).minOf { l[it] }
            if (c.last() > maxR) f.add(Factor(Grupo.GRAFICO, "Breakout", 1.5, "Close above the 20-candle high"))
            else if (c.last() < minR) f.add(Factor(Grupo.GRAFICO, "Breakdown", -1.5, "Close below the 20-candle low"))
            val bb = Indicadores.bollinger(c); val anchos = (0 until n).mapNotNull { i -> if (bb.media[i].isNaN() || bb.media[i] == 0f) null else (bb.alta[i] - bb.baja[i]) / bb.media[i] }
            if (anchos.size > 50 && anchos.last() <= anchos.sorted()[anchos.size / 10]) f.add(Factor(Grupo.GRAFICO, "Bollinger squeeze", 0.0, "Volatility at a 10% low of the period: a strong move often follows (direction unknown)"))
        }
        // volumen: vela actual frente a la media de 20, en la dirección de la vela
        if (n >= 22 && vol.any { it > 0 }) {
            val media = (n - 22 until n - 1).map { vol[it] }.average()
            val k = v[n - 2]; val rel = if (media > 0) k.vol / media else 1.0
            if (rel >= 1.5) f.add(Factor(Grupo.MERCADO, "Volume spike x%.1f".format(rel), if (k.c >= k.o) 1.0 else -1.0,
                "Last closed candle traded ${"%.1f".format(rel)}× its average volume ${if (k.c >= k.o) "while rising" else "while falling"}"))
        }
        // marco superior (diario)
        if (d.diarias.size >= 55) {
            val cd = FloatArray(d.diarias.size) { d.diarias[it].c }
            val d50 = ultimo(Indicadores.ema(cd, 50)); val pd = cd.last()
            if (!d50.isNaN()) f.add(Factor(Grupo.MERCADO, "Daily trend", if (pd > d50) 1.0 else -1.0, if (pd > d50) "Daily close above its EMA 50" else "Daily close below its EMA 50"))
        }
        // ── MERCADO: derivados ──
        d.funding?.let { fr ->
            val pct = fr * 100
            val pts = when { pct > 0.05 -> -1.0; pct < -0.03 -> 1.0; else -> 0.0 }
            f.add(Factor(Grupo.MERCADO, "Funding %+.4f%%".format(pct), pts, when { pts < 0 -> "Longs paying a lot: crowded long, squeeze risk down"; pts > 0 -> "Shorts paying: crowded short, squeeze risk up"; else -> "Normal funding" }))
        }
        d.pctLong?.let { pl ->
            val pts = when { pl >= 70 -> -1.0; pl <= 35 -> 1.0; else -> 0.0 }
            f.add(Factor(Grupo.MERCADO, "Accounts %.0f%% long".format(pl), pts, when { pts < 0 -> "Most retail accounts long (contrarian bearish)"; pts > 0 -> "Most retail accounts short (contrarian bullish)"; else -> "Balanced positioning" }))
        }
        d.ratioTaker?.let { t ->
            val pts = when { t >= 1.15 -> 1.0; t <= 0.87 -> -1.0; else -> 0.0 }
            f.add(Factor(Grupo.MERCADO, "Taker buy/sell %.2f".format(t), pts, when { pts > 0 -> "Aggressive buyers dominate"; pts < 0 -> "Aggressive sellers dominate"; else -> "Balanced aggressive flow" }))
        }
        // ── SOCIAL ──
        d.miedoCodicia?.let { fg ->
            val pts = when { fg <= 20 -> 1.0; fg >= 80 -> -1.0; else -> 0.0 }
            f.add(Factor(Grupo.SOCIAL, "Fear & Greed $fg", pts, when { pts > 0 -> "Extreme fear: historically a contrarian buy zone"; pts < 0 -> "Extreme greed: historically a contrarian caution zone"; else -> "Sentiment not extreme" }))
        }
        d.enTendencia?.let { t -> if (t) f.add(Factor(Grupo.SOCIAL, "Trending on CoinGecko", 0.5, "Among the most searched coins right now: attention, and volatility, rising")) }
        d.votosPositivos?.let { vp ->
            val pts = when { vp >= 80 -> 0.5; vp <= 40 -> -0.5; else -> 0.0 }
            f.add(Factor(Grupo.SOCIAL, "Community %.0f%% bullish".format(vp), pts, "CoinGecko community sentiment votes"))
        }
        d.tonoReddit?.let { t ->
            val pts = when { t >= 0.25 -> 0.5; t <= -0.25 -> -0.5; else -> 0.0 }
            f.add(Factor(Grupo.SOCIAL, "Reddit tone %+.2f".format(t), pts, when { pts > 0 -> "Recent posts lean positive"; pts < 0 -> "Recent posts lean negative"; else -> "Mixed or neutral posts" }))
        }
        // patrones de velas → factor
        val pats = patronesVelas(v)
        for (p in pats) if (p.alcista != null) f.add(Factor(Grupo.GRAFICO, p.nombre, if (p.alcista) 1.0 else -1.0, "Candlestick pattern on the last closed candle"))

        // ── veredicto ──
        val total = f.sumOf { it.puntos }
        val maxPosible = f.sumOf { Math.abs(it.puntos).coerceAtLeast(1.0) }.coerceAtLeast(1.0)
        val confianza = (Math.abs(total) / maxPosible * 100).toInt().coerceIn(0, 95)
        val atr = ultimo(Indicadores.atr(h, l, c, 14)).toDouble().takeIf { !it.isNaN() } ?: (precio * 0.02)
        val atrPct = if (precio > 0) atr / precio * 100 else 0.0
        // derivados muy cargados en contra → Spot en vez de Long apalancado
        val derivadosEnContra = f.filter { it.grupo == Grupo.MERCADO && (it.nombre.startsWith("Funding") || it.nombre.startsWith("Accounts")) }.sumOf { it.puntos } <= -1
        val veredicto = when {
            total >= 2.5 && !derivadosEnContra && d.esCripto -> Veredicto.LONG
            total >= 1.5 -> Veredicto.SPOT
            total <= -2.5 && d.esCripto -> Veredicto.SHORT
            else -> Veredicto.ESPERAR
        }
        val (sop, res) = niveles(v)
        val plan = when (veredicto) {
            Veredicto.LONG, Veredicto.SPOT -> {
                val stop = minOf(precio - 1.5 * atr, (sop.firstOrNull() ?: precio) - 0.3 * atr).coerceAtLeast(precio * 0.5)
                val r = precio - stop
                val t1 = res.firstOrNull()?.takeIf { it > precio + r } ?: (precio + 1.5 * r)
                Plan(true, precio, stop, t1, maxOf(precio + 3 * r, t1 + 0.5 * r), apalancamiento(r / precio))
            }
            Veredicto.SHORT -> {
                val stop = maxOf(precio + 1.5 * atr, (res.firstOrNull() ?: precio) + 0.3 * atr)
                val r = stop - precio
                val t1 = sop.firstOrNull()?.takeIf { it < precio - r } ?: (precio - 1.5 * r)
                Plan(false, precio, stop, t1, minOf(precio - 3 * r, t1 - 0.5 * r).coerceAtLeast(precio * 0.05), apalancamiento(r / precio))
            }
            else -> null
        }
        return Resultado(veredicto, total, confianza, f, pats, sop, res, plan, atrPct)
    }

    /** Apalancamiento máximo prudente: que el stop no supere ~40 % del margen, tope 10x. */
    fun apalancamiento(distanciaStop: Double): Int = if (distanciaStop <= 0) 1 else (0.4 / distanciaStop).toInt().coerceIn(1, 10)

    /** Tono de titulares con un léxico simple: +1 alcista, −1 bajista, 0 neutro por titular. */
    fun tono(titulo: String): Int {
        val t = titulo.lowercase()
        val pos = listOf("bull", "surge", "soar", "rally", "pump", "moon", "ath", "all-time high", "breakout", "adoption", "approve", "approval", "etf inflow", "buy", "gain", "record", "up ")
        val neg = listOf("bear", "crash", "dump", "plunge", "hack", "exploit", "ban", "lawsuit", "sec sues", "sell", "fall", "drop", "liquidat", "fear", "scam", "rug", "down ")
        val p = pos.count { it in t }; val n = neg.count { it in t }
        return when { p > n -> 1; n > p -> -1; else -> 0 }
    }
}
