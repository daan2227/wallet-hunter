package com.hunter.btc

import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Gráfico técnico de velas, movible (arrastrar = desplazar, pellizcar = zoom),
 * con paneles apilados: precio (velas + MA/EMA + Bollinger), volumen, RSI y MACD.
 * Eje de precio a la derecha y eje de tiempo abajo. Todo nativo sobre Canvas; los
 * indicadores se calculan en Indicadores.kt. Qué se ve lo deciden los flags.
 */
class GraficoTA(ctx: Context) : View(ctx) {
    private var velas: List<GraficoPrecio.Vela> = emptyList()
    var modoMedia = 1            // 0 off, 1 MA (SMA), 2 EMA
    var maP1 = 7
    var maP2 = 30
    var showVol = true
    var showRsi = true
    var showMacd = true
    var showBB = false
    var intervaloSeg = 3600L     // para formatear el eje de tiempo

    private val d = resources.displayMetrics.density
    private val padLpx = 2f * d
    private val padRpx = 52f * d
    private val padTpx = 6f * d
    private val padBpx = 16f * d

    // Ventana visible (en índices de velas, float para desplazamiento suave).
    private var desde = 0f
    private var cuantas = 90f

    fun poner(v: List<GraficoPrecio.Vela>) {
        velas = v
        cuantas = minOf(90f, v.size.toFloat()).coerceAtLeast(2f)
        desde = (v.size - cuantas).coerceAtLeast(0f)
        postInvalidate()
    }

