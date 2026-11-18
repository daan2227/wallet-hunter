package com.hunter.btc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*

/**
 * Libro de órdenes y operaciones en vivo del activo del gráfico, como en un
 * exchange: asks arriba (rojo), precio y spread en medio, bids abajo (verde), con
 * barras de profundidad acumulada y agrupación por tick. Debajo, la proporción
 * compra/venta. Tocar un precio lo pasa como precio límite al simulador.
 * Datos: Binance (par contra USDT) o Bitstamp para BTC; se refresca cada 1,5 s
 * mientras se ve. La bolsa tradicional no publica libro gratis: se avisa.
 */
class PanelLibro(private val act: MainActivity, private val activo: () -> Mercado.Activo,
                 private val alElegirPrecio: (Double) -> Unit) {

    private fun dp(v: Int) = act.dp(v)
    private val prefs = act.getSharedPreferences("home_chart", Context.MODE_PRIVATE)
    private var pestanaTrades = prefs.getBoolean("libro_trades", false)
    private var nivelTick = prefs.getInt("libro_tick", 0)

    val vista = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(16), 0, 0) }
    private val tvEstado = TextView(act).apply { textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED); setPadding(dp(2), dp(6), 0, 0) }
    private val libroV = VistaLibro(act)
    private val tradesV = VistaTrades(act)
    private val filaTick = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }

    private var libro: Mercado.Libro? = null
    private var trades: List<Mercado.Trade> = emptyList()
    private var claveDatos = ""
    private val ocupado = java.util.concurrent.atomic.AtomicBoolean(false)
    private val h = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        vista.addView(TextView(act).apply {
            text = "MARKET DEPTH"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.bold(act)
            letterSpacing = 0.08f; setPadding(dp(2), 0, 0, dp(8))
        })
        vista.addView(Ui.segmented(act, listOf("Order book" to null, "Trades" to null), if (pestanaTrades) 1 else 0) { k ->
            pestanaTrades = k == 1; prefs.edit().putBoolean("libro_trades", pestanaTrades).apply(); mostrar(); pedir()
        }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } })
        vista.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(filaTick)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) } })
        vista.addView(libroV); vista.addView(tradesV); vista.addView(tvEstado)
        libroV.alTocar = { p -> alElegirPrecio(p) }
        mostrar()
        h.postDelayed(object : Runnable {
            override fun run() {
                if (!vista.isAttachedToWindow && claveDatos.isNotEmpty()) return
                if (vista.isShown) pedir()
                h.postDelayed(this, 1_500)
            }
        }, 800)
    }

    private fun mostrar() {
        libroV.visibility = if (pestanaTrades) View.GONE else View.VISIBLE
        tradesV.visibility = if (pestanaTrades) View.VISIBLE else View.GONE
        filaTick.visibility = if (pestanaTrades) View.GONE else View.VISIBLE
    }

    /** Tamaños de agrupación según el precio (p. ej. BTC: 0.01, 0.1, 1, 10, 100). */
    private fun ticks(precio: Double): List<Double> {
        if (precio <= 0) return listOf(0.0)
        val base = Math.pow(10.0, Math.floor(Math.log10(precio)) - 6)
        return (0..4).map { base * Math.pow(10.0, it.toDouble()) }
    }
    private fun fmtTick(t: Double) = if (t >= 1) "%.0f".format(t) else java.math.BigDecimal(t).round(java.math.MathContext(1)).stripTrailingZeros().toPlainString()

    private fun pintarTicks(precio: Double) {
        val ts = ticks(precio)
        if (filaTick.childCount == ts.size && filaTick.tag == fmtTick(ts[0])) return
        filaTick.removeAllViews(); filaTick.tag = fmtTick(ts[0])
        nivelTick = nivelTick.coerceIn(0, ts.size - 1)
        filaTick.addView(TextView(act).apply { text = "Group"; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED); setPadding(dp(2), 0, dp(8), 0) })
        ts.forEachIndexed { k, t ->
            filaTick.addView(TextView(act).apply {
                text = fmtTick(t); textSize = AppTheme.SP_MICRO; gravity = Gravity.CENTER; typeface = AppTheme.mono(act)
                setPadding(dp(9), dp(4), dp(9), dp(4)); isClickable = true; foreground = Ui.toque()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(5) }
                val on = k == nivelTick
                background = Ui.cardBg(AppTheme.R_CHIP, if (on) AppTheme.BG_KEY else AppTheme.BG_CARD, act)
                setTextColor(if (on) AppTheme.TXT_PRI else AppTheme.TXT_SEC)
                setOnClickListener { nivelTick = k; prefs.edit().putInt("libro_tick", k).apply(); filaTick.tag = null; pintarTicks(precio); aplicar() }
            })
        }
    }

    /** El gráfico cambió de activo. */
    fun alCambiarActivo() { libro = null; trades = emptyList(); claveDatos = ""; libroV.poner(null, 0.0, 0.0); tradesV.poner(emptyList()); pedir() }

    fun pedir() {
        val a = activo()
        if (a.tipo != Mercado.Tipo.CRYPTO) {
            tvEstado.text = "Order book and trades are only available for crypto (stock exchanges don't publish them for free)."
            libroV.poner(null, 0.0, 0.0); tradesV.poner(emptyList()); return
        }
        if (!ocupado.compareAndSet(false, true)) return
        val verTrades = pestanaTrades
        Thread {
            val l = if (!verTrades) Mercado.libro(a, 100) else null
            val tr = Mercado.trades(a, if (verTrades) 40 else 1)
            act.runOnUiThread {
                ocupado.set(false)
                if (a.clave != activo().clave) return@runOnUiThread
                claveDatos = a.clave
                if (!verTrades) libro = l
                if (tr.isNotEmpty()) trades = if (verTrades) tr else (tr + trades).distinctBy { it.t to it.precio }.take(40)
                aplicar()
                val fuente = libro?.fuente ?: if (tr.isNotEmpty()) "Binance" else ""
                tvEstado.text = when {
                    !verTrades && l == null -> "Order book unavailable for ${a.simbolo}/USDT (not listed on Binance or source unreachable)."
                    verTrades && tr.isEmpty() -> "Trades unavailable for ${a.simbolo}/USDT (not listed on Binance or source unreachable)."
                    else -> "Live · $fuente ${a.simbolo}/USDT · refreshes every 1.5 s" + if (!verTrades) " · tap a price to use it as limit price" else ""
                }
            }
        }.start()
    }

    private fun aplicar() {
        val l = libro
        val ultimo = trades.firstOrNull()?.precio ?: 0.0
        val previo = trades.getOrNull(1)?.precio ?: ultimo
        if (l != null) {
            val ref = l.bids.firstOrNull()?.first ?: l.asks.firstOrNull()?.first ?: 0.0
            pintarTicks(ref)
            val tick = ticks(ref)[nivelTick.coerceIn(0, 4)]
            libroV.poner(Mercado.Libro(agrupar(l.bids, tick, false), agrupar(l.asks, tick, true), l.fuente), ultimo, previo)
        }
        tradesV.poner(trades)
    }

    private fun agrupar(n: List<Pair<Double, Double>>, tick: Double, arriba: Boolean): List<Pair<Double, Double>> {
        if (tick <= 0) return n
        val m = LinkedHashMap<Long, Double>()
        for ((p, q) in n) { val k = if (arriba) Math.ceil(p / tick - 1e-9).toLong() else Math.floor(p / tick + 1e-9).toLong(); m[k] = (m[k] ?: 0.0) + q }
        return m.map { (k, q) -> k * tick to q }
    }
}

