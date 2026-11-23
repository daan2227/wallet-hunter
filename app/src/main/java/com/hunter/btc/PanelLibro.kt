package com.hunter.btc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
    /** 0 libro, 1 operaciones, 2 profundidad. */
    private var modo = prefs.getInt("libro_modo", if (prefs.getBoolean("libro_trades", false)) 1 else 0).coerceIn(0, 2)
    private val pestanaTrades get() = modo == 1
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
    private val profV = VistaProfundidad(act)
    private val tvTick = TextView(act).apply {
        textSize = 14f; setTextColor(DexTema.TXT); gravity = Gravity.CENTER_VERTICAL; minHeight = dp(48)
        background = DexTema.fondoTactil(act, DexTema.CHIP, 6f, 36f); setPadding(dp(10), 0, dp(8), 0); isClickable = true; foreground = Ui.toque()
        compoundDrawablePadding = dp(4); contentDescription = "Price grouping"
    }
    private val btnModo = android.widget.ImageView(act).apply {
        setImageResource(R.drawable.ic_clock); setColorFilter(DexTema.TXT); scaleType = android.widget.ImageView.ScaleType.CENTER
        isClickable = true; foreground = Ui.toque(); layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(2) }
    }

    /** Mejor compra y mejor venta del último libro (para pintarlas en el gráfico y el botón BBO). */
    var alMejores: ((Double?, Double?) -> Unit)? = null
    var mejorBid: Double? = null; private set
    var mejorAsk: Double? = null; private set
    /** Con el stream abierto, el libro y las operaciones llegan solos: no se sondea. */
    var enStream = false
    private var ultAplicar = 0L
    private var aplicarPendiente = false
    private var libro: Mercado.Libro? = null
    /** Libro sin agrupar (para simular el llenado de órdenes a mercado). */
    val libroCrudo get() = libro
    /** Funding y precio marca reales del par (Binance Futures), cada 30 s. */
    var premium: Mercado.Premium? = null; private set
    var alPremium: ((String, Mercado.Premium) -> Unit)? = null
    private var ultPremium = 0L
    private var trades: List<Mercado.Trade> = emptyList()
    private var claveDatos = ""
    private val ocupado = java.util.concurrent.atomic.AtomicBoolean(false)
    private val h = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        vista.addView(tvFunding)
        vista.addView(libroV); vista.addView(tradesV); vista.addView(profV)
        tvTick.setCompoundDrawables(null, null, act.getDrawable(R.drawable.ic_caret_down)?.mutate()?.apply { setTint(DexTema.TXT2); setBounds(0, 0, dp(14), dp(14)) }, null)
        tvTick.setOnClickListener { menuTick() }
        btnModo.setOnClickListener { modo = (modo + 1) % 3; prefs.edit().putInt("libro_modo", modo).apply(); mostrar(); pedir() }
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
        val ahora = System.currentTimeMillis()
        val pr = premium
        val resta = (if (pr != null && pr.proximo > ahora) (pr.proximo - ahora) / 1000 else Simulador.FUNDING_SEG - ahora / 1000 % Simulador.FUNDING_SEG)
        tvFunding.text = "Funding (8h) / Countdown\n%.4f%% / %02d:%02d:%02d".format((pr?.tasa ?: Simulador.FUNDING) * 100, resta / 3600, resta % 3600 / 60, resta % 60)
        // cada 30 s: tasa y precio marca reales (solo futuros, cripto)
        val a = activo()
        if (futuros && a.tipo == Mercado.Tipo.CRYPTO && ahora - ultPremium > 30_000) {
            ultPremium = ahora
            Thread { val p = Mercado.premium(a); act.runOnUiThread { if (p != null && a.clave == activo().clave) { premium = p; libroV.marca = p.marca; alPremium?.invoke(a.clave, p) } } }.start()
        }
    }

    private fun mostrar() {
        libroV.visibility = if (modo == 0) View.VISIBLE else View.GONE
        tradesV.visibility = if (modo == 1) View.VISIBLE else View.GONE
        profV.visibility = if (modo == 2) View.VISIBLE else View.GONE
        tvTick.visibility = if (modo == 0) View.VISIBLE else View.INVISIBLE
        // el icono dice a qué vista se pasa al tocar
        btnModo.setImageResource(when (modo) { 0 -> R.drawable.ic_clock; 1 -> R.drawable.ic_chart; else -> R.drawable.ic_scan })
        btnModo.contentDescription = when (modo) { 0 -> "Show recent trades"; 1 -> "Show depth chart"; else -> "Show order book" }
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
    fun alCambiarActivo() { libro = null; trades = emptyList(); claveDatos = ""; mejorBid = null; mejorAsk = null; premium = null; ultPremium = 0; libroV.marca = 0.0
        libroV.poner(null, 0.0, 0.0); tradesV.poner(emptyList()); pedir() }

    /** Libro del stream (20 niveles por lado, cada segundo). */
    fun libroStream(bids: List<Pair<Double, Double>>, asks: List<Pair<Double, Double>>) {
        libro = Mercado.Libro(bids, asks, "Binance stream"); claveDatos = activo().clave
        tvEstado.text = "Live stream · Binance"; aplicarSuave()
    }
    /** Operación del stream: se añaden al principio y se repinta como mucho 5 veces por segundo. */
    fun tradeStream(t: Mercado.Trade) {
        if (t.precio <= 0) return
        trades = (listOf(t) + trades).take(40); aplicarSuave()
    }
    private fun aplicarSuave() {
        val ahora = System.currentTimeMillis()
        if (ahora - ultAplicar >= 200) { ultAplicar = ahora; aplicar() }
        else if (!aplicarPendiente) { aplicarPendiente = true; h.postDelayed({ aplicarPendiente = false; ultAplicar = System.currentTimeMillis(); aplicar() }, 200) }
    }

    fun pedir() {
        if (enStream) return
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
            profV.poner(l)
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
    /** Precio marca (futuros); si se conoce se muestra bajo el último en lugar del medio. */
    var marca = 0.0
    var alTocar: ((Double) -> Unit)? = null
    private val zonas = ArrayList<Triple<Float, Float, Double>>()

    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f * d; typeface = AppTheme.medium(ctx) }
    private val pCab = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d; color = DexTema.TXT2; typeface = AppTheme.body(ctx) }
    private val pBarra = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pGrande = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f * d; typeface = AppTheme.bold(ctx); textAlign = Paint.Align.CENTER }

    init { contentDescription = "Order book. Tap a price to use it as the limit price." }

    fun poner(l: Mercado.Libro?, ult: Double, prev: Double) { libro = l; ultimo = ult; previo = prev; invalidate()
        contentDescription = if (l == null) "Order book loading" else "Order book. Best ask ${l.asks.firstOrNull()?.first?.let { fmtLibroPrecio(it) } ?: "none"}, " +
            "best bid ${l.bids.firstOrNull()?.first?.let { fmtLibroPrecio(it) } ?: "none"}. Tap a price to use it as the limit price." }

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
        pCab.textAlign = Paint.Align.CENTER
        if (marca > 0) c.drawText("Mark " + fmtLibroPrecio(marca), w / 2, y + 46f * d, pCab)
        else if (medio > 0) c.drawText(fmtLibroPrecio(medio), w / 2, y + 46f * d, pCab)
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
            pTxt.textSize = 14f * d
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
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13.5f * d; typeface = AppTheme.medium(ctx) }
    private val pCab = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d; color = DexTema.TXT2; typeface = AppTheme.body(ctx) }
    private val pFlecha = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    fun poner(t: List<Mercado.Trade>) { datos = t; invalidate()
        contentDescription = t.firstOrNull()?.let { "Recent trades. Last: ${if (it.compra) "buy" else "sell"} ${fmtLibroQty(it.qty)} at ${fmtLibroPrecio(it.precio)}" } ?: "Recent trades" }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (altoFila * filas + 34f * d).toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        pCab.textAlign = Paint.Align.LEFT; c.drawText("Price (USD)", 0f, 13f * d, pCab)
        pCab.textAlign = Paint.Align.RIGHT; c.drawText("Amount · time", w, 13f * d, pCab)
        if (datos.isEmpty()) { pCab.textAlign = Paint.Align.LEFT; c.drawText("Loading…", 0f, 50f * d, pCab); return }
        var y = 34f * d
        for (t in datos.take(filas)) {
            val yt = y + altoFila * 0.68f
            // ▲ compra / ▼ venta: la dirección no depende solo del color
            val col = if (t.compra) DexTema.UP else DexTema.DOWN; val cy = y + altoFila / 2; val r = 4f * d
            pFlecha.color = col
            c.drawPath(android.graphics.Path().apply { if (t.compra) { moveTo(r, cy - r); lineTo(2 * r, cy + r * 0.8f); lineTo(0f, cy + r * 0.8f) }
                else { moveTo(0f, cy - r * 0.8f); lineTo(2 * r, cy - r * 0.8f); lineTo(r, cy + r) }; close() }, pFlecha)
            pTxt.color = col; pTxt.textAlign = Paint.Align.LEFT; c.drawText(fmtLibroPrecio(t.precio), 2 * r + 5f * d, yt, pTxt)
            pTxt.color = DexTema.TXT; pTxt.textAlign = Paint.Align.RIGHT
            c.drawText(fmtLibroQty(t.qty) + "  " + sdf.format(java.util.Date(t.t)).substring(3), w, yt, pTxt)
            y += altoFila
        }
    }
}

