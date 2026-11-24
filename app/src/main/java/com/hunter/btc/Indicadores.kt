package com.hunter.btc

/**
 * Indicadores técnicos sobre una serie de cierres (o la que haga falta). Todo se
 * calcula en el teléfono a partir de las velas que ya se descargan; no hace falta
 * ninguna fuente extra. Donde un valor aún no existe (no hay suficiente historia)
 * va NaN, para que el gráfico simplemente no lo pinte.
 */
object Indicadores {

    /** Media móvil simple de [n]. */
    fun sma(v: FloatArray, n: Int): FloatArray {
        val out = FloatArray(v.size) { Float.NaN }
        if (n <= 0 || v.size < n) return out
        var suma = 0f
        for (i in v.indices) {
            suma += v[i]
            if (i >= n) suma -= v[i - n]
            if (i >= n - 1) out[i] = suma / n
        }
        return out
    }

    /** Media móvil exponencial de [n] (arranca en la SMA de los primeros n). */
    fun ema(v: FloatArray, n: Int): FloatArray {
        val out = FloatArray(v.size) { Float.NaN }
        if (n <= 0 || v.size < n) return out
        val k = 2f / (n + 1)
        var e = 0f
        for (i in 0 until n) e += v[i]
        e /= n
        out[n - 1] = e
        for (i in n until v.size) { e = v[i] * k + e * (1 - k); out[i] = e }
        return out
    }

    /** RSI de Wilder de [n] (por defecto 14), en 0..100. */
    fun rsi(v: FloatArray, n: Int = 14): FloatArray {
        val out = FloatArray(v.size) { Float.NaN }
        if (v.size <= n) return out
        var gan = 0f; var per = 0f
        for (i in 1..n) { val d = v[i] - v[i - 1]; if (d >= 0) gan += d else per -= d }
        gan /= n; per /= n
        out[n] = if (per == 0f) 100f else 100f - 100f / (1f + gan / per)
        for (i in n + 1 until v.size) {
            val d = v[i] - v[i - 1]
            val g = if (d >= 0) d else 0f; val p = if (d < 0) -d else 0f
            gan = (gan * (n - 1) + g) / n; per = (per * (n - 1) + p) / n
            out[i] = if (per == 0f) 100f else 100f - 100f / (1f + gan / per)
        }
        return out
    }

    /** MACD (línea, señal, histograma) con rápidas/lentas por defecto 12/26/9. */
    data class Macd(val macd: FloatArray, val signal: FloatArray, val hist: FloatArray)
    fun macd(v: FloatArray, rapida: Int = 12, lenta: Int = 26, senal: Int = 9): Macd {
        val er = ema(v, rapida); val el = ema(v, lenta)
        val m = FloatArray(v.size) { i -> if (er[i].isNaN() || el[i].isNaN()) Float.NaN else er[i] - el[i] }
        // señal = EMA(senal) de la parte válida de m
        val sig = FloatArray(v.size) { Float.NaN }
        val ini = m.indexOfFirst { !it.isNaN() }
        if (ini >= 0 && v.size - ini >= senal) {
            val k = 2f / (senal + 1); var e = 0f
            for (i in 0 until senal) e += m[ini + i]; e /= senal
            sig[ini + senal - 1] = e
            for (i in ini + senal until v.size) { e = m[i] * k + e * (1 - k); sig[i] = e }
        }
        val h = FloatArray(v.size) { i -> if (m[i].isNaN() || sig[i].isNaN()) Float.NaN else m[i] - sig[i] }
        return Macd(m, sig, h)
    }

