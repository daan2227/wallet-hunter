package com.hunter.btc

import android.content.Context
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * "Ocultar saldos", el ojo de cualquier cartera.
 *
 * Un solo ajuste para toda la app, guardado: si se tapan en la cartera, siguen
 * tapados al volver a abrirla. Antes había un toque escondido sobre la cifra
 * que no se veía por ningún lado y se olvidaba al salir de la pantalla.
 *
 * NO se aplica en el baúl de hallazgos, a propósito: ahí se entra con PIN
 * cada vez y la pantalla no admite capturas, así que ya está protegido dos
 * veces. Tapar también ahí sólo obligaría a destaparlo cada vez.
 */
object Privacidad {

    /** Lo que se enseña en lugar de una cantidad. */
    const val TAPADO = "••••••"

    private const val PREFS = "app_settings"
    private const val CLAVE = "ocultar_saldos"

    fun ocultos(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLAVE, false)

    private fun poner(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CLAVE, v).apply()
    }

    /** La cantidad, o [TAPADO] si los saldos están ocultos. */
    fun monto(ctx: Context, texto: String): String = if (ocultos(ctx)) TAPADO else texto

    /**
     * El botón del ojo: abierto si se ven, tachado si están tapados. Cambia
     * el ajuste al tocarlo y avisa con el valor nuevo (true = ocultos).
     */
    fun ojo(ctx: Context, tint: Int = AppTheme.TXT_SEC, onCambio: (Boolean) -> Unit): ImageView {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        return ImageView(ctx).apply {
            fun pintar(oc: Boolean) {
                setImageResource(if (oc) R.drawable.ic_eye_off else R.drawable.ic_eye)
                contentDescription = if (oc) "Show balances" else "Hide balances"
            }
            setColorFilter(tint)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            isClickable = true; isFocusable = true
            foreground = Ui.toque()
            pintar(ocultos(ctx))
            setOnClickListener {
                val nuevo = !ocultos(ctx)
                poner(ctx, nuevo)
                pintar(nuevo)
                onCambio(nuevo)
            }
        }
    }
}
