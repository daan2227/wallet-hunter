package com.hunter.btc

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/**
 * Gráfico del DEx a pantalla completa y en horizontal: mismo par, intervalo,
 * indicadores, dibujos y apariencia que en el DEx, más alto y más ancho. Se
 * actualiza solo cada pocos segundos. Volver (o la X) regresa al DEx.
 */
class GraficoCompletoActivity : androidx.appcompat.app.AppCompatActivity() {
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var chart: GraficoTA
    private lateinit var activo: Mercado.Activo
    private var intervalo = "1h"
    private var vivo = true

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        setTheme(AppTheme.estilo(this))
        super.onCreate(s)
        PantallaSegura.aplicar(this)
        AppTheme.init(this)
        val prefs = getSharedPreferences("home_chart", MODE_PRIVATE)
        activo = Mercado.Activo.deJson(intent.getStringExtra("activo")) ?: Mercado.BTC
        intervalo = intent.getStringExtra("intervalo") ?: "1h"
        val segIv = intent.getLongExtra("intervaloSeg", 3600L)

        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(DexTema.BG); setPadding(dp(12), dp(4), dp(4), 0) }
        val tvTitulo = TextView(this).apply { text = activo.par; textSize = 18f; setTextColor(DexTema.TXT); typeface = AppTheme.bold(context) }
        val tvPrecio = TextView(this).apply { textSize = 18f; setTextColor(DexTema.TXT); typeface = AppTheme.bold(context); setPadding(dp(14), 0, 0, 0) }
        val tvIv = TextView(this).apply { text = intervalo; textSize = 14f; setTextColor(DexTema.TXT2); setPadding(dp(12), 0, 0, 0) }
        val cerrar = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_x); setColorFilter(DexTema.TXT); scaleType = android.widget.ImageView.ScaleType.CENTER
            contentDescription = "Close full screen chart"; isClickable = true; foreground = Ui.toque()
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)); setOnClickListener { finish() }
        }
        raiz.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(tvTitulo); addView(tvPrecio); addView(tvIv)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }); addView(cerrar)
        })
        chart = GraficoTA(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            intervaloSeg = segIv
            cfg.deJson(intent.getStringExtra("cfg"))
            cargarDibujos(prefs.getString("dibujos_" + activo.clave, null))
            val inv = prefs.getBoolean("i_inv", false)
            cUp = if (inv) DexTema.DOWN else DexTema.UP; cDown = if (inv) DexTema.UP else DexTema.DOWN
            huecaAlcista = prefs.getBoolean("i_hueca_up", false); huecaBajista = prefs.getBoolean("i_hueca_down", false)
            tipoGrafico = prefs.getInt("i_tipo", 0).coerceIn(0, 3)
            if (prefs.getBoolean("i_utc", false)) zona = java.util.TimeZone.getTimeZone("UTC")
            mostrarCuenta = activo.tipo == Mercado.Tipo.CRYPTO && prefs.getBoolean("c_cuenta", true)
            // en horizontal el panel de precio usa el alto que dejan los paneles de indicadores
            val altoDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
            alturaPrecioDp = (altoDp - 56 - cfg.paneles() * 86 - 40).coerceAtLeast(160f)
        }
        raiz.addView(ScrollView(this).apply { addView(chart)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) })
        setContentView(raiz)

        fun pintarPrecio() { chart.velasActuales().lastOrNull()?.let { v ->
            val ini = chart.velasActuales().first().o; val pct = if (ini > 0) (v.c - ini) / ini * 100 else 0f
            tvPrecio.text = "%s  %s%.2f%%".format(fmtSim(v.c.toDouble()), if (pct >= 0) "+" else "", pct)
            tvPrecio.setTextColor(if (pct >= 0) chart.cUp else chart.cDown) } }
        Thread { val v = Mercado.velas(activo, intervalo, 500); runOnUiThread { if (v.isNotEmpty()) { chart.poner(v); pintarPrecio() } } }.start()
        // actualización cada 3 s (o lo que permita la fuente)
        h.postDelayed(object : Runnable {
            override fun run() {
                if (!vivo) return
                Thread {
                    val n = try { Mercado.velas(activo, intervalo, 3) } catch (e: Throwable) { emptyList() }
                    runOnUiThread {
                        val out = ArrayList(chart.velasActuales()); if (out.isEmpty() || n.isEmpty()) return@runOnUiThread
                        var quitadas = 0
                        for (k in n) { val u = out.last()
                            when { k.t == u.t -> out[out.size - 1] = k; k.t > u.t -> { out.add(k); if (out.size > 500) { out.removeAt(0); quitadas++ } } } }
                        chart.actualizar(out, quitadas); pintarPrecio()
                    }
                }.start()
                h.postDelayed(this, maxOf(3_000L, Mercado.refrescoMinimoMs(activo)))
            }
        }, 3_000)
        chart.alCambiarDibujos = { prefs.edit().putString("dibujos_" + activo.clave, chart.dibujosJson()).apply() }
    }

    override fun onDestroy() { vivo = false; h.removeCallbacksAndMessages(null); super.onDestroy() }
}
