package com.hunter.btc

import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gráfico técnico de velas, movible (arrastrar = desplazar, pellizcar = zoom).
 * Panel de precio con superposiciones (3 medias móviles, Bollinger, VWAP, SAR) y
 * paneles inferiores opcionales (Volumen, RSI, MACD, Estocástico, ATR, OBV, CCI,
 * Williams %R). Eje de precio a la derecha y de tiempo abajo. Herramientas de
 * dibujo (línea horizontal, línea de tendencia, nota) ancladas a tiempo/precio,
 * así que siguen a las velas al desplazar o hacer zoom. Todo nativo sobre Canvas.
 */
class GraficoTA(ctx: Context) : View(ctx) {

    // ── Configuración (se guarda como JSON en preferencias) ──
    class Media(var on: Boolean, var ema: Boolean, var n: Int)
    class Config {
        val medias = arrayOf(Media(true, false, 7), Media(true, false, 25), Media(false, false, 99))
        var bb = false; var bbN = 20; var bbK = 2f
        var vwap = false; var sar = false
        var vol = true; var rsi = true; var rsiN = 14
        var macd = true; var mF = 12; var mS = 26; var mSig = 9
        var stoch = false; var stK = 14; var stD = 3
        var atr = false; var atrN = 14
        var obv = false; var cci = false; var cciN = 20; var wr = false; var wrN = 14

        fun aJson(): String = JSONObject().apply {
            put("medias", JSONArray().apply { medias.forEach { put(JSONObject().put("on", it.on).put("ema", it.ema).put("n", it.n)) } })
            put("bb", bb); put("bbN", bbN); put("bbK", bbK.toDouble()); put("vwap", vwap); put("sar", sar)
            put("vol", vol); put("rsi", rsi); put("rsiN", rsiN); put("macd", macd); put("mF", mF); put("mS", mS); put("mSig", mSig)
            put("stoch", stoch); put("stK", stK); put("stD", stD); put("atr", atr); put("atrN", atrN)
            put("obv", obv); put("cci", cci); put("cciN", cciN); put("wr", wr); put("wrN", wrN)
        }.toString()

        fun deJson(s: String?) {
            if (s.isNullOrEmpty()) return
            try {
                val o = JSONObject(s)
                o.optJSONArray("medias")?.let { a -> for (i in 0 until minOf(a.length(), medias.size)) { val m = a.getJSONObject(i)
                    medias[i].on = m.optBoolean("on", medias[i].on); medias[i].ema = m.optBoolean("ema", false); medias[i].n = m.optInt("n", medias[i].n) } }
                bb = o.optBoolean("bb", bb); bbN = o.optInt("bbN", bbN); bbK = o.optDouble("bbK", bbK.toDouble()).toFloat()
                vwap = o.optBoolean("vwap", vwap); sar = o.optBoolean("sar", sar)
                vol = o.optBoolean("vol", vol); rsi = o.optBoolean("rsi", rsi); rsiN = o.optInt("rsiN", rsiN)
                macd = o.optBoolean("macd", macd); mF = o.optInt("mF", mF); mS = o.optInt("mS", mS); mSig = o.optInt("mSig", mSig)
                stoch = o.optBoolean("stoch", stoch); stK = o.optInt("stK", stK); stD = o.optInt("stD", stD)
                atr = o.optBoolean("atr", atr); atrN = o.optInt("atrN", atrN)
                obv = o.optBoolean("obv", obv); cci = o.optBoolean("cci", cci); cciN = o.optInt("cciN", cciN)
                wr = o.optBoolean("wr", wr); wrN = o.optInt("wrN", wrN)
            } catch (e: Exception) { }
        }
        fun paneles(): Int = listOf(vol, rsi, macd, stoch, atr, obv, cci, wr).count { it }
    }
    val cfg = Config()

    // ── Dibujos ──
    /** tipo: 0 línea horizontal, 1 línea de tendencia, 2 nota. */
    class Dibujo(val tipo: Int, val t1: Long, val p1: Float, val t2: Long = 0, val p2: Float = 0f, val texto: String = "")
    val dibujos = ArrayList<Dibujo>()
    /** 0 mover, 1 línea horizontal, 2 tendencia, 3 nota. */
    var herramienta = 0
    private var pendiente: Pair<Long, Float>? = null       // primer punto de la tendencia
    var alPedirNota: ((Long, Float) -> Unit)? = null
    var alCambiarDibujos: (() -> Unit)? = null

