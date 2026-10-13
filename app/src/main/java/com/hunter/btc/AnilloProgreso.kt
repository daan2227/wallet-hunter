package com.hunter.btc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Anillo de progreso con el porcentaje en el centro (propuesta B). Un arco de
 * fondo tenue y, encima, el arco de acento que avanza de 0 a 1.
 */
class AnilloProgreso(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private var frac = 0f
    private val gr = 6f * d                       // grosor del anillo
    private val pBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = gr; strokeCap = Paint.Cap.ROUND
    }
    private val pFg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = gr; strokeCap = Paint.Cap.ROUND
    }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 15f * d; typeface = AppTheme.bold(ctx)
    }

    fun set(f: Float) { frac = f.coerceIn(0f, 1f); invalidate() }

    override fun onDraw(c: Canvas) {
        pBg.color = AppTheme.BG_ELEV
        pFg.color = AppTheme.ACCENT
        pTxt.color = AppTheme.TXT_PRI
        val w = width.toFloat(); val h = height.toFloat(); val pad = gr
        val r = RectF(pad, pad, w - pad, h - pad)
        c.drawArc(r, 0f, 360f, false, pBg)
        if (frac > 0f) c.drawArc(r, -90f, 360f * frac, false, pFg)
        val cy = h / 2f - (pTxt.ascent() + pTxt.descent()) / 2f
        c.drawText("${(frac * 100).toInt()}%", w / 2f, cy, pTxt)
    }
}
