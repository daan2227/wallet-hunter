package com.hunter.btc

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.WindowManager

/**
 * FLAG_SECURE según un ajuste del usuario (More › Privacy).
 *
 * Con la marca puesta, Android no deja capturar la pantalla ni la enseña en la
 * vista de apps recientes: el contenido sale en negro. Para una cartera es lo
 * prudente por defecto: una captura de una clave o de las 12 palabras acaba en
 * la galería y de ahí en la nube.
 *
 * Va con interruptor porque también tapa las capturas de las estadísticas del
 * escáner, que alguien puede querer compartir. Las pantallas que enseñan una
 * semilla o un WIF (el diálogo "Show seed / WIF" y el baúl) llevan su propia
 * marca fija y NO dependen de este ajuste.
 */
object PantallaSegura {
    private const val PREFS = "ui_seguridad"
    private const val CLAVE = "bloquear_capturas"

    fun activa(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLAVE, true)
    } catch (e: Throwable) { true }

    fun poner(ctx: Context, valor: Boolean) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CLAVE, valor).apply()
        } catch (e: Throwable) { }
    }

    /** Pone o quita la marca en la ventana de la actividad, según el ajuste. */
    fun aplicar(a: Activity) {
        if (activa(a)) a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Un diálogo es otra ventana: la marca de la actividad no lo cubre. */
    fun aplicarDialogo(d: Dialog?, ctx: Context) {
        if (activa(ctx)) d?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
