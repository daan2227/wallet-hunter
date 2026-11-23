package com.hunter.btc

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/**
 * Replay y backtesting del gráfico del DEx.
 *  - Replay: oculta las velas a partir de un punto y las va mostrando una a una
 *    (reproducir/pausa, paso a paso, velocidad). Se opera con una cuenta de replay
 *    aparte (10.000 VUSD, sin tocar la de prueba) al cierre de cada vela.
 *  - Backtest: prueba una estrategia (cruce de medias, RSI, Bollinger) sobre hasta
 *    1000 velas del intervalo y enseña resultados, curva y operaciones en el gráfico.
 * Mientras dura el replay, el DEx no mete precios en vivo en el gráfico ni deja
 * operar la cuenta normal con precios del pasado ([alCambiar] avisa al Home).
 */
class PanelReplay(private val act: MainActivity, private val chart: GraficoTA,
                  private val activo: () -> Mercado.Activo, private val intervalo: () -> String,
                  private val alCambiar: (Boolean) -> Unit) {
    private fun dp(v: Int) = act.dp(v)
    var activo_ = false; private set
    private var velas: List<GraficoPrecio.Vela> = emptyList()
    private var cursor = 0
    private var reproduciendo = false
    private var velocidad = 1
    private val h = android.os.Handler(android.os.Looper.getMainLooper())

    // cuenta de replay: una posición a la vez, todo el capital, comisión taker
    private var capital = 10_000.0; private var pos = 0; private var entrada = 0.0; private var capEnt = 0.0
    private val operaciones = ArrayList<Backtest.Trade>()
    private val com = Simulador.COM_TAKER

    private fun texto(sp: Float, color: Int, negrita: Boolean = false) = TextView(act).apply {
        textSize = sp; setTextColor(color); typeface = if (negrita) AppTheme.bold(act) else AppTheme.body(act) }
    private fun boton(t: String, color: Int = DexTema.TXT, accion: () -> Unit) = TextView(act).apply {
        text = t; textSize = 14f; setTextColor(color); typeface = AppTheme.medium(act); gravity = Gravity.CENTER; minHeight = dp(48)
        background = DexTema.fondoTactil(act, DexTema.CHIP, 8f, 36f); setPadding(dp(14), 0, dp(14), 0); isClickable = true; foreground = Ui.toque()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
        setOnClickListener { accion() }
    }

    /** Fila bajo el gráfico: Replay y Backtest. */
    val vistaBotones = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
    /** Controles del replay (solo mientras está activo). */
    val vistaControles = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; visibility = View.GONE; background = DexTema.fondo(act, DexTema.SURFACE, 10f)
        setPadding(dp(12), dp(8), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }
    private val tvFecha = texto(14f, DexTema.TXT, true)
    private val tvCuenta = texto(13f, DexTema.TXT).apply { setPadding(0, dp(6), 0, dp(4)); setLineSpacing(0f, 1.25f) }
    private val seek = SeekBar(act).apply { contentDescription = "Replay position" }
    private val btnPlay = boton("Play") { if (reproduciendo) pausar() else reproducir() }
    private val btnVel = boton("1x") { velocidad = when (velocidad) { 1 -> 2; 2 -> 5; 5 -> 10; else -> 1 }; pintar() }

    init {
        vistaBotones.addView(boton("Replay") { entrar() }.apply { contentDescription = "Bar replay: replay past candles and trade them" })
        vistaBotones.addView(boton("Backtest") { dialogoBacktest() }.apply { contentDescription = "Backtest a strategy on this chart" })
        vistaControles.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(texto(13f, DexTema.ACCENT, true).apply { text = "REPLAY  " })
            tvFecha.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); addView(tvFecha)
            addView(android.widget.ImageView(act).apply { setImageResource(R.drawable.ic_x); setColorFilter(DexTema.TXT); scaleType = android.widget.ImageView.ScaleType.CENTER
                contentDescription = "Exit replay"; isClickable = true; foreground = Ui.toque(); layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setOnClickListener { salir() } })
        })
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, deUsuario: Boolean) { if (deUsuario) { cursor = p.coerceIn(30, velas.size - 1); mostrar() } }
            override fun onStartTrackingTouch(s: SeekBar?) { pausar() }
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        vistaControles.addView(seek)
        vistaControles.addView(LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(btnPlay); addView(boton("Step") { pausar(); paso() }.apply { contentDescription = "Next candle" }); addView(btnVel) })
        vistaControles.addView(tvCuenta)
        vistaControles.addView(LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL
            addView(boton("Buy", DexTema.UP) { operar(1) }); addView(boton("Sell", DexTema.DOWN) { operar(-1) }); addView(boton("Close") { operar(0) }) })
    }

    // ── replay ──
    private fun entrar() {
        val v = chart.velasActuales()
        if (v.size < 60) { aviso("Load the chart first"); return }
        velas = ArrayList(v); cursor = v.size / 2
        capital = 10_000.0; pos = 0; operaciones.clear()
        activo_ = true; alCambiar(true)
        seek.max = velas.size - 1; vistaControles.visibility = View.VISIBLE
        chart.marcadores = emptyList(); chart.poner(velas.subList(0, cursor + 1)); pintar()
        aviso("Replay: drag the bar to pick a start, then Play or Step. Trades here use a separate 10,000 VUSD replay account.")
    }

    fun salir() {
        if (!activo_) return
        pausar(); activo_ = false; vistaControles.visibility = View.GONE
        chart.marcadores = emptyList(); chart.poner(velas)
        val r = capital * (1 + if (pos != 0) noRealizado() else 0.0)
        if (operaciones.isNotEmpty() || pos != 0) aviso("Replay ended: %s VUSD (%s%.2f%%) in %d trades".format(fmtSim(r), if (r >= 10_000) "+" else "", (r / 10_000 - 1) * 100, operaciones.size))
        alCambiar(false)
    }

    private fun mostrar() { chart.actualizar(velas.subList(0, cursor + 1)); pintar() }
    private fun paso() { if (cursor < velas.size - 1) { cursor++; chart.actualizar(velas.subList(0, cursor + 1)); pintar() } else { pausar(); aviso("End of the loaded candles") } }
    private fun reproducir() { reproduciendo = true; pintar(); h.post(tic) }
    private fun pausar() { reproduciendo = false; h.removeCallbacks(tic); pintar() }
    private val tic = object : Runnable { override fun run() { if (!reproduciendo || !activo_) return; paso(); if (reproduciendo) h.postDelayed(this, 1000L / velocidad) } }

    private fun precio() = velas.getOrNull(cursor)?.c?.toDouble() ?: 0.0
    private fun noRealizado() = if (pos == 0) 0.0 else (precio() / entrada - 1) * pos - com

    private fun operar(lado: Int) {
        val p = precio(); if (p <= 0) return
        if (pos != 0 && lado != pos) {        // cerrar lo abierto
            val res = (p / entrada - 1) * pos - 2 * com
            capital = capEnt * (1 + res)
            operaciones.add(Backtest.Trade(0, cursor, pos == 1, entrada, p, res, "Replay"))
            chart.marcadores = chart.marcadores + GraficoTA.Marcador(velas[cursor].t, p.toFloat(), pos == -1, "%+.1f%%".format(res * 100))
            pos = 0
        }
        if (lado != 0 && pos == 0) {
            pos = lado; entrada = p; capEnt = capital
            chart.marcadores = chart.marcadores + GraficoTA.Marcador(velas[cursor].t, p.toFloat(), lado == 1, if (lado == 1) "Buy" else "Sell")
        }
        pintar()
    }

    private fun pintar() {
        btnPlay.text = if (reproduciendo) "Pause" else "Play"; btnVel.text = "${velocidad}x"
        btnVel.contentDescription = "Speed ${velocidad} candles per second"
        seek.progress = cursor
        velas.getOrNull(cursor)?.let { tvFecha.text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(it.t * 1000)) + " · " + fmtSim(it.c.toDouble()) }
        val eq = capital * (1 + if (pos != 0) noRealizado() else 0.0)
        val ganadas = operaciones.count { it.resultado > 0 }
        tvCuenta.text = "Replay account  %s VUSD (%s%.2f%%)\nPosition  %s · trades %d · won %d".format(fmtSim(eq), if (eq >= 10_000) "+" else "", (eq / 10_000 - 1) * 100,
            when (pos) { 1 -> "long from ${fmtSim(entrada)} (%+.2f%%)".format(noRealizado() * 100); -1 -> "short from ${fmtSim(entrada)} (%+.2f%%)".format(noRealizado() * 100); else -> "none" },
            operaciones.size, ganadas)
    }

    private fun aviso(t: String) = Toast.makeText(act, t, Toast.LENGTH_LONG).show()

    // ── backtest ──
    private fun dialogoBacktest() {
        val caja = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        var est = Backtest.Estrategia.CRUCE_MA
        fun campo(hint: String, v: String) = DexTema.campo(act, hint, true).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(v); setSingleLine()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) } }
        fun etiqueta(t: String) = texto(12f, DexTema.TXT2).apply { text = t; setPadding(0, dp(4), 0, dp(2)) }
        val tvEst = texto(15f, DexTema.TXT).apply { minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; background = DexTema.fondo(act, DexTema.CHIP, 8f)
            setPadding(dp(14), 0, dp(10), 0); isClickable = true; foreground = Ui.toque()
            setCompoundDrawables(null, null, act.getDrawable(R.drawable.ic_caret_down)?.mutate()?.apply { setTint(DexTema.TXT2); setBounds(0, 0, dp(18), dp(18)) }, null)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } }
        val pMa = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val pRsi = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val pBb = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val etR = campo("Fast EMA", "9"); val etL = campo("Slow EMA", "21")
        pMa.addView(etiqueta("Fast / slow EMA periods")); pMa.addView(etR); pMa.addView(etL)
        val etRn = campo("RSI period", "14"); val etRb = campo("Oversold", "30"); val etRa = campo("Overbought", "70")
        pRsi.addView(etiqueta("RSI period, oversold and overbought levels")); pRsi.addView(etRn); pRsi.addView(etRb); pRsi.addView(etRa)
        val etBn = campo("Bollinger period", "20"); val etBk = campo("Standard deviations", "2")
        pBb.addView(etiqueta("Bollinger period and width (σ)")); pBb.addView(etBn); pBb.addView(etBk)
        fun pintarEst() { tvEst.text = est.nombre
            pMa.visibility = if (est == Backtest.Estrategia.CRUCE_MA) View.VISIBLE else View.GONE
            pRsi.visibility = if (est == Backtest.Estrategia.RSI) View.VISIBLE else View.GONE
            pBb.visibility = if (est == Backtest.Estrategia.BOLLINGER) View.VISIBLE else View.GONE }
        tvEst.setOnClickListener { val pm = androidx.appcompat.widget.PopupMenu(act, tvEst)
            Backtest.Estrategia.values().forEachIndexed { k, e -> pm.menu.add(0, k, k, e.nombre) }
            pm.setOnMenuItemClickListener { est = Backtest.Estrategia.values()[it.itemId]; pintarEst(); true }; pm.show() }
        caja.addView(etiqueta("Strategy")); caja.addView(tvEst); caja.addView(pMa); caja.addView(pRsi); caja.addView(pBb)
        val swLargos = DexTema.casilla(act, "Long only", false) { }
        caja.addView(swLargos)
        val etSl = campo("Stop-loss % (0 = none)", "0"); val etTp = campo("Take-profit % (0 = none)", "0")
        val etAp = campo("Leverage (1–125)", "1").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        caja.addView(etiqueta("Risk")); caja.addView(etSl); caja.addView(etTp); caja.addView(etAp)
        caja.addView(texto(12f, DexTema.TXT2).apply { setPadding(0, dp(6), 0, 0)
            text = "Uses up to 1000 ${intervalo()} candles of ${activo().par}. Signals on candle close, fills at the next open, 0.05% fee per side, profits reinvested. Past results don't predict future ones." })
        pintarEst()
        fun n(e: EditText, def: Double) = e.text.toString().replace(",", ".").toDoubleOrNull() ?: def
        DexTema.dialogo(act).setTitle("Backtest").setView(ScrollView(act).apply { addView(caja) })
            .setPositiveButton("Run") { _, _ ->
                val p = Backtest.Params(est, n(etR, 9.0).toInt().coerceIn(2, 400), n(etL, 21.0).toInt().coerceIn(3, 400),
                    n(etRn, 14.0).toInt().coerceIn(2, 100), n(etRb, 30.0).coerceIn(1.0, 99.0), n(etRa, 70.0).coerceIn(1.0, 99.0),
                    n(etBn, 20.0).toInt().coerceIn(5, 200), n(etBk, 2.0).coerceIn(0.5, 4.0), swLargos.isChecked, Simulador.COM_TAKER,
                    n(etSl, 0.0).coerceIn(0.0, 90.0), n(etTp, 0.0).coerceIn(0.0, 1000.0), n(etAp, 1.0).toInt().coerceIn(1, 125))
                if (p.estrategia == Backtest.Estrategia.CRUCE_MA && p.rapida >= p.lenta) { aviso("The fast EMA must be shorter than the slow one"); return@setPositiveButton }
                correr(p)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun correr(p: Backtest.Params) {
        val a = activo(); val iv = intervalo()
        aviso("Running backtest on ${a.par} $iv…")
        Thread {
            val v = try { Mercado.velas(a, iv, 1000) } catch (e: Throwable) { emptyList() }
            val r = if (v.size >= 60) Backtest.ejecutar(v, p) else null
            act.runOnUiThread { if (r == null) aviso("Not enough candles to backtest") else resultados(v, p, r) }
        }.start()
    }

    private fun resultados(v: List<GraficoPrecio.Vela>, p: Backtest.Params, r: Backtest.Resultado) {
        val caja = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        fun fila(k: String, valor: String, color: Int = DexTema.TXT) = caja.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3))
            addView(texto(14f, DexTema.TXT2).apply { text = k; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(texto(14f, color, true).apply { text = valor }) })
        caja.addView(texto(13f, DexTema.TXT2).apply { text = "${activo().par} · ${intervalo()} · ${v.size} candles · ${f.format(java.util.Date(v.first().t * 1000))} → ${f.format(java.util.Date(v.last().t * 1000))}" })
        fila("Strategy return", "%+.2f%%".format(r.retornoPct), if (r.retornoPct >= 0) DexTema.UP else DexTema.DOWN)
        fila("Buy & hold", "%+.2f%%".format(r.comprarYMantenerPct), if (r.comprarYMantenerPct >= 0) DexTema.UP else DexTema.DOWN)
        fila("Trades", r.trades.size.toString())
        fila("Win rate", if (r.trades.isEmpty()) "—" else "%.1f%%".format(r.tasaAcierto))
        fila("Profit factor", when { r.trades.isEmpty() -> "—"; r.factorBeneficio.isInfinite() -> "∞"; else -> "%.2f".format(r.factorBeneficio) })
        fila("Max drawdown", "−%.2f%%".format(r.maxDrawdownPct), DexTema.DOWN)
        if (r.liquidado) fila("Result", "LIQUIDATED", DexTema.DOWN)
        caja.addView(texto(13f, DexTema.TXT2).apply { text = "Equity curve"; setPadding(0, dp(10), 0, dp(4)) })
        caja.addView(GraficoPrecio(act).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130)); poner(r.curva.map { it.toFloat() }) })
        caja.addView(texto(13f, DexTema.TXT2).apply { text = "Last trades"; setPadding(0, dp(10), 0, dp(4)) })
        for (t in r.trades.asReversed().take(40)) caja.addView(texto(13f, if (t.resultado >= 0) DexTema.UP else DexTema.DOWN).apply {
            text = "%s %s  %s → %s  %+.2f%%  · %s".format(if (t.largo) "Long" else "Short", f.format(java.util.Date(v[t.iEntrada].t * 1000)),
                fmtSim(t.entrada), fmtSim(t.salida), t.resultado * 100, t.motivo); setPadding(0, dp(2), 0, dp(2)) })
        DexTema.dialogo(act).setTitle("Backtest · ${p.estrategia.nombre}").setView(ScrollView(act).apply { addView(caja) })
            .setPositiveButton("Show on chart") { _, _ ->
                if (activo_) salir()
                chart.marcadores = r.trades.flatMap { t -> listOf(
                    GraficoTA.Marcador(v[t.iEntrada].t, t.entrada.toFloat(), t.largo, if (t.largo) "L" else "S"),
                    GraficoTA.Marcador(v[t.iSalida].t, t.salida.toFloat(), !t.largo, "%+.1f%%".format(t.resultado * 100))) }
                aviso("Trades marked on the chart. Clear them with Backtest > Clear marks.")
            }
            .setNeutralButton("Clear marks") { _, _ -> chart.marcadores = emptyList() }
            .setNegativeButton("Close", null).show()
    }
}
