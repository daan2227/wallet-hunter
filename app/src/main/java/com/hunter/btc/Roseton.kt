package com.hunter.btc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * El rosetón de guilloche que va detrás de la cifra principal (dirección "Security print").
 *
 * El guilloche es el trazo fino de los billetes y los títulos: se imprime para que una cifra no se
 * pueda falsificar. Aquí dice lo mismo: la cifra de delante está medida en este móvil.
 *
 * Son tres hipotrocoides (la curva de un espirógrafo) en el oro del tema y uno en el violeta, con
 * trazo de 0,6 dp y poca opacidad: se ve como textura, no como dibujo.
 *
 * Mientras [activo] dice que hay un trabajo en marcha (escaneo, minado), el rosetón gira despacio:
 * de un vistazo se sabe si la cosa corre sin leer nada. Parado, se queda quieto, y no consume nada
 * más que una comprobación cada medio segundo para saber si ha vuelto a arrancar. Con las
 * animaciones del sistema quitadas (accesibilidad) nunca gira.
 *
 * Es decorativo: TalkBack lo ignora; el estado lo dice el texto de la pantalla.
 */
class Roseton(ctx: Context, private val activo: () -> Boolean = { false }) : View(ctx) {

    // "grande" y "chico", no R y r: en la JVM los dos darían getR() y no compila.
    private data class Capa(val grande: Int, val chico: Int, val d: Int, val escala: Float, val color: Int, val alfa: Int)

    private val capas: List<Capa>
        get() = listOf(
            Capa(96, 36, 62, 1.00f, AppTheme.ACCENT, if (AppTheme.isDark) 0x38 else 0x30),
            Capa(100, 28, 70, 0.88f, AppTheme.ACCENT, if (AppTheme.isDark) 0x24 else 0x20),
            Capa(90, 42, 48, 1.20f, AppTheme.ACCENT2, if (AppTheme.isDark) 0x1A else 0x18)
        )

    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 0.6f
    }
    private var trazos: List<Pair<Path, Capa>> = emptyList()
    private var anguloGrados = 0f
    private var ultimoFrame = 0L

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun mcd(a: Int, b: Int): Int = if (b == 0) a else mcd(b, a % b)

    /** Una hipotrocoide cerrada, en un radio unidad de 170: se escala al tamaño de la vista. */
    private fun curva(c: Capa, radio: Float): Path {
        val p = Path()
        val R = c.grande; val r = c.chico
        val vueltas = r / mcd(R, r)
        val pasos = 120 * vueltas
        val k = radio / 170f * c.escala
        for (i in 0..pasos) {
            val t = i.toDouble() / pasos * Math.PI * 2 * vueltas
            val x = ((R - r) * Math.cos(t) + c.d * Math.cos((R - r).toDouble() / r * t)) * k
            val y = ((R - r) * Math.sin(t) - c.d * Math.sin((R - r).toDouble() / r * t)) * k
            if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
        }
        return p
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val radio = minOf(w, h) / 2f
        trazos = capas.map { curva(it, radio) to it }
    }

    private val sondeo = Runnable { invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (trazos.isEmpty()) return
        val corre = Ui.animar() && try { activo() } catch (e: Throwable) { false }
        val ahora = System.nanoTime()
        if (corre && ultimoFrame != 0L) {
            // Una vuelta cada 90 s: se nota que se mueve, no distrae.
            anguloGrados = (anguloGrados + (ahora - ultimoFrame) / 1e9f * 4f) % 360f
        }
        ultimoFrame = if (corre) ahora else 0L

        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        trazos.forEachIndexed { i, (path, c) ->
            pincel.color = (c.color and 0x00FFFFFF) or (c.alfa shl 24)
            canvas.save()
            // Las capas giran en sentidos opuestos: el moiré es lo que hace que se vea vivo.
            canvas.rotate(if (i % 2 == 0) anguloGrados else -anguloGrados * 0.6f)
            canvas.drawPath(path, pincel)
            canvas.restore()
        }
        canvas.restore()

        if (!isAttachedToWindow || !isShown) return
        if (corre) postInvalidateOnAnimation()
        else { removeCallbacks(sondeo); postDelayed(sondeo, 500) }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(sondeo)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }
}