    /** Estocástico: %K de [k] (suavizado 1) y %D = SMA(%K, [d]). 0..100. */
    data class Estoc(val k: FloatArray, val d: FloatArray)
    fun estocastico(h: FloatArray, l: FloatArray, c: FloatArray, k: Int = 14, d: Int = 3): Estoc {
        val n = c.size; val kk = FloatArray(n) { Float.NaN }
        for (i in k - 1 until n) {
            var hi = -Float.MAX_VALUE; var lo = Float.MAX_VALUE
            for (j in i - k + 1..i) { if (h[j] > hi) hi = h[j]; if (l[j] < lo) lo = l[j] }
            kk[i] = if (hi > lo) (c[i] - lo) / (hi - lo) * 100f else 50f
        }
        return Estoc(kk, smaNaN(kk, d))
    }

    /** SMA que salta los NaN iniciales (para suavizar series derivadas). */
    fun smaNaN(v: FloatArray, n: Int): FloatArray {
        val out = FloatArray(v.size) { Float.NaN }
        val ini = v.indexOfFirst { !it.isNaN() }; if (ini < 0) return out
        val sub = v.copyOfRange(ini, v.size); val s = sma(sub, n)
        for (i in s.indices) out[ini + i] = s[i]
        return out
    }

    /** ATR de Wilder de [n]. */
    fun atr(h: FloatArray, l: FloatArray, c: FloatArray, n: Int = 14): FloatArray {
        val sz = c.size; val out = FloatArray(sz) { Float.NaN }; if (sz <= n) return out
        val tr = FloatArray(sz) { i -> if (i == 0) h[0] - l[0] else maxOf(h[i] - l[i], Math.abs(h[i] - c[i - 1]), Math.abs(l[i] - c[i - 1])) }
        var a = 0f; for (i in 1..n) a += tr[i]; a /= n; out[n] = a
        for (i in n + 1 until sz) { a = (a * (n - 1) + tr[i]) / n; out[i] = a }
        return out
    }

    /** On-Balance Volume. */
    /**
     * Delta de volumen acumulado (CVD): compras − ventas de cada vela, con el volumen
     * comprador real si existe ([compra], Binance) o estimado por dónde cierra la vela
     * dentro de su rango.
     */
    fun cvd(h: FloatArray, l: FloatArray, c: FloatArray, vol: FloatArray, compra: FloatArray): FloatArray {
        val out = FloatArray(c.size); var acc = 0.0
        for (i in c.indices) {
            val d = if (!compra[i].isNaN()) 2.0 * compra[i] - vol[i]
                else { val r = (h[i] - l[i]).toDouble(); if (r > 0) vol[i] * ((c[i] - l[i]) - (h[i] - c[i])) / r else 0.0 }
            acc += d; out[i] = acc.toFloat()
        }
        return out
    }

    fun obv(c: FloatArray, vol: FloatArray): FloatArray {
        val out = FloatArray(c.size); if (c.isEmpty()) return out
        for (i in 1 until c.size) out[i] = out[i - 1] + when { c[i] > c[i - 1] -> vol[i]; c[i] < c[i - 1] -> -vol[i]; else -> 0f }
        return out
    }

    /** CCI de [n] sobre el precio típico. */
    fun cci(h: FloatArray, l: FloatArray, c: FloatArray, n: Int = 20): FloatArray {
        val sz = c.size; val out = FloatArray(sz) { Float.NaN }
        val tp = FloatArray(sz) { (h[it] + l[it] + c[it]) / 3f }; val m = sma(tp, n)
        for (i in n - 1 until sz) {
            var md = 0f; for (j in i - n + 1..i) md += Math.abs(tp[j] - m[i]); md /= n
            out[i] = if (md > 0f) (tp[i] - m[i]) / (0.015f * md) else 0f
        }
        return out
    }

    /** Williams %R de [n], −100..0. */
    fun williams(h: FloatArray, l: FloatArray, c: FloatArray, n: Int = 14): FloatArray {
        val sz = c.size; val out = FloatArray(sz) { Float.NaN }
        for (i in n - 1 until sz) {
            var hi = -Float.MAX_VALUE; var lo = Float.MAX_VALUE
            for (j in i - n + 1..i) { if (h[j] > hi) hi = h[j]; if (l[j] < lo) lo = l[j] }
            out[i] = if (hi > lo) (hi - c[i]) / (hi - lo) * -100f else -50f
        }
        return out
    }

