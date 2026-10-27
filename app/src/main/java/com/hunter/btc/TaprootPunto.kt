package com.hunter.btc

import android.content.Context

/**
 * El punto de reanudación de la auditoría Taproot, en disco.
 *
 * Antes sólo se guardaba al pararse con la pantalla abierta: si Android mataba el proceso, el móvil
 * se reiniciaba o la batería se acababa, días de barrido quedaban sin rastro. Ahora se anota al
 * arrancar y se renueva mientras corre (desde la pantalla, desde el servicio y al terminar), y el
 * valor es el punto SEGURO del motor (todo lo anterior está probado), no el número de candidatas
 * hechas, que no es un prefijo y se saltaba lo que quedaba a medias.
 */
object TaprootPunto {
    private const val PREFS = "taproot_audit"   // el mismo fichero que usa la pantalla
    private const val CADA_MS = 10_000L

    @Volatile private var ultimo = 0L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Al arrancar: de qué rango se trata y desde dónde. Deja el punto consistente aunque se muera enseguida. */
    fun iniciar(ctx: Context, inicio: String, ancho: Long, desde: Long) {
        prefs(ctx).edit().putString("ultimo_start", inicio).putLong("ultimo_ancho", ancho)
            .putLong("ultimo_done", desde).putBoolean("ultimo_completo", false).apply()
        ultimo = System.currentTimeMillis()
    }

    /** Punto seguro actual del motor, acotado al ancho; -1 si el motor no tiene ningún barrido. */
    fun seguro(): Long {
        val ancho = try { HunterEngine.taprootAncho() } catch (e: Throwable) { 0L }
        if (ancho <= 0) return -1L
        return (try { HunterEngine.taprootSeguro() } catch (e: Throwable) { 0L }).coerceIn(0L, ancho)
    }

    /**
     * Anota el punto si el motor trabaja (o acaba de parar) en el mismo rango que quedó guardado, y si
     * hace falta ([forzar] o pasaron [CADA_MS]). Nunca retrocede: un barrido reanudado parte de su propio punto.
     */
    fun guardar(ctx: Context, forzar: Boolean = false) {
        val ahora = System.currentTimeMillis()
        if (!forzar && ahora - ultimo < CADA_MS) return
        val p = prefs(ctx)
        val ancho = try { HunterEngine.taprootAncho() } catch (e: Throwable) { 0L }
        if (ancho <= 0 || ancho != p.getLong("ultimo_ancho", -1L)) return
        val s = seguro()
        if (s < 0 || s <= p.getLong("ultimo_done", 0L)) { ultimo = ahora; return }
        p.edit().putLong("ultimo_done", s).putBoolean("ultimo_completo", s >= ancho).apply()
        ultimo = ahora
    }
}
