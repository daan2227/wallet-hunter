package com.hunter.btc

import android.app.Application
import android.content.Context
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

/**
 * Las consultas ligeras (saldos, comisiones, difusión, Electrum, pares P2P del
 * cliente ligero y el actualizador) por el proxy SOCKS5 de Tor, si se pide en
 * More → Privacy.
 *
 * Con Tor activado NUNCA se cae a conexión directa: si el proxy no responde,
 * la consulta falla. Un respaldo silencioso mandaría las direcciones a la
 * vista de todos justo cuando el usuario pidió lo contrario.
 *
 * El nombre del servidor se manda sin resolver al proxy (createUnresolved), así
 * ni el DNS del teléfono sabe a qué se conecta la app.
 */
object RedPrivada {
    private const val PREFS = "red_privada"
    private const val K_ON = "tor"
    private const val K_PROXY = "proxy"

    @Volatile private var proxy: Proxy? = null

    /** El proxy en uso, o null para conexión directa. */
    fun proxy(): Proxy? = proxy
    fun activa() = proxy != null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun direccion(ctx: Context): String =
        prefs(ctx).getString(K_PROXY, CoreConfig.PROXY_TOR)?.takeIf { CoreConfig.proxyValido(it) } ?: CoreConfig.PROXY_TOR

    fun cargar(ctx: Context) {
        val p = prefs(ctx)
        proxy = if (p.getBoolean(K_ON, false)) deDireccion(direccion(ctx)) else null
    }

    fun poner(ctx: Context, activar: Boolean) {
        prefs(ctx).edit().putBoolean(K_ON, activar).apply()
        cargar(ctx)
    }

    fun ponerDireccion(ctx: Context, hostPuerto: String) {
        if (!CoreConfig.proxyValido(hostPuerto)) return
        prefs(ctx).edit().putString(K_PROXY, hostPuerto.trim()).apply()
        cargar(ctx)
    }

    /** "127.0.0.1:9050" → proxy SOCKS; null si no es una dirección válida. */
    fun deDireccion(hostPuerto: String): Proxy? {
        val t = hostPuerto.trim()
        val i = t.lastIndexOf(':')
        if (!CoreConfig.proxyValido(t)) return null
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(t.substring(0, i), t.substring(i + 1).toInt()))
    }

    /** HttpURLConnection por el proxy si está activo (los tiempos de espera los pone quien llama). */
    fun abrir(url: String): HttpURLConnection {
        val u = URL(url)
        val p = proxy
        return (if (p != null) u.openConnection(p) else u.openConnection()) as HttpURLConnection
    }

    /** El propio teléfono (nodo Core local) no tiene por qué pasar por Tor. */
    private fun local(host: String) = host == "localhost" || host == "::1" || host.startsWith("127.")

    /** Socket sin conectar; con Tor, los destinos se mandan SIN resolver (usar [destino] con el mismo host). */
    fun socket(host: String): Socket {
        val p = proxy
        return if (p != null && !local(host)) Socket(p) else Socket()
    }

    fun destino(host: String, puerto: Int): InetSocketAddress =
        if (proxy != null && !local(host)) InetSocketAddress.createUnresolved(host, puerto) else InetSocketAddress(host, puerto)
}

/** Para que el ajuste esté cargado desde cualquier punto de entrada (servicios, arranque del móvil). */
class HunterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try { RedPrivada.cargar(this) } catch (_: Exception) {}
    }
}
