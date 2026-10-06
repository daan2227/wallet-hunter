package com.hunter.btc

import android.graphics.Canvas
import android.graphics.Paint

/**
 * Una barra de progreso fina y redondeada, con la paleta de "Bóveda".
 *
 * Determinada a propósito: sólo se usa donde hay un total REAL contra el que
 * medir (la auditoría weak procesa una lista finita de claves). El puzzle y el
 * escáner recorren un espacio astronómico donde "porcentaje" no significa nada
 * —el avance se dice en probabilidad, "1 entre 10^N"—, así que ahí no va barra:
 * una que se quedara clavada en 0 % para siempre mentiría.
 */
class BarraProgreso(ctx: android.content.Context) : android.view.View(ctx) {
    private var frac = 0f
    private val pTrack = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Fija la fracción [0..1]. Sólo repinta si cambia de verdad. */
    fun set(f: Float) {
        val n = f.coerceIn(0f, 1f)
        if (n != frac) { frac = n; postInvalidate() }
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f
        pTrack.color = AppTheme.BG_ELEV
        c.drawRoundRect(0f, 0f, w, h, r, r, pTrack)
        if (frac > 0f) {
            pFill.color = AppTheme.ACCENT
            // Al menos un punto redondo cuando acaba de arrancar, para que se
            // vea que ya hay algo en vez de una barra vacía.
            val fw = (w * frac).coerceAtLeast(h)
            c.drawRoundRect(0f, 0f, fw, h, r, r, pFill)
        }
    }
}
