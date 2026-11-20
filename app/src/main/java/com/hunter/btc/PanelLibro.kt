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
 * Libro de órdenes compacto (columna derecha del DEx, como en los exchanges):
 * funding y cuenta atrás (futuros), asks en rojo, último precio grande, bids en
 * verde con barras de profundidad, proporción compra/venta y agrupación por tick.
 * El icono de abajo alterna con las últimas operaciones. Tocar un precio lo pasa
 * como precio límite al simulador. Datos: Binance (par contra USDT) o Bitstamp
 * para BTC; cada 1,5 s mientras se ve. La bolsa no publica libro gratis: se avisa.
 */
class PanelLibro(private val act: MainActivity, private val activo: () -> Mercado.Activo,
                 private val alElegirPrecio: (Double) -> Unit) {

    private fun dp(v: Int) = act.dp(v)
    private val prefs = act.getSharedPreferences("home_chart", Context.MODE_PRIVATE)
    private var pestanaTrades = prefs.getBoolean("libro_trades", false)
    private var nivelTick = prefs.getInt("libro_tick", 0)
    /** En futuros se muestra el funding (8 h) con su cuenta atrás. */
    var futuros = true
        set(v) { field = v; tvFunding.visibility = if (v) View.VISIBLE else View.GONE }

    val vista = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private fun txt(sp: Float, color: Int) = TextView(act).apply { textSize = sp; setTextColor(color) }
    private val tvFunding = txt(12f, DexTema.TXT2).apply { gravity = Gravity.END; setPadding(0, 0, 0, dp(6)); setLineSpacing(0f, 1.15f) }
    private val tvEstado = txt(11f, DexTema.TXT2).apply { setPadding(0, dp(6), 0, 0) }
    private val libroV = VistaLibro(act)
    private val tradesV = VistaTrades(act)
    private val tvTick = TextView(act).apply {
        textSize = 13f; setTextColor(DexTema.TXT); gravity = Gravity.CENTER_VERTICAL; minHeight = dp(36)
        background = DexTema.fondo(act, DexTema.CHIP, 6f); setPadding(dp(10), 0, dp(8), 0); isClickable = true; foreground = Ui.toque()
        compoundDrawablePadding = dp(4); contentDescription = "Price grouping"
    }
    private val btnModo = android.widget.ImageView(act).apply {
        setImageResource(R.drawable.ic_clock); setColorFilter(DexTema.TXT); scaleType = android.widget.ImageView.ScaleType.CENTER
        isClickable = true; foreground = Ui.toque(); layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) }
    }

    /** Mejor compra y mejor venta del último libro (para pintarlas en el gráfico y el botón BBO). */
    var alMejores: ((Double?, Double?) -> Unit)? = null
    var mejorBid: Double? = null; private set
    var mejorAsk: Double? = null; private set
    private var libro: Mercado.Libro? = null
    private var trades: List<Mercado.Trade> = emptyList()
    private var claveDatos = ""
    private val ocupado = java.util.concurrent.atomic.AtomicBoolean(false)
    private val h = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        vista.addView(tvFunding)
        vista.addView(libroV); vista.addView(tradesV)
        tvTick.setCompoundDrawables(null, null, act.getDrawable(R.drawable.ic_caret_down)?.mutate()?.apply { setTint(DexTema.TXT2); setBounds(0, 0, dp(14), dp(14)) }, null)
        tvTick.setOnClickListener { menuTick() }
        btnModo.setOnClickListener { pestanaTrades = !pestanaTrades; prefs.edit().putBoolean("libro_trades", pestanaTrades).apply(); mostrar(); pedir() }
        vista.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0)
            tvTick.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); addView(tvTick); addView(btnModo)
        })
        vista.addView(tvEstado)
        libroV.alTocar = { p -> alElegirPrecio(p) }
        mostrar(); pintarFunding()
        h.postDelayed(object : Runnable {
            override fun run() {
                if (!vista.isAttachedToWindow && claveDatos.isNotEmpty()) return
                if (vista.isShown) { pedir(); pintarFunding() }
                h.postDelayed(this, 1_500)
            }
        }, 800)
    }

    private fun pintarFunding() {
        val ahora = System.currentTimeMillis() / 1000
        val resta = Simulador.FUNDING_SEG - ahora % Simulador.FUNDING_SEG
        tvFunding.text = "Funding (8h) / Countdown\n%.4f%% / %02d:%02d:%02d".format(Simulador.FUNDING * 100, resta / 3600, resta % 3600 / 60, resta % 60)
    }

    private fun mostrar() {
        libroV.visibility = if (pestanaTrades) View.GONE else View.VISIBLE
        tradesV.visibility = if (pestanaTrades) View.VISIBLE else View.GONE
        tvTick.visibility = if (pestanaTrades) View.INVISIBLE else View.VISIBLE
        btnModo.setImageResource(if (pestanaTrades) R.drawable.ic_chart else R.drawable.ic_clock)
        btnModo.contentDescription = if (pestanaTrades) "Show order book" else "Show recent trades"
    }

    /** Tamaños de agrupación según el precio (p. ej. BTC: 0.01, 0.1, 1, 10, 100). */
    private fun ticks(precio: Double): List<Double> {
        if (precio <= 0) return listOf(0.0)
        val base = Math.pow(10.0, Math.floor(Math.log10(precio)) - 6)
        return (0..4).map { base * Math.pow(10.0, it.toDouble()) }
    }
    private fun fmtTick(t: Double) = if (t >= 1) "%.0f".format(t) else java.math.BigDecimal(t).round(java.math.MathContext(1)).stripTrailingZeros().toPlainString()
    private fun refPrecio() = libro?.bids?.firstOrNull()?.first ?: libro?.asks?.firstOrNull()?.first ?: 0.0

    private fun menuTick() {
        val ts = ticks(refPrecio()); if (ts.size < 2) return
        val pm = androidx.appcompat.widget.PopupMenu(act, tvTick)
        ts.forEachIndexed { k, t -> pm.menu.add(0, k, k, fmtTick(t)) }
        pm.setOnMenuItemClickListener { nivelTick = it.itemId; prefs.edit().putInt("libro_tick", nivelTick).apply(); aplicar(); true }
        pm.show()
    }

    /** El gráfico cambió de activo. */
    fun alCambiarActivo() { libro = null; trades = emptyList(); claveDatos = ""; mejorBid = null; mejorAsk = null
        libroV.poner(null, 0.0, 0.0); tradesV.poner(emptyList()); pedir() }

    fun pedir() {
        val a = activo()
        if (a.tipo != Mercado.Tipo.CRYPTO) {
            tvEstado.text = "No order book for stocks and markets (not published for free)."
            libroV.poner(null, 0.0, 0.0); tradesV.poner(emptyList()); return
        }
        if (!ocupado.compareAndSet(false, true)) return
        val verTrades = pestanaTrades
        Thread {
            val l = if (!verTrades) Mercado.libro(a, 100) else null
            val tr = Mercado.trades(a, if (verTrades) 40 else 2)
            act.runOnUiThread {
                ocupado.set(false)
                if (a.clave != activo().clave) return@runOnUiThread
                claveDatos = a.clave
                if (!verTrades) libro = l
                if (tr.isNotEmpty()) trades = if (verTrades) tr else (tr + trades).distinctBy { it.t to it.precio }.take(40)
                aplicar()
                tvEstado.text = when {
                    !verTrades && l == null -> "Order book unavailable for ${a.simbolo}/USDT."
                    verTrades && tr.isEmpty() -> "Trades unavailable for ${a.simbolo}/USDT."
                    else -> "Live · ${libro?.fuente ?: "Binance"}"
                }
            }
        }.start()
    }

    private fun aplicar() {
        val l = libro
        val ultimo = trades.firstOrNull()?.precio ?: 0.0
        val previo = trades.getOrNull(1)?.precio ?: ultimo
        if (l != null) {
            val ts = ticks(refPrecio()); nivelTick = nivelTick.coerceIn(0, ts.size - 1)
            val tick = ts[nivelTick]; tvTick.text = fmtTick(tick)
            libroV.poner(Mercado.Libro(agrupar(l.bids, tick, false), agrupar(l.asks, tick, true), l.fuente), ultimo, previo)
            mejorBid = l.bids.firstOrNull()?.first; mejorAsk = l.asks.firstOrNull()?.first
            alMejores?.invoke(mejorBid, mejorAsk)
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
private fun fmtLibroPrecio(p: Double) = when { p >= 1000 -> "%,.1f".format(p); p >= 1 -> "%.4f".format(p); else -> "%.6g".format(p) }
private fun fmtLibroQty(q: Double) = when { q >= 1_000_000 -> "%.2fM".format(q / 1e6); q >= 1000 -> "%.2fK".format(q / 1e3); q >= 1 -> "%.2f".format(q); else -> "%.4f".format(q) }

/**
 * Libro compacto en Canvas: cabecera Precio / Monto (USD), 7 asks, último precio
 * grande con el precio medio debajo, 7 bids y barra de proporción compra/venta.
 */
class VistaLibro(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val filas = 7
    private val altoFila = 24f * d
    private val altoCab = 34f * d
    private val altoMedio = 54f * d
    private val altoBarra = 26f * d
    private var libro: Mercado.Libro? = null
    private var ultimo = 0.0; private var previo = 0.0
    var alTocar: ((Double) -> Unit)? = null
    private val zonas = ArrayList<Triple<Float, Float, Double>>()

    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * d; typeface = AppTheme.medium(ctx) }
    private val pCab = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f * d; color = DexTema.TXT2; typeface = AppTheme.body(ctx) }
    private val pBarra = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pGrande = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f * d; typeface = AppTheme.bold(ctx); textAlign = Paint.Align.CENTER }

    init { contentDescription = "Order book. Tap a price to use it as the limit price." }

    fun poner(l: Mercado.Libro?, ult: Double, prev: Double) { libro = l; ultimo = ult; previo = prev; invalidate() }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (altoCab + altoFila * 2 * filas + altoMedio + altoBarra).toInt())

    private fun alfa(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val l = libro
        zonas.clear()
        pCab.textAlign = Paint.Align.LEFT; c.drawText("Price", 0f, 13f * d, pCab); c.drawText("(USD)", 0f, 28f * d, pCab)
        pCab.textAlign = Paint.Align.RIGHT; c.drawText("Amount", w, 13f * d, pCab); c.drawText("(USD)", w, 28f * d, pCab)
        if (l == null) { pCab.textAlign = Paint.Align.LEFT; c.drawText("Loading…", 0f, altoCab + 24f * d, pCab); return }
        val asks = l.asks.take(filas); val bids = l.bids.take(filas)
        val maxQ = (asks + bids).maxOfOrNull { it.first * it.second } ?: 1.0
        var y = altoCab
        fun fila(p: Double, q: Double, color: Int) {
            val monto = p * q
            pBarra.color = alfa(color, 0x2A); c.drawRect(w - (w * (monto / maxQ)).toFloat().coerceAtMost(w), y + 2f * d, w, y + altoFila - 2f * d, pBarra)
            val yt = y + altoFila * 0.68f
            pTxt.color = color; pTxt.textAlign = Paint.Align.LEFT; c.drawText(fmtLibroPrecio(p), 0f, yt, pTxt)
            pTxt.color = DexTema.TXT; pTxt.textAlign = Paint.Align.RIGHT; c.drawText(fmtLibroQty(monto), w, yt, pTxt)
            zonas.add(Triple(y, y + altoFila, p)); y += altoFila
        }
        y += (filas - asks.size) * altoFila
        for (i in asks.indices.reversed()) fila(asks[i].first, asks[i].second, DexTema.DOWN)
        // último precio y medio
        val mejorA = asks.firstOrNull()?.first; val mejorB = bids.firstOrNull()?.first
        val medio = if (mejorA != null && mejorB != null) (mejorA + mejorB) / 2 else 0.0
        val ref = if (ultimo > 0) ultimo else medio
        pGrande.color = if (ultimo >= previo) DexTema.UP else DexTema.DOWN
        c.drawText(fmtLibroPrecio(ref), w / 2, y + 26f * d, pGrande)
        if (medio > 0) { pCab.textAlign = Paint.Align.CENTER; c.drawText(fmtLibroPrecio(medio), w / 2, y + 46f * d, pCab) }
        y += altoMedio
        for (b in bids) fila(b.first, b.second, DexTema.UP)
        y += (filas - bids.size) * altoFila
        // proporción compra / venta de lo visible
        val sb = bids.sumOf { it.first * it.second }; val sa = asks.sumOf { it.first * it.second }
        if (sa + sb > 0) {
            val fb = (sb / (sa + sb)).toFloat(); val yb = y + 12f * d
            pTxt.textSize = 12f * d
            val tB = "%.2f%%".format(fb * 100); val tS = "%.2f%%".format((1 - fb) * 100)
            val anB = pTxt.measureText(tB) + 6f * d; val anS = pTxt.measureText(tS) + 6f * d
            pTxt.color = DexTema.UP; pTxt.textAlign = Paint.Align.LEFT; c.drawText(tB, 0f, yb + 4f * d, pTxt)
            pTxt.color = DexTema.DOWN; pTxt.textAlign = Paint.Align.RIGHT; c.drawText(tS, w, yb + 4f * d, pTxt)
            val x0 = anB; val x1 = w - anS; val xm = x0 + (x1 - x0) * fb
            pBarra.color = DexTema.UP; c.drawRoundRect(x0, yb - 2f * d, xm - 2f * d, yb + 2f * d, 2f * d, 2f * d, pBarra)
            pBarra.color = DexTema.DOWN; c.drawRoundRect(xm + 2f * d, yb - 2f * d, x1, yb + 2f * d, 2f * d, 2f * d, pBarra)
            pTxt.textSize = 13f * d
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) zonas.firstOrNull { e.y >= it.first && e.y < it.second }?.let { alTocar?.invoke(it.third); performClick() }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Últimas operaciones: precio (verde compra / rojo venta), cantidad y hora. */
class VistaTrades(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val filas = 15
    private val altoFila = 24f * d
    private var datos: List<Mercado.Trade> = emptyList()
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f * d; typeface = AppTheme.medium(ctx) }
    private val pCab = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f * d; color = DexTema.TXT2; typeface = AppTheme.body(ctx) }
    private val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    fun poner(t: List<Mercado.Trade>) { datos = t; invalidate() }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (altoFila * filas + 34f * d).toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        pCab.textAlign = Paint.Align.LEFT; c.drawText("Price (USD)", 0f, 13f * d, pCab)
        pCab.textAlign = Paint.Align.RIGHT; c.drawText("Amount · time", w, 13f * d, pCab)
        if (datos.isEmpty()) { pCab.textAlign = Paint.Align.LEFT; c.drawText("Loading…", 0f, 50f * d, pCab); return }
        var y = 34f * d
        for (t in datos.take(filas)) {
            val yt = y + altoFila * 0.68f
            pTxt.color = if (t.compra) DexTema.UP else DexTema.DOWN; pTxt.textAlign = Paint.Align.LEFT; c.drawText(fmtLibroPrecio(t.precio), 0f, yt, pTxt)
            pTxt.color = DexTema.TXT; pTxt.textAlign = Paint.Align.RIGHT
            c.drawText(fmtLibroQty(t.qty) + "  " + sdf.format(java.util.Date(t.t)).substring(3), w, yt, pTxt)
            y += altoFila
        }
    }
}
