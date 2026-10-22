package com.hunter.btc

/**
 * La «instantánea de UTXO» de una cartera: un CSV con una fila por moneda sin gastar y sólo seis columnas:
 * cartera, utxo (txid:vout), fondo (BTC), tipo de cartera, clave pública y fecha de la transacción.
 *
 * No lleva claves privadas ni direcciones. Sin Android, para probarlo en la JVM.
 */
object InstantaneaUtxo {

    data class Fila(
        val cartera: String,
        val utxo: String,
        val sat: Long,
        val tipo: String,
        val clavePublica: String,
        val fecha: String
    )

    val CABECERA = listOf("wallet", "utxo", "funds_btc", "wallet_type", "public_key", "tx_date")

    /** Tipo de cartera según el formato de la dirección. */
    fun tipoDeDireccion(addr: String): String {
        val a = addr.trim()
        val l = a.lowercase()
        return when {
            l.startsWith("bc1p") || l.startsWith("tb1p") || l.startsWith("bcrt1p") -> "P2TR (Taproot)"
            l.startsWith("bc1q") || l.startsWith("tb1q") || l.startsWith("bcrt1q") ->
                if (l.length > 50) "P2WSH (SegWit script)" else "P2WPKH (Native SegWit)"
            a.startsWith("3") || a.startsWith("2") -> "P2SH (SegWit wrapped)"
            a.startsWith("1") || a.startsWith("m") || a.startsWith("n") -> "P2PKH (Legacy)"
            else -> "Unknown"
        }
    }

    /**
     * La fecha de la transacción que creó la moneda, en UTC. Sin marca de tiempo se dice la altura del
     * bloque; sin confirmar, «unconfirmed».
     */
    fun fecha(confirmada: Boolean, marcaSeg: Long, altura: Long): String = when {
        !confirmada -> "unconfirmed"
        marcaSeg > 0 -> java.text.SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(marcaSeg * 1000))
        altura > 0 -> "block $altura"
        else -> ""
    }

    /** En un script P2TR (OP_1 <32 bytes>), la clave de salida x-only: es la clave pública que va en la cadena. */
    fun claveTaproot(script: ByteArray): String? =
        if (script.size == 34 && script[0].toInt() == 0x51 && script[1].toInt() == 0x20)
            script.copyOfRange(2, 34).joinToString("") { "%02x".format(it) } else null

    /** Una celda de CSV: entre comillas si hace falta, y sin que una hoja de cálculo la tome por fórmula. */
    fun celda(s: String): String {
        var t = s.replace("\r", " ").replace("\n", " ")
        if (t.isNotEmpty() && t[0] in "=+-@\t") t = "'$t"
        return if (t.any { it == ',' || it == '"' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
    }

    fun csv(filas: List<Fila>): String = buildString {
        appendLine(CABECERA.joinToString(","))
        for (f in filas) appendLine(listOf(celda(f.cartera), celda(f.utxo), "%d.%08d".format(f.sat / 100_000_000L, f.sat % 100_000_000L),
            celda(f.tipo), celda(f.clavePublica), celda(f.fecha)).joinToString(","))
    }
}
