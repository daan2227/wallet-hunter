package com.hunter.btc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.TypedValue

/**
 * Cambia los símbolos de estado de un texto (✓ ✗ ⚠ 🔒 🔓 ❄) por los iconos vectoriales del juego de trazo
 * de la app, cada uno con su color semántico del tema. Los emojis los dibuja la fuente del sistema, así que
 * cada móvil enseñaba uno distinto y a todo color; un icono del juego se ve igual en todos y sigue al tema.
 *
 * El carácter se queda debajo del icono: TalkBack sigue leyendo "marca de verificación", "advertencia"…,
 * y copiar el texto da el mismo de antes.
 */
object IconosTexto {

    private class Marca(val res: Int, val color: () -> Int)

    private val MARCAS: List<Pair<String, Marca>> = listOf(
        "🔒" to Marca(R.drawable.ic_lock) { AppTheme.WARN },
        "🔓" to Marca(R.drawable.ic_unlock) { AppTheme.OK },
        "✓" to Marca(R.drawable.ic_check) { AppTheme.OK },
        "✗" to Marca(R.drawable.ic_x) { AppTheme.RED },
        "⚠" to Marca(R.drawable.ic_warning) { AppTheme.WARN },
        "❄" to Marca(R.drawable.ic_snow) { AppTheme.BLUE })

    /** Un icono centrado con la línea, del alto de la letra (los ImageSpan de serie se apoyan abajo). */
    private class IconoSpan(private val d: Drawable) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            d.bounds.right + (paint.textSize * 0.15f).toInt()

        override fun draw(c: Canvas, text: CharSequence?, start: Int, end: Int, x: Float,
                          top: Int, y: Int, bottom: Int, paint: Paint) {
            val fm = paint.fontMetricsInt
            val centro = y + (fm.ascent + fm.descent) / 2f
            c.save()
            c.translate(x, centro - d.bounds.bottom / 2f)
            d.draw(c)
            c.restore()
        }
    }

    /** [texto] con sus símbolos cambiados por iconos del tamaño de una letra de [sp] sp. */
    fun en(ctx: Context, texto: CharSequence, sp: Float = AppTheme.SP_BODY): CharSequence {
        if (MARCAS.none { (m, _) -> texto.contains(m) }) return texto
        val lado = (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, ctx.resources.displayMetrics) * 1.05f).toInt()
        val sb = SpannableStringBuilder(texto)
        for ((m, marca) in MARCAS) {
            var i = sb.indexOf(m)
            while (i >= 0) {
                val d = androidx.core.content.ContextCompat.getDrawable(ctx, marca.res)?.mutate()
                if (d != null) {
                    d.setTint(marca.color()); d.setBounds(0, 0, lado, lado)
                    sb.setSpan(IconoSpan(d), i, i + m.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                i = sb.indexOf(m, i + m.length)
            }
        }
        return sb
    }
}
