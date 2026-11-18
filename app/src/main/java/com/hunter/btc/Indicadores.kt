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
