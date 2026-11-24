package com.hunter.btc

/**
 * Scalping con VUSD de prueba (cálculo puro, probado en la JVM): flujo de órdenes
 * (desequilibrio del libro y delta de las últimas operaciones), señal rápida para el
 * bot IA en 1m/5m y estadísticas de la sesión. No es asesoramiento.
 */
object Scalping {
    /** Desequilibrio del libro en ±[banda] del precio medio: +1 todo compras, −1 todo ventas. */
    fun desequilibrio(l: Mercado.Libro?, banda: Double = 0.005): Double? {
        if (l == null) return null
        val bb = l.bids.firstOrNull()?.first ?: return null; val ba = l.asks.firstOrNull()?.first ?: return null
        val mid = (bb + ba) / 2
        val b = l.bids.filter { it.first >= mid * (1 - banda) }.sumOf { it.first * it.second }
        val a = l.asks.filter { it.first <= mid * (1 + banda) }.sumOf { it.first * it.second }
        return if (a + b <= 0) null else (b - a) / (a + b)
    }

    /** Delta de las operaciones: (compras − ventas) / total, en volumen. */
    fun delta(tr: List<Mercado.Trade>): Double? {
        val c = tr.filter { it.compra }.sumOf { it.qty * it.precio }; val v = tr.filter { !it.compra }.sumOf { it.qty * it.precio }
        return if (c + v <= 0) null else (c - v) / (c + v)
    }

    class Senal(val dir: Int, val confianza: Int, val stop: Double, val objetivo: Double, val apal: Int, val motivos: List<String>)

    /**
     * Señal de scalping sobre velas CERRADAS de 1m/5m: tendencia corta (EMA 9/21),
     * lado del VWAP de sesión, impulso de las 3 últimas velas, desequilibrio del libro
     * y RSI(7) en extremos. Stop a 1,2 ATR (mín. 0,15 %), objetivo a 1,5 veces el stop.
     */
    fun senal(v: List<GraficoPrecio.Vela>, libro: Double? = null): Senal? {
        val n = v.size; if (n < 40) return null
        val c = FloatArray(n) { v[it].c }; val h = FloatArray(n) { v[it].h }; val l = FloatArray(n) { v[it].l }; val vo = FloatArray(n) { v[it].vol }
        val e9 = Indicadores.ema(c, 9)[n - 1]; val e21 = Indicadores.ema(c, 21)[n - 1]
        val vw = Indicadores.vwapSesion(LongArray(n) { v[it].t }, h, l, c, vo).media[n - 1]
        val rsi = Indicadores.rsi(c, 7)[n - 1]; val atr = Indicadores.atr(h, l, c, 14)[n - 1]
        val p = c[n - 1].toDouble(); if (p <= 0) return null
        var pts = 0; val mot = ArrayList<String>()
        if (!e9.isNaN() && !e21.isNaN()) { if (e9 > e21) { pts++; mot.add("EMA9 > EMA21") } else if (e9 < e21) { pts--; mot.add("EMA9 < EMA21") } }
        if (!vw.isNaN()) { if (p > vw) { pts++; mot.add("above VWAP") } else if (p < vw) { pts--; mot.add("below VWAP") } }
        if (c[n - 1] > c[n - 2] && c[n - 2] > c[n - 3] && c[n - 3] > c[n - 4]) { pts++; mot.add("3 rising closes") }
        else if (c[n - 1] < c[n - 2] && c[n - 2] < c[n - 3] && c[n - 3] < c[n - 4]) { pts--; mot.add("3 falling closes") }
        if (libro != null) { if (libro > 0.15) { pts++; mot.add("book bids heavy") } else if (libro < -0.15) { pts--; mot.add("book asks heavy") } }
        if (!rsi.isNaN()) { if (rsi > 85 && pts > 0) { pts--; mot.add("RSI(7) overbought") } else if (rsi < 15 && pts < 0) { pts++; mot.add("RSI(7) oversold") } }
        val dir = when { pts >= 3 -> 1; pts <= -3 -> -1; else -> 0 }
        val dist = maxOf(if (atr.isNaN()) 0.0 else 1.2 * atr, p * 0.0015)
        val apal = (0.25 / (dist / p)).toInt().coerceIn(1, 50)
        val conf = (40 + Math.abs(pts) * 12).coerceAtMost(95)
        return Senal(dir, conf, if (dir >= 0) p - dist else p + dist, if (dir >= 0) p + 1.5 * dist else p - 1.5 * dist, apal, mot)
    }

    class Sesion(val operaciones: Int, val ganadas: Int, val resultado: Double, val comisiones: Double, val minutos: Double) {
        val porMinuto get() = if (minutos >= 1) resultado / minutos else 0.0
        val acierto get() = if (operaciones == 0) 0.0 else ganadas * 100.0 / operaciones
    }

    /** Estadísticas de futuros desde [desde] (s): cierres, aciertos, resultado neto (con comisiones de apertura) y comisiones. */
    fun sesion(h: List<Simulador.Operacion>, desde: Long, ahora: Long): Sesion {
        val fut = h.filter { it.mercado == Simulador.Mercado.FUT && it.t >= desde }
        val cierres = fut.filter { !it.lado.startsWith("Open") }
        return Sesion(cierres.size, cierres.count { it.pnl > 0 }, Simulador.resultadoFut(fut), fut.sumOf { it.comision },
            ((ahora - desde) / 60.0).coerceAtLeast(0.0))
    }
}

