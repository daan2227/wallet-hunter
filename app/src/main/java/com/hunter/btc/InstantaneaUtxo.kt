package com.hunter.btc

/**
 * Formato del CSV de la instantánea de UTXO (ver [ExportadorUtxo]): seis columnas, sin claves privadas.
 * Sin Android, para probarlo en la JVM.
 */
object InstantaneaUtxo {

    val CABECERA = listOf("wallet", "utxo", "funds_btc", "wallet_type", "public_key", "tx_date")

    /** Satoshis como BTC con ocho decimales, sin pasar por coma flotante. */
    fun btc(sat: Long): String {
        val e = sat / 100_000_000L; val d = sat % 100_000_000L
        return e.toString() + "." + d.toString().padStart(8, '0')
    }

    /** Una celda de CSV: entre comillas si hace falta, y sin que una hoja de cálculo la tome por fórmula. */
    fun celda(s: String): String {
        var t = s.replace("\r", " ").replace("\n", " ")
        if (t.isNotEmpty() && t[0] in "=+-@\t") t = "'$t"
        return if (t.any { it == ',' || it == '"' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
    }
}
