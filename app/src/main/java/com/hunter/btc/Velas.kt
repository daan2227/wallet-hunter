package com.hunter.btc

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Un mini gráfico de VELAS (candlesticks), como el de un exchange: mechas finas
 * y cuerpos verde/rojo. Es decorativo —no hay precios que mostrar en un escáner
 * de claves—, así que las velas salen de un patrón fijo (los mismos senos), no
 * de datos: se ve igual siempre y no engaña con un "mercado" que no existe.
 *
 * Acompaña al tema Exchange. Los colores se le pasan para que la vista previa
 * del selector y la cabecera usen exactamente la paleta de ese tema.
 */
class Velas(ctx: android.content.Context) : android.view.View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = android.graphics.Path()
    private val r = RectF()

    private var bg = 0
    private var sube = 0     // vela alcista (verde)
    private var baja = 0     // vela bajista (rojo)
    private var redondo = true

    private var fase = 0f    // deriva lenta de la animación
    private var animado = false
    private var anim: android.animation.ValueAnimator? = null

    fun colores(fondo: Int, verde: Int, rojo: Int, esquinas: Boolean = true) {
        bg = fondo; sube = verde; baja = rojo; redondo = esquinas; invalidate()
    }

    /** Enciende (o apaga) la animación: las velas van morfando despacio, como
     *  un gráfico vivo, pero el movimiento sale de un seno que recorre su ciclo
     *  y vuelve —no hay datos detrás—. Se para sola al salir de pantalla. */
    fun animar(on: Boolean) {
        animado = on
        if (on) arrancar() else parar()
    }

    private fun arrancar() {
        if (anim != null) return
        anim = android.animation.ValueAnimator.ofFloat(0f, (2.0 * Math.PI).toFloat()).apply {
            duration = 8000                 // un ciclo completo bien lento
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { fase = it.animatedValue as Float; postInvalidateOnAnimation() }
            start()
        }
    }

    private fun parar() { anim?.cancel(); anim = null }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (animado) arrancar() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); parar() }

    /** Pausa cuando la pestaña no se ve (otra pestaña delante) y reanuda al
     *  volver: nada de animar en segundo plano gastando batería. */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (animado) { if (isVisible) arrancar() else parar() }
    }

    private fun dp(v: Float) = v * d

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (bg != 0) {
            val rad = if (redondo) dp(8f) else 0f
            r.set(0f, 0f, w, h)
            clip.reset(); clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW)
            c.save(); c.clipPath(clip)
            c.drawColor(bg)
        } else c.save()

        val padV = h * 0.12f
        val top = padV; val bot = h - padV
        fun y(frac: Float) = bot - (bot - top) * frac.coerceIn(0f, 1f)

        // Cuántas velas caben, con un hueco cómodo entre ellas.
        val slot = dp(13f)
        val n = (w / slot).toInt().coerceIn(4, 48)
        val paso = w / n
        val cuerpo = paso * 0.56f

        for (i in 0 until n) {
            val cx = paso * (i + 0.5f)
            // Patrón fijo y suave: dos senos desfasados dan apertura y cierre, y
            // la mecha asoma un poco por encima y por debajo del cuerpo.
            val o = 0.5f + 0.30f * Math.sin(i * 0.9 + fase).toFloat()
            val cl = 0.5f + 0.30f * Math.sin(i * 0.9 + 0.8 + fase).toFloat()
            val cuerpoTop = maxOf(o, cl)      // más arriba en pantalla = fracción mayor
            val cuerpoBot = minOf(o, cl)
            val hi = (cuerpoTop + 0.07f + 0.04f * Math.abs(Math.sin(i * 2.1)).toFloat())
            val lo = (cuerpoBot - 0.07f - 0.04f * Math.abs(Math.cos(i * 1.7)).toFloat())
            val alcista = cl >= o
            p.color = if (alcista) sube else baja
            // Mecha.
            p.strokeWidth = dp(1.3f)
            c.drawLine(cx, y(hi), cx, y(lo), p)
            // Cuerpo (mínimo 2 px para que una vela "doji" no desaparezca).
            val yt = y(cuerpoTop); val yb = y(cuerpoBot)
            r.set(cx - cuerpo / 2f, yt, cx + cuerpo / 2f, maxOf(yb, yt + dp(2f)))
            c.drawRoundRect(r, dp(1.5f), dp(1.5f), p)
        }
        c.restore()
    }
}