/** Utilidades de scalping para la UI (puras, probadas en la JVM). */
object ScalpUtil {
    /**
     * Margen para arriesgar [riesgo] VUSD si salta un stop a [slPct] % con [apal]x:
     * pérdida = nocional·(sl% + 2·comisión taker).
     */
    fun margenPorRiesgo(riesgo: Double, slPct: Double, apal: Int): Double {
        if (riesgo <= 0 || slPct <= 0 || apal <= 0) return 0.0
        return riesgo / (slPct / 100 + 2 * Simulador.COM_TAKER) / apal
    }

    /** Los [n] mayores muros del libro agrupado en tramos de [paso] (fracción del precio). */
    fun muros(l: Mercado.Libro?, n: Int = 8, paso: Double = 0.0005): List<GraficoTA.Muro> {
        if (l == null) return emptyList()
        val mid = ((l.bids.firstOrNull()?.first ?: return emptyList()) + (l.asks.firstOrNull()?.first ?: return emptyList())) / 2
        val tick = mid * paso
        fun agrupar(x: List<Pair<Double, Double>>) = x.groupBy { Math.round(it.first / tick) }.map { (k, v) -> k * tick to v.sumOf { it.first * it.second } }
        val todos = agrupar(l.bids).map { Triple(it.first, it.second, true) } + agrupar(l.asks).map { Triple(it.first, it.second, false) }
        val max = todos.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: return emptyList()
        return todos.sortedByDescending { it.second }.take(n).map { GraficoTA.Muro(it.first.toFloat(), (it.second / max).toFloat(), it.third) }
    }

    enum class Alerta(val texto: String) { VWAP_ARRIBA("Price crossed above VWAP"), VWAP_ABAJO("Price crossed below VWAP"),
        LIBRO_COMPRA("Order book: strong bids (>70%)"), LIBRO_VENTA("Order book: strong asks (>70%)"), VOLUMEN("Volume spike (3× average)") }

    /** Alertas de scalping sobre las velas del gráfico (la última en curso) y el desequilibrio del libro. */
    fun alertas(v: List<GraficoPrecio.Vela>, libro: Double?): List<Alerta> {
        val out = ArrayList<Alerta>(); val n = v.size
        if (n >= 3) {
            val vw = Indicadores.vwapSesion(LongArray(n) { v[it].t }, FloatArray(n) { v[it].h }, FloatArray(n) { v[it].l },
                FloatArray(n) { v[it].c }, FloatArray(n) { v[it].vol }).media
            val a = v[n - 2].c - vw[n - 2]; val b = v[n - 1].c - vw[n - 1]
            if (!a.isNaN() && !b.isNaN()) { if (a <= 0 && b > 0) out.add(Alerta.VWAP_ARRIBA) else if (a >= 0 && b < 0) out.add(Alerta.VWAP_ABAJO) }
        }
        if (n >= 21) { val media = (n - 21 until n - 1).map { v[it].vol }.average(); if (media > 0 && v[n - 1].vol > 3 * media) out.add(Alerta.VOLUMEN) }
        if (libro != null) { if (libro > 0.4) out.add(Alerta.LIBRO_COMPRA) else if (libro < -0.4) out.add(Alerta.LIBRO_VENTA) }
        return out
    }

    class Grupo(val clave: String, val operaciones: Int, val ganadas: Int, val resultado: Double)
    class Analisis(val porHora: List<Grupo>, val porPar: List<Grupo>, val porLado: List<Grupo>,
                   val duracionMedia: Double, val maeMedio: Double, val mfeMedio: Double)

    /** Cierres de futuros agrupados por hora local ([zona]), par y lado, con duración y MAE/MFE medios. */
    fun analizar(h: List<Simulador.Operacion>, zona: java.util.TimeZone = java.util.TimeZone.getDefault()): Analisis {
        val c = h.filter { it.mercado == Simulador.Mercado.FUT && !it.lado.startsWith("Open") }
        fun grupos(f: (Simulador.Operacion) -> String) = c.groupBy(f).map { (k, l) -> Grupo(k, l.size, l.count { it.pnl > 0 }, l.sumOf { it.pnl }) }
        val cal = java.util.Calendar.getInstance(zona)
        val hora = grupos { cal.timeInMillis = it.t * 1000; "%02d:00".format(cal.get(java.util.Calendar.HOUR_OF_DAY)) }.sortedBy { it.clave }
        val conMet = c.filter { it.dur > 0 }
        return Analisis(hora, grupos { it.simbolo }.sortedByDescending { it.resultado },
            grupos { if (it.lado.contains("long", true)) "Long" else "Short" },
            if (conMet.isEmpty()) 0.0 else conMet.map { it.dur.toDouble() }.average(),
            if (conMet.isEmpty()) 0.0 else conMet.map { it.mae }.average(), if (conMet.isEmpty()) 0.0 else conMet.map { it.mfe }.average())
    }
}
