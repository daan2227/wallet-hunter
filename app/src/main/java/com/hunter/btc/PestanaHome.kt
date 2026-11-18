package com.hunter.btc

import android.content.Intent
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
    val chart = GraficoTA(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(330)) }
    val tok = AtomicInteger(0)
    fun cargar() {
        val myTok = tok.incrementAndGet()
        tvCambio.text = "Loading…"; tvCambio.setTextColor(AppTheme.TXT_SEC)
        Thread {
            val v = Mercado.velasBtc(intervalo)
            runOnUiThread {
                if (myTok != tok.get()) return@runOnUiThread
                if (v.isEmpty()) { tvCambio.text = "Chart unavailable (exchange unreachable)"; return@runOnUiThread }
                chart.poner(v)
                val fin = v.last().c; val ini = v.first().o
                val pct = if (ini > 0) (fin - ini) / ini * 100f else 0f
                tvPrecio.text = "%,.2f USD".format(fin)
                tvCambio.text = "%s%.2f%% · %s window".format(if (pct >= 0) "+" else "", pct, intervalo)
                tvCambio.setTextColor(if (pct >= 0) AppTheme.OK else AppTheme.RED)
            }
        }.start()
    }

    page.addView(Ui.segmented(this, listOf("1m" to null, "1h" to null, "1D" to null), initial = 1) { i ->
        intervalo = when (i) { 0 -> "1m"; 1 -> "1h"; else -> "1d" }; cargar()
    }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) } })

    // Media móvil: Off / MA / EMA
    page.addView(Ui.segmented(this, listOf("MA off" to null, "MA" to null, "EMA" to null), initial = 1) { i ->
        chart.modoMedia = i; chart.invalidate()
    }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) } })

    // Chips Vol / RSI / MACD
    fun chip(txt: String, on: Boolean, onTap: (Boolean) -> Unit): TextView {
        val c = TextView(this)
        var estado = on
        fun pintar() {
            c.background = Ui.cardBg(AppTheme.R_CHIP, if (estado) AppTheme.BG_KEY else AppTheme.BG_CARD, this)
            c.setTextColor(if (estado) AppTheme.TXT_PRI else AppTheme.TXT_SEC)
            c.typeface = if (estado) AppTheme.bold(this) else AppTheme.medium(this)
        }
        c.apply {
            text = txt; textSize = AppTheme.SP_CAPTION; gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7)); isClickable = true; foreground = Ui.toque()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
            setOnClickListener { estado = !estado; pintar(); onTap(estado) }
        }
        pintar(); return c
    }
    page.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(8))
        addView(chip("Volume", true) { chart.showVol = it; chart.invalidate() })
        addView(chip("RSI", true) { chart.showRsi = it; chart.invalidate() })
        addView(chip("MACD", true) { chart.showMacd = it; chart.invalidate() })
    })
    page.addView(chart)
    page.addView(TextView(this).apply {
        text = "Candles & volume from Binance (USD); MA/EMA, RSI and MACD computed on-device."
        textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_MUTED); typeface = AppTheme.body(context); setPadding(0, dp(6), 0, dp(18))
    })
    cargar()

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

    // ── Funciones ──
    page.addView(TextView(this).apply {
        text = "FUNCTIONS"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.bold(context); letterSpacing = 0.08f; setPadding(dp(2), 0, 0, dp(10))
    })
    data class Fn(val icono: Int, val titulo: String, val sub: String, val ir: () -> Unit)
    val fns = listOf(
        Fn(R.drawable.ic_search,   "Scanner",  "Scan addresses & keys")  { pestana(BottomBar.SCANNER) },
        Fn(R.drawable.ic_puzzle,   "Puzzle",   "Bitcoin puzzle hunt")    { pestana(BottomBar.PUZZLE) },
        Fn(R.drawable.ic_warning,  "Weak",     "Weak-key audit")         { pestana(BottomBar.WEAK) },
        Fn(R.drawable.ic_recovery, "Recovery", "Recover a seed phrase")  { pestana(BottomBar.RECOVERY) },
        Fn(R.drawable.ic_copy,     "Tools",    "Keys, tx, sign, audits") { startActivity(Intent(this, PubkeyToolsActivity::class.java)); overridePendingTransition(0, 0) },
        Fn(R.drawable.ic_chip,     "Miner",    "Mine / solo lottery")    { pestana(BottomBar.MINER) },
        Fn(R.drawable.ic_wallet,   "Wallet",   "Your keys & balance")    { pestana(BottomBar.WALLET) },
        Fn(R.drawable.ic_core,     "Core",     "Engine & local node")    { pestana(BottomBar.CORE) }
    )
    fun tarjeta(f: Fn) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.cardBg(AppTheme.R_INNER, AppTheme.BG_CARD, context)
        setPadding(dp(14), dp(14), dp(14), dp(14)); minimumHeight = dp(98)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
        isClickable = true; isFocusable = true; foreground = Ui.toque(); contentDescription = f.titulo
        addView(FrameLayout(context).apply {
            background = Ui.cardBg(AppTheme.R_CHIP, AppTheme.BG_ELEV, context)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            addView(Ui.icon(this@buildHomeTab, f.icono, 20, AppTheme.TXT_PRI).apply { layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER) })
        })
        addView(TextView(context).apply { text = f.titulo; textSize = AppTheme.SP_BODY - 1f; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.bold(context); setPadding(0, dp(10), 0, dp(2)) })
        addView(TextView(context).apply { text = f.sub; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        setOnClickListener { Ui.pulso(this); f.ir() }
    }
    for (par in fns.chunked(2)) {
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (f in par) fila.addView(tarjeta(f))
        if (par.size == 1) { fila.minimumHeight = dp(108); fila.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }) }
        page.addView(fila)
    }

    return scroll
}
