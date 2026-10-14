package com.hunter.btc

/**
 * Lo que se pega o se escanea en el campo de destino: una dirección suelta
 * o una petición de pago BIP21, "bitcoin:<dirección>?amount=0.001&label=…".
 *
 * Los QR de cobro de casi todas las carteras son BIP21. Pegar eso tal cual
 * en el campo dejaba "bitcoin:bc1…?amount=…" como dirección, que no valida.
 *
 * Pura: se prueba en la JVM (PagoUriTest).
 */
object PagoUri {

    /**
     * @param importeBtc el importe pedido, o null si no pide ninguno.
     * @param esPeticion true si era un "bitcoin:…": entonces un importe ausente
     *   significa "sin importe" y hay que vaciar el campo, no dejar el anterior.
     * @param error motivo por el que no se puede usar, o null.
     */
    data class Pago(val direccion: String, val importeBtc: String?,
                    val esPeticion: Boolean = false, val error: String? = null)

    /** Decimal de BTC: hasta 8 decimales, sin signo, sin exponente, sin Infinity/NaN. */
    private val IMPORTE = Regex("^[0-9]{1,8}(\\.[0-9]{1,8})?$")

    fun leer(texto: String): Pago {
        var t = texto.trim()
        val esPeticion = t.startsWith("bitcoin:", ignoreCase = true)
        if (esPeticion) t = t.substring(8)
        val dir = t.substringBefore('?').trim()
        val params = if ('?' in t) t.substringAfter('?') else ""
        val pares = params.split('&').filter { it.isNotEmpty() }
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
        // BIP21: un parámetro "req-…" es obligatorio; si no se entiende, la
        // petición no se puede cumplir y hay que rechazarla, no ignorarlo.
        pares.firstOrNull { it.first.startsWith("req-", ignoreCase = true) }?.let {
            return Pago("", null, esPeticion, "This payment request needs \"${it.first}\", which this wallet doesn't support.")
        }
        val bruto = pares.firstOrNull { it.first.equals("amount", ignoreCase = true) }?.second
        var importe: String? = null
        if (bruto != null) {
            // "Infinity", "1e9" o "-1" pasaban por toDouble() > 0, y un importe
            // infinito desbordaba las sumas del envío.
            val v = bruto.toBigDecimalOrNull()
            if (!IMPORTE.matches(bruto) || v == null || v.signum() <= 0 ||
                v > java.math.BigDecimal(21_000_000))
                return Pago(limpiar(dir), null, esPeticion, "The amount in this payment request is not valid.")
            importe = bruto
        }
        return Pago(limpiar(dir), importe, esPeticion)
    }

    /** Los QR suelen llevar bech32 en MAYÚSCULAS (ocupan menos): todo en mayúsculas pasa a minúsculas. */
    private fun limpiar(dir: String): String =
        if ((dir.startsWith("BC1") || dir.startsWith("TB1")) && dir == dir.uppercase()) dir.lowercase() else dir
}
