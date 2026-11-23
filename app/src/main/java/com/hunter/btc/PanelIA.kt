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
