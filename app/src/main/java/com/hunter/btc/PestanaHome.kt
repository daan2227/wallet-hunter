package com.hunter.btc

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Home: panel de inicio (primer item de la barra). Arriba un gráfico técnico de
 * BTC (velas + MA/EMA, volumen, RSI, MACD, intervalo); debajo el top-10 de
 * monedas sin stablecoins; y al final las funciones de la app en tarjetas.
 */
internal fun MainActivity.buildHomeTab(): ScrollView {
    val scroll = ScrollView(this).apply {
        setBackgroundColor(DexTema.BG); visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }
    val page = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(DexTema.BG)
        setPadding(dp(16), dp(8), dp(16), dp(32))
    }
    scroll.addView(page)
    val prefsG = getSharedPreferences("home_chart", android.content.Context.MODE_PRIVATE)

    // ── Pestañas de arriba (texto grande, como los exchanges): Spot · Futures · Markets ──
    val pagTrade = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val pagMercado = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val prefsTab = prefsG
    var tabHome = prefsTab.getInt("tab", 0).coerceIn(0, 2)
    var alCambiarTab: (Int) -> Unit = {}
    val segTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val nombresTop = listOf("Spot", "Futures", "Markets")
    fun pintarTop() = nombresTop.indices.forEach { k -> (segTop.getChildAt(k) as TextView).apply {
        setTextColor(if (k == tabHome) DexTema.TXT else DexTema.TXT2); typeface = if (k == tabHome) AppTheme.bold(context) else AppTheme.medium(context)
        isSelected = k == tabHome } }
    nombresTop.forEachIndexed { k, t -> segTop.addView(TextView(this).apply {
        text = t; textSize = 21f; minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, dp(22), 0); isClickable = true
        setOnClickListener {
            tabHome = k; prefsTab.edit().putInt("tab", k).apply(); pintarTop()
            if (k < 2) prefsTab.edit().putInt("ultimo_trade", k).apply()
            pagTrade.visibility = if (k < 2) View.VISIBLE else View.GONE
            pagMercado.visibility = if (k == 2) View.VISIBLE else View.GONE
            alCambiarTab(k)
        }
    }) }
    pintarTop()
    page.addView(segTop)
    page.addView(View(this).apply { setBackgroundColor(DexTema.STROKE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)).apply { bottomMargin = dp(8) } })
    page.addView(pagTrade); page.addView(pagMercado)
    pagTrade.visibility = if (tabHome < 2) View.VISIBLE else View.GONE
    pagMercado.visibility = if (tabHome == 2) View.VISIBLE else View.GONE
    /** Desde Markets: abrir el par en la última pestaña de trading usada. */
    fun irATrade() { val k = prefsTab.getInt("ultimo_trade", 0).coerceIn(0, 1); segTop.getChildAt(k).performClick() }

    // ── Cabecera del par: nombre ▾ · estrella · alertas · en vivo · info ──
    val tvPar = TextView(this).apply {
        textSize = 22f; setTextColor(DexTema.TXT); typeface = AppTheme.bold(context); minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
        isClickable = true; foreground = Ui.toque(); compoundDrawablePadding = dp(6)
        setCompoundDrawablesWithIntrinsicBounds(null, null, getDrawable(R.drawable.ic_caret_down)?.mutate()?.apply { setTint(DexTema.TXT) }, null)
        contentDescription = "Select pair"
    }
    fun icono(res: Int, desc: String) = android.widget.ImageView(this).apply {
        setImageResource(res); setColorFilter(DexTema.TXT); contentDescription = desc
        scaleType = android.widget.ImageView.ScaleType.CENTER; isClickable = true; foreground = Ui.toque()
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(48))
    }
    val btnFav = icono(R.drawable.ic_star, "Add to watchlist")
    val btnAlerta = icono(R.drawable.ic_bell, "Price alerts")
    val btnInfo = icono(R.drawable.ic_info, "Asset information")
    val btnVivo = TextView(this).apply {
        textSize = 12f; typeface = AppTheme.medium(context); gravity = Gravity.CENTER; minHeight = dp(32)
        background = DexTema.fondo(context, DexTema.CHIP, 100f); setPadding(dp(10), 0, dp(10), 0)
        isClickable = true; foreground = Ui.toque()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(2) }
    }
    pagTrade.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(tvPar); addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        addView(btnVivo); addView(btnFav); addView(btnAlerta); addView(btnInfo)
    })
    // precio grande en color + cambio a su lado; debajo una nota y (opcional) cambios por periodo
    val tvPrecio = TextView(this).apply { textSize = 34f; setTextColor(DexTema.TXT); typeface = AppTheme.bold(context); text = "…"; includeFontPadding = false }
    val tvCambio = TextView(this).apply { textSize = 16f; setTextColor(DexTema.TXT2); typeface = AppTheme.medium(context); setPadding(dp(10), 0, 0, dp(3)) }
    pagTrade.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(0, dp(2), 0, 0)
        addView(tvPrecio); addView(tvCambio)
    })
    val tvNota = TextView(this).apply { textSize = 12f; setTextColor(DexTema.TXT2); setPadding(0, dp(4), 0, dp(4)) }
    val tvMulti = TextView(this).apply { textSize = 13f; typeface = AppTheme.mono(context); setPadding(0, dp(2), 0, dp(6)) }
    pagTrade.addView(tvNota); pagTrade.addView(tvMulti)

    // ── Gráfico (config, dibujos e intervalo se guardan) ──
    // Los 5 primeros van a la vista; el resto en el desplegable "More".
    val intervalos = listOf(
        Triple("1m", "1m", 60L), Triple("15m", "15m", 900L), Triple("1h", "1h", 3600L), Triple("4h", "4h", 14400L),
        Triple("1D", "1d", 86400L),
        Triple("1s", "1s", 1L), Triple("5m", "5m", 300L), Triple("30m", "30m", 1800L), Triple("2h", "2h", 7200L),
        Triple("6h", "6h", 21600L), Triple("12h", "12h", 43200L), Triple("3D", "3d", 259200L),
        Triple("1W", "1w", 604800L), Triple("1M", "1M", 2592000L))
    val visibles = 5
    var intervalo = prefsG.getString("intervalo", "1h") ?: "1h"
    var activo = Mercado.Activo.deJson(prefsG.getString("activo", null)) ?: Mercado.BTC
    tvPar.text = activo.par + "  ▾"
    btnInfo.setOnClickListener { abrirInfoActivo(activo) }
    fun pintarFav() { val f = Favoritos.es(this, activo.clave)
        btnFav.setImageResource(if (f) R.drawable.ic_star_filled else R.drawable.ic_star); btnFav.setColorFilter(if (f) DexTema.WARN else DexTema.TXT)
        btnFav.contentDescription = if (f) "Remove from watchlist" else "Add to watchlist" }
    pintarFav()
    btnFav.setOnClickListener { val f = Favoritos.alternar(this, activo); pintarFav()
        Toast.makeText(this, if (f) "${activo.simbolo} added to watchlist" else "${activo.simbolo} removed from watchlist", Toast.LENGTH_SHORT).show() }

    fun claveDib(a: Mercado.Activo) = "dibujos_" + a.clave
    val chart = GraficoTA(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        intervaloSeg = intervalos.firstOrNull { it.second == intervalo }?.third ?: 3600L
        mostrarCuenta = activo.tipo == Mercado.Tipo.CRYPTO && prefsG.getBoolean("c_cuenta", true)
        cfg.deJson(prefsG.getString("cfg", null))
        cargarDibujos(prefsG.getString(claveDib(activo), if (activo.clave == Mercado.BTC.clave) prefsG.getString("dibujos", null) else null))
    }
    fun guardarCfg() { prefsG.edit().putString("cfg", chart.cfg.aJson()).apply(); chart.refrescarConfig() }
    chart.alCambiarDibujos = { prefsG.edit().putString(claveDib(activo), chart.dibujosJson()).apply() }
    chart.alPedirNota = { t, p ->
        val et = DexTema.campo(this, "Note text")
        DexTema.dialogo(this).setTitle("Add note")
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) })
            .setPositiveButton("Add") { _, _ -> chart.agregarNota(t, p, et.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    fun fmtPrecio(x: Float): String {
        val num = when { x >= 1000f -> "%,.2f".format(x); x >= 1f -> (if (activo.sinDolar && x < 10f) "%.4f" else "%.2f").format(x)
            x == 0f -> "0"; else -> "%.6g".format(x) }
        return if (activo.sinDolar) num else "$num USD"
    }
    fun mostrarPrecio(v: List<GraficoPrecio.Vela>, sufijo: String) {
        val fin = v.last().c; val ini = v.first().o
        val pct = if (ini > 0) (fin - ini) / ini * 100f else 0f
        val col = if (pct >= 0) chart.cUp else chart.cDown
        tvPrecio.text = fmtPrecio(fin).removeSuffix(" USD"); tvPrecio.setTextColor(col)
        tvCambio.text = "%s%.2f%%".format(if (pct >= 0) "+" else "", pct); tvCambio.setTextColor(col)
        tvNota.text = (if (activo.sinDolar) "" else "USD · ") + sufijo
    }
    var panel: PanelTrading? = null
    var cargarMulti: (() -> Unit)? = null
    var libroPanel: PanelLibro? = null
    val tok = AtomicInteger(0)
    fun cargar() {
        val myTok = tok.incrementAndGet()
        tvNota.text = "Loading…"
        val iv = intervalo; val a = activo
        Thread {
            val v = Mercado.velas(a, iv, 500)
            runOnUiThread {
                if (myTok != tok.get()) return@runOnUiThread
                if (v.isEmpty()) { tvNota.text = "Chart unavailable (data source unreachable)"; return@runOnUiThread }
                chart.poner(v); panel?.tick(a.clave, v.last().c.toDouble()); comprobarAlertas(a.clave, v.last().c.toDouble())
                mostrarPrecio(v, listOf("$iv candles", Mercado.avisoIntervalo(a, iv)).filter { it.isNotEmpty() }.joinToString(" · "))
            }
        }.start()
    }
    /** Cambia el gráfico a otro activo (desde la lista de mercados). */
    fun seleccionar(a: Mercado.Activo) {
        scroll.smoothScrollTo(0, 0)
        if (a.clave == activo.clave) return
        activo = a; prefsG.edit().putString("activo", a.aJson()).apply()
        tvPar.text = a.par + "  ▾"; tvPrecio.text = "…"; pintarFav(); chart.mostrarCuenta = a.tipo == Mercado.Tipo.CRYPTO && prefsG.getBoolean("c_cuenta", true)
        chart.lineasExtra = emptyList(); if (tvMulti.visibility == View.VISIBLE) { tvMulti.text = ""; cargarMulti?.invoke() }
        chart.cargarDibujos(prefsG.getString(claveDib(a), null))
        chart.poner(emptyList()); panel?.alCambiarActivo(); libroPanel?.alCambiarActivo(); cargar()
    }

    // Fila de chips de selección única.
    fun filaChips(etiquetas: List<String>, sel: Int, alElegir: (Int) -> Unit): Pair<HorizontalScrollView, (Int) -> Unit> {
        val chips = ArrayList<TextView>()
        fun pintar(s: Int) = chips.forEachIndexed { k, c ->
            val on = k == s
            c.background = Ui.cardBg(AppTheme.R_CHIP, if (on) DexTema.CHIP else DexTema.SURFACE, this)
            c.setTextColor(if (on) DexTema.TXT else DexTema.TXT2)
            c.typeface = if (on) AppTheme.bold(this) else AppTheme.medium(this)
        }
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etiquetas.forEachIndexed { k, t ->
            val c = TextView(this).apply {
                text = t; textSize = AppTheme.SP_CAPTION; gravity = Gravity.CENTER
                setPadding(dp(13), dp(7), dp(13), dp(7)); isClickable = true; foreground = Ui.toque()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(7) }
                setOnClickListener { pintar(k); alElegir(k) }
            }
            chips.add(c); fila.addView(c)
        }
        pintar(sel)
        val hsv = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(fila)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        return hsv to { s: Int -> pintar(s) }
    }

    // ── Intervalo (como en los exchanges: los habituales a la vista + "More ▾") y ajustes ──
    fun tabIv(t: String) = TextView(this).apply {
        text = t; textSize = 15f; gravity = Gravity.CENTER; minHeight = dp(40)
        setPadding(dp(12), 0, dp(12), 0); isClickable = true; foreground = Ui.toque()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(2) }
    }
    val tabsIv = intervalos.take(visibles).map { tabIv(it.first) }
    val tabMas = tabIv("More ▾")
    fun pintarIv() {
        val k = intervalos.indexOfFirst { it.second == intervalo }
        fun marca(tv: TextView, on: Boolean) {
            tv.background = if (on) DexTema.fondo(this, DexTema.CHIP, 100f) else null
            tv.setTextColor(if (on) DexTema.TXT else DexTema.TXT2); tv.typeface = if (on) AppTheme.bold(this) else AppTheme.medium(this)
            tv.isSelected = on
        }
        tabsIv.forEachIndexed { n, tv -> marca(tv, n == k) }
        marca(tabMas, k >= visibles); tabMas.text = if (k >= visibles) intervalos[k].first + " ▾" else "More ▾"
    }
    fun elegirIv(k: Int) {
        intervalo = intervalos[k].second; chart.intervaloSeg = intervalos[k].third
        prefsG.edit().putString("intervalo", intervalo).apply(); pintarIv(); cargar()
    }
    tabsIv.forEachIndexed { n, tv -> tv.setOnClickListener { elegirIv(n) } }
    tabMas.setOnClickListener {
        val pm = androidx.appcompat.widget.PopupMenu(this, tabMas)
        for (n in visibles until intervalos.size) pm.menu.add(0, n, n, intervalos[n].first)
        pm.setOnMenuItemClickListener { elegirIv(it.itemId); true }; pm.show()
    }
    pintarIv()
    val btnAjustes = icono(R.drawable.ic_gear, "Chart settings").apply { layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)) }
    pagTrade.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; tabsIv.forEach { addView(it) }; addView(tabMas) })
        })
        addView(btnAjustes)
    })

    // ── Actualización en vivo: desplegable junto al par ──
    val refrescos = listOf("Off" to 0L, "1s" to 1_000L, "5s" to 5_000L, "10s" to 10_000L, "30s" to 30_000L,
        "1m" to 60_000L, "5m" to 300_000L, "10m" to 600_000L)
    var refrescoMs = prefsG.getLong("refresco", 10_000L)
    val hVivo = android.os.Handler(android.os.Looper.getMainLooper())
    val enCurso = java.util.concurrent.atomic.AtomicBoolean(false)
    lateinit var vivo: Runnable
    fun pintarVivo() {
        val on = refrescoMs > 0
        btnVivo.text = if (on) "● " + (refrescos.firstOrNull { it.second == refrescoMs }?.first ?: "") else "○ Off"
        btnVivo.setTextColor(if (on) DexTema.UP else DexTema.TXT2)
        btnVivo.contentDescription = "Live update: " + (if (on) refrescos.firstOrNull { it.second == refrescoMs }?.first else "off")
    }
    btnVivo.setOnClickListener {
        val pm = androidx.appcompat.widget.PopupMenu(this, btnVivo)
        refrescos.forEachIndexed { n, (t, ms) -> pm.menu.add(0, n, n, if (ms == 0L) "Off" else "Every $t") }
        pm.setOnMenuItemClickListener {
            refrescoMs = refrescos[it.itemId].second; prefsG.edit().putLong("refresco", refrescoMs).apply()
            pintarVivo(); hVivo.removeCallbacks(vivo); hVivo.post(vivo); true
        }
        pm.show()
    }
    pintarVivo()

    // ── Herramientas de dibujo ──
    val tvAyuda = TextView(this).apply { textSize = AppTheme.SP_MICRO; setTextColor(DexTema.TXT2); setPadding(dp(2), 0, 0, dp(6)) }
    val ayudas = listOf("Drag to pan · pinch to zoom · tap or long-press for crosshair + candle details",
        "Tap the chart to place a horizontal line", "Tap two points for a trend line", "Tap the chart where the note goes")
    tvAyuda.text = ayudas[0]
    var marcarHerr: (Int) -> Unit = {}
    val (filaHerr, marcar) = filaChips(listOf("Move", "Line", "Trend", "Note", "Undo", "Clear"), 0) { k ->
        when (k) {
            in 0..3 -> { chart.herramienta = k; tvAyuda.text = ayudas[k] }
            4 -> { chart.deshacer(); marcarHerr(chart.herramienta) }
            5 -> { marcarHerr(chart.herramienta)
                if (chart.dibujos.isNotEmpty()) DexTema.dialogo(this).setTitle("Clear drawings")
                    .setMessage("Remove all ${chart.dibujos.size} lines and notes?")
                    .setPositiveButton("Clear") { _, _ -> chart.borrarTodo() }.setNegativeButton("Cancel", null).show() }
        }
    }
    marcarHerr = marcar
    pagTrade.addView(chart)
    val cajaDibujo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
        addView(tvAyuda.apply { setPadding(dp(2), dp(6), 0, dp(6)) }); addView(filaHerr)
        visibility = if (prefsG.getBoolean("ver_dibujo", false)) View.VISIBLE else View.GONE }
    pagTrade.addView(cajaDibujo)

    // ── Simulador de trading (Spot / Futuros) ──
    val pt = PanelTrading(this, { activo }, { chart.velasActuales().lastOrNull()?.c?.toDouble() }, chart)
    panel = pt
    // ── Libro de órdenes y trades en vivo (tocar un precio → precio límite del simulador) ──
    val pl = PanelLibro(this, { activo }) { p -> pt.ponerPrecioLimite(p) }
    libroPanel = pl
    pagTrade.addView(pl.vista)
    pagTrade.addView(pt.vista)
    val hVigia = android.os.Handler(android.os.Looper.getMainLooper())
    hVigia.postDelayed(object : Runnable {
        override fun run() {
            if (!scroll.isAttachedToWindow) return
            if (scroll.isShown) { pt.vigilar(); vigilarAlertas(activo.clave) }
            hVigia.postDelayed(this, 20_000)
        }
    }, 1_500)
    cargar()

    // ── En vivo: pide solo las últimas velas y las fusiona (barato aunque sea cada segundo) ──
    vivo = object : Runnable {
        override fun run() {
            if (!scroll.isAttachedToWindow) return
            if (refrescoMs <= 0L) { hVivo.postDelayed(this, 2_000); return }
            if (scroll.isShown && chart.velasActuales().size > 1 && enCurso.compareAndSet(false, true)) {
                val iv = intervalo; val a = activo
                Thread {
                    val nuevas = try { Mercado.velas(a, iv, 3) } catch (e: Throwable) { emptyList() }
                    runOnUiThread {
                        enCurso.set(false)
                        if (iv != intervalo || a.clave != activo.clave || nuevas.isEmpty() || !scroll.isAttachedToWindow) return@runOnUiThread
                        val out = ArrayList(chart.velasActuales()); var quitadas = 0
                        for (k in nuevas) {
                            val ult = out.last()
                            when {
                                k.t == ult.t -> out[out.size - 1] = k
                                k.t > ult.t -> { out.add(k); if (out.size > 500) { out.removeAt(0); quitadas++ } }
                                else -> { val i = out.indexOfLast { it.t == k.t }; if (i >= 0) out[i] = k }
                            }
                        }
                        val minimo = Mercado.refrescoMinimoMs(a)
                        chart.actualizar(out, quitadas); panel?.tick(a.clave, out.last().c.toDouble()); comprobarAlertas(a.clave, out.last().c.toDouble()); mostrarPrecio(out, if (refrescoMs < minimo) "live · every ${minimo / 1000}s for this source" else "live")
                    }
                }.start()
            }
            hVivo.postDelayed(this, refrescoMs.coerceAtLeast(maxOf(1_000L, Mercado.refrescoMinimoMs(activo))))
        }
    }
    hVivo.postDelayed(vivo, refrescoMs.coerceAtLeast(1_000L))

    // ── Mercado general + Fear & Greed ──
    fun abrev(x: Double) = when {
        x >= 1e12 -> "$%.2fT".format(x / 1e12); x >= 1e9 -> "$%.1fB".format(x / 1e9)
        x >= 1e6 -> "$%.1fM".format(x / 1e6); else -> "$%,.0f".format(x)
    }
    pagMercado.addView(TextView(this).apply {
        text = "CRYPTO MARKET"; textSize = AppTheme.SP_CAPTION; setTextColor(DexTema.TXT2)
        typeface = AppTheme.bold(context); letterSpacing = 0.08f; setPadding(dp(2), dp(18), 0, dp(10))
    })
    fun stat(titulo: String): Pair<LinearLayout, Pair<TextView, TextView>> {
        val v1 = TextView(this).apply { text = "…"; textSize = AppTheme.SP_BODY; setTextColor(DexTema.TXT); typeface = AppTheme.bold(context); setPadding(0, dp(4), 0, 0) }
        val v2 = TextView(this).apply { textSize = AppTheme.SP_MICRO; setTextColor(DexTema.TXT2) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(AppTheme.R_INNER, DexTema.SURFACE, context)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
            addView(TextView(context).apply { text = titulo; textSize = AppTheme.SP_MICRO; setTextColor(DexTema.TXT2) })
            addView(v1); addView(v2)
        }
        return card to (v1 to v2)
    }
    val (cMcap, tMcap) = stat("Market cap")
    val (cVol, tVol) = stat("Volume 24h")
    val (cDom, tDom) = stat("BTC dominance")
    pagMercado.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        addView(cMcap); addView(cVol); addView(cDom)
    })

    // Fear & Greed: número de color por zona + barra rojo→verde con marcador.
    val tvFng = TextView(this).apply { text = "…"; textSize = 30f; typeface = AppTheme.title(context); setTextColor(DexTema.TXT) }
    val tvFngC = TextView(this).apply { textSize = AppTheme.SP_CAPTION; setTextColor(DexTema.TXT2); setPadding(dp(10), 0, 0, 0) }
    val izq = View(this); val der = View(this)
    val marca = View(this).apply { setBackgroundColor(DexTema.TXT); layoutParams = LinearLayout.LayoutParams(dp(3), dp(14)) }
    val barra = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFFE5484D.toInt(), 0xFFF5A524.toInt(), 0xFFF5D90A.toInt(), 0xFF8BD64A.toInt(), 0xFF2EBD6B.toInt())).apply { cornerRadius = dp(4).toFloat() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(10) }
        izq.layoutParams = LinearLayout.LayoutParams(0, dp(8), 50f); der.layoutParams = LinearLayout.LayoutParams(0, dp(8), 50f)
        addView(izq); addView(marca); addView(der)
    }
    pagMercado.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.cardBg(AppTheme.R_CARD, DexTema.SURFACE, context)
        setPadding(dp(16), dp(14), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) }
        addView(TextView(context).apply { text = "Fear & Greed index"; textSize = AppTheme.SP_CAPTION; setTextColor(DexTema.TXT2) })
        addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; addView(tvFng); addView(tvFngC) })
        addView(barra)
    })

    Thread {
        val g = Mercado.global(); val f = Mercado.fearGreed()
        runOnUiThread {
            if (g != null) {
                tMcap.first.text = abrev(g.mcap)
                tMcap.second.text = "%s%.2f%%".format(if (g.cambio24h >= 0) "+" else "", g.cambio24h)
                tMcap.second.setTextColor(if (g.cambio24h >= 0) DexTema.UP else DexTema.DOWN)
                tVol.first.text = abrev(g.vol24h); tVol.second.text = "all coins"
                tDom.first.text = "%.1f%%".format(g.btcDom); tDom.second.text = "of total cap"
            } else listOf(tMcap, tVol, tDom).forEach { it.first.text = "—" }
            if (f != null) {
                val val0 = f.valor.coerceIn(0, 100)
                tvFng.text = "$val0"; tvFngC.text = f.clasif
                tvFng.setTextColor(when { val0 < 25 -> 0xFFE5484D.toInt(); val0 < 45 -> 0xFFF5A524.toInt(); val0 < 55 -> 0xFFF5D90A.toInt(); val0 < 75 -> 0xFF8BD64A.toInt(); else -> 0xFF2EBD6B.toInt() })
                (izq.layoutParams as LinearLayout.LayoutParams).weight = val0.toFloat().coerceAtLeast(0.01f)
                (der.layoutParams as LinearLayout.LayoutParams).weight = (100 - val0).toFloat().coerceAtLeast(0.01f)
                barra.requestLayout()
            } else { tvFng.text = "—"; tvFngC.text = "unavailable" }
        }
    }.start()

    // ── Mercados: cripto o tradicional; tocar una fila abre su gráfico; "Load more" de 10 en 10 ──
    pagMercado.addView(TextView(this).apply {
        text = "MARKETS"; textSize = AppTheme.SP_CAPTION; setTextColor(DexTema.TXT2)
        typeface = AppTheme.bold(context); letterSpacing = 0.08f; setPadding(dp(2), 0, 0, dp(8))
    })
    var modoLista = prefsG.getInt("lista", 0).coerceIn(0, 2)
    val tvNotaLista = TextView(this).apply { textSize = AppTheme.SP_MICRO; setTextColor(DexTema.TXT2); setPadding(dp(2), dp(6), 0, dp(8)) }
    val notasLista = listOf("By market cap · stablecoins hidden · tap a coin to chart it vs USD",
        "Indices, commodities, FX and large caps (Yahoo Finance) · tap to chart",
        "Your watchlist · add or remove pairs with ☆ next to the pair name")
    val contLista = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.cardBg(AppTheme.R_CARD, DexTema.SURFACE, context)
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }
    val btnMas = TextView(this).apply {
        text = "Load more"; textSize = AppTheme.SP_BODY - 1f; setTextColor(DexTema.LINK); typeface = AppTheme.bold(context)
        gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)); isClickable = true; foreground = Ui.toque()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) }
    }
    val cripto = ArrayList<Mercado.Moneda>(); var paginaCripto = 0
    val bolsaCache = java.util.concurrent.ConcurrentHashMap<String, Mercado.Moneda>()
    var cursor = 0; var numerados = 0
    val tokLista = AtomicInteger(0)

    fun mensajeLista(t: String) = contLista.addView(TextView(this).apply {
        text = t; textSize = AppTheme.SP_CAPTION; setTextColor(DexTema.TXT2); setPadding(dp(12), dp(12), dp(12), dp(12)) })
    fun separador() = View(this).apply {
        setBackgroundColor((DexTema.TXT2 and 0x00FFFFFF) or 0x22000000)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)).apply { leftMargin = dp(12); rightMargin = dp(12) }
    }
    fun filaMercado(num: Int, m: Mercado.Moneda) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10)); isClickable = true; foreground = Ui.toque()
        setOnClickListener { Ui.pulso(this); seleccionar(m.activo); irATrade() }
        addView(TextView(context).apply {
            text = "$num"; textSize = AppTheme.SP_CAPTION; setTextColor(DexTema.TXT2); typeface = AppTheme.mono(context)
            layoutParams = LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)
            addView(TextView(context).apply { text = m.simbolo; textSize = AppTheme.SP_BODY - 1f; setTextColor(DexTema.TXT); typeface = AppTheme.bold(context); maxLines = 1 })
            addView(TextView(context).apply { text = m.nombre; textSize = AppTheme.SP_MICRO; setTextColor(DexTema.TXT2); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        })
        addView(GraficoPrecio(context).apply {
            poner(m.spark.toList())
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(30)).apply { marginEnd = dp(10) }
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val pre = if (m.activo.sinDolar) "" else "$"
            addView(TextView(context).apply {
                text = when { m.precio >= 1 -> "$pre%,.2f".format(m.precio); m.precio > 0 -> "$pre%.6g".format(m.precio); else -> "—" }
                textSize = AppTheme.SP_BODY - 1f; setTextColor(DexTema.TXT); typeface = AppTheme.medium(context); gravity = Gravity.END
            })
            addView(TextView(context).apply {
                text = "%s%.2f%%".format(if (m.cambio24h >= 0) "+" else "", m.cambio24h)
                textSize = AppTheme.SP_MICRO; setTextColor(if (m.cambio24h >= 0) DexTema.UP else DexTema.DOWN); gravity = Gravity.END
            })
        })
    }

    fun mostrarMas(reset: Boolean) {
        val myTok = tokLista.incrementAndGet()
        if (reset) { contLista.removeAllViews(); mensajeLista("Loading market…"); cursor = 0; numerados = 0 }
        val desde = cursor; val modo = modoLista
        btnMas.text = "Loading…"; btnMas.isEnabled = false
        Thread {
            val items: List<Mercado.Moneda>; val avance: Int
            if (modo == 2) {
                val favs = if (desde == 0) Favoritos.lista(this) else emptyList()
                val pool = java.util.concurrent.Executors.newFixedThreadPool(5)
                val futs = favs.map { a -> pool.submit<Mercado.Moneda?> {
                    if (a.tipo == Mercado.Tipo.BOLSA) bolsaCache[a.clave] ?: Mercado.cotizacion(a)?.also { bolsaCache[a.clave] = it }
                    else synchronized(cripto) { cripto.firstOrNull { it.activo.clave == a.clave } } ?: Mercado.velas(a, "1h", 25).takeIf { it.size > 1 }?.let { v ->
                        Mercado.Moneda(a, v.last().c.toDouble(), (v.last().c - v.first().o) / v.first().o * 100.0, FloatArray(v.size) { v[it].c }) }
                } }
                items = futs.mapNotNull { try { it.get(25, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { null } }
                pool.shutdown(); avance = favs.size
            } else if (modo == 0) {
                items = synchronized(cripto) {
                    while (cripto.size < desde + 10) {
                        val pg = Mercado.topCripto(paginaCripto + 1, 100); if (pg.isEmpty()) break
                        cripto.addAll(pg.filter { n -> cripto.none { it.activo.clave == n.activo.clave } }); paginaCripto++
                    }
                    cripto.drop(desde).take(10)
                }
                avance = items.size
            } else {
                val lote = Mercado.BOLSA.drop(desde).take(10)
                val pool = java.util.concurrent.Executors.newFixedThreadPool(5)
                val futs = lote.map { a -> pool.submit<Mercado.Moneda?> { bolsaCache[a.clave] ?: Mercado.cotizacion(a)?.also { bolsaCache[a.clave] = it } } }
                items = futs.mapNotNull { try { it.get(25, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { null } }
                pool.shutdown(); avance = lote.size
            }
            runOnUiThread {
                if (myTok != tokLista.get()) return@runOnUiThread
                if (desde == 0) contLista.removeAllViews()
                if (items.isEmpty() && desde == 0)
                    mensajeLista(when (modo) { 0 -> "Market list unavailable (CoinGecko unreachable)."; 1 -> "Quotes unavailable (Yahoo Finance unreachable)."
                        else -> if (Favoritos.lista(this).isEmpty()) "No pairs in your watchlist yet: open a pair and tap ☆ next to its name." else "Quotes unavailable right now." })
                for (m in items) { if (numerados > 0) contLista.addView(separador()); numerados++; contLista.addView(filaMercado(numerados, m)) }
                cursor = desde + avance
                val fin = when (modo) { 0 -> items.isEmpty(); 1 -> cursor >= Mercado.BOLSA.size; else -> true }
                btnMas.isEnabled = true; btnMas.text = "Load more"
                btnMas.visibility = if (fin) View.GONE else View.VISIBLE
            }
        }.start()
    }

    pagMercado.addView(DexTema.pestanas(this, listOf("Crypto" to null, "Stocks" to null, "★ Watchlist" to null), inicial = modoLista) { k ->
        if (k == modoLista) return@pestanas
        modoLista = k; prefsG.edit().putInt("lista", k).apply(); tvNotaLista.text = notasLista[k]; mostrarMas(true)
    })
    tvNotaLista.text = notasLista[modoLista]
    pagMercado.addView(tvNotaLista); pagMercado.addView(contLista); pagMercado.addView(btnMas)
    btnMas.setOnClickListener { mostrarMas(false) }
    mostrarMas(true)

    // ── Ajustes del gráfico (hoja inferior, como en los exchanges) ──
    fun on(k: String, def: Boolean) = prefsG.getBoolean(k, def)
    cargarMulti = {
        val a = activo
        Thread {
            val v = try { Mercado.velas(a, "1d", 370) } catch (e: Throwable) { emptyList() }
            runOnUiThread {
                if (a.clave != activo.clave || v.size < 3) return@runOnUiThread
                val ult = v.last().c.toDouble()
                val sb = android.text.SpannableStringBuilder()
                for ((et, dias) in listOf("1D" to 1, "7D" to 7, "30D" to 30, "90D" to 90, "1Y" to 365)) {
                    val lim = v.last().t - dias * 86_400L; val i = v.indexOfLast { it.t <= lim }
                    if (i < 0) continue
                    val pc = (ult - v[i].c) / v[i].c * 100
                    sb.append("$et ")
                    val ini = sb.length; sb.append("%+.2f%%".format(pc))
                    sb.setSpan(android.text.style.ForegroundColorSpan(if (pc >= 0) chart.cUp else chart.cDown), ini, sb.length, 0)
                    sb.append("   ")
                }
                tvMulti.setTextColor(DexTema.TXT2); tvMulti.text = sb
            }
        }.start()
    }
    fun aplicarAjustesGrafico() {
        val inv = on("i_inv", false)
        chart.cUp = if (inv) DexTema.DOWN else DexTema.UP; chart.cDown = if (inv) DexTema.UP else DexTema.DOWN
        chart.huecaAlcista = on("i_hueca_up", false); chart.huecaBajista = on("i_hueca_down", false)
        chart.zona = if (on("i_utc", false)) java.util.TimeZone.getTimeZone("UTC") else java.util.TimeZone.getDefault()
        chart.alturaPrecioDp = listOf(180f, 240f, 340f)[prefsG.getInt("altura", 1).coerceIn(0, 2)]
        chart.mostrarCuenta = activo.tipo == Mercado.Tipo.CRYPTO && on("c_cuenta", true)
        if (!on("c_bidask", false)) chart.lineasExtra = emptyList()
        if (on("c_multi", false)) { tvMulti.visibility = View.VISIBLE; cargarMulti?.invoke() } else tvMulti.visibility = View.GONE
        pt.refrescar(); chart.invalidate()
    }
    pl.alMejores = { bid, ask ->
        if (on("c_bidask", false)) chart.lineasExtra = listOfNotNull(
            bid?.let { GraficoTA.LineaPrecio(it.toFloat(), chart.cUp, "Bid") }, ask?.let { GraficoTA.LineaPrecio(it.toFloat(), chart.cDown, "Ask") })
    }
    fun filaDos(a: View, b: View?) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        a.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); addView(a)
        if (b != null) { b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); addView(b) }
        else addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
    }
    fun casilla(t: String, k: String, def: Boolean) = DexTema.casilla(this, t, on(k, def)) { b -> prefsG.edit().putBoolean(k, b).apply(); aplicarAjustesGrafico() }
    fun opcion(titulo: String, opciones: List<String>, sel: Int, alElegir: (Int) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(10))
        addView(TextView(context).apply { text = titulo; textSize = 16f; setTextColor(DexTema.TXT); setPadding(0, 0, 0, dp(8)) })
        addView(DexTema.pestanas(context, opciones.map { it to null }, sel, alElegir))
    }

    fun abrirGuiaVelas() {
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // dibujo: vela alcista y bajista con sus partes
        caja.addView(object : View(this) {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(190))
            override fun onDraw(c: android.graphics.Canvas) {
                val d = resources.displayMetrics.density; p.textSize = 12f * d
                fun vela(cx: Float, up: Boolean) {
                    val col = if (up) chart.cUp else chart.cDown
                    p.color = col; p.strokeWidth = 2f * d
                    c.drawLine(cx, 20f * d, cx, 170f * d, p)
                    c.drawRect(cx - 18f * d, 55f * d, cx + 18f * d, 135f * d, p)
                    p.color = DexTema.TXT2; p.textAlign = android.graphics.Paint.Align.LEFT
                    val tx = cx + 26f * d
                    c.drawText("High", tx, 24f * d, p); c.drawText(if (up) "Close" else "Open", tx, 60f * d, p)
                    c.drawText(if (up) "Open" else "Close", tx, 138f * d, p); c.drawText("Low", tx, 172f * d, p)
                    p.color = col; p.textAlign = android.graphics.Paint.Align.CENTER
                    c.drawText(if (up) "Bullish" else "Bearish", cx, 188f * d, p)
                }
                vela(width * 0.25f, true); vela(width * 0.68f, false)
            }
        })
        fun parrafo(t: String, cuerpo: String) {
            caja.addView(TextView(this).apply { text = t; textSize = 16f; setTextColor(DexTema.TXT); typeface = AppTheme.bold(context); setPadding(0, dp(14), 0, dp(4)) })
            caja.addView(TextView(this).apply { text = cuerpo; textSize = 14f; setTextColor(DexTema.TXT2); setLineSpacing(0f, 1.3f) })
        }
        parrafo("Anatomy", "Each candle is one period of the chosen interval. The body spans open to close; the wicks reach the period's high and low.")
        parrafo("Bullish vs bearish", "Close above open is bullish (green by default); close below open is bearish (red). You can swap the colors or use hollow bodies in Interface.")
        parrafo("Doji", "Open ≈ close, tiny body: indecision. Meaningful after a strong move.")
        parrafo("Hammer / hanging man", "Small body at the top, long lower wick (≥ 2× body). After a fall it can signal a bounce (hammer); after a rise, weakness (hanging man).")
        parrafo("Shooting star / inverted hammer", "Small body at the bottom, long upper wick: buyers pushed up and were rejected.")
        parrafo("Engulfing", "A body that fully covers the previous one in the opposite color: a possible reversal in its direction.")
        parrafo("Marubozu", "Large body with little or no wicks: strong one-sided pressure for the whole period.")
        parrafo("Note", "Patterns are hints, not signals on their own. Confirm with volume, trend and higher intervals.")
        DexTema.hoja(this, "Candlestick guide", caja).show()
    }

    fun abrirInterfaz() {
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        caja.addView(opcion("Candlestick color", listOf("Green up · red down", "Red up · green down"), if (on("i_inv", false)) 1 else 0) { k ->
            prefsG.edit().putBoolean("i_inv", k == 1).apply(); aplicarAjustesGrafico(); cargar() })
        caja.addView(opcion("Bullish candlestick", listOf("Filled", "Hollow"), if (on("i_hueca_up", false)) 1 else 0) { k ->
            prefsG.edit().putBoolean("i_hueca_up", k == 1).apply(); aplicarAjustesGrafico() })
        caja.addView(opcion("Bearish candlestick", listOf("Filled", "Hollow"), if (on("i_hueca_down", false)) 1 else 0) { k ->
            prefsG.edit().putBoolean("i_hueca_down", k == 1).apply(); aplicarAjustesGrafico() })
        val tz = java.util.TimeZone.getDefault(); val off = tz.getOffset(System.currentTimeMillis()) / 60_000
        val local = "Local (UTC%s%d%s)".format(if (off >= 0) "+" else "-", Math.abs(off) / 60, if (off % 60 != 0) ":%02d".format(Math.abs(off) % 60) else "")
        caja.addView(opcion("Candlestick time", listOf(local, "UTC"), if (on("i_utc", false)) 1 else 0) { k ->
            prefsG.edit().putBoolean("i_utc", k == 1).apply(); aplicarAjustesGrafico() })
        caja.addView(TextView(this).apply {
            text = "Gestures: drag to pan, pinch to zoom, tap or long-press for the crosshair and candle details."
            textSize = 13f; setTextColor(DexTema.TXT2); setPadding(0, dp(8), 0, 0) })
        DexTema.hoja(this, "Interface", caja).show()
    }

    fun abrirAjustesGrafico() {
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dlg: android.app.Dialog? = null
        fun tile(res: Int, t: String, accion: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isClickable = true; foreground = Ui.toque()
            contentDescription = t; setOnClickListener { dlg?.dismiss(); accion() }
            addView(android.widget.ImageView(context).apply {
                setImageResource(res); setColorFilter(DexTema.TXT); scaleType = android.widget.ImageView.ScaleType.CENTER
                background = DexTema.fondo(context, DexTema.CHIP, 16f); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            })
            addView(TextView(context).apply { text = t; textSize = 13f; setTextColor(DexTema.TXT); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        caja.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(4))
            addView(tile(R.drawable.ic_chart, "Indicators") { abrirAjustesTA(chart) { guardarCfg() } })
            addView(tile(R.drawable.ic_edit, if (cajaDibujo.visibility == View.VISIBLE) "Hide draw" else "Draw") {
                val ver = cajaDibujo.visibility != View.VISIBLE
                cajaDibujo.visibility = if (ver) View.VISIBLE else View.GONE; prefsG.edit().putBoolean("ver_dibujo", ver).apply()
                if (!ver) { chart.herramienta = 0; marcarHerr(0) }
            })
            addView(tile(R.drawable.ic_gear, "Interface") { abrirInterfaz() })
            addView(tile(R.drawable.ic_book, "Candlestick\nguide") { abrirGuiaVelas() })
        })
        caja.addView(DexTema.seccion(this, "Order display"))
        caja.addView(filaDos(casilla("Position info", "o_pos", true), casilla("Trigger orders", "o_trig", true)))
        caja.addView(filaDos(casilla("TP/SL", "o_tpsl", true), casilla("Liquidation line", "o_liq", true)))
        caja.addView(filaDos(casilla("Breakeven price", "o_be", false), null))
        caja.addView(DexTema.seccion(this, "Chart display"))
        caja.addView(filaDos(casilla("Countdown", "c_cuenta", true), casilla("Buy & sell prices", "c_bidask", false)))
        caja.addView(filaDos(casilla("Multi-period changes", "c_multi", false), null))
        caja.addView(DexTema.seccion(this, "Height"))
        caja.addView(DexTema.pestanas(this, listOf("Minimum" to null, "Default" to null, "Max" to null), prefsG.getInt("altura", 1).coerceIn(0, 2)) { k ->
            prefsG.edit().putInt("altura", k).apply(); aplicarAjustesGrafico() })
        dlg = DexTema.hoja(this, "Chart Settings", caja).also { it.show() }
    }
    btnAjustes.setOnClickListener { abrirAjustesGrafico() }
    aplicarAjustesGrafico()

    // ── Selector de par (tocar el nombre del par) y modo Spot/Futures del simulador ──
    tvPar.setOnClickListener { abrirSelectorPar { seleccionar(it) } }
    btnAlerta.setOnClickListener { abrirAlertas(activo, chart.velasActuales().lastOrNull()?.c?.toDouble() ?: 0.0) }
    alCambiarTab = { k -> if (k < 2) pt.ponerModo(k == 1) }
    if (tabHome < 2) pt.ponerModo(tabHome == 1)

    return scroll
}