    private fun clamp() {
        val n = velas.size.toFloat()
        cuantas = cuantas.coerceIn(8f, n.coerceAtLeast(8f))
        desde = desde.coerceIn(0f, (n - cuantas).coerceAtLeast(0f))
    }

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val gw = width - padLpx - padRpx; if (velas.size < 2 || gw <= 0) return false
            desde += dx / (gw / cuantas); clamp(); postInvalidate(); return true
        }
    })
    private val sd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val gw = width - padLpx - padRpx; if (velas.size < 2 || gw <= 0) return false
            val fx = (det.focusX - padLpx) / gw
            val centro = desde + cuantas * fx
            cuantas = (cuantas / det.scaleFactor).coerceIn(8f, velas.size.toFloat())
            desde = centro - cuantas * fx; clamp(); postInvalidate(); return true
        }
    })
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        sd.onTouchEvent(e); gd.onTouchEvent(e); return true
    }

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pWick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f * d; style = Paint.Style.STROKE }
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.4f * d; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val pGuide = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AppTheme.TXT_MUTED; strokeWidth = 0.8f * d; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(4f * d, 4f * d), 0f) }
    private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (AppTheme.TXT_MUTED and 0x00FFFFFF) or 0x24000000; strokeWidth = 1f * d }
    private val lbl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AppTheme.TXT_MUTED; textSize = 9f * d; typeface = AppTheme.mono(ctx) }

    private fun alfa(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)
    private fun fmtP(v: Float) = when { v >= 1000f -> "%,.0f".format(v); v >= 1f -> "%.2f".format(v); else -> "%.4f".format(v) }
    private val sdfIntra = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
    private val sdfSeg = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    private val sdfDia = java.text.SimpleDateFormat("dd/MM", java.util.Locale.US)
    private val sdfMes = java.text.SimpleDateFormat("MM/yy", java.util.Locale.US)
    private fun fmtT(t: Long): String {
        val dt = java.util.Date(t * 1000)
        return when { intervaloSeg < 60L -> sdfSeg.format(dt); intervaloSeg < 86_400L -> sdfIntra.format(dt); intervaloSeg < 2_592_000L -> sdfDia.format(dt); else -> sdfMes.format(dt) }.also { }
    }

    private fun linea(c: Canvas, s: FloatArray, color: Int, i0: Int, i1: Int, xf: (Int) -> Float, yf: (Float) -> Float, dash: Boolean = false) {
        pLine.color = color; pLine.pathEffect = if (dash) DashPathEffect(floatArrayOf(5f * d, 4f * d), 0f) else null
        val path = Path(); var started = false
        for (i in i0..i1) { if (i < 0 || i >= s.size) continue; val vv = s[i]; if (vv.isNaN()) { started = false; continue }
            val x = xf(i); val y = yf(vv); if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y) }
        c.drawPath(path, pLine); pLine.pathEffect = null
    }

    override fun onDraw(c: Canvas) {
        val v = velas
        if (v.size < 2) return
        clamp()
        val n = v.size
        val closes = FloatArray(n) { v[it].c }
        val w = width.toFloat(); val h = height.toFloat()
        val gw = w - padLpx - padRpx
        val i0 = Math.floor(desde.toDouble()).toInt().coerceIn(0, n - 1)
        val i1 = Math.ceil((desde + cuantas).toDouble()).toInt().coerceIn(0, n - 1)
        val xf = { i: Int -> padLpx + gw * (i - desde) / cuantas }
        val medio = (gw / cuantas * 0.34f).coerceIn(0.5f * d, 7f * d)

        val subs = (if (showVol) 1 else 0) + (if (showRsi) 1 else 0) + (if (showMacd) 1 else 0)
        val gap = 8f * d
        val ejeT = 14f * d
        val totalH = h - padTpx - padBpx - ejeT - gap * subs
        val subH = if (subs > 0) totalH * 0.18f else 0f
        val priceH = totalH - subH * subs
        var y0 = padTpx

        // ── PRECIO ──
        val pTop = y0; val pBot = y0 + priceH; y0 = pBot + gap
        var minL = Float.MAX_VALUE; var maxH = -Float.MAX_VALUE
        for (i in i0..i1) { val k = v[i]; if (k.l < minL) minL = k.l; if (k.h > maxH) maxH = k.h
            if (showBB) { /* las bandas pueden salir del rango de precio; se recortan solas */ } }
        if (maxH <= minL) maxH = minL + 1f
        val yP = { p: Float -> pBot - (pBot - pTop) * (p - minL) / (maxH - minL) }
        // rejilla + eje de precio (derecha)
        val niveles = 4
        for (g in 0..niveles) {
            val p = minL + (maxH - minL) * g / niveles; val y = yP(p)
            c.drawLine(padLpx, y, w - padRpx, y, pGrid)
            c.drawText(fmtP(p), w - padRpx + 3f * d, y + 3f * d, lbl)
        }
        for (i in i0..i1) {
            val k = v[i]; val up = k.c >= k.o; val col = if (up) AppTheme.OK else AppTheme.RED
            val x = xf(i); pWick.color = col; c.drawLine(x, yP(k.h), x, yP(k.l), pWick)
            pFill.color = col; var t = yP(maxOf(k.o, k.c)); var b = yP(minOf(k.o, k.c)); if (b - t < 1f * d) { t -= 0.5f * d; b += 0.5f * d }
            c.drawRect(x - medio, t, x + medio, b, pFill)
        }
        if (showBB) {
            val bb = Indicadores.bollinger(closes, 20, 2f)
            linea(c, bb.alta, alfa(AppTheme.BLUE, 0xCC), i0, i1, xf, yP, true)
            linea(c, bb.media, alfa(AppTheme.BLUE, 0x88), i0, i1, xf, yP)
            linea(c, bb.baja, alfa(AppTheme.BLUE, 0xCC), i0, i1, xf, yP, true)
        }
        if (modoMedia != 0) {
            val m1 = if (modoMedia == 1) Indicadores.sma(closes, maP1) else Indicadores.ema(closes, maP1)
            val m2 = if (modoMedia == 1) Indicadores.sma(closes, maP2) else Indicadores.ema(closes, maP2)
            linea(c, m1, AppTheme.WARN, i0, i1, xf, yP); linea(c, m2, AppTheme.ACCENT2, i0, i1, xf, yP)
            c.drawText((if (modoMedia == 1) "MA" else "EMA") + " $maP1/$maP2", padLpx, pTop + 9f * d, lbl)
        }

        // ── VOLUMEN ──
        if (showVol) {
            val vTop = y0; val vBot = y0 + subH; y0 = vBot + gap
            var mv = 0f; for (i in i0..i1) if (v[i].vol > mv) mv = v[i].vol; if (mv <= 0f) mv = 1f
            for (i in i0..i1) { val k = v[i]; pFill.color = alfa(if (k.c >= k.o) AppTheme.OK else AppTheme.RED, 0x66)
                val bh = (vBot - vTop) * (k.vol / mv); c.drawRect(xf(i) - medio, vBot - bh, xf(i) + medio, vBot, pFill) }
            c.drawText("Vol", padLpx, vTop + 8f * d, lbl)
        }
        // ── RSI ──
        if (showRsi) {
            val rTop = y0; val rBot = y0 + subH; y0 = rBot + gap
            val rsi = Indicadores.rsi(closes, 14); val yR = { r: Float -> rBot - (rBot - rTop) * (r / 100f) }
            c.drawLine(padLpx, yR(30f), w - padRpx, yR(30f), pGuide); c.drawLine(padLpx, yR(70f), w - padRpx, yR(70f), pGuide)
            linea(c, rsi, AppTheme.WARN, i0, i1, xf, yR); c.drawText("RSI 14", padLpx, rTop + 8f * d, lbl)
        }
        // ── MACD ──
        if (showMacd) {
            val mTop = y0; val mBot = y0 + subH
            val mac = Indicadores.macd(closes)
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (i in i0..i1) for (x in floatArrayOf(mac.macd[i], mac.signal[i], mac.hist[i])) if (!x.isNaN()) { if (x < lo) lo = x; if (x > hi) hi = x }
            if (lo == Float.MAX_VALUE) { lo = -1f; hi = 1f }; if (hi <= lo) hi = lo + 1f
            val yM = { m: Float -> mBot - (mBot - mTop) * (m - lo) / (hi - lo) }; val zy = yM(0f)
            for (i in i0..i1) { val hgt = mac.hist[i]; if (hgt.isNaN()) continue
                pFill.color = alfa(if (hgt >= 0) AppTheme.OK else AppTheme.RED, 0x88)
                c.drawRect(xf(i) - medio, minOf(zy, yM(hgt)), xf(i) + medio, maxOf(zy, yM(hgt)), pFill) }
            linea(c, mac.macd, AppTheme.WARN, i0, i1, xf, yM); linea(c, mac.signal, AppTheme.ACCENT2, i0, i1, xf, yM)
            c.drawText("MACD", padLpx, mTop + 8f * d, lbl)
        }

        // ── Eje de tiempo (abajo) ──
        val yT = h - padBpx
        val pasos = 4
        for (g in 0..pasos) {
            val idx = (desde + cuantas * g / pasos).toInt().coerceIn(0, n - 1)
            val x = xf(idx)
            val txt = fmtT(v[idx].t)
            val tw = lbl.measureText(txt)
            val xt = x.coerceIn(padLpx, w - padRpx - tw)
            c.drawText(txt, xt, yT + 10f * d, lbl)
        }
    }
}
