package com.hunter.btc

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Top cripto ya descargado (para que el selector abra al instante la segunda vez). */
private var cacheTop: List<Mercado.Moneda> = emptyList()
private var cacheTopT = 0L

/**
 * Selector de par para operar, como el desplegable de un exchange: buscador,
 * Crypto (top 250 sin stablecoins, y búsqueda en CoinGecko si no está en el top)
 * o Stocks & markets. Tocar una fila elige el par.
 */
internal fun MainActivity.abrirSelectorPar(alElegir: (Mercado.Activo) -> Unit) {
    var modo = 0
    val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), 0) }
    val et = Ui.input(this, "Search coin or symbol (e.g. BTC, Solana)").apply { setSingleLine() }
    val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scrollLista = ScrollView(this).apply {
        addView(lista)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.55f).toInt())
    }
    var dlg: androidx.appcompat.app.AlertDialog? = null
    val tokBusqueda = java.util.concurrent.atomic.AtomicInteger(0)

    fun nota(t: String) = lista.addView(TextView(this).apply { text = t; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); setPadding(dp(4), dp(12), dp(4), dp(12)) })
    fun fila(a: Mercado.Activo, precio: Double?, cambio: Double?) = lista.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(10), dp(4), dp(10)); isClickable = true; foreground = Ui.toque()
        setOnClickListener { dlg?.dismiss(); alElegir(a) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply { text = if (a.tipo == Mercado.Tipo.CRYPTO) "${a.simbolo} / USD" else a.simbolo
                textSize = AppTheme.SP_BODY - 1f; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.bold(context) })
            addView(TextView(context).apply { text = a.nombre; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED); maxLines = 1 })
        })
        if (precio != null && precio > 0) addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            addView(TextView(context).apply { text = if (precio >= 1) "$%,.2f".format(precio) else "$%.6g".format(precio)
                textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_PRI); gravity = Gravity.END })
            if (cambio != null) addView(TextView(context).apply { text = "%s%.2f%%".format(if (cambio >= 0) "+" else "", cambio)
                textSize = AppTheme.SP_MICRO; setTextColor(if (cambio >= 0) AppTheme.OK else AppTheme.RED); gravity = Gravity.END })
        })
    })

    fun pintar() {
        lista.removeAllViews()
        val q = et.text.toString().trim().lowercase()
        if (modo == 1) {
            val r = Mercado.BOLSA.filter { q.isEmpty() || it.simbolo.lowercase().contains(q) || it.nombre.lowercase().contains(q) }
            if (r.isEmpty()) nota("No match in the markets list.") else r.forEach { fila(it, null, null) }
            return
        }
        if (cacheTop.isEmpty()) { nota("Loading top coins…"); return }
        val r = cacheTop.filter { q.isEmpty() || it.simbolo.lowercase().startsWith(q) || it.nombre.lowercase().contains(q) }
        r.take(150).forEach { fila(it.activo, it.precio, it.cambio24h) }
        if (q.length >= 2) {
            // más allá del top: búsqueda en CoinGecko (con una pausa para no buscar en cada letra)
            val mio = tokBusqueda.incrementAndGet()
            if (r.isEmpty()) nota("Searching all coins…")
            lista.postDelayed({
                if (mio != tokBusqueda.get()) return@postDelayed
                Thread {
                    val extra = Mercado.buscarCripto(q).filter { a -> r.none { it.activo.clave == a.clave } }
                    runOnUiThread {
                        if (mio != tokBusqueda.get() || modo != 0) return@runOnUiThread
                        if (r.isEmpty()) lista.removeAllViews()
                        if (extra.isNotEmpty()) {
                            lista.addView(TextView(this).apply { text = "MORE RESULTS"; textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED)
                                typeface = AppTheme.bold(context); letterSpacing = 0.08f; setPadding(dp(4), dp(12), 0, dp(2)) })
                            extra.forEach { fila(it, null, null) }
                        } else if (r.isEmpty()) nota("No coin found for \"$q\".")
                    }
                }.start()
            }, 450)
        } else if (r.isEmpty()) nota("No match.")
    }

    caja.addView(Ui.segmented(this, listOf("Crypto" to null, "Stocks & markets" to null), 0) { k -> modo = k; pintar() }
        .apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } })
    caja.addView(et); caja.addView(scrollLista)
    caja.addView(TextView(this).apply {
        text = "Crypto pairs trade against USD (Binance USDT books). The chart, order book and simulator switch to the chosen pair."
        textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_MUTED); setPadding(dp(4), dp(6), 0, 0)
    })
    et.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(e: android.text.Editable?) = pintar()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    dlg = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Select pair").setView(caja)
        .setNegativeButton("Close", null).show()
    pintar()

    if (cacheTop.isEmpty() || System.currentTimeMillis() - cacheTopT > 5 * 60_000) Thread {
        val top = (Mercado.topCripto(1, 250)).ifEmpty { Mercado.topCripto(1, 100) }
        runOnUiThread {
            if (top.isNotEmpty()) { cacheTop = top; cacheTopT = System.currentTimeMillis() }
            else if (cacheTop.isEmpty() && modo == 0) { lista.removeAllViews(); nota("Coin list unavailable (CoinGecko unreachable). You can still search.") }
            if (dlg?.isShowing == true && top.isNotEmpty()) pintar()
        }
    }.start()
}
