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
        background = AppTheme.fondoApp(); visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }
    val page = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(AppTheme.PAD_SIDE), dp(24), dp(AppTheme.PAD_SIDE), dp(32))
    }
    scroll.addView(page)

    page.addView(TextView(this).apply { text = "Hunter"; textSize = 26f; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.title(context) })

    // ── Precio BTC ──
    val tvPrecio = TextView(this).apply { textSize = 28f; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.title(context); text = "…"; setPadding(0, dp(8), 0, 0) }
    val tvCambio = TextView(this).apply { textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context); setPadding(0, dp(2), 0, dp(8)) }
    page.addView(tvPrecio); page.addView(tvCambio)

    // ── Gráfico (config, dibujos e intervalo se guardan) ──
    val prefsG = getSharedPreferences("home_chart", android.content.Context.MODE_PRIVATE)
    val intervalos = listOf(
        Triple("1s", "1s", 1L), Triple("1m", "1m", 60L), Triple("1h", "1h", 3600L),
        Triple("4h", "4h", 14400L), Triple("12h", "12h", 43200L), Triple("1D", "1d", 86400L),
        Triple("3D", "3d", 259200L), Triple("1W", "1w", 604800L), Triple("1M", "1M", 2592000L))
    var intervalo = prefsG.getString("intervalo", "1h") ?: "1h"
    val chart = GraficoTA(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        intervaloSeg = intervalos.firstOrNull { it.second == intervalo }?.third ?: 3600L
        cfg.deJson(prefsG.getString("cfg", null))
        cargarDibujos(prefsG.getString("dibujos", null))
    }
    fun guardarCfg() { prefsG.edit().putString("cfg", chart.cfg.aJson()).apply(); chart.refrescarConfig() }
    chart.alCambiarDibujos = { prefsG.edit().putString("dibujos", chart.dibujosJson()).apply() }
    chart.alPedirNota = { t, p ->
        val et = Ui.input(this, "Note text")
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Add note")
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) })
            .setPositiveButton("Add") { _, _ -> chart.agregarNota(t, p, et.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    fun mostrarPrecio(v: List<GraficoPrecio.Vela>, sufijo: String) {
        val fin = v.last().c; val ini = v.first().o
        val pct = if (ini > 0) (fin - ini) / ini * 100f else 0f
        tvPrecio.text = "%,.2f USD".format(fin)
        tvCambio.text = "%s%.2f%% · %s".format(if (pct >= 0) "+" else "", pct, sufijo)
        tvCambio.setTextColor(if (pct >= 0) AppTheme.OK else AppTheme.RED)
    }
    val tok = AtomicInteger(0)
    fun cargar() {
        val myTok = tok.incrementAndGet()
        tvCambio.text = "Loading…"; tvCambio.setTextColor(AppTheme.TXT_SEC)
        val iv = intervalo
        Thread {
            val v = Mercado.velasBtc(iv, 500)
            runOnUiThread {
                if (myTok != tok.get()) return@runOnUiThread
                if (v.isEmpty()) { tvCambio.text = "Chart unavailable (exchange unreachable)"; return@runOnUiThread }
                chart.poner(v); mostrarPrecio(v, "$iv candles")
            }
        }.start()
    }

    // Fila de chips de selección única.
    fun filaChips(etiquetas: List<String>, sel: Int, alElegir: (Int) -> Unit): Pair<HorizontalScrollView, (Int) -> Unit> {
        val chips = ArrayList<TextView>()
        fun pintar(s: Int) = chips.forEachIndexed { k, c ->
            val on = k == s
            c.background = Ui.cardBg(AppTheme.R_CHIP, if (on) AppTheme.BG_KEY else AppTheme.BG_CARD, this)
            c.setTextColor(if (on) AppTheme.TXT_PRI else AppTheme.TXT_SEC)
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
    fun rotuloFila(t: String) = page.addView(TextView(this).apply {
        text = t; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED); setPadding(dp(2), dp(2), 0, dp(4))
    })

    // ── Intervalo de velas ──
    rotuloFila("Candle interval")
    page.addView(filaChips(intervalos.map { it.first }, intervalos.indexOfFirst { it.second == intervalo }.coerceAtLeast(0)) { k ->
        intervalo = intervalos[k].second; chart.intervaloSeg = intervalos[k].third
        prefsG.edit().putString("intervalo", intervalo).apply(); cargar()
    }.first)

    // ── Frecuencia de actualización ──
    val refrescos = listOf("Off" to 0L, "1s" to 1_000L, "5s" to 5_000L, "10s" to 10_000L, "30s" to 30_000L,
        "1m" to 60_000L, "5m" to 300_000L, "10m" to 600_000L)
    var refrescoMs = prefsG.getLong("refresco", 10_000L)
    val hVivo = android.os.Handler(android.os.Looper.getMainLooper())
    val enCurso = java.util.concurrent.atomic.AtomicBoolean(false)
    lateinit var vivo: Runnable
    rotuloFila("Live update")
    page.addView(filaChips(refrescos.map { it.first }, refrescos.indexOfFirst { it.second == refrescoMs }.coerceAtLeast(0)) { k ->
        refrescoMs = refrescos[k].second; prefsG.edit().putLong("refresco", refrescoMs).apply()
        hVivo.removeCallbacks(vivo); hVivo.post(vivo)
    }.first)

    // ── Herramientas de dibujo ──
    val tvAyuda = TextView(this).apply { textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED); setPadding(dp(2), 0, 0, dp(6)) }
    val ayudas = listOf("Drag to pan · pinch to zoom", "Tap the chart to place a horizontal line",
        "Tap two points for a trend line", "Tap the chart where the note goes")
    tvAyuda.text = ayudas[0]
    rotuloFila("Draw")
    var marcarHerr: (Int) -> Unit = {}
    val (filaHerr, marcar) = filaChips(listOf("✋ Move", "— Line", "╱ Trend", "✎ Note", "↶ Undo", "✕ Clear", "⚙ Indicators"), 0) { k ->
        when (k) {
            in 0..3 -> { chart.herramienta = k; tvAyuda.text = ayudas[k] }
            4 -> { chart.deshacer(); marcarHerr(chart.herramienta) }
            5 -> { marcarHerr(chart.herramienta)
                if (chart.dibujos.isNotEmpty()) androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Clear drawings")
                    .setMessage("Remove all ${chart.dibujos.size} lines and notes?")
                    .setPositiveButton("Clear") { _, _ -> chart.borrarTodo() }.setNegativeButton("Cancel", null).show() }
            6 -> { marcarHerr(chart.herramienta); abrirAjustesTA(chart) { guardarCfg() } }
        }
    }
    marcarHerr = marcar
    page.addView(filaHerr); page.addView(tvAyuda)
    page.addView(chart)
    cargar()

    // ── En vivo: pide solo las últimas velas y las fusiona (barato aunque sea cada segundo) ──
    vivo = object : Runnable {
        override fun run() {
            if (!scroll.isAttachedToWindow) return
            if (refrescoMs <= 0L) { hVivo.postDelayed(this, 2_000); return }
            if (scroll.isShown && chart.velasActuales().size > 1 && enCurso.compareAndSet(false, true)) {
                val iv = intervalo
                Thread {
                    val nuevas = try { Mercado.velasBtc(iv, 3) } catch (e: Throwable) { emptyList() }
                    runOnUiThread {
                        enCurso.set(false)
                        if (iv != intervalo || nuevas.isEmpty() || !scroll.isAttachedToWindow) return@runOnUiThread
                        val out = ArrayList(chart.velasActuales()); var quitadas = 0
                        for (k in nuevas) {
                            val ult = out.last()
                            when {
                                k.t == ult.t -> out[out.size - 1] = k
                                k.t > ult.t -> { out.add(k); if (out.size > 500) { out.removeAt(0); quitadas++ } }
                                else -> { val i = out.indexOfLast { it.t == k.t }; if (i >= 0) out[i] = k }
                            }
                        }
                        chart.actualizar(out, quitadas); mostrarPrecio(out, "live")
                    }
                }.start()
            }
            hVivo.postDelayed(this, refrescoMs.coerceAtLeast(1_000L))
        }
    }
    hVivo.postDelayed(vivo, refrescoMs.coerceAtLeast(1_000L))

    // ── Mercado general + Fear & Greed ──
    fun abrev(x: Double) = when {
        x >= 1e12 -> "$%.2fT".format(x / 1e12); x >= 1e9 -> "$%.1fB".format(x / 1e9)
        x >= 1e6 -> "$%.1fM".format(x / 1e6); else -> "$%,.0f".format(x)
    }
    page.addView(TextView(this).apply {
        text = "CRYPTO MARKET"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.bold(context); letterSpacing = 0.08f; setPadding(dp(2), dp(18), 0, dp(10))
    })
    fun stat(titulo: String): Pair<LinearLayout, Pair<TextView, TextView>> {
        val v1 = TextView(this).apply { text = "…"; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.bold(context); setPadding(0, dp(4), 0, 0) }
        val v2 = TextView(this).apply { textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_SEC) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
            addView(TextView(context).apply { text = titulo; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_SEC) })
            addView(v1); addView(v2)
        }
        return card to (v1 to v2)
    }
    val (cMcap, tMcap) = stat("Market cap")
    val (cVol, tVol) = stat("Volume 24h")
    val (cDom, tDom) = stat("BTC dominance")
    page.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        addView(cMcap); addView(cVol); addView(cDom)
    })

    // Fear & Greed: número de color por zona + barra rojo→verde con marcador.
    val tvFng = TextView(this).apply { text = "…"; textSize = 30f; typeface = AppTheme.title(context); setTextColor(AppTheme.TXT_PRI) }
    val tvFngC = TextView(this).apply { textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); setPadding(dp(10), 0, 0, 0) }
    val izq = View(this); val der = View(this)
    val marca = View(this).apply { setBackgroundColor(AppTheme.TXT_PRI); layoutParams = LinearLayout.LayoutParams(dp(3), dp(14)) }
    val barra = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFFE5484D.toInt(), 0xFFF5A524.toInt(), 0xFFF5D90A.toInt(), 0xFF8BD64A.toInt(), 0xFF2EBD6B.toInt())).apply { cornerRadius = dp(4).toFloat() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(10) }
        izq.layoutParams = LinearLayout.LayoutParams(0, dp(8), 50f); der.layoutParams = LinearLayout.LayoutParams(0, dp(8), 50f)
        addView(izq); addView(marca); addView(der)
    }
    page.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.cardBg(AppTheme.R_CARD, AppTheme.BG_CARD, context)
        setPadding(dp(16), dp(14), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) }
        addView(TextView(context).apply { text = "Fear & Greed index"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC) })
        addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; addView(tvFng); addView(tvFngC) })
        addView(barra)
    })

    Thread {
        val g = Mercado.global(); val f = Mercado.fearGreed()
        runOnUiThread {
            if (g != null) {
                tMcap.first.text = abrev(g.mcap)
                tMcap.second.text = "%s%.2f%%".format(if (g.cambio24h >= 0) "+" else "", g.cambio24h)
                tMcap.second.setTextColor(if (g.cambio24h >= 0) AppTheme.OK else AppTheme.RED)
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

    // ── Top 10 (sin stablecoins) ──
    page.addView(TextView(this).apply {
        text = "TOP 10 · NO STABLECOINS"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.bold(context); letterSpacing = 0.08f; setPadding(dp(2), 0, 0, dp(10))
    })
    val cont10 = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.cardBg(AppTheme.R_CARD, AppTheme.BG_CARD, context)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) }
        addView(TextView(this@buildHomeTab).apply { text = "Loading market…"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); setPadding(dp(12), dp(12), dp(12), dp(12)) })
    }
    page.addView(cont10)
    Thread {
        val lista = Mercado.topMonedas(10)
        runOnUiThread {
            cont10.removeAllViews()
            if (lista.isEmpty()) {
                cont10.addView(TextView(this).apply { text = "Market list unavailable (CoinGecko unreachable)."; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); setPadding(dp(12), dp(12), dp(12), dp(12)) })
                return@runOnUiThread
            }
            for ((idx, m) in lista.withIndex()) {
                cont10.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    addView(TextView(context).apply {
                        text = "${idx + 1}"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_MUTED); typeface = AppTheme.mono(context)
                        layoutParams = LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT)
                    })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)
                        addView(TextView(context).apply { text = m.simbolo; textSize = AppTheme.SP_BODY - 1f; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.bold(context) })
                        addView(TextView(context).apply { text = m.nombre; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    })
                    addView(GraficoPrecio(context).apply {
                        poner(m.spark.toList())
                        layoutParams = LinearLayout.LayoutParams(dp(56), dp(30)).apply { marginEnd = dp(10) }
                    })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        addView(TextView(context).apply {
                            text = if (m.precio >= 1) "$%,.2f".format(m.precio) else "$%.4f".format(m.precio)
                            textSize = AppTheme.SP_BODY - 1f; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.medium(context); gravity = Gravity.END
                        })
                        addView(TextView(context).apply {
                            text = "%s%.2f%%".format(if (m.cambio24h >= 0) "+" else "", m.cambio24h)
                            textSize = AppTheme.SP_MICRO; setTextColor(if (m.cambio24h >= 0) AppTheme.OK else AppTheme.RED); gravity = Gravity.END
                        })
                    })
                })
                if (idx < lista.size - 1) cont10.addView(View(this).apply {
                    setBackgroundColor((AppTheme.TXT_MUTED and 0x00FFFFFF) or 0x22000000)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)).apply { leftMargin = dp(12); rightMargin = dp(12) }
                })
            }
        }
    }.start()

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
        text = t.uppercase(); textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.bold(context)
        letterSpacing = 0.08f; setPadding(0, dp(14), 0, dp(2))
    })
    fun fmtR(x: Float) = if (x == Math.floor(x.toDouble()).toFloat()) x.toInt().toString() else x.toString()
    fun num(ini: Float, rango: ClosedFloatingPointRange<Float>, entero: Boolean = true,
            valida: (Float) -> String? = { null }, set: (Float) -> Unit): EditText = Ui.input(this, "").apply {
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
            text = titulo; isChecked = on; setTextColor(AppTheme.TXT_PRI); textSize = AppTheme.SP_BODY - 1f
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
        fun pintar() { text = if (m.ema) "EMA" else "SMA"; setTextColor(if (m.ema) AppTheme.ACCENT2 else AppTheme.WARN) }
        textSize = AppTheme.SP_CAPTION; typeface = AppTheme.bold(context); gravity = Gravity.CENTER
        background = Ui.cardBg(AppTheme.R_CHIP, AppTheme.BG_KEY, context); setPadding(dp(10), dp(6), dp(10), dp(6))
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
        textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED); setPadding(0, dp(12), 0, 0)
    })

    androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Chart settings")
        .setView(ScrollView(this).apply { addView(caja) })
        .setPositiveButton("Done", null)
        .setNeutralButton("Reset") { _, _ -> c.deJson(GraficoTA.Config().aJson()); alCambiar() }
        .show()
}