/** Formato compartido del libro y los trades. */
private fun fmtLibroPrecio(p: Double) = when { p >= 1000 -> "%,.2f".format(p); p >= 1 -> "%.4f".format(p); else -> "%.6g".format(p) }
private fun fmtLibroQty(q: Double) = when { q >= 1_000_000 -> "%.2fM".format(q / 1e6); q >= 1000 -> "%.2fK".format(q / 1e3); q >= 1 -> "%.4f".format(q); else -> "%.5f".format(q) }

/** Libro dibujado en Canvas: 10 asks, precio + spread, 10 bids, proporción compra/venta. */
class VistaLibro(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val filas = 10
    private val altoFila = 19f * d
    private var libro: Mercado.Libro? = null
    private var ultimo = 0.0; private var previo = 0.0
    var alTocar: ((Double) -> Unit)? = null
    // filas tocables: (yArriba, yAbajo, precio)
    private val zonas = ArrayList<Triple<Float, Float, Double>>()

    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * d; typeface = AppTheme.mono(ctx) }
    private val pCab = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * d; color = AppTheme.TXT_MUTED; typeface = AppTheme.body(ctx) }
    private val pBarra = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pGrande = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17f * d; typeface = AppTheme.bold(ctx) }

    fun poner(l: Mercado.Libro?, ult: Double, prev: Double) { libro = l; ultimo = ult; previo = prev; invalidate() }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (altoFila * (2 * filas + 1) + 18f * d + 34f * d + 26f * d).toInt())

    private fun alfa(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val l = libro
        zonas.clear()
        // cabecera
        val yCab = 12f * d
        pCab.textAlign = Paint.Align.LEFT; c.drawText("Price (USDT)", 0f, yCab, pCab)
        pCab.textAlign = Paint.Align.RIGHT; c.drawText("Amount", w * 0.66f, yCab, pCab); c.drawText("Total", w, yCab, pCab)
        if (l == null) { pCab.textAlign = Paint.Align.LEFT; c.drawText("Loading…", 0f, yCab + 30f * d, pCab); return }
        val asks = l.asks.take(filas); val bids = l.bids.take(filas)
        val acA = asks.runningFold(0.0) { s, x -> s + x.second }.drop(1)
        val acB = bids.runningFold(0.0) { s, x -> s + x.second }.drop(1)
        val max = maxOf(acA.lastOrNull() ?: 0.0, acB.lastOrNull() ?: 0.0).coerceAtLeast(1e-18)
        var y = 18f * d
        fun fila(p: Double, q: Double, ac: Double, color: Int) {
            pBarra.color = alfa(color, 0x26); c.drawRect(w - (w * (ac / max)).toFloat(), y + 1f * d, w, y + altoFila - 1f * d, pBarra)
            val yt = y + altoFila * 0.7f
            pTxt.color = color; pTxt.textAlign = Paint.Align.LEFT; c.drawText(fmtLibroPrecio(p), 0f, yt, pTxt)
            pTxt.color = AppTheme.TXT_PRI; pTxt.textAlign = Paint.Align.RIGHT; c.drawText(fmtLibroQty(q), w * 0.66f, yt, pTxt)
            pTxt.color = AppTheme.TXT_SEC; c.drawText(fmtLibroQty(ac), w, yt, pTxt)
            zonas.add(Triple(y, y + altoFila, p)); y += altoFila
        }
        // asks de más caro a más barato (el mejor justo encima del precio)
        val vacias = filas - asks.size; y += vacias * altoFila
        for (i in asks.indices.reversed()) fila(asks[i].first, asks[i].second, acA[i], AppTheme.RED)
        // precio y spread
        val mejorA = asks.firstOrNull()?.first; val mejorB = bids.firstOrNull()?.first
        val ref = if (ultimo > 0) ultimo else if (mejorA != null && mejorB != null) (mejorA + mejorB) / 2 else 0.0
        pGrande.color = if (ultimo >= previo) AppTheme.OK else AppTheme.RED; pGrande.textAlign = Paint.Align.LEFT
        val ym = y + 34f * d * 0.62f
        c.drawText(fmtLibroPrecio(ref) + if (ultimo > 0) (if (ultimo >= previo) " ↑" else " ↓") else "", 0f, ym, pGrande)
        if (mejorA != null && mejorB != null) {
            val sp = mejorA - mejorB
            pCab.textAlign = Paint.Align.RIGHT
            c.drawText("Spread %s (%.3f%%)".format(fmtLibroPrecio(sp), if (mejorB > 0) sp / mejorB * 100 else 0.0), w, ym, pCab)
        }
        y += 34f * d
        for (i in bids.indices) fila(bids[i].first, bids[i].second, acB[i], AppTheme.OK)
        y += (filas - bids.size) * altoFila
        // proporción compra/venta en lo visible
        val sb = acB.lastOrNull() ?: 0.0; val sa = acA.lastOrNull() ?: 0.0
        if (sa + sb > 0) {
            val fb = (sb / (sa + sb)).toFloat(); val yb = y + 8f * d; val alto = 14f * d
            pBarra.color = alfa(AppTheme.OK, 0x55); c.drawRect(0f, yb, w * fb, yb + alto, pBarra)
            pBarra.color = alfa(AppTheme.RED, 0x55); c.drawRect(w * fb, yb, w, yb + alto, pBarra)
            pTxt.textSize = 10f * d
            pTxt.color = AppTheme.OK; pTxt.textAlign = Paint.Align.LEFT; c.drawText("B %.0f%%".format(fb * 100), 4f * d, yb + alto * 0.75f, pTxt)
            pTxt.color = AppTheme.RED; pTxt.textAlign = Paint.Align.RIGHT; c.drawText("%.0f%% S".format((1 - fb) * 100), w - 4f * d, yb + alto * 0.75f, pTxt)
            pTxt.textSize = 11f * d
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            zonas.firstOrNull { e.y >= it.first && e.y < it.second }?.let { alTocar?.invoke(it.third); performClick() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Operaciones recientes: hora, precio (verde compra / rojo venta) y cantidad. */
class VistaTrades(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val filas = 20
    private val altoFila = 18f * d
    private var datos: List<Mercado.Trade> = emptyList()
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * d; typeface = AppTheme.mono(ctx) }
    private val pCab = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * d; color = AppTheme.TXT_MUTED; typeface = AppTheme.body(ctx) }
    private val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    fun poner(t: List<Mercado.Trade>) { datos = t; invalidate() }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (altoFila * filas + 18f * d).toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        pCab.textAlign = Paint.Align.LEFT; c.drawText("Time", 0f, 12f * d, pCab)
        pCab.textAlign = Paint.Align.RIGHT; c.drawText("Price (USDT)", w * 0.62f, 12f * d, pCab); c.drawText("Amount", w, 12f * d, pCab)
        if (datos.isEmpty()) { pCab.textAlign = Paint.Align.LEFT; c.drawText("Loading…", 0f, 40f * d, pCab); return }
        var y = 18f * d
        for (t in datos.take(filas)) {
            val yt = y + altoFila * 0.7f
            pTxt.color = AppTheme.TXT_SEC; pTxt.textAlign = Paint.Align.LEFT; c.drawText(sdf.format(java.util.Date(t.t)), 0f, yt, pTxt)
            pTxt.color = if (t.compra) AppTheme.OK else AppTheme.RED; pTxt.textAlign = Paint.Align.RIGHT; c.drawText(fmtLibroPrecio(t.precio), w * 0.62f, yt, pTxt)
            pTxt.color = AppTheme.TXT_PRI; c.drawText(fmtLibroQty(t.qty), w, yt, pTxt)
            y += altoFila
        }
    }
}