    fun dibujosJson(): String = JSONArray().apply { dibujos.forEach { put(JSONObject().put("tipo", it.tipo).put("t1", it.t1).put("p1", it.p1.toDouble())
        .put("t2", it.t2).put("p2", it.p2.toDouble()).put("tx", it.texto)) } }.toString()
    fun cargarDibujos(s: String?) {
        dibujos.clear(); if (s.isNullOrEmpty()) return
        try { val a = JSONArray(s); for (i in 0 until a.length()) { val o = a.getJSONObject(i)
            dibujos.add(Dibujo(o.optInt("tipo"), o.optLong("t1"), o.optDouble("p1").toFloat(), o.optLong("t2"), o.optDouble("p2").toFloat(), o.optString("tx"))) } } catch (e: Exception) { }
    }
    fun agregarNota(t: Long, p: Float, texto: String) { if (texto.isNotBlank()) { dibujos.add(Dibujo(2, t, p, texto = texto.trim().take(60))); alCambiarDibujos?.invoke(); invalidate() } }
    fun deshacer() { if (dibujos.isNotEmpty()) { dibujos.removeAt(dibujos.size - 1); alCambiarDibujos?.invoke(); invalidate() } }
    fun borrarTodo() { dibujos.clear(); pendiente = null; alCambiarDibujos?.invoke(); invalidate() }

    // ── Datos y ventana visible ──
    private var velas: List<GraficoPrecio.Vela> = emptyList()
    var intervaloSeg = 3600L
    private val d = resources.displayMetrics.density
    private val padLpx = 2f * d
    private val padRpx = 56f * d
    private val padTpx = 6f * d
    private val padBpx = 16f * d
    private val alturaPrecio = 240f * d
    private val alturaPanel = 78f * d
    private val gapPx = 8f * d
    private var desde = 0f
    private var cuantas = 90f

    // Mapeo del último frame (para convertir un toque en tiempo/precio).
    private var mapPTop = 0f; private var mapPBot = 0f; private var mapMin = 0f; private var mapMax = 1f

    fun poner(v: List<GraficoPrecio.Vela>) {
        velas = v
        cuantas = minOf(90f, v.size.toFloat()).coerceAtLeast(2f)
        desde = (v.size - cuantas).coerceAtLeast(0f)
        pendiente = null
        requestLayout(); invalidate()
    }

    /** Refresco en vivo: cambia los datos SIN resetear el zoom/desplazamiento. */
    fun actualizar(v: List<GraficoPrecio.Vela>, quitadasAlInicio: Int = 0) {
        val alFinal = velas.isEmpty() || desde >= velas.size - cuantas - 0.5f
        velas = v
        if (alFinal) desde = (v.size - cuantas).coerceAtLeast(0f)
        else desde -= quitadasAlInicio      // mantener las mismas velas a la vista
        clamp(); invalidate()
    }
    fun velasActuales(): List<GraficoPrecio.Vela> = velas
    fun refrescarConfig() { requestLayout(); invalidate() }

    private fun clamp() {
        val n = velas.size.toFloat()
        cuantas = cuantas.coerceIn(8f, n.coerceAtLeast(8f))
        desde = desde.coerceIn(0f, (n - cuantas).coerceAtLeast(0f))
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = View.MeasureSpec.getSize(wSpec)
        val p = cfg.paneles()
        val h = (padTpx + alturaPrecio + p * (alturaPanel + gapPx) + gapPx + 14f * d + padBpx).toInt()
        setMeasuredDimension(w, h)
    }

