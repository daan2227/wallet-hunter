package com.hunter.btc

import android.content.Context
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A partir de qué importe un envío vuelve a pedir el PIN.
 *
 * La sesión ya está abierta con el PIN, así que alguien con el teléfono
 * desbloqueado en la mano podría vaciar la cartera con tres toques. Por encima
 * del umbral se pide el PIN otra vez justo antes de firmar. Por debajo, basta
 * la pantalla de revisión que ya existe.
 *
 * Valores guardados (en satoshis): -1 = nunca, 0 = siempre, n > 0 = desde n sat.
 * La lógica es pura (sin Android) y se prueba en la JVM; sólo leer y guardar
 * tocan las preferencias.
 */
object UmbralEnvio {
    private const val PREFS = "ui_seguridad"
    private const val CLAVE = "pin_envio_sat"

    /** 0,01 BTC: un envío pequeño no estorba, uno grande sí pide confirmar. */
    const val POR_DEFECTO_SAT = 1_000_000L
    const val NUNCA = -1L

    /** ¿Hay que pedir el PIN para enviar [enviarSat] con ese umbral? */
    fun exige(umbralSat: Long, enviarSat: Long): Boolean = when {
        umbralSat < 0 -> false
        umbralSat == 0L -> true
        else -> enviarSat >= umbralSat
    }

    /** De texto en BTC a sat: "" = nunca, "0" = siempre. null si no es un número válido. */
    fun parsear(texto: String): Long? {
        val t = texto.trim().replace(',', '.')
        if (t.isEmpty()) return NUNCA
        val btc = try { BigDecimal(t) } catch (e: NumberFormatException) { return null }
        if (btc.signum() < 0 || btc > BigDecimal(21_000_000)) return null
        return btc.movePointRight(8).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    /** Inverso de [parsear], para enseñarlo en el campo. */
    fun aTexto(sat: Long): String = when {
        sat < 0 -> ""
        else -> BigDecimal(sat).movePointLeft(8).stripTrailingZeros().toPlainString()
    }

    fun leer(ctx: Context): Long = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(CLAVE, POR_DEFECTO_SAT)
    } catch (e: Throwable) { POR_DEFECTO_SAT }

    fun guardar(ctx: Context, sat: Long) {
        try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(CLAVE, sat).apply() }
        catch (e: Throwable) { }
    }
}
