package com.hunter.btc

import android.content.Context
import android.graphics.*
import android.view.View

/**
 * Gráfico de línea nativo de una serie de precios, auto-escalado a su min..max
 * (el precio de BTC se mueve poco en %, así que arrancar en cero aplastaría la
 * curva). Relleno degradado bajo la línea y un punto en el último valor. Sin
 * dependencias ni WebView: dibuja sobre un Canvas, como el resto de la app.
 */
class GraficoPrecio(ctx: Context) : View(ctx) {
    private var datos: List<Float> = emptyList()
    private val d = resources.displayMetrics.density

    private val linea = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppTheme.ACCENT; strokeWidth = 2f * d; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val punto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AppTheme.ACCENT }
    private val lbl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppTheme.TXT_MUTED; textSize = 9f * d; typeface = AppTheme.mono(ctx)
    }

    fun poner(serie: List<Float>) { datos = serie; postInvalidate() }

    private fun corto(v: Float) = when {
        v >= 1_000f -> "%,.0f".format(v)
        else        -> "%.2f".format(v)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val v = datos.filter { it.isFinite() && it > 0f }
        if (v.size < 2) return
        val padT = 12f * d; val padB = 14f * d; val padL = 2f * d; val padR = 2f * d
        var min = v.min(); var max = v.max()
        if (max <= min) max = min + 1f
        val gw = w - padL - padR; val gh = h - padT - padB
        fun x(i: Int) = padL + gw * i / (v.size - 1)
        fun y(p: Float) = padT + gh * (1f - (p - min) / (max - min))

        val path = Path(); val fill = Path()
        path.moveTo(x(0), y(v[0]))
        fill.moveTo(x(0), h - padB); fill.lineTo(x(0), y(v[0]))
        for (i in 1 until v.size) { path.lineTo(x(i), y(v[i])); fill.lineTo(x(i), y(v[i])) }
        fill.lineTo(x(v.size - 1), h - padB); fill.close()

        relleno.shader = LinearGradient(0f, padT, 0f, h - padB,
            (AppTheme.ACCENT and 0x00FFFFFF) or 0x40000000,
            (AppTheme.ACCENT and 0x00FFFFFF) or 0x00000000, Shader.TileMode.CLAMP)
        c.drawPath(fill, relleno)
        c.drawPath(path, linea)
        c.drawCircle(x(v.size - 1), y(v[v.size - 1]), 3.5f * d, punto)

        // Ejes de valor: máximo arriba, mínimo abajo (en la esquina izquierda).
        c.drawText(corto(max), padL, padT - 2f * d, lbl)
        c.drawText(corto(min), padL, h - 3f * d, lbl)
    }
}