    /** VWAP acumulado sobre el rango cargado (precio típico × volumen). */
    fun vwap(h: FloatArray, l: FloatArray, c: FloatArray, vol: FloatArray): FloatArray {
        val out = FloatArray(c.size) { Float.NaN }; var pv = 0.0; var vv = 0.0
        for (i in c.indices) { val tp = (h[i] + l[i] + c[i]) / 3.0; pv += tp * vol[i]; vv += vol[i]; if (vv > 0) out[i] = (pv / vv).toFloat() }
        return out
    }

    /** VWAP de sesión: se reinicia cada día UTC; [desv] es la desviación típica del precio típico ponderada por volumen. */
    class VwapSesion(val media: FloatArray, val desv: FloatArray)
    fun vwapSesion(t: LongArray, h: FloatArray, l: FloatArray, c: FloatArray, vol: FloatArray): VwapSesion {
        val n = c.size; val m = FloatArray(n) { Float.NaN }; val d = FloatArray(n) { Float.NaN }
        var pv = 0.0; var vv = 0.0; var p2v = 0.0; var dia = Long.MIN_VALUE
        for (i in 0 until n) {
            val di = Math.floorDiv(t[i], 86_400L)
            if (di != dia) { dia = di; pv = 0.0; vv = 0.0; p2v = 0.0 }
            val tp = (h[i] + l[i] + c[i]) / 3.0; val w = vol[i].toDouble().coerceAtLeast(0.0)
            pv += tp * w; vv += w; p2v += tp * tp * w
            if (vv > 0) { val mu = pv / vv; m[i] = mu.toFloat(); d[i] = Math.sqrt((p2v / vv - mu * mu).coerceAtLeast(0.0)).toFloat() }
        }
        return VwapSesion(m, d)
    }

    /** Parabolic SAR (paso [af], máximo [mx]). */
    fun sar(h: FloatArray, l: FloatArray, af0: Float = 0.02f, mx: Float = 0.2f): FloatArray {
        val n = h.size; val out = FloatArray(n) { Float.NaN }; if (n < 2) return out
        var alcista = h[1] >= h[0]; var sar = if (alcista) l[0] else h[0]
        var ep = if (alcista) h[0] else l[0]; var af = af0
        for (i in 1 until n) {
            sar += af * (ep - sar)
            if (alcista) {
                sar = minOf(sar, l[i - 1], if (i > 1) l[i - 2] else l[i - 1])
                if (l[i] < sar) { alcista = false; sar = ep; ep = l[i]; af = af0 }
                else if (h[i] > ep) { ep = h[i]; af = minOf(af + af0, mx) }
            } else {
                sar = maxOf(sar, h[i - 1], if (i > 1) h[i - 2] else h[i - 1])
                if (h[i] > sar) { alcista = true; sar = ep; ep = h[i]; af = af0 }
                else if (l[i] < ep) { ep = l[i]; af = minOf(af + af0, mx) }
            }
            out[i] = sar
        }
        return out
    }

    /** Bandas de Bollinger: media SMA(n) y ±[k] desviaciones típicas. */
    data class Bollinger(val media: FloatArray, val alta: FloatArray, val baja: FloatArray)
    fun bollinger(v: FloatArray, n: Int = 20, k: Float = 2f): Bollinger {
        val mid = sma(v, n)
        val alta = FloatArray(v.size) { Float.NaN }; val baja = FloatArray(v.size) { Float.NaN }
        if (v.size >= n) for (i in n - 1 until v.size) {
            val m = mid[i]; if (m.isNaN()) continue
            var s = 0f; for (j in i - n + 1..i) { val dd = v[j] - m; s += dd * dd }
            val sd = Math.sqrt((s / n).toDouble()).toFloat()
            alta[i] = m + k * sd; baja[i] = m - k * sd
        }
        return Bollinger(mid, alta, baja)
    }
}
