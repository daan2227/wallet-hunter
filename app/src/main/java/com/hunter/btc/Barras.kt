package com.hunter.btc

import android.graphics.LinearGradient
import android.graphics.Shader

/**
 * El degradado común de todas las barras: BLUE (izquierda) → ACCENT (derecha),
 * a lo ancho que se le pida. Es el mismo que ya usaba la barra de cobertura del
 * puzzle; al compartirlo, la de progreso (weak) y la de actividad (Kangaroo) se
 * ven del mismo sistema en vez de tres acentos sueltos.
 *
 * Se cachea por ancho: onDraw se llama muchas veces y crear un shader cada vez
 * es basura para el recolector. BLUE y ACCENT no cambian con el tema (son fijos
 * en claro y oscuro), así que basta la clave de ancho.
 */
object Barras {
    @Volatile private var cacheW = 0f
    @Volatile private var cache: LinearGradient? = null

    fun degradado(w: Float): LinearGradient {
        val c = cache
        if (c != null && cacheW == w) return c
        val g = LinearGradient(0f, 0f, w, 0f, AppTheme.BLUE, AppTheme.ACCENT, Shader.TileMode.CLAMP)
        cache = g; cacheW = w
        return g
    }
}
