package com.hunter.btc

import android.content.Context
import java.security.MessageDigest

/**
 * Punto de control de la búsqueda BSGS por lotes.
 *
 * El motor guarda, por cada clave pública aún sin resolver, en qué paso va. Aquí
 * se persiste eso por CAMPAÑA (la lista de claves + rango + cap): si la app se
 * cierra o el móvil se reinicia, al volver a lanzar la misma búsqueda continúa
 * desde donde se quedó en vez de empezar de cero.
 *
 * Sólo se guarda el progreso (paso y punto). Nada de claves privadas: las
 * claves encontradas van por el camino normal, al baúl.
 */
object BsgsCheckpoint {
    private const val PREFS = "bsgs_checkpoint"

    /** Identidad de la campaña: mismas claves, mismo rango y mismo cap => misma. */
    fun campana(pubs: String, ini: String, fin: String, cap: Int): String {
        val d = MessageDigest.getInstance("SHA-256")
            .digest("$pubs\u0001$ini\u0001$fin\u0001$cap".toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /** Líneas "pubhex|estadohex" de la campaña, tal como las pide el motor. */
    fun cargar(ctx: Context?, campana: String): String {
        if (ctx == null) return ""
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(campana, "") ?: ""
        } catch (e: Throwable) { "" }
    }

    /**
     * Toma del informe del motor las líneas "E\tpub\testado" y las guarda. Si no
     * queda ninguna (todo resuelto), borra la campaña.
     */
    fun guardarDesdeInforme(ctx: Context?, campana: String, informe: String) {
        if (ctx == null) return
        val sb = StringBuilder()
        for (ln in informe.split('\n')) {
            if (!ln.startsWith("E\t")) continue
            val f = ln.split('\t')
            if (f.size >= 3) sb.append(f[1]).append('|').append(f[2]).append('\n')
        }
        try {
            val ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (sb.isEmpty()) ed.remove(campana) else ed.putString(campana, sb.toString())
            ed.apply()
        } catch (e: Throwable) { }
    }
}
