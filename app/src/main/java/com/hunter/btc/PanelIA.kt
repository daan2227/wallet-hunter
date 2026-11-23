package com.hunter.btc

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/**
 * Hoja del modo IA: veredicto (Long / Short / Spot / Esperar) con confianza, plan,
 * factores por grupo (mercado, gráfico, social), patrones, niveles y titulares; botón
 * para cargar el plan en el formulario, para ver los niveles en el gráfico y, con la
 * clave del usuario, para pedir a Claude un informe escrito.
 */
internal fun MainActivity.abrirAnalisisIA(a: Mercado.Activo, intervalo: String, chart: GraficoTA, panel: PanelTrading?) {
    val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val estado = TextView(this).apply { text = "Analyzing ${a.par} ($intervalo): candles, market, sentiment…"; textSize = 15f; setTextColor(DexTema.TXT2); setPadding(0, dp(8), 0, dp(16)) }
    caja.addView(estado)
    val dlg = DexTema.hoja(this, "AI analysis · ${a.par}", caja); dlg.show()

    fun t(sp: Float, color: Int, negrita: Boolean = false) = TextView(this).apply { textSize = sp; setTextColor(color); typeface = if (negrita) AppTheme.bold(context) else AppTheme.body(context) }
    fun boton(txt: String, relleno: Boolean = false, accion: () -> Unit) = TextView(this).apply {
        text = txt; textSize = 15f; typeface = AppTheme.bold(context); gravity = Gravity.CENTER; minHeight = dp(48)
        setTextColor(if (relleno) AppTheme.ON_ACCENT else DexTema.TXT)
        background = if (relleno) DexTema.fondo(context, DexTema.ACCENT, 8f) else DexTema.fondo(context, DexTema.CHIP, 8f)
        setPadding(dp(14), 0, dp(14), 0); isClickable = true; foreground = Ui.toque()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        setOnClickListener { accion() }
    }

    Thread {
        val d = try { RecolectorIA.recolectar(a, intervalo) } catch (e: Throwable) { null }
        val r = d?.takeIf { it.velas.size >= 30 }?.let { AnalisisIA.evaluar(it) }
        runOnUiThread {
            if (!dlg.isShowing) return@runOnUiThread
            caja.removeAllViews()
            if (d == null || r == null) { estado.text = "Not enough data to analyze ${a.par} right now."; caja.addView(estado); return@runOnUiThread }
            // ── veredicto ──
            val col = when (r.veredicto) { AnalisisIA.Veredicto.LONG, AnalisisIA.Veredicto.SPOT -> DexTema.UP; AnalisisIA.Veredicto.SHORT -> DexTema.DOWN; else -> DexTema.TXT2 }
            caja.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; background = DexTema.fondo(context, DexTema.CHIP, 12f); setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(t(13f, DexTema.TXT2).apply { text = "Local engine verdict" })
                addView(t(26f, col, true).apply { text = when (r.veredicto) {
                    AnalisisIA.Veredicto.LONG -> "▲ LONG"; AnalisisIA.Veredicto.SHORT -> "▼ SHORT"; AnalisisIA.Veredicto.SPOT -> "▲ SPOT BUY"; else -> "■ WAIT" } })
                addView(t(14f, DexTema.TXT).apply { text = "Score %+.1f · confidence %d%% · ATR %.2f%% of price".format(r.puntuacion, r.confianza, r.atrPct) })
                addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = r.confianza; progressTintList = android.content.res.ColorStateList.valueOf(col)
                    contentDescription = "Confidence ${r.confianza} percent"
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(8) } })
            })
            // ── temporalidades ──
            if (d.tendencias.isNotEmpty()) caja.addView(t(14f, DexTema.TXT, true).apply {
                text = d.tendencias.entries.joinToString("    ") { (k, x) -> "$k ${when (x) { 1 -> "▲ up"; -1 -> "▼ down"; else -> "■ flat" }}" }
                setPadding(0, dp(10), 0, 0); contentDescription = "Trend by timeframe: " + d.tendencias.entries.joinToString(", ") { (k, x) -> "$k ${when (x) { 1 -> "up"; -1 -> "down"; else -> "flat" }}" } })
            // ── plan ──
            r.plan?.let { p ->
                caja.addView(DexTema.seccion(this, "Plan"))
                fun fila(k: String, v: String, c: Int = DexTema.TXT) = caja.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3))
                    addView(t(14f, DexTema.TXT2).apply { text = k; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                    addView(t(14f, c, true).apply { text = v }) })
                val riesgo = Math.abs(p.entrada - p.stop) / p.entrada * 100
                fila("Side", if (p.largo) "Long / buy" else "Short / sell", if (p.largo) DexTema.UP else DexTema.DOWN)
                fila("Entry (now)", fmtSim(p.entrada))
                fila("Stop-loss", "%s (%.2f%%)".format(fmtSim(p.stop), riesgo), DexTema.DOWN)
                fila("Target 1 / 2", "${fmtSim(p.objetivo1)} / ${fmtSim(p.objetivo2)}", DexTema.UP)
                fila("Max leverage (prudent)", if (r.veredicto == AnalisisIA.Veredicto.SPOT) "none (spot)" else "${p.apalancamientoMax}x")
                caja.addView(boton("Load plan into the order form", true) {
                    panel?.aplicarPlan(p.largo, p.stop, p.objetivo1, if (r.veredicto == AnalisisIA.Veredicto.SPOT) 1 else p.apalancamientoMax); dlg.dismiss() })
            }
            caja.addView(boton("Show levels on the chart") {
                chart.lineasAnalisis = r.soportes.map { GraficoTA.LineaPrecio(it.toFloat(), DexTema.UP, "S") } + r.resistencias.map { GraficoTA.LineaPrecio(it.toFloat(), DexTema.DOWN, "R") } +
                    (r.plan?.let { p -> listOf(GraficoTA.LineaPrecio(p.stop.toFloat(), DexTema.LIQ, "AI stop"), GraficoTA.LineaPrecio(p.objetivo1.toFloat(), DexTema.ACCENT, "AI T1")) } ?: emptyList())
                dlg.dismiss()
            })
            // ── factores por grupo ──
            for (g in AnalisisIA.Grupo.values()) {
                val fs = r.factores.filter { it.grupo == g }; if (fs.isEmpty()) continue
                caja.addView(DexTema.seccion(this, "${g.nombre} analysis  %+.1f".format(fs.sumOf { it.puntos })))
                for (x in fs) caja.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(6))
                    addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL
                        addView(t(14f, DexTema.TXT, true).apply { text = x.nombre; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                        // signo y palabra, no solo color
                        addView(t(14f, if (x.puntos > 0) DexTema.UP else if (x.puntos < 0) DexTema.DOWN else DexTema.TXT2, true).apply {
                            text = if (x.puntos > 0) "bullish %+.1f".format(x.puntos) else if (x.puntos < 0) "bearish %+.1f".format(x.puntos) else "neutral" }) })
                    addView(t(13f, DexTema.TXT2).apply { text = x.texto })
                })
            }
            if (r.soportes.isNotEmpty() || r.resistencias.isNotEmpty()) {
                caja.addView(DexTema.seccion(this, "Key levels"))
                caja.addView(t(14f, DexTema.TXT).apply { text = "Resistance: ${r.resistencias.joinToString(" · ") { fmtSim(it) }.ifEmpty { "—" }}\nSupport: ${r.soportes.joinToString(" · ") { fmtSim(it) }.ifEmpty { "—" }}"
                    setLineSpacing(0f, 1.3f) })
            }
            if (d.titularesReddit.isNotEmpty()) {
                caja.addView(DexTema.seccion(this, "Reddit headlines"))
                for ((tit, sc) in d.titularesReddit.take(6)) { val to = AnalisisIA.tono(tit)
                    caja.addView(t(13f, DexTema.TXT).apply { text = "${when (to) { 1 -> "+ "; -1 -> "− "; else -> "· " }}$tit  ($sc)"; setPadding(0, dp(3), 0, dp(3)) }) }
            }
            // ── informe de Claude ──
            caja.addView(DexTema.seccion(this, "Written analysis by Claude"))
            val tvClaude = t(14f, DexTema.TXT).apply { setLineSpacing(0f, 1.35f); setTextIsSelectable(true) }
            caja.addView(tvClaude)
            fun pedir() {
                val idioma = java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { "English" }
                tvClaude.text = "Asking Claude (Opus 5.5)…"; tvClaude.setTextColor(DexTema.TXT2)
                val datos = RecolectorIA.resumen(a, intervalo, d, r)
                Thread {
                    val (ok, txt) = try { true to ClaudeIA.analizar(this, datos, idioma) } catch (e: Exception) { false to (e.message ?: "Error") }
                    runOnUiThread { tvClaude.text = txt; tvClaude.setTextColor(if (ok) DexTema.TXT else DexTema.DOWN) }
                }.start()
            }
            fun pedirClave(despues: () -> Unit) {
                val et = DexTema.campo(this, "sk-ant-…", true).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
                DexTema.dialogo(this).setTitle("Claude API key")
                    .setView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(et)
                        addView(t(12f, DexTema.TXT2).apply { setPadding(0, dp(8), 0, 0)
                            text = "Create a key at console.anthropic.com (pay per use, about US$0.05 per analysis). It is stored encrypted with the Android Keystore and only sent to api.anthropic.com. Leave empty and save to remove it." }) })
                    .setPositiveButton("Save") { _, _ -> ClaudeIA.guardarClave(this, et.text.toString()); if (ClaudeIA.tieneClave(this)) despues() }
                    .setNegativeButton("Cancel", null).show()
            }
            caja.addView(t(12f, DexTema.TXT2).apply { setPadding(0, dp(2), 0, 0)
                text = "Sends only this analysis (recent candles, indicators, levels, factors, headlines) to Anthropic with your own API key, through Tor if it's on. Nothing from your wallet or accounts." })
            caja.addView(boton(if (ClaudeIA.tieneClave(this)) "Ask Claude (≈ US$0.05)" else "Add Claude API key to ask Claude", true) {
                if (ClaudeIA.tieneClave(this)) pedir() else pedirClave { pedir() } })
            if (ClaudeIA.tieneClave(this)) caja.addView(boton("Change or remove API key") { pedirClave { } })
            caja.addView(t(12f, DexTema.WARN).apply { setPadding(0, dp(14), 0, 0)
                text = "Educational tool for the VUSD test exchange. Patterns and scores are probabilistic, not signals; this is not financial advice." })
        }
    }.start()
}

