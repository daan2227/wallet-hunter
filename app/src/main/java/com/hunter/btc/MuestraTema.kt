package com.hunter.btc

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * La vista previa de un tema en el selector: un mini-mockup de la pantalla —el
 * fondo, una tarjeta encima con dos líneas de "texto" y un punto de acento—,
 * para elegir por cómo se ve y no sólo por el nombre.
 */
class MuestraTema(ctx: android.content.Context) : android.view.View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = android.graphics.Path()
    private val r = RectF()

    private var muestra: AppTheme.Muestra? = null

    fun individual(m: AppTheme.Muestra) { muestra = m; invalidate() }

    private fun dp(v: Float) = v * d

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val base = muestra ?: return
        val rad = dp(8f)
        r.set(0f, 0f, w, h)
        // Recorta todo al rectángulo redondeado del "teléfono".
        clip.reset(); clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW)
        c.save(); c.clipPath(clip)

        p.color = base.bg; c.drawRect(0f, 0f, w, h, p)

        // Una "tarjeta" centrada con dos líneas de texto.
        val m = dp(7f)
        val cardR = dp(5f)
        p.color = base.card
        r.set(m, m, w - m, h - m); c.drawRoundRect(r, cardR, cardR, p)
        lineas(c, base.texto, m, w - m)

        // Punto de acento, centrado.
        p.color = base.accent
        c.drawCircle(w / 2f, h / 2f, dp(4.5f), p)
        c.restore()
    }

    private fun lineas(c: Canvas, color: Int, x0: Float, x1: Float) {
        p.color = color
        val h = height.toFloat()
        val lh = dp(2.5f)
        r.set(x0 + dp(4f), h * 0.30f, x1 - dp(8f), h * 0.30f + lh)
        c.drawRoundRect(r, lh / 2f, lh / 2f, p)
        r.set(x0 + dp(4f), h * 0.46f, x1 - dp(14f), h * 0.46f + lh)
        c.drawRoundRect(r, lh / 2f, lh / 2f, p)
    }
}