/**
 * Gráfico de profundidad: volumen acumulado de compras (verde, a la izquierda) y de
 * ventas (rojo, a la derecha) según el precio, con el precio medio en el centro.
 */
class VistaProfundidad(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var libro: Mercado.Libro? = null
    private val pArea = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pLinea = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d; color = DexTema.TXT2; typeface = AppTheme.body(ctx) }

    fun poner(l: Mercado.Libro?) { libro = l; invalidate()
        contentDescription = if (l == null) "Depth chart loading" else "Depth chart. Buy side %s, sell side %s within the visible book"
            .format(fmtLibroQty(l.bids.sumOf { it.first * it.second }), fmtLibroQty(l.asks.sumOf { it.first * it.second })) }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (24f * d * 14 + 34f * d + 54f * d + 26f * d).toInt())

    override fun onDraw(c: Canvas) {
        val l = libro; val w = width.toFloat(); val h = height.toFloat()
        val arriba = 20f * d; val abajo = h - 34f * d
        if (l == null || l.bids.isEmpty() || l.asks.isEmpty()) { c.drawText("Loading…", 0f, 40f * d, pTxt); return }
        val bids = l.bids.take(100); val asks = l.asks.take(100)
        val pMin = bids.last().first; val pMax = asks.last().first
        if (pMax <= pMin) return
        val acB = bids.runningFold(0.0) { s, x -> s + x.first * x.second }.drop(1)
        val acA = asks.runningFold(0.0) { s, x -> s + x.first * x.second }.drop(1)
        val maxV = maxOf(acB.last(), acA.last()).coerceAtLeast(1e-9)
        fun x(p: Double) = ((p - pMin) / (pMax - pMin) * w).toFloat()
        fun y(v: Double) = (abajo - (abajo - arriba) * (v / maxV)).toFloat()
        fun lado(niv: List<Pair<Double, Double>>, ac: List<Double>, col: Int) {
            val linea = Path(); val area = Path()
            linea.moveTo(x(niv[0].first), abajo); area.moveTo(x(niv[0].first), abajo)
            for (i in niv.indices) { val xi = x(niv[i].first); val yi = y(ac[i])
                if (i > 0) { linea.lineTo(xi, y(ac[i - 1])); area.lineTo(xi, y(ac[i - 1])) }
                linea.lineTo(xi, yi); area.lineTo(xi, yi) }
            area.lineTo(x(niv.last().first), abajo); area.close()
            pArea.color = (col and 0x00FFFFFF) or (0x40 shl 24); c.drawPath(area, pArea)
            pLinea.color = col; c.drawPath(linea, pLinea)
        }
        lado(bids, acB, DexTema.UP); lado(asks, acA, DexTema.DOWN)
        val medio = (bids[0].first + asks[0].first) / 2
        pTxt.textAlign = Paint.Align.CENTER; pTxt.color = DexTema.TXT; c.drawText(fmtLibroPrecio(medio), w / 2, 14f * d, pTxt)
        pTxt.color = DexTema.TXT2
        pTxt.textAlign = Paint.Align.LEFT; c.drawText(fmtLibroPrecio(pMin), 0f, h - 14f * d, pTxt)
        pTxt.textAlign = Paint.Align.RIGHT; c.drawText(fmtLibroPrecio(pMax), w, h - 14f * d, pTxt)
        c.drawText(fmtLibroQty(maxV), w, arriba + 12f * d, pTxt)
    }
}
