package com.hunter.btc

import android.graphics.Canvas
import android.graphics.Paint

/**
 * Indicador de actividad INDETERMINADO: un par de segmentos que barren de
 * izquierda a derecha sin parar. Dice "esto está trabajando", no "va por el
 * N %". Para el Kangaroo del puzzle, que no tiene porcentaje honesto: recorre
 * un espacio astronómico guiándose por probabilidad, así que una barra que se
 * llenara mentiría. Ésta sólo se mueve.
 *
 * Se anima sólo cuando hace falta: [arrancar] la pone en marcha y la muestra,
 * [parar] la detiene y la oculta, y al salir de pantalla se cancela sola para
 * no gastar batería dibujando lo que nadie ve.
 */
class BarraActividad(ctx: android.content.Context) : android.view.View(ctx) {
    private val pTrack = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pSeg = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fase = 0f
    private var anim: android.animation.ValueAnimator? = null

    fun arrancar() {
        visibility = VISIBLE
        if (anim != null) return
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { fase = it.animatedFraction; postInvalidateOnAnimation() }
            start()
        }
    }

    fun parar() {
        anim?.cancel(); anim = null
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        anim?.cancel(); anim = null
    }

    private fun seg(c: Canvas, w: Float, h: Float, r: Float, t: Float) {
        val segW = w * 0.28f
        val travel = w + segW
        val x = -segW + travel * t
        c.drawRoundRect(x, 0f, x + segW, h, r, r, pSeg)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f
        pTrack.color = AppTheme.BG_ELEV
        c.drawRoundRect(0f, 0f, w, h, r, r, pTrack)
        // Mismo degradado BLUE→ACCENT que el resto de barras; los segmentos
        // cambian de tono según por dónde cruzan, lo que da sensación de vida.
        pSeg.shader = Barras.degradado(w)
        // Dos segmentos a media fase: casi siempre hay uno a la vista, así que
        // el barrido se lee continuo y no "a tirones".
        seg(c, w, h, r, fase)
        seg(c, w, h, r, (fase + 0.5f) % 1f)
    }
}
