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

    // ── Controles del gráfico ──
    var intervalo = "1h"
    val chart = GraficoTA(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)); intervaloSeg = 3600L }
    val tok = AtomicInteger(0)
    fun cargar() {
        val myTok = tok.incrementAndGet()
        tvCambio.text = "Loading…"; tvCambio.setTextColor(AppTheme.TXT_SEC)
        Thread {
            val v = Mercado.velasBtc(intervalo, 500)
            runOnUiThread {
                if (myTok != tok.get()) return@runOnUiThread
                if (v.isEmpty()) { tvCambio.text = "Chart unavailable (exchange unreachable)"; return@runOnUiThread }
                chart.poner(v)
                val fin = v.last().c; val ini = v.first().o
                val pct = if (ini > 0) (fin - ini) / ini * 100f else 0f
                tvPrecio.text = "%,.2f USD".format(fin)
                tvCambio.text = "%s%.2f%% · drag to pan, pinch to zoom".format(if (pct >= 0) "+" else "", pct)
                tvCambio.setTextColor(if (pct >= 0) AppTheme.OK else AppTheme.RED)
            }
        }.start()
    }

    // ── Intervalos (fila desplazable) ──
    val intervalos = listOf(
        Triple("1s", "1s", 1L), Triple("1m", "1m", 60L), Triple("1h", "1h", 3600L),
        Triple("4h", "4h", 14400L), Triple("12h", "12h", 43200L), Triple("1D", "1d", 86400L),
        Triple("3D", "3d", 259200L), Triple("1W", "1w", 604800L), Triple("1M", "1M", 2592000L))
    val chipsInt = ArrayList<TextView>()
    fun pintarInt(sel: Int) {
        for ((k, c) in chipsInt.withIndex()) {
            val on = k == sel
            c.background = Ui.cardBg(AppTheme.R_CHIP, if (on) AppTheme.BG_KEY else AppTheme.BG_CARD, this)
            c.setTextColor(if (on) AppTheme.TXT_PRI else AppTheme.TXT_SEC)
            c.typeface = if (on) AppTheme.bold(this) else AppTheme.medium(this)
        }
    }
    val filaInt = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    for ((idx, tr) in intervalos.withIndex()) {
        val (lab, code, seg) = tr
        val c = TextView(this).apply {
            text = lab; textSize = AppTheme.SP_CAPTION; gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7)); isClickable = true; foreground = Ui.toque()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
            setOnClickListener { intervalo = code; chart.intervaloSeg = seg; pintarInt(idx); cargar() }
        }
        chipsInt.add(c); filaInt.addView(c)
    }
    page.addView(HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false; addView(filaInt)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    })
    pintarInt(2)   // 1h por defecto

    // ── Ajustes de indicadores (modificar / añadir) ──
    fun abrirAjustes() {
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(4)) }
        fun titulo(t: String) = caja.addView(TextView(this).apply { text = t; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.bold(context); setPadding(0, dp(10), 0, dp(6)) })
        fun check(t: String, ini: Boolean, set: (Boolean) -> Unit) = caja.addView(android.widget.CheckBox(this).apply { text = t; isChecked = ini; setTextColor(AppTheme.TXT_PRI); setOnCheckedChangeListener { _, b -> set(b) } })
        titulo("Moving average")
        caja.addView(Ui.segmented(this, listOf("Off" to null, "MA" to null, "EMA" to null), initial = chart.modoMedia) { chart.modoMedia = it })
        val filaP = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        val e1 = EditText(this).apply { setText(chart.maP1.toString()); hint = "fast"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setTextColor(AppTheme.TXT_PRI); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val e2 = EditText(this).apply { setText(chart.maP2.toString()); hint = "slow"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setTextColor(AppTheme.TXT_PRI); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        filaP.addView(e1); filaP.addView(e2); caja.addView(filaP)
        titulo("Overlays & panels")
        check("Bollinger Bands (20, 2)", chart.showBB) { chart.showBB = it }
        check("Volume", chart.showVol) { chart.showVol = it }
        check("RSI (14)", chart.showRsi) { chart.showRsi = it }
        check("MACD (12, 26, 9)", chart.showMacd) { chart.showMacd = it }
        android.app.AlertDialog.Builder(this).setTitle("Chart settings").setView(caja)
            .setPositiveButton("Apply") { _, _ ->
                chart.maP1 = e1.text.toString().toIntOrNull()?.coerceIn(2, 400) ?: chart.maP1
                chart.maP2 = e2.text.toString().toIntOrNull()?.coerceIn(2, 400) ?: chart.maP2
                chart.invalidate()
            }.setNegativeButton("Close", null).show()
    }
    page.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(8))
        addView(TextView(this@buildHomeTab).apply {
            text = "drag to pan · pinch to zoom"; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@buildHomeTab).apply {
            text = "⚙ Indicators"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.ACCENT); typeface = AppTheme.bold(context)
            setPadding(dp(10), dp(6), dp(2), dp(6)); isClickable = true; foreground = Ui.toque(); setOnClickListener { abrirAjustes() }
        })
    })
    page.addView(chart)
    cargar()

    // ── En vivo: refresca las velas cada 15 s mientras el Home está a la vista ──
    // Usa actualizar() (no resetea el zoom/desplazamiento). Se para solo cuando la
    // vista se destruye; si el Home no se ve, no pide nada.
    val hVivo = android.os.Handler(android.os.Looper.getMainLooper())
    hVivo.postDelayed(object : Runnable {
        override fun run() {
            if (!scroll.isAttachedToWindow) return
            if (scroll.isShown) {
                val iv = intervalo
                Thread {
                    val v = Mercado.velasBtc(iv, 500)
                    if (v.isNotEmpty()) runOnUiThread {
                        if (iv == intervalo && scroll.isAttachedToWindow) {
                            chart.actualizar(v)
                            val fin = v.last().c; val ini = v.first().o
                            val pct = if (ini > 0) (fin - ini) / ini * 100f else 0f
                            tvPrecio.text = "%,.2f USD".format(fin)
                            tvCambio.text = "%s%.2f%% · live".format(if (pct >= 0) "+" else "", pct)
                            tvCambio.setTextColor(if (pct >= 0) AppTheme.OK else AppTheme.RED)
                        }
                    }
                }.start()
            }
            hVivo.postDelayed(this, 15_000)
        }
    }, 15_000)

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