/**
 * Ajustes del gráfico técnico. Cada interruptor y cada periodo se aplica y se guarda
 * al momento (no hay "Apply" que olvidar); un periodo fuera de rango se marca en el
 * campo y no se aplica. Usa el AlertDialog de AppCompat para heredar el tema oscuro.
 */
internal fun MainActivity.abrirAjustesTA(chart: GraficoTA, alCambiar: () -> Unit) {
    val c = chart.cfg
    val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(8)) }
    fun seccion(t: String) = caja.addView(TextView(this).apply {
        text = t.uppercase(); textSize = AppTheme.SP_MICRO; setTextColor(DexTema.TXT2); typeface = AppTheme.bold(context)
        letterSpacing = 0.08f; setPadding(0, dp(14), 0, dp(2))
    })
    fun fmtR(x: Float) = if (x == Math.floor(x.toDouble()).toFloat()) x.toInt().toString() else x.toString()
    fun num(ini: Float, rango: ClosedFloatingPointRange<Float>, entero: Boolean = true,
            valida: (Float) -> String? = { null }, set: (Float) -> Unit): EditText = DexTema.campo(this, "").apply {
        setText(if (entero) ini.toInt().toString() else ini.toString())
        inputType = if (entero) android.text.InputType.TYPE_CLASS_NUMBER
                    else android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        gravity = Gravity.CENTER; textSize = AppTheme.SP_CAPTION; setSingleLine()
        layoutParams = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                val x = e.toString().replace(',', '.').toFloatOrNull()
                val err = when { x == null -> "number"; x !in rango -> "${fmtR(rango.start)}–${fmtR(rango.endInclusive)}"; else -> valida(x) }
                if (err != null) error = err else { error = null; set(if (entero) x!!.toInt().toFloat() else x!!); alCambiar() }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        })
    }
    fun fila(titulo: String, on: Boolean, setOn: (Boolean) -> Unit, vararg extras: View) {
        val sw = android.widget.Switch(this).apply {
            text = titulo; isChecked = on; setTextColor(DexTema.TXT); textSize = AppTheme.SP_BODY - 1f
            Ui.tintarInterruptor(this)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnCheckedChangeListener { _, b -> setOn(b); alCambiar() }
        }
        caja.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3))
            addView(sw); extras.forEach { addView(it) }
        })
    }
    fun tipoMedia(m: GraficoTA.Media) = TextView(this).apply {
        fun pintar() { text = if (m.ema) "EMA" else "SMA"; setTextColor(if (m.ema) DexTema.LINK else DexTema.WARN) }
        textSize = AppTheme.SP_CAPTION; typeface = AppTheme.bold(context); gravity = Gravity.CENTER
        background = Ui.cardBg(AppTheme.R_CHIP, DexTema.CHIP, context); setPadding(dp(10), dp(6), dp(10), dp(6))
        isClickable = true; foreground = Ui.toque(); pintar()
        setOnClickListener { m.ema = !m.ema; pintar(); alCambiar() }
    }

    seccion("Moving averages (tap SMA/EMA to switch)")
    c.medias.forEachIndexed { k, m ->
        fila("MA ${k + 1}", m.on, { m.on = it }, tipoMedia(m), num(m.n.toFloat(), 2f..400f) { m.n = it.toInt() })
    }
    seccion("Overlays")
    fila("Bollinger (period, σ)", c.bb, { c.bb = it }, num(c.bbN.toFloat(), 5f..100f) { c.bbN = it.toInt() },
        num(c.bbK, 0.5f..4f, entero = false) { c.bbK = it })
    fila("VWAP", c.vwap, { c.vwap = it })
    fila("Parabolic SAR", c.sar, { c.sar = it })
    seccion("Panels")
    fila("Volume", c.vol, { c.vol = it })
    fila("RSI", c.rsi, { c.rsi = it }, num(c.rsiN.toFloat(), 2f..100f) { c.rsiN = it.toInt() })
    fila("MACD", c.macd, { c.macd = it },
        num(c.mF.toFloat(), 2f..100f, valida = { if (it >= c.mS) "< slow" else null }) { c.mF = it.toInt() },
        num(c.mS.toFloat(), 3f..200f, valida = { if (it <= c.mF) "> fast" else null }) { c.mS = it.toInt() },
        num(c.mSig.toFloat(), 2f..50f) { c.mSig = it.toInt() })
    fila("Stochastic", c.stoch, { c.stoch = it }, num(c.stK.toFloat(), 2f..100f) { c.stK = it.toInt() }, num(c.stD.toFloat(), 1f..20f) { c.stD = it.toInt() })
    fila("ATR", c.atr, { c.atr = it }, num(c.atrN.toFloat(), 2f..100f) { c.atrN = it.toInt() })
    fila("OBV", c.obv, { c.obv = it })
    fila("CCI", c.cci, { c.cci = it }, num(c.cciN.toFloat(), 5f..100f) { c.cciN = it.toInt() })
    fila("Williams %R", c.wr, { c.wr = it }, num(c.wrN.toFloat(), 2f..100f) { c.wrN = it.toInt() })
    caja.addView(TextView(this).apply {
        text = "Changes apply and save instantly. Periods longer than the loaded candles (500) aren't drawn."
        textSize = AppTheme.SP_MICRO; setTextColor(DexTema.TXT2); setPadding(0, dp(12), 0, 0)
    })

    DexTema.dialogo(this).setTitle("Chart settings")
        .setView(ScrollView(this).apply { addView(caja) })
        .setPositiveButton("Done", null)
        .setNeutralButton("Reset") { _, _ -> c.deJson(GraficoTA.Config().aJson()); alCambiar() }
        .show()
}