    // ── Tiempo ↔ índice ──
    private fun idxDeT(t: Long): Float {
        val v = velas; if (v.isEmpty()) return 0f
        if (t <= v[0].t) return (t - v[0].t).toFloat() / intervaloSeg
        if (t >= v.last().t) return v.size - 1 + (t - v.last().t).toFloat() / intervaloSeg
        var lo = 0; var hi = v.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (v[m].t <= t) lo = m else hi = m }
        val span = (v[hi].t - v[lo].t).coerceAtLeast(1)
        return lo + (t - v[lo].t).toFloat() / span
    }
    private fun tDeIdx(idx: Float): Long {
        val v = velas; if (v.isEmpty()) return 0
        if (idx <= 0f) return v[0].t + (idx * intervaloSeg).toLong()
        if (idx >= v.size - 1) return v.last().t + ((idx - (v.size - 1)) * intervaloSeg).toLong()
        val i = idx.toInt(); return v[i].t + ((idx - i) * (v[i + 1].t - v[i].t)).toLong()
    }

    // ── Gestos ──
    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val gw = width - padLpx - padRpx; if (velas.size < 2 || gw <= 0) return false
            desde += dx / (gw / cuantas); clamp(); invalidate(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (herramienta == 0 || velas.size < 2) return false
            if (e.y < mapPTop || e.y > mapPBot) return false
            val gw = width - padLpx - padRpx
            val idx = desde + cuantas * (e.x - padLpx) / gw
            val t = tDeIdx(idx)
            val p = mapMin + (mapMax - mapMin) * (mapPBot - e.y) / (mapPBot - mapPTop)
            when (herramienta) {
                1 -> { dibujos.add(Dibujo(0, t, p)); alCambiarDibujos?.invoke() }
                2 -> { val a = pendiente
                    if (a == null) pendiente = t to p
                    else { dibujos.add(Dibujo(1, a.first, a.second, t, p)); pendiente = null; alCambiarDibujos?.invoke() } }
                3 -> alPedirNota?.invoke(t, p)
            }
            invalidate(); return true
        }
    })
    private val sd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val gw = width - padLpx - padRpx; if (velas.size < 2 || gw <= 0) return false
            val fx = (det.focusX - padLpx) / gw
            val centro = desde + cuantas * fx
            cuantas = (cuantas / det.scaleFactor).coerceIn(8f, velas.size.toFloat())
            desde = centro - cuantas * fx; clamp(); invalidate(); return true
        }
    })
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        sd.onTouchEvent(e); gd.onTouchEvent(e); return true
    }

    // ── Pinceles ──
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pWick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f * d; style = Paint.Style.STROKE }
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.4f * d; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val pGuide = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AppTheme.TXT_MUTED; strokeWidth = 0.8f * d; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(4f * d, 4f * d), 0f) }
    private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (AppTheme.TXT_MUTED and 0x00FFFFFF) or 0x24000000; strokeWidth = 1f * d }
    private val lbl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AppTheme.TXT_MUTED; textSize = 9f * d; typeface = AppTheme.mono(ctx) }
    private val pDib = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AppTheme.ACCENT; strokeWidth = 1.6f * d; style = Paint.Style.STROKE }
    private val pNota = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AppTheme.TXT_PRI; textSize = 10.5f * d; typeface = AppTheme.bold(ctx) }
    private val pNotaBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pUltimo = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f * d; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(3f * d, 3f * d), 0f) }

    private fun alfa(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)
    private fun fmtP(v: Float) = when { Math.abs(v) >= 1_000_000f -> "%.2fM".format(v / 1e6f); Math.abs(v) >= 1000f -> "%,.0f".format(v); Math.abs(v) >= 1f -> "%.2f".format(v); else -> "%.4f".format(v) }
    private val sdfIntra = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
    private val sdfSeg = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    private val sdfDia = java.text.SimpleDateFormat("dd/MM", java.util.Locale.US)
    private val sdfMes = java.text.SimpleDateFormat("MM/yy", java.util.Locale.US)
    private fun fmtT(t: Long): String {
        val dt = java.util.Date(t * 1000)
        return when { intervaloSeg < 60L -> sdfSeg.format(dt); intervaloSeg < 86_400L -> sdfIntra.format(dt); intervaloSeg < 2_592_000L -> sdfDia.format(dt); else -> sdfMes.format(dt) }
    }
    private val coloresMedia = intArrayOf(0xFFF5A524.toInt(), 0xFF38BDF8.toInt(), 0xFFC084FC.toInt())

    private fun linea(c: Canvas, s: FloatArray, color: Int, i0: Int, i1: Int, xf: (Int) -> Float, yf: (Float) -> Float, dash: Boolean = false) {
        pLine.color = color; pLine.pathEffect = if (dash) DashPathEffect(floatArrayOf(5f * d, 4f * d), 0f) else null
        val path = Path(); var started = false
        for (i in i0..i1) { if (i < 0 || i >= s.size) continue; val vv = s[i]; if (vv.isNaN()) { started = false; continue }
            val x = xf(i); val y = yf(vv); if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y) }
        c.drawPath(path, pLine); pLine.pathEffect = null
    }

    /** Un panel inferior con su serie(s), escalado a su min..max visible (o a [fijo]). */
    private fun panel(c: Canvas, top: Float, bot: Float, titulo: String, i0: Int, i1: Int, xf: (Int) -> Float, w: Float,
                      series: List<Pair<FloatArray, Int>>, fijo: Pair<Float, Float>? = null, guias: List<Float> = emptyList(),
                      histo: FloatArray? = null) {
        var lo: Float; var hi: Float
        if (fijo != null) { lo = fijo.first; hi = fijo.second } else {
            lo = Float.MAX_VALUE; hi = -Float.MAX_VALUE
            for (i in i0..i1) { for ((s, _) in series) { val x = s[i]; if (!x.isNaN()) { if (x < lo) lo = x; if (x > hi) hi = x } }
                histo?.let { val x = it[i]; if (!x.isNaN()) { if (x < lo) lo = x; if (x > hi) hi = x } } }
            if (lo == Float.MAX_VALUE) { lo = 0f; hi = 1f }; if (hi <= lo) hi = lo + 1f
        }
        val y = { v: Float -> bot - (bot - top) * (v - lo) / (hi - lo) }
        c.drawLine(padLpx, bot, w - padRpx, bot, pGrid)
        for (g in guias) c.drawLine(padLpx, y(g), w - padRpx, y(g), pGuide)
        if (histo != null) {
            val zy = y(0f.coerceIn(lo, hi)); val medio = ((w - padLpx - padRpx) / cuantas * 0.34f).coerceIn(0.5f * d, 7f * d)
            for (i in i0..i1) { val hv = histo[i]; if (hv.isNaN()) continue
                pFill.color = alfa(if (hv >= 0) AppTheme.OK else AppTheme.RED, 0x88)
                c.drawRect(xf(i) - medio, minOf(zy, y(hv)), xf(i) + medio, maxOf(zy, y(hv)), pFill) }
        }
        for ((s, col) in series) linea(c, s, col, i0, i1, xf, y)
        c.drawText(titulo, padLpx, top + 9f * d, lbl)
        c.drawText(fmtP(hi), w - padRpx + 3f * d, top + 8f * d, lbl)
        c.drawText(fmtP(lo), w - padRpx + 3f * d, bot, lbl)
    }

    override fun onDraw(c: Canvas) {
        val v = velas
        if (v.size < 2) { lbl.textSize = 12f * d; c.drawText("Loading candles…", padLpx + 8f * d, 40f * d, lbl); lbl.textSize = 9f * d; return }
        clamp()
        val n = v.size
        val op = FloatArray(n) { v[it].o }; val hi = FloatArray(n) { v[it].h }; val lo = FloatArray(n) { v[it].l }
        val cl = FloatArray(n) { v[it].c }; val vo = FloatArray(n) { v[it].vol }
        val w = width.toFloat()
        val gw = w - padLpx - padRpx
        val i0 = Math.floor(desde.toDouble()).toInt().coerceIn(0, n - 1)
        val i1 = Math.ceil((desde + cuantas).toDouble()).toInt().coerceIn(0, n - 1)
        val xf = { i: Int -> padLpx + gw * (i - desde) / cuantas }
        val xfF = { f: Float -> padLpx + gw * (f - desde) / cuantas }
        val medio = (gw / cuantas * 0.34f).coerceIn(0.5f * d, 7f * d)

        // ── PRECIO ──
        val pTop = padTpx; val pBot = pTop + alturaPrecio
        var minL = Float.MAX_VALUE; var maxH = -Float.MAX_VALUE
        for (i in i0..i1) { if (lo[i] < minL) minL = lo[i]; if (hi[i] > maxH) maxH = hi[i] }
        if (maxH <= minL) maxH = minL + 1f
        val margen = (maxH - minL) * 0.04f; minL -= margen; maxH += margen
        mapPTop = pTop; mapPBot = pBot; mapMin = minL; mapMax = maxH
        val yP = { p: Float -> pBot - (pBot - pTop) * (p - minL) / (maxH - minL) }

        c.save(); c.clipRect(0f, pTop, w - padRpx, pBot)
        for (g in 0..4) { val p = minL + (maxH - minL) * g / 4; c.drawLine(padLpx, yP(p), w - padRpx, yP(p), pGrid) }
        for (i in i0..i1) {
            val up = cl[i] >= op[i]; val col = if (up) AppTheme.OK else AppTheme.RED
            val x = xf(i); pWick.color = col; c.drawLine(x, yP(hi[i]), x, yP(lo[i]), pWick)
            pFill.color = col; var t = yP(maxOf(op[i], cl[i])); var b = yP(minOf(op[i], cl[i])); if (b - t < 1f * d) { t -= 0.5f * d; b += 0.5f * d }
            c.drawRect(x - medio, t, x + medio, b, pFill)
        }
        if (cfg.bb) { val bb = Indicadores.bollinger(cl, cfg.bbN, cfg.bbK)
            linea(c, bb.alta, alfa(AppTheme.BLUE, 0xCC), i0, i1, xf, yP, true); linea(c, bb.media, alfa(AppTheme.BLUE, 0x88), i0, i1, xf, yP)
            linea(c, bb.baja, alfa(AppTheme.BLUE, 0xCC), i0, i1, xf, yP, true) }
        if (cfg.vwap) linea(c, Indicadores.vwap(hi, lo, cl, vo), 0xFFE879F9.toInt(), i0, i1, xf, yP)
        val etiquetas = StringBuilder()
        cfg.medias.forEachIndexed { k, m -> if (m.on && m.n in 2..n) {
            linea(c, if (m.ema) Indicadores.ema(cl, m.n) else Indicadores.sma(cl, m.n), coloresMedia[k], i0, i1, xf, yP)
            etiquetas.append(if (m.ema) "EMA" else "MA").append(m.n).append("  ") } }
        if (cfg.sar) { val s = Indicadores.sar(hi, lo); pFill.color = 0xFFE2E8F0.toInt()
            for (i in i0..i1) if (!s[i].isNaN()) c.drawCircle(xf(i), yP(s[i]), 1.6f * d, pFill) }
        // dibujos
        for (dj in dibujos) when (dj.tipo) {
            0 -> { val y = yP(dj.p1); c.drawLine(padLpx, y, w - padRpx, y, pDib) }
            1 -> c.drawLine(xfF(idxDeT(dj.t1)), yP(dj.p1), xfF(idxDeT(dj.t2)), yP(dj.p2), pDib)
            2 -> { val x = xfF(idxDeT(dj.t1)); val y = yP(dj.p1); val tw = pNota.measureText(dj.texto)
                pNotaBg.color = alfa(AppTheme.BG_ELEV, 0xE6)
                c.drawRoundRect(x - 3f * d, y - 13f * d, x + tw + 5f * d, y + 4f * d, 4f * d, 4f * d, pNotaBg)
                pFill.color = AppTheme.ACCENT; c.drawCircle(x - 3f * d, y, 2.5f * d, pFill)
                c.drawText(dj.texto, x + 1f * d, y, pNota) }
        }
        pendiente?.let { (t, p) -> pFill.color = AppTheme.ACCENT; c.drawCircle(xfF(idxDeT(t)), yP(p), 4f * d, pFill) }
        c.restore()

        // eje de precio + línea del último precio + valores de dibujos horizontales
        for (g in 0..4) { val p = minL + (maxH - minL) * g / 4; c.drawText(fmtP(p), w - padRpx + 3f * d, yP(p) + 3f * d, lbl) }
        val ult = cl[n - 1]; if (ult in minL..maxH) {
            val col = if (cl[n - 1] >= op[n - 1]) AppTheme.OK else AppTheme.RED
            pUltimo.color = col; c.drawLine(padLpx, yP(ult), w - padRpx, yP(ult), pUltimo)
            pFill.color = col; val ty = yP(ult)
            c.drawRect(w - padRpx + 1f * d, ty - 7f * d, w - 1f * d, ty + 6f * d, pFill)
            lbl.color = 0xFFFFFFFF.toInt(); c.drawText(fmtP(ult), w - padRpx + 3f * d, ty + 3f * d, lbl); lbl.color = AppTheme.TXT_MUTED
        }
        for (dj in dibujos) if (dj.tipo == 0 && dj.p1 in minL..maxH) { lbl.color = AppTheme.ACCENT; c.drawText(fmtP(dj.p1), padLpx + 2f * d, yP(dj.p1) - 3f * d, lbl); lbl.color = AppTheme.TXT_MUTED }
        if (etiquetas.isNotEmpty()) c.drawText(etiquetas.toString().trim(), padLpx + 2f * d, pTop + 10f * d, lbl)

        // ── PANELES ──
        var y0 = pBot + gapPx
        fun sig(): Pair<Float, Float> { val t = y0; val b = y0 + alturaPanel; y0 = b + gapPx; return t to b }
        if (cfg.vol) { val (t, b) = sig()
            var mv = 0f; for (i in i0..i1) if (vo[i] > mv) mv = vo[i]; if (mv <= 0f) mv = 1f
            for (i in i0..i1) { pFill.color = alfa(if (cl[i] >= op[i]) AppTheme.OK else AppTheme.RED, 0x66)
                val bh = (b - t) * (vo[i] / mv); c.drawRect(xf(i) - medio, b - bh, xf(i) + medio, b, pFill) }
            c.drawText("Volume", padLpx, t + 9f * d, lbl); c.drawText(fmtP(mv), w - padRpx + 3f * d, t + 8f * d, lbl); c.drawLine(padLpx, b, w - padRpx, b, pGrid) }
        if (cfg.rsi) { val (t, b) = sig(); panel(c, t, b, "RSI ${cfg.rsiN}", i0, i1, xf, w, listOf(Indicadores.rsi(cl, cfg.rsiN) to AppTheme.WARN), 0f to 100f, listOf(30f, 70f)) }
        if (cfg.macd) { val (t, b) = sig(); val m = Indicadores.macd(cl, cfg.mF, cfg.mS, cfg.mSig)
            panel(c, t, b, "MACD ${cfg.mF},${cfg.mS},${cfg.mSig}", i0, i1, xf, w, listOf(m.macd to AppTheme.WARN, m.signal to AppTheme.ACCENT2), histo = m.hist) }
        if (cfg.stoch) { val (t, b) = sig(); val s = Indicadores.estocastico(hi, lo, cl, cfg.stK, cfg.stD)
            panel(c, t, b, "Stoch ${cfg.stK},${cfg.stD}", i0, i1, xf, w, listOf(s.k to AppTheme.WARN, s.d to AppTheme.ACCENT2), 0f to 100f, listOf(20f, 80f)) }
        if (cfg.atr) { val (t, b) = sig(); panel(c, t, b, "ATR ${cfg.atrN}", i0, i1, xf, w, listOf(Indicadores.atr(hi, lo, cl, cfg.atrN) to AppTheme.WARN)) }
        if (cfg.obv) { val (t, b) = sig(); panel(c, t, b, "OBV", i0, i1, xf, w, listOf(Indicadores.obv(cl, vo) to AppTheme.ACCENT2)) }
        if (cfg.cci) { val (t, b) = sig(); panel(c, t, b, "CCI ${cfg.cciN}", i0, i1, xf, w, listOf(Indicadores.cci(hi, lo, cl, cfg.cciN) to AppTheme.WARN), guias = listOf(-100f, 100f)) }
        if (cfg.wr) { val (t, b) = sig(); panel(c, t, b, "Williams %R ${cfg.wrN}", i0, i1, xf, w, listOf(Indicadores.williams(hi, lo, cl, cfg.wrN) to AppTheme.WARN), -100f to 0f, listOf(-80f, -20f)) }

        // ── Eje de tiempo ──
        val yT = y0 + 10f * d
        for (g in 0..4) {
            val idx = (desde + cuantas * g / 4).toInt().coerceIn(0, n - 1)
            val txt = fmtT(v[idx].t); val tw = lbl.measureText(txt)
            c.drawText(txt, xf(idx).coerceIn(padLpx, w - padRpx - tw), yT, lbl)
        }
    }
}
