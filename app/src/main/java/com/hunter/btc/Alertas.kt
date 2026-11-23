package com.hunter.btc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Alertas de precio del DEx: "por encima de", "por debajo de" o "se mueve ±X %
 * desde que se creó". Se comprueban con los precios en vivo del Home y, cada
 * 20 s, con velas de 1 min de todos los pares con alerta. Al cumplirse avisan con
 * una notificación (si el permiso está concedido) y se borran.
 */
object Alertas {
    private const val CANAL = "dex_alerts"
    private const val PREFS = "dex_alertas"

    /**
     * tipo: 0 por encima, 1 por debajo, 2 movimiento ±% · de indicador (con velas de [iv]):
     * 3 RSI(14) ≤ valor, 4 RSI(14) ≥ valor, 5 cierre cruza hacia arriba la SMA(valor), 6 hacia abajo.
     */
    class Alerta(val id: Long, val activo: String, val clave: String, val simbolo: String,
                 val tipo: Int, val valor: Double, val base: Double, val creada: Long, val iv: String = "") {
        fun texto() = when (tipo) {
            0 -> "$simbolo above ${fmtSim(valor)}"
            1 -> "$simbolo below ${fmtSim(valor)}"
            2 -> "$simbolo moves ±${"%.2f".format(valor)}% from ${fmtSim(base)}"
            3 -> "$simbolo RSI(14) ≤ ${valor.toInt()} on $iv"
            4 -> "$simbolo RSI(14) ≥ ${valor.toInt()} on $iv"
            5 -> "$simbolo closes above MA${valor.toInt()} on $iv"
            else -> "$simbolo closes below MA${valor.toInt()} on $iv"
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lista(ctx: Context): MutableList<Alerta> {
        val out = ArrayList<Alerta>()
        try { val a = JSONArray(prefs(ctx).getString("lista", "[]")); for (i in 0 until a.length()) { val o = a.getJSONObject(i)
            out.add(Alerta(o.getLong("id"), o.optString("a"), o.getString("c"), o.getString("s"), o.getInt("t"), o.getDouble("v"), o.optDouble("b", 0.0), o.optLong("cr"), o.optString("iv"))) }
        } catch (e: Exception) { }
        return out
    }
    private fun guardar(ctx: Context, l: List<Alerta>) = prefs(ctx).edit().putString("lista", JSONArray().apply { l.forEach { x ->
        put(JSONObject().put("id", x.id).put("a", x.activo).put("c", x.clave).put("s", x.simbolo).put("t", x.tipo).put("v", x.valor).put("b", x.base).put("cr", x.creada).put("iv", x.iv)) } }.toString()).apply()

    fun agregar(ctx: Context, a: Mercado.Activo, tipo: Int, valor: Double, precioActual: Double, iv: String = ""): String? {
        if (valor <= 0) return "Enter a value"
        if ((tipo == 3 || tipo == 4) && valor >= 100) return "RSI goes from 0 to 100"
        if ((tipo == 5 || tipo == 6) && (valor < 2 || valor > 400)) return "Moving average period must be 2–400"
        if (tipo == 0 && precioActual > 0 && valor <= precioActual) return "\"Above\" must be higher than the current price"
        if (tipo == 1 && precioActual > 0 && valor >= precioActual) return "\"Below\" must be lower than the current price"
        if (tipo == 2 && (precioActual <= 0 || valor > 100)) return if (precioActual <= 0) "Price not loaded yet" else "Use a move of up to 100%"
        val l = lista(ctx)
        if (l.size >= 50) return "Up to 50 alerts"
        l.add(Alerta(System.currentTimeMillis(), a.aJson(), a.clave, a.simbolo, tipo, if (tipo >= 5) Math.round(valor).toDouble() else valor, precioActual,
            System.currentTimeMillis() / 1000, if (tipo >= 3) iv else ""))
        guardar(ctx, l); return null
    }
    fun borrar(ctx: Context, id: Long) = guardar(ctx, lista(ctx).filter { it.id != id })
    fun claves(ctx: Context): Map<String, String> = lista(ctx).associate { it.clave to it.activo }

    /** Comprueba un precio de [clave]; avisa y borra las que se cumplen. Devuelve sus textos. */
    fun comprobar(ctx: Context, clave: String, precio: Double): List<String> {
        if (precio <= 0) return emptyList()
        val l = lista(ctx); if (l.none { it.clave == clave }) return emptyList()
        val hechas = l.filter { x -> x.clave == clave && when (x.tipo) {
            0 -> precio >= x.valor; 1 -> precio <= x.valor
            2 -> x.base > 0 && Math.abs(precio - x.base) / x.base * 100 >= x.valor
            else -> false                       // las de indicador se miran con velas (revisarIndicadores)
        } }
        if (hechas.isEmpty()) return emptyList()
        guardar(ctx, l - hechas.toSet())
        val textos = hechas.map { "${it.texto()} · now ${fmtSim(precio)}" }
        textos.forEach { notificar(ctx, it) }
        return textos
    }

    /**
     * Alertas de indicador (hilo de fondo): baja las velas de cada par e intervalo con
     * alertas y comprueba RSI(14) y cruces de la media en la última vela cerrada.
     */
    fun revisarIndicadores(ctx: Context): List<String> {
        val l = lista(ctx).filter { it.tipo >= 3 }; if (l.isEmpty()) return emptyList()
        val hechas = ArrayList<Pair<Alerta, String>>()
        for ((par, grupo) in l.groupBy { it.clave to it.iv }) {
            val a = Mercado.Activo.deJson(grupo[0].activo) ?: continue
            val v = try { Mercado.velas(a, par.second, 200) } catch (e: Throwable) { emptyList() }
            if (v.size < 3) continue
            val cerradas = v.dropLast(1)              // la última vela aún no ha cerrado
            val c = FloatArray(cerradas.size) { cerradas[it].c }
            val rsi = Indicadores.rsi(c, 14).lastOrNull()?.takeIf { !it.isNaN() }
            for (x in grupo) {
                val ok = when (x.tipo) {
                    3 -> rsi != null && rsi <= x.valor
                    4 -> rsi != null && rsi >= x.valor
                    else -> {
                        val n = x.valor.toInt(); val ma = Indicadores.sma(c, n)
                        val k = c.size - 1
                        if (k < 1 || ma[k].isNaN() || ma[k - 1].isNaN()) false
                        else if (x.tipo == 5) c[k - 1] <= ma[k - 1] && c[k] > ma[k] else c[k - 1] >= ma[k - 1] && c[k] < ma[k]
                    }
                }
                if (ok) hechas.add(x to (x.texto() + (if (x.tipo <= 4 && rsi != null) " · RSI %.1f".format(rsi) else " · close ${fmtSim(c.last().toDouble())}")))
            }
        }
        if (hechas.isEmpty()) return emptyList()
        val ids = hechas.map { it.first.id }.toSet()
        guardar(ctx, lista(ctx).filter { it.id !in ids })
        hechas.forEach { notificar(ctx, it.second, "Indicator alert") }
        return hechas.map { it.second }
    }

    /** Aviso con título propio (lo usa la vigilancia del simulador en segundo plano). */
    fun notificarTexto(ctx: Context, titulo: String, texto: String) = notificar(ctx, texto, titulo)

    private fun notificar(ctx: Context, texto: String, titulo: String = "Price alert") {
        try {
            if (!Avisos.permitidos(ctx)) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CANAL) == null)
                nm.createNotificationChannel(NotificationChannel(CANAL, "DEx price alerts", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify((System.nanoTime() % 100_000).toInt() + 20_000, NotificationCompat.Builder(ctx, CANAL)
                .setSmallIcon(R.drawable.ic_notif).setContentTitle(titulo).setContentText(texto)
                .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi).build())
        } catch (e: Throwable) { }
    }
}

/** Pares favoritos (watchlist) del DEx, guardados como JSON de Activo. */
object Favoritos {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dex_favoritos", Context.MODE_PRIVATE)
    fun lista(ctx: Context): List<Mercado.Activo> = try {
        val a = JSONArray(prefs(ctx).getString("lista", "[]")); (0 until a.length()).mapNotNull { Mercado.Activo.deJson(a.getString(it)) }
    } catch (e: Exception) { emptyList() }
    fun es(ctx: Context, clave: String) = lista(ctx).any { it.clave == clave }
    /** Añade o quita; devuelve true si ahora es favorito. */
    fun alternar(ctx: Context, a: Mercado.Activo): Boolean {
        val l = lista(ctx).toMutableList(); val ya = l.any { it.clave == a.clave }
        if (ya) l.removeAll { it.clave == a.clave } else l.add(a)
        prefs(ctx).edit().putString("lista", JSONArray().apply { l.forEach { put(it.aJson()) } }.toString()).apply()
        return !ya
    }
}
