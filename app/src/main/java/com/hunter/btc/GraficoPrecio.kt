package com.hunter.btc

import android.content.Context
import android.graphics.*
import android.view.View

/**
 * Gráfico de precio nativo: línea (una serie de valores) o velas (OHLC), auto-
 * escalado a su min..max — el precio de BTC se mueve poco en %, así que arrancar
 * en cero aplastaría la curva. Sin dependencias ni WebView: dibuja sobre Canvas.
 */
class GraficoPrecio(ctx: Context) : View(ctx) {
    data class Vela(val t: Long, val o: Float, val h: Float, val l: Float, val c: Float)

    private var datos: List<Float> = emptyList()
    private var velas: List<Vela> = emptyList()
    private var modoVelas = false
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
    private val mecha = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f * d; style = Paint.Style.STROKE }
    private val cuerpo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun poner(serie: List<Float>) { datos = serie; modoVelas = false; postInvalidate() }
    fun ponerVelas(v: List<Vela>) { velas = v; modoVelas = true; postInvalidate() }

    private fun corto(v: Float) = when {
        v >= 1_000f -> "%,.0f".format(v)
        else        -> "%.2f".format(v)
    }

    private val padT get() = 12f * d
    private val padB get() = 14f * d
    private val padL get() = 2f * d
    private val padR get() = 2f * d

    override fun onDraw(c: Canvas) {
        if (modoVelas) dibujarVelas(c) else dibujarLinea(c)
    }

    private fun dibujarLinea(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val v = datos.filter { it.isFinite() && it > 0f }
        if (v.size < 2) return
        var min = v.min(); var max = v.max(); if (max <= min) max = min + 1f
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
        c.drawText(corto(max), padL, padT - 2f * d, lbl)
        c.drawText(corto(min), padL, h - 3f * d, lbl)
    }

    private fun dibujarVelas(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val v = velas.filter { it.h.isFinite() && it.l > 0f }
        if (v.isEmpty()) return
        var min = v.minOf { it.l }; var max = v.maxOf { it.h }; if (max <= min) max = min + 1f
        val gw = w - padL - padR; val gh = h - padT - padB
        fun cx(i: Int) = padL + gw * (i + 0.5f) / v.size
        fun y(p: Float) = padT + gh * (1f - (p - min) / (max - min))
        val medio = (gw / v.size * 0.34f).coerceIn(0.6f * d, 6f * d)

        for (i in v.indices) {
            val k = v[i]
            val sube = k.c >= k.o
            val col = if (sube) AppTheme.OK else AppTheme.RED
            mecha.color = col; cuerpo.color = col
            val x = cx(i)
            c.drawLine(x, y(k.h), x, y(k.l), mecha)
            var top = y(maxOf(k.o, k.c)); var bot = y(minOf(k.o, k.c))
            if (bot - top < 1f * d) { top -= 0.5f * d; bot += 0.5f * d }   // doji: que se vea
            c.drawRect(x - medio, top, x + medio, bot, cuerpo)
        }
        c.drawText(corto(max), padL, padT - 2f * d, lbl)
        c.drawText(corto(min), padL, h - 3f * d, lbl)
    }
}