/**
 * Escáner IA: pasa el motor local (solo gráfico: rápido y sin cuentas) por las ~50
 * primeras criptos de Binance en la temporalidad elegida y ordena las oportunidades
 * más claras. Tocar una abre el par en el DEx.
 */
internal fun MainActivity.abrirEscanerIA(intervaloInicial: String, alElegir: (Mercado.Activo) -> Unit) {
    val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val estado = TextView(this).apply { textSize = 14f; setTextColor(DexTema.TXT2); setPadding(0, dp(8), 0, dp(8)) }
    var iv = if (intervaloInicial in listOf("15m", "1h", "4h", "1d")) intervaloInicial else "1h"
    val tok = java.util.concurrent.atomic.AtomicInteger(0)
    var dlg: android.app.Dialog? = null
    fun t(sp: Float, color: Int, negrita: Boolean = false) = TextView(this).apply { textSize = sp; setTextColor(color); typeface = if (negrita) AppTheme.bold(context) else AppTheme.body(context) }
    fun escanear() {
        val mio = tok.incrementAndGet(); lista.removeAllViews(); estado.text = "Loading the top coins…"
        val ivAhora = iv
        Thread {
            val universo = universoEscaner()
            val hechos = java.util.concurrent.atomic.AtomicInteger(0)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(5)
            val futuros = universo.map { a -> pool.submit<Pair<Mercado.Activo, AnalisisIA.Resultado>?> {
                val v = try { Mercado.velas(a, ivAhora, 250) } catch (e: Throwable) { emptyList() }
                val n = hechos.incrementAndGet()
                runOnUiThread { if (mio == tok.get()) estado.text = "Scanning $n / ${universo.size} on $ivAhora…" }
                if (v.size < 60) null else a to AnalisisIA.evaluar(AnalisisIA.Datos(v))
            } }
            val res = futuros.mapNotNull { try { it.get(60, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { null } }
            pool.shutdown()
            runOnUiThread {
                if (mio != tok.get() || dlg?.isShowing != true) return@runOnUiThread
                if (res.isEmpty()) { estado.text = if (universo.isEmpty()) "Could not load the coin list from Binance. Check your connection and tap a timeframe to retry."
                    else "Could not load candles from Binance. Pick a timeframe or reopen the scan to retry."; return@runOnUiThread }
                val orden = res.sortedWith(compareBy<Pair<Mercado.Activo, AnalisisIA.Resultado>> {
                    when (it.second.veredicto) { AnalisisIA.Veredicto.LONG, AnalisisIA.Veredicto.SHORT -> 0; AnalisisIA.Veredicto.SPOT -> 1; else -> 2 } }
                    .thenByDescending { Math.abs(it.second.puntuacion) })
                estado.text = "${res.size} pairs scanned on $ivAhora · chart signals only · tap one to open it"
                for ((a, r) in orden.take(25)) {
                    val col = when (r.veredicto) { AnalisisIA.Veredicto.LONG, AnalisisIA.Veredicto.SPOT -> DexTema.UP; AnalisisIA.Veredicto.SHORT -> DexTema.DOWN; else -> DexTema.TXT2 }
                    lista.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(52); setPadding(0, dp(4), 0, dp(4))
                        isClickable = true; foreground = Ui.toque()
                        contentDescription = "${a.simbolo}: ${r.veredicto.nombre}, score %+.1f, confidence ${r.confianza} percent".format(r.puntuacion)
                        setOnClickListener { dlg?.dismiss(); alElegir(a) }
                        addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            addView(t(15f, DexTema.TXT, true).apply { text = a.simbolo })
                            addView(t(12f, DexTema.TXT2).apply { text = "${fmtSim(r.plan?.entrada ?: 0.0).takeIf { r.plan != null } ?: ""}${if (r.plan != null) " · " else ""}ATR %.2f%% · ${r.patrones.firstOrNull { it.alcista != null }?.nombre ?: "no pattern"}".format(r.atrPct) }) })
                        addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                            addView(t(15f, col, true).apply { gravity = Gravity.END; text = when (r.veredicto) {
                                AnalisisIA.Veredicto.LONG -> "▲ LONG"; AnalisisIA.Veredicto.SHORT -> "▼ SHORT"; AnalisisIA.Veredicto.SPOT -> "▲ SPOT"; else -> "■ WAIT" } })
                            addView(t(12f, DexTema.TXT2).apply { gravity = Gravity.END; text = "%+.1f · %d%%".format(r.puntuacion, r.confianza) }) })
                    })
                }
            }
        }.start()
    }
    caja.addView(DexTema.pestanas(this, listOf("15m" to null, "1h" to null, "4h" to null, "1d" to null), listOf("15m", "1h", "4h", "1d").indexOf(iv)) { k ->
        iv = listOf("15m", "1h", "4h", "1d")[k]; escanear() })
    caja.addView(estado); caja.addView(lista)
    caja.addView(t(12f, DexTema.TXT2).apply { setPadding(0, dp(12), 0, 0)
        text = "Ranks the clearest chart setups among the top ~50 coins on Binance. Open a pair and run AI analysis for market and social factors. Not financial advice." })
    dlg = DexTema.hoja(this, "AI market scan", caja).also { it.show() }
    escanear()
}

/**
 * Las ~50 criptos a escanear: pares USDT de Binance (la misma fuente que el gráfico)
 * ordenados por volumen de 24 h; si Binance no responde, el top de CoinGecko.
 */
private fun universoEscaner(): List<Mercado.Activo> {
    val bin = try { Mercado.movimientos() } catch (e: Throwable) { emptyList() }
    if (bin.isNotEmpty()) return bin.sortedByDescending { it.volumenUsd }.take(50)
        .map { Mercado.Activo(Mercado.Tipo.CRYPTO, it.simbolo, it.simbolo) }
    return try { Mercado.topCripto(1, 70).map { it.activo }.take(50) } catch (e: Throwable) { emptyList() }
}
