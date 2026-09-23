package com.hunter.btc

import android.graphics.*

/**
 * Velocidad en el tiempo: una muestra cada diez segundos, la ultima hora.
 *
 * Tres cosas que la version anterior hacia mal y que se veian en cuanto habia
 * datos de verdad:
 *
 *  - Repartia los puntos sobre los 60 huecos aunque solo hubiera tres, asi que
 *    la linea salia amontonada en el borde izquierdo y el resto vacio.
 *  - El eje vertical arrancaba en cero. En una busqueda estable todas las
 *    muestras son parecidas, asi que la linea quedaba pegada al techo y la
 *    variacion —que es LO UNICO que se quiere mirar— no se apreciaba.
 *  - Las etiquetas de maximo y media se dibujaban las dos arriba a la
 *    izquierda, una encima de la otra.
 */
class SpeedChartView(context: android.content.Context) : android.view.View(context) {
    /** Una hora a una muestra cada diez segundos. */
    private val maxPoints = 360
    companion object { const val SEG_MUESTRA = 10 }
    private val wpsPoints = ArrayDeque<Float>()
    private val d = context.resources.displayMetrics.density

    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppTheme.ACCENT; strokeWidth = 2f * d; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AppTheme.ACCENT }
    private val paintLbl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppTheme.TXT_SEC; textSize = 9f * d; typeface = Typeface.MONOSPACE
    }
    private val paintMedia = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppTheme.TXT_SEC; strokeWidth = 1f * d; style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f * d, 4f * d), 0f)
    }
    /** El dato sin suavizar: fino y apagado, de fondo. */
    private val paintCrudo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (AppTheme.ACCENT and 0x00FFFFFF) or 0x40000000
        strokeWidth = 1f * d; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    fun addPoint(wps: Float) {
        wpsPoints.addLast(wps); if (wpsPoints.size > maxPoints) wpsPoints.removeFirst()
        postInvalidate()
    }
    fun reset() { wpsPoints.clear(); postInvalidate() }
    fun getPoints(): List<Float> = wpsPoints.toList()

    /** El promedio de lo que hay dibujado. 0 si no hay nada. */
    fun media(): Float = if (wpsPoints.isEmpty()) 0f else wpsPoints.sum() / wpsPoints.size

    private fun corto(v: Float) = when {
        v >= 1e9f -> "%.2fG".format(v / 1e9f)
        v >= 1e6f -> "%.1fM".format(v / 1e6f)
        v >= 1e3f -> "%.0fK".format(v / 1e3f)
        else      -> "%.0f".format(v)
    }

    /** Media móvil de [n] muestras. Suaviza sin inventar: cada punto es el
     *  promedio real de su entorno. */
    private fun suavizado(n: Int): List<Float> {
        val v = wpsPoints.toList()
        if (v.size < 2) return v
        return v.indices.map { i ->
            val a = maxOf(0, i - n / 2); val b = minOf(v.size - 1, i + n / 2)
            var s = 0f; for (j in a..b) s += v[j]
            s / (b - a + 1)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val topTxt = 11f * d
        val botTxt = 11f * d
        val pad    = 2f * d
        val y0 = topTxt; val y1 = h - botTxt

        if (wpsPoints.size < 2) {
            canvas.drawText("waiting for samples…", pad, h / 2, paintLbl)
            return
        }

        val mx = wpsPoints.max(); val mn = wpsPoints.min(); val med = media()
        val aire = ((mx - mn) * 0.08f).coerceAtLeast(mx * 0.02f).coerceAtLeast(1f)
        val lo = (mn - aire).coerceAtLeast(0f); val hi = mx + aire
        val rango = (hi - lo).coerceAtLeast(1f)
        fun yDe(v: Float) = y1 - (y1 - y0) * ((v - lo) / rango)
        fun xDe(i: Int) = pad + (w - pad * 2) * i / (wpsPoints.size - 1).coerceAtLeast(1)

        // El dato crudo, en fino y apagado. A diez segundos por muestra, en un
        // movil de 2 nucleos grandes y 6 pequenos, la linea salta de 14 a 4
        // millones segun donde ponga el planificador cada hilo: es real, pero
        // dibujada sola parece que algo va mal.
        val crudo = wpsPoints.toList()
        val pc = Path()
        crudo.forEachIndexed { i, v -> if (i == 0) pc.moveTo(xDe(i), yDe(v)) else pc.lineTo(xDe(i), yDe(v)) }
        canvas.drawPath(pc, paintCrudo)

        // Y encima la media movil de un minuto, que es la que se lee.
        // Antes habia ademas una banda gris entre el minimo y el maximo; con la
        // linea cruda de fondo sobra, porque la dispersion ya se ve, y las dos
        // cosas juntas emborronaban el dibujo.
        val suave = suavizado(6)
        val ps = Path()
        suave.forEachIndexed { i, v -> if (i == 0) ps.moveTo(xDe(i), yDe(v)) else ps.lineTo(xDe(i), yDe(v)) }
        // Relleno suave bajo la media, para que la linea tenga peso.
        val fill = Path(ps)
        fill.lineTo(xDe(suave.size - 1), y1); fill.lineTo(xDe(0), y1); fill.close()
        canvas.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, y0, 0f, y1, 0x3341D9A0, 0x0041D9A0, Shader.TileMode.CLAMP)
            style = Paint.Style.FILL
        })
        canvas.drawPath(ps, paintLine)
        canvas.drawCircle(xDe(crudo.size - 1), yDe(suave.last()), 3f * d, paintDot)

        // La media de todo, en trazos.
        canvas.drawLine(pad, yDe(med), w - pad, yDe(med), paintMedia)

        canvas.drawText("max ${corto(mx)}", pad, topTxt - 2f * d, paintLbl)
        val tMin = "min ${corto(mn)}"
        canvas.drawText(tMin, w - pad - paintLbl.measureText(tMin), topTxt - 2f * d, paintLbl)

        val min = wpsPoints.size * SEG_MUESTRA / 60
        canvas.drawText(
            if (min >= 1) "$min min ago" else "${wpsPoints.size * SEG_MUESTRA} s ago",
            pad, h - 2f * d, paintLbl)
        val tAhora = "now ${corto(suave.last())}"
        canvas.drawText(tAhora, w - pad - paintLbl.measureText(tAhora), h - 2f * d, paintLbl)
    }
}
