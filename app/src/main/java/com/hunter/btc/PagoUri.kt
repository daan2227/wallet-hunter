package com.hunter.btc

/**
 * Lo que se pega o se escanea en el campo de destino: una dirección suelta
 * o una petición de pago BIP21, "bitcoin:<dirección>?amount=0.001&label=…".
 *
 * Los QR de cobro de casi todas las carteras son BIP21. Pegar eso tal cual
 * en el campo dejaba "bitcoin:bc1…?amount=…" como dirección, que no valida.
 */
object PagoUri {

    data class Pago(val direccion: String, val importeBtc: String?)

    fun leer(texto: String): Pago {
        var t = texto.trim()
        if (t.startsWith("bitcoin:", ignoreCase = true)) t = t.substring(8)
        val dir = t.substringBefore('?').trim()
        val params = if ('?' in t) t.substringAfter('?') else ""
        val importe = params.split('&')
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .firstOrNull { it.first.equals("amount", ignoreCase = true) }
            ?.second?.takeIf { it.toDoubleOrNull()?.let { v -> v > 0 } == true }
        // Los QR suelen llevar bech32 en MAYÚSCULAS (ocupan menos). La
        // dirección es la misma, pero el validador la quiere en minúsculas.
        val limpia = if (dir.startsWith("BC1") || dir.startsWith("TB1")) dir.lowercase() else dir
        return Pago(limpia, importe)
    }
}