/**
 * Ficha completa del activo abierto en el gráfico: descarga [Mercado.info] en segundo
 * plano y la muestra por secciones (etiqueta → valor), con descripción y enlaces.
 */
internal fun MainActivity.abrirInfoActivo(a: Mercado.Activo) {
    val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(12)) }
    val estado = TextView(this).apply {
        text = "Loading…"; textSize = AppTheme.SP_BODY; setTextColor(DexTema.TXT2); setPadding(0, dp(12), 0, dp(12))
    }
    caja.addView(estado)
    val dlg = DexTema.dialogo(this).setTitle(a.nombre)
        .setView(ScrollView(this).apply { addView(caja) })
        .setPositiveButton("Close", null).show()

    fun seccion(t: String) = caja.addView(TextView(this).apply {
        text = t.uppercase(); textSize = AppTheme.SP_MICRO; setTextColor(DexTema.TXT2); typeface = AppTheme.bold(context)
        letterSpacing = 0.08f; setPadding(0, dp(16), 0, dp(4))
    })
    fun fila(k: String, v: String) = caja.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3))
        addView(TextView(context).apply {
            text = k; textSize = AppTheme.SP_CAPTION; setTextColor(DexTema.TXT2)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(context).apply {
            text = v; textSize = AppTheme.SP_CAPTION; typeface = AppTheme.bold(context); gravity = Gravity.END
            setTextIsSelectable(true)
            setTextColor(when { v.startsWith("+") -> DexTema.UP; v.startsWith("-") -> DexTema.DOWN; else -> DexTema.TXT })
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
        })
    })

    Thread {
        val info = try { Mercado.info(a) } catch (e: Throwable) { null }
        runOnUiThread {
            if (!dlg.isShowing) return@runOnUiThread
            caja.removeAllViews()
            if (info == null) { estado.text = "Details unavailable (data source unreachable)"; caja.addView(estado); return@runOnUiThread }
            dlg.setTitle(info.titulo)
            for ((titulo, filas) in info.secciones) {
                if (filas.isEmpty()) continue
                seccion(titulo); filas.forEach { (k, v) -> fila(k, v) }
            }
            if (info.descripcion.isNotBlank()) {
                seccion("About")
                val largo = info.descripcion.length > 700
                val tv = TextView(this).apply {
                    textSize = AppTheme.SP_CAPTION; setTextColor(DexTema.TXT); setLineSpacing(0f, 1.2f); setTextIsSelectable(true)
                    text = if (largo) info.descripcion.take(700).substringBeforeLast(' ') + "…" else info.descripcion
                }
                caja.addView(tv)
                if (largo) caja.addView(TextView(this).apply {
                    text = "Read more"; textSize = AppTheme.SP_CAPTION; typeface = AppTheme.bold(context); setTextColor(DexTema.LINK)
                    setPadding(0, dp(6), 0, 0); isClickable = true
                    setOnClickListener { tv.text = info.descripcion; visibility = View.GONE }
                })
                if (info.fuenteDesc.isNotEmpty()) caja.addView(TextView(this).apply {
                    text = "Source: ${info.fuenteDesc}"; textSize = AppTheme.SP_MICRO; setTextColor(DexTema.TXT2); setPadding(0, dp(4), 0, 0)
                })
            }
            if (info.enlaces.isNotEmpty()) {
                seccion("Links")
                info.enlaces.forEach { (t, url) ->
                    caja.addView(TextView(this).apply {
                        text = "↗  $t"; textSize = AppTheme.SP_BODY - 1f; typeface = AppTheme.bold(context); setTextColor(DexTema.LINK)
                        setPadding(0, dp(7), 0, dp(7)); isClickable = true; foreground = Ui.toque()
                        setOnClickListener {
                            try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                            catch (e: Throwable) { Toast.makeText(this@abrirInfoActivo, url, Toast.LENGTH_LONG).show() }
                        }
                    })
                }
                caja.addView(TextView(this).apply {
                    text = "Links open in your browser, outside the app's Tor setting."
                    textSize = AppTheme.SP_MICRO; setTextColor(DexTema.TXT2); setPadding(0, dp(4), 0, 0)
                })
            }
            caja.addView(TextView(this).apply {
                text = "Market data: CoinGecko / Yahoo Finance. Computed metrics use daily closes of the last year. Not financial advice."
                textSize = AppTheme.SP_MICRO; setTextColor(DexTema.TXT2); setPadding(0, dp(14), 0, 0)
            })
        }
    }.start()
}

