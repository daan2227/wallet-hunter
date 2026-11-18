package com.hunter.btc

import android.content.Context
import android.graphics.*
import android.view.View

/**
 * Gráfico técnico de velas con paneles apilados: precio (velas + MA/EMA),
 * volumen, RSI y MACD. Qué se ve lo deciden los flags (los toggles del Home).
 * Todo nativo sobre Canvas; los indicadores se calculan en Indicadores.kt.
 */
class GraficoTA(ctx: Context) : View(ctx) {
    private var velas: List<GraficoPrecio.Vela> = emptyList()
    var modoMedia = 1    // 0 = off, 1 = MA (SMA), 2 = EMA
    var showVol = true
    var showRsi = true
    var showMacd = true
    private val d = resources.displayMetrics.density

    fun poner(v: List<GraficoPrecio.Vela>) { velas = v; postInvalidate() }

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pWick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f * d; style = Paint.Style.STROKE }
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.4f * d; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val pGuide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppTheme.TXT_MUTED; strokeWidth = 0.8f * d; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(4f * d, 4f * d), 0f)
    }
    private val pSep = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (AppTheme.TXT_MUTED and 0x00FFFFFF) or 0x30000000; strokeWidth = 1f * d }
    private val lbl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AppTheme.TXT_MUTED; textSize = 9f * d; typeface = AppTheme.mono(ctx) }

    private fun alfa(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)
    private fun fmt(v: Float) = if (v >= 1000f) "%,.0f".format(v) else "%.1f".format(v)

    private fun linea(c: Canvas, s: FloatArray, color: Int, cx: (Int) -> Float, yf: (Float) -> Float) {
        pLine.color = color; val path = Path(); var started = false
        for (i in s.indices) {
            val vv = s[i]; if (vv.isNaN()) { started = false; continue }
            val x = cx(i); val y = yf(vv)
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        c.drawPath(path, pLine)
    }

    override fun onDraw(c: Canvas) {
        val v = velas
        if (v.size < 2) return
        val n = v.size
        val closes = FloatArray(n) { v[it].c }
        val w = width.toFloat(); val h = height.toFloat()
        val padL = 2f * d; val padR = 48f * d; val padT = 6f * d; val padB = 12f * d
        val gw = w - padL - padR
        val cx = { i: Int -> padL + gw * (i + 0.5f) / n }
        val medio = (gw / n * 0.34f).coerceIn(0.5f * d, 6f * d)

        val subs = (if (showVol) 1 else 0) + (if (showRsi) 1 else 0) + (if (showMacd) 1 else 0)
        val gap = 8f * d
        val totalH = h - padT - padB - gap * subs
        val subH = if (subs > 0) totalH * 0.18f else 0f
        val priceH = totalH - subH * subs
        var y0 = padT

        // ── PRECIO (velas + MA/EMA) ──
        val pTop = y0; val pBot = y0 + priceH; y0 = pBot + gap
        var minL = Float.MAX_VALUE; var maxH = -Float.MAX_VALUE
        for (k in v) { if (k.l < minL) minL = k.l; if (k.h > maxH) maxH = k.h }
        if (maxH <= minL) maxH = minL + 1f
        val yP = { p: Float -> pBot - (pBot - pTop) * (p - minL) / (maxH - minL) }
        for (i in v.indices) {
            val k = v[i]; val up = k.c >= k.o; val col = if (up) AppTheme.OK else AppTheme.RED
            val x = cx(i); pWick.color = col; c.drawLine(x, yP(k.h), x, yP(k.l), pWick)
            pFill.color = col
            var t = yP(maxOf(k.o, k.c)); var b = yP(minOf(k.o, k.c)); if (b - t < 1f * d) { t -= 0.5f * d; b += 0.5f * d }
            c.drawRect(x - medio, t, x + medio, b, pFill)
        }
        if (modoMedia != 0) {
            val m7 = if (modoMedia == 1) Indicadores.sma(closes, 7) else Indicadores.ema(closes, 7)
            val m30 = if (modoMedia == 1) Indicadores.sma(closes, 30) else Indicadores.ema(closes, 30)
            linea(c, m7, AppTheme.WARN, cx, yP)
            linea(c, m30, AppTheme.ACCENT2, cx, yP)
        }
        c.drawText(fmt(maxH), w - padR + 3f * d, pTop + 8f * d, lbl)
        c.drawText(fmt(minL), w - padR + 3f * d, pBot, lbl)

        // ── VOLUMEN ──
        if (showVol) {
            val vTop = y0; val vBot = y0 + subH; y0 = vBot + gap
            var mv = 0f; for (k in v) if (k.vol > mv) mv = k.vol; if (mv <= 0f) mv = 1f
            for (i in v.indices) {
                val k = v[i]; pFill.color = alfa(if (k.c >= k.o) AppTheme.OK else AppTheme.RED, 0x66)
                val bh = (vBot - vTop) * (k.vol / mv); c.drawRect(cx(i) - medio, vBot - bh, cx(i) + medio, vBot, pFill)
            }
            c.drawText("Vol", padL, vTop + 8f * d, lbl); c.drawLine(padL, vBot, w - padR, vBot, pSep)
        }

        // ── RSI ──
        if (showRsi) {
            val rTop = y0; val rBot = y0 + subH; y0 = rBot + gap
            val rsi = Indicadores.rsi(closes, 14)
            val yR = { r: Float -> rBot - (rBot - rTop) * (r / 100f) }
            c.drawLine(padL, yR(30f), w - padR, yR(30f), pGuide); c.drawLine(padL, yR(70f), w - padR, yR(70f), pGuide)
            linea(c, rsi, AppTheme.WARN, cx, yR)
            c.drawText("RSI 14", padL, rTop + 8f * d, lbl)
        }

        // ── MACD ──
        if (showMacd) {
            val mTop = y0; val mBot = y0 + subH
            val mac = Indicadores.macd(closes)
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (i in 0 until n) for (x in floatArrayOf(mac.macd[i], mac.signal[i], mac.hist[i])) if (!x.isNaN()) { if (x < lo) lo = x; if (x > hi) hi = x }
            if (lo == Float.MAX_VALUE) { lo = -1f; hi = 1f }; if (hi <= lo) hi = lo + 1f
            val yM = { m: Float -> mBot - (mBot - mTop) * (m - lo) / (hi - lo) }
            val zy = yM(0f)
            for (i in 0 until n) {
                val hgt = mac.hist[i]; if (hgt.isNaN()) continue
                pFill.color = alfa(if (hgt >= 0) AppTheme.OK else AppTheme.RED, 0x88)
                c.drawRect(cx(i) - medio, minOf(zy, yM(hgt)), cx(i) + medio, maxOf(zy, yM(hgt)), pFill)
            }
            linea(c, mac.macd, AppTheme.WARN, cx, yM)
            linea(c, mac.signal, AppTheme.ACCENT2, cx, yM)
            c.drawText("MACD", padL, mTop + 8f * d, lbl)
        }
    }
}
