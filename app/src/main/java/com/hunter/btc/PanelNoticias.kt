package com.hunter.btc

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Hoja de noticias del par, todas las noticias y calendario económico (USD) de la semana. */
internal fun MainActivity.abrirNoticias(a: Mercado.Activo) {
    val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val estado = TextView(this).apply { textSize = 14f; setTextColor(DexTema.TXT2); setPadding(0, dp(8), 0, dp(4)) }
    var noticias: List<Noticias.Noticia>? = null
    var eventos: List<Noticias.Evento>? = null
    var modo = 0
    var dlg: android.app.Dialog? = null
    val fh = java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.getDefault())
    fun t(sp: Float, color: Int, negrita: Boolean = false) = TextView(this).apply { textSize = sp; setTextColor(color); typeface = if (negrita) AppTheme.bold(context) else AppTheme.body(context) }
    fun hace(ts: Long): String {
        if (ts <= 0) return ""
        val m = (System.currentTimeMillis() / 1000 - ts) / 60
        return when { m < 1 -> "now"; m < 60 -> "${m}m ago"; m < 1440 -> "${m / 60}h ago"; else -> "${m / 1440}d ago" }
    }
    fun abrirEnlace(n: Noticias.Noticia) {
        DexTema.dialogo(this).setTitle(n.fuente).setMessage("Open this article in your browser? The browser does not use the app's Tor connection.\n\n${n.enlace}")
            .setPositiveButton("Open") { _, _ -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.enlace))) } catch (e: Exception) { } }
            .setNegativeButton("Cancel", null).show()
    }
    fun pintar() {
        lista.removeAllViews()
        if (modo == 2) {
            val ev = eventos ?: run { estado.text = "Loading this week's calendar…"; return }
            if (ev.isEmpty()) { estado.text = "No calendar data (source unreachable)."; return }
            val ahora = System.currentTimeMillis() / 1000
            estado.text = "US economic events this week · high and medium impact · your local time"
            for (e in ev) lista.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(52); setPadding(0, dp(4), 0, dp(4))
                alpha = if (e.t in 1 until ahora - 3600) 0.55f else 1f
                val alto = e.impacto == "High"
                contentDescription = "${e.titulo}, ${e.impacto} impact, ${fh.format(java.util.Date(e.t * 1000))}"
                addView(t(12f, if (alto) DexTema.DOWN else DexTema.LIQ, true).apply { text = if (alto) "HIGH" else "MED"; minWidth = dp(44) })
                addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(t(14f, DexTema.TXT, true).apply { text = e.titulo })
                    addView(t(12f, DexTema.TXT2).apply { text = listOfNotNull(if (e.t > 0) fh.format(java.util.Date(e.t * 1000)) else null,
                        e.prevision.takeIf { it.isNotBlank() }?.let { "forecast $it" }, e.anterior.takeIf { it.isNotBlank() }?.let { "previous $it" }).joinToString(" · ") }) })
            })
            return
        }
        val todas = noticias ?: run { estado.text = "Loading news from CoinDesk, Cointelegraph and Decrypt…"; return }
        if (todas.isEmpty()) { estado.text = "No news (sources unreachable)."; return }
        val ver = if (modo == 0) Noticias.filtrar(todas, a.simbolo, a.nombre) else todas
        estado.text = if (modo == 0) (if (ver.isEmpty()) "No recent headlines mention ${a.simbolo}. See All news." else "${ver.size} headlines mention ${a.simbolo}")
            else "${todas.size} latest headlines"
        for (n in ver.take(60)) lista.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; minimumHeight = dp(48); setPadding(0, dp(8), 0, dp(8))
            isClickable = true; foreground = Ui.toque(); contentDescription = "${n.titulo}. ${n.fuente}, ${hace(n.t)}. Opens in the browser"
            setOnClickListener { abrirEnlace(n) }
            addView(t(14f, DexTema.TXT).apply { text = n.titulo })
            addView(t(12f, DexTema.TXT2).apply { text = listOf(n.fuente, hace(n.t)).filter { it.isNotEmpty() }.joinToString(" · ") })
        })
    }
    caja.addView(DexTema.pestanas(this, listOf(a.simbolo to null, "All news" to null, "Calendar" to null), 0) { k -> modo = k; pintar() })
    caja.addView(estado); caja.addView(lista)
    caja.addView(t(12f, DexTema.TXT2).apply { setPadding(0, dp(12), 0, 0)
        text = "Headlines from public RSS feeds and the Forex Factory calendar, loaded through Tor when it is on. Not financial advice." })
    dlg = DexTema.hoja(this, "News & calendar", caja).also { it.show() }
    pintar()
    Thread {
        val n = try { Noticias.cargarNoticias() } catch (e: Throwable) { emptyList() }
        runOnUiThread { noticias = n; if (dlg?.isShowing == true) pintar() }
    }.start()
    Thread {
        val e = try { Noticias.cargarCalendario() } catch (e: Throwable) { emptyList() }
        runOnUiThread { eventos = e; if (dlg?.isShowing == true) pintar() }
    }.start()
}
