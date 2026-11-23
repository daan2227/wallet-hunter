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

    class Alerta(val id: Long, val activo: String, val clave: String, val simbolo: String,
                 /** 0 por encima, 1 por debajo, 2 movimiento ±%. */ val tipo: Int, val valor: Double, val base: Double, val creada: Long) {
        fun texto() = when (tipo) {
            0 -> "$simbolo above ${fmtSim(valor)}"
            1 -> "$simbolo below ${fmtSim(valor)}"
            else -> "$simbolo moves ±${"%.2f".format(valor)}% from ${fmtSim(base)}"
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lista(ctx: Context): MutableList<Alerta> {
        val out = ArrayList<Alerta>()
        try { val a = JSONArray(prefs(ctx).getString("lista", "[]")); for (i in 0 until a.length()) { val o = a.getJSONObject(i)
            out.add(Alerta(o.getLong("id"), o.optString("a"), o.getString("c"), o.getString("s"), o.getInt("t"), o.getDouble("v"), o.optDouble("b", 0.0), o.optLong("cr"))) }
        } catch (e: Exception) { }
        return out
    }
    private fun guardar(ctx: Context, l: List<Alerta>) = prefs(ctx).edit().putString("lista", JSONArray().apply { l.forEach { x ->
        put(JSONObject().put("id", x.id).put("a", x.activo).put("c", x.clave).put("s", x.simbolo).put("t", x.tipo).put("v", x.valor).put("b", x.base).put("cr", x.creada)) } }.toString()).apply()

    fun agregar(ctx: Context, a: Mercado.Activo, tipo: Int, valor: Double, precioActual: Double): String? {
        if (valor <= 0) return "Enter a value"
        if (tipo == 0 && precioActual > 0 && valor <= precioActual) return "\"Above\" must be higher than the current price"
        if (tipo == 1 && precioActual > 0 && valor >= precioActual) return "\"Below\" must be lower than the current price"
        if (tipo == 2 && (precioActual <= 0 || valor > 100)) return if (precioActual <= 0) "Price not loaded yet" else "Use a move of up to 100%"
        val l = lista(ctx)
        if (l.size >= 50) return "Up to 50 alerts"
        l.add(Alerta(System.currentTimeMillis(), a.aJson(), a.clave, a.simbolo, tipo, valor, precioActual, System.currentTimeMillis() / 1000))
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
            else -> x.base > 0 && Math.abs(precio - x.base) / x.base * 100 >= x.valor
        } }
        if (hechas.isEmpty()) return emptyList()
        guardar(ctx, l - hechas.toSet())
        val textos = hechas.map { "🔔 ${it.texto()} · now ${fmtSim(precio)}" }
        textos.forEach { notificar(ctx, it) }
        return textos
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