/** Comprueba las alertas de un par con su precio en vivo; las cumplidas se avisan aquí también. */
internal fun MainActivity.comprobarAlertas(clave: String, precio: Double) {
    val t = Alertas.comprobar(this, clave, precio)
    if (t.isNotEmpty()) Toast.makeText(this, t.joinToString("\n"), Toast.LENGTH_LONG).show()
}

/** Cada 20 s: precio de 1 min de los pares con alertas que no están en el gráfico. */
internal fun MainActivity.vigilarAlertas(actual: String) {
    val pares = Alertas.claves(this).filterKeys { it != actual }
    if (pares.isEmpty()) return
    Thread {
        val precios = pares.mapNotNull { (clave, js) -> Mercado.Activo.deJson(js)?.let { a ->
            Mercado.velas(a, "1m", 2).lastOrNull()?.let { clave to it.c.toDouble() } } }
        runOnUiThread { precios.forEach { (c, p) -> comprobarAlertas(c, p) } }
    }.start()
}

/** Alertas de precio: crear para el par abierto y ver / borrar todas. */
internal fun MainActivity.abrirAlertas(a: Mercado.Activo, precio: Double) {
    val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
    var tipo = 0
    val et = DexTema.campo(this, "Price (USD)", true).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine() }
    caja.addView(TextView(this).apply { text = "${a.par} · now ${if (precio > 0) fmtSim(precio) else "—"}"; textSize = AppTheme.SP_BODY
        setTextColor(DexTema.TXT); typeface = AppTheme.bold(context); setPadding(0, 0, 0, dp(8)) })
    caja.addView(DexTema.pestanas(this, listOf("Above" to null, "Below" to null, "Move %" to null), 0) { k -> tipo = k
        et.hint = if (k == 2) "Move in % (e.g. 5)" else "Price (USD)" }
        .apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } })
    caja.addView(et)
    caja.addView(TextView(this).apply {
        text = "Checked while the app is open: live for this pair, every 20 s for the others. Fires once, then it's removed."
        textSize = AppTheme.SP_MICRO; setTextColor(DexTema.TXT2); setPadding(0, dp(4), 0, dp(10))
    })
    val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun pintar() {
        lista.removeAllViews()
        val l = Alertas.lista(this)
        lista.addView(TextView(this).apply { text = "ACTIVE ALERTS (${l.size})"; textSize = AppTheme.SP_MICRO; setTextColor(DexTema.TXT2)
            typeface = AppTheme.bold(context); letterSpacing = 0.08f; setPadding(0, dp(4), 0, dp(4)) })
        if (l.isEmpty()) lista.addView(TextView(this).apply { text = "None."; textSize = AppTheme.SP_CAPTION; setTextColor(DexTema.TXT2) })
        for (x in l) lista.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4))
            addView(TextView(context).apply { text = x.texto(); textSize = AppTheme.SP_CAPTION; setTextColor(DexTema.TXT)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(TextView(context).apply { text = "✕"; textSize = AppTheme.SP_BODY; setTextColor(DexTema.DOWN); setPadding(dp(10), dp(4), dp(6), dp(4))
                isClickable = true; foreground = Ui.toque(); setOnClickListener { Alertas.borrar(this@abrirAlertas, x.id); pintar() } })
        })
    }
    caja.addView(lista); pintar()
    val dlg = DexTema.dialogo(this).setTitle("Price alerts").setView(ScrollView(this).apply { addView(caja) })
        .setPositiveButton("Add alert", null).setNegativeButton("Close", null).show()
    dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val v = et.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0
        val err = Alertas.agregar(this, a, tipo, v, precio)
        if (err != null) Toast.makeText(this, err, Toast.LENGTH_SHORT).show() else { et.setText(""); pintar() }
    }
    if (android.os.Build.VERSION.SDK_INT >= 33 && !Avisos.permitidos(this))
        requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7311)
}
