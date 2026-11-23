package com.hunter.btc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews

/**
 * Widget de inicio del DEx: precio y cambio 24 h de hasta 4 favoritos (BTC y ETH si
 * no hay) y el patrimonio y rendimiento de la cuenta de prueba VUSD. Se actualiza
 * cada 30 min (mínimo de Android) y tras cada revisión en segundo plano (DexJob).
 * Tocarlo abre la app (con su bloqueo, si lo tiene).
 */
class WidgetDex : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pendiente = goAsync()
        Thread {
            try { actualizar(ctx) } catch (e: Throwable) { RegistroFallos.anotar("WidgetDex", e) } finally { pendiente.finish() }
        }.start()
    }

    companion object {
        private const val UP = 0xFF0ECB81.toInt(); private const val DOWN = 0xFFF6465D.toInt(); private const val GRIS = 0xFF848E9C.toInt()
        private val FILAS = intArrayOf(R.id.w_fila1, R.id.w_fila2, R.id.w_fila3, R.id.w_fila4)
        private val SIMS = intArrayOf(R.id.w_sim1, R.id.w_sim2, R.id.w_sim3, R.id.w_sim4)
        private val PXS = intArrayOf(R.id.w_px1, R.id.w_px2, R.id.w_px3, R.id.w_px4)
        private val CHS = intArrayOf(R.id.w_ch1, R.id.w_ch2, R.id.w_ch3, R.id.w_ch4)

        fun hayWidgets(ctx: Context): Boolean = try {
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, WidgetDex::class.java)).isNotEmpty()
        } catch (e: Exception) { false }

        /** Red + pintado (hilo de fondo). */
        fun actualizar(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WidgetDex::class.java))
            if (ids.isEmpty()) return
            val favs = Favoritos.lista(ctx).take(4).ifEmpty { listOf(Mercado.BTC, Mercado.Activo(Mercado.Tipo.CRYPTO, "ETH", "Ethereum", "ethereum")) }
            val datos = favs.map { a ->
                val v = try { Mercado.velas(a, "1h", 25) } catch (e: Throwable) { emptyList() }
                val p = v.lastOrNull()?.c?.toDouble()
                val ch = if (v.size >= 25 && v.first().c > 0) (v.last().c / v.first().c - 1) * 100.0 else null
                Triple(a, p, ch)
            }
            val sim = Simulador.deJson(ctx.getSharedPreferences("simulador", Context.MODE_PRIVATE).getString("estado", null))
            for ((a, p, _) in datos) if (p != null && p > 0) sim.precios[a.clave] = p
            val rv = RemoteViews(ctx.packageName, R.layout.widget_dex)
            rv.setTextViewText(R.id.w_patrimonio, "${fmtSim(sim.patrimonio())} VUSD")
            val r = sim.rendimiento(); val pct = if (sim.netoDepositado > 0) r / sim.netoDepositado * 100 else 0.0
            rv.setTextViewText(R.id.w_pnl, "%s%s (%+.2f%%)".format(if (r >= 0) "+" else "", fmtSim(r), pct))
            rv.setTextColor(R.id.w_pnl, if (r >= 0) UP else DOWN)
            rv.setTextViewText(R.id.w_hora, java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))
            for (i in 0 until 4) {
                val d = datos.getOrNull(i)
                rv.setViewVisibility(FILAS[i], if (d == null) View.GONE else View.VISIBLE)
                if (d == null) continue
                val (a, p, ch) = d
                rv.setTextViewText(SIMS[i], a.simbolo)
                rv.setTextViewText(PXS[i], p?.let { fmtSim(it) } ?: "—")
                rv.setTextViewText(CHS[i], ch?.let { "%+.2f%%".format(it) } ?: "—")
                rv.setTextColor(CHS[i], when { ch == null -> GRIS; ch >= 0 -> UP; else -> DOWN })
            }
            rv.setViewVisibility(R.id.w_vacio, if (Favoritos.lista(ctx).isEmpty()) View.VISIBLE else View.GONE)
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { i ->
                rv.setOnClickPendingIntent(R.id.w_raiz, PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            mgr.updateAppWidget(ids, rv)
        }
    }
}
