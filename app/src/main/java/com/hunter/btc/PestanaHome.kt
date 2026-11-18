package com.hunter.btc

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/**
 * Home: el panel de inicio (primer item de la barra de abajo). Arriba, el precio
 * de BTC con un gráfico nativo; debajo, las funciones de la app en tarjetas, cada
 * una abre su sección. No busca nada por sí misma.
 */
internal fun MainActivity.buildHomeTab(): ScrollView {
    val scroll = ScrollView(this).apply {
        background = AppTheme.fondoApp()
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }
    val page = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(AppTheme.PAD_SIDE), dp(24), dp(AppTheme.PAD_SIDE), dp(32))
    }
    scroll.addView(page)

    page.addView(TextView(this).apply {
        text = "Hunter"; textSize = 26f; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.title(context)
    })
    page.addView(TextView(this).apply {
        text = "Everything runs on this phone"; textSize = AppTheme.SP_CAPTION
        setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context); setPadding(0, dp(2), 0, dp(14))
    })

    // ── Precio + gráfico (toca para abrir el tool de precio) ──
    val tvPrecio = TextView(this).apply { textSize = 24f; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.title(context); text = "…" }
    val tvCambio = TextView(this).apply { textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context); setPadding(0, dp(2), 0, dp(8)) }
    val chart = GraficoPrecio(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)) }
    page.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.cardBg(AppTheme.R_CARD, AppTheme.BG_CARD, context)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) }
        addView(TextView(context).apply { text = "BTC price"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.medium(context) })
        addView(tvPrecio); addView(tvCambio); addView(chart)
        isClickable = true; isFocusable = true; foreground = Ui.toque()
        setOnClickListener { startActivity(Intent(this@buildHomeTab, PubkeyToolsActivity::class.java)); overridePendingTransition(0, 0) }
    })

    val cod = Fiat.codigo(this)
    Thread {
        val actualJs = try { ChainApi.get("/v1/prices") } catch (e: Throwable) { null }
        val actual = if (actualJs != null) try { org.json.JSONObject(actualJs).optDouble(cod, 0.0) } catch (e: Exception) { 0.0 } else 0.0
        val histJs = try { ChainApi.get("/v1/historical-price?currency=$cod") } catch (e: Throwable) { null }
        val serie = ArrayList<Float>()
        if (histJs != null) try {
            val arr = org.json.JSONObject(histJs).getJSONArray("prices")
            val pares = ArrayList<Pair<Long, Float>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); val t = o.optLong("time", 0L); val p = o.optDouble(cod, 0.0)
                if (t > 0 && p > 0 && p.isFinite()) pares.add(t to p.toFloat())
            }
            pares.sortBy { it.first }
            val desde = System.currentTimeMillis() / 1000 - 90L * 86_400L   // últimos 90 días
            for (pr in pares) if (pr.first >= desde) serie.add(pr.second)
        } catch (e: Exception) { }
        if (actual > 0) serie.add(actual.toFloat())
        runOnUiThread {
            tvPrecio.text = if (actual > 0) "%,.2f %s".format(actual, cod)
                            else if (serie.isNotEmpty()) "%,.2f %s".format(serie.last(), cod) else "—"
            chart.poner(serie)
            if (serie.size >= 2 && serie.first() > 0) {
                val pct = (serie.last() - serie.first()) / serie.first() * 100f
                tvCambio.text = "%s%.1f%% · 90d".format(if (pct >= 0) "+" else "", pct)
                tvCambio.setTextColor(if (pct >= 0) AppTheme.OK else AppTheme.RED)
            } else tvCambio.text = "mempool.space · tap for candles"
        }
    }.start()

    // ── Funciones ──
    page.addView(TextView(this).apply {
        text = "FUNCTIONS"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
        typeface = AppTheme.bold(context); letterSpacing = 0.08f; setPadding(dp(2), dp(4), 0, dp(10))
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
        isClickable = true; isFocusable = true; foreground = Ui.toque()
        contentDescription = f.titulo
        addView(FrameLayout(context).apply {
            background = Ui.cardBg(AppTheme.R_CHIP, AppTheme.BG_ELEV, context)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            addView(Ui.icon(this@buildHomeTab, f.icono, 20, AppTheme.TXT_PRI).apply {
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            })
        })
        addView(TextView(context).apply {
            text = f.titulo; textSize = AppTheme.SP_BODY - 1f; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.bold(context); setPadding(0, dp(10), 0, dp(2))
        })
        addView(TextView(context).apply {
            text = f.sub; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); typeface = AppTheme.body(context)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        setOnClickListener { Ui.pulso(this); f.ir() }
    }
    for (par in fns.chunked(2)) {
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (f in par) fila.addView(tarjeta(f))
        if (par.size == 1) {
            fila.minimumHeight = dp(98 + 10)
            fila.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) })
        }
        page.addView(fila)
    }

    return scroll
}
