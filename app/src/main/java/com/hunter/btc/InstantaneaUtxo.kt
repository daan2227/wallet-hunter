package com.hunter.btc

/**
 * Formato del CSV de la instantánea de UTXO (ver [ExportadorUtxo]): seis columnas, sin claves privadas.
 * Sin Android, para probarlo en la JVM.
 */
object InstantaneaUtxo {

    /**
     * Lo que el conjunto de UTXO sabe de cada moneda. El orden de este enum es el de las columnas del CSV.
     * No está aquí lo que el conjunto no guarda: de qué transacción vienen las entradas, el historial de una
     * dirección o la clave pública de un P2PKH/P2WPKH (sólo hay su hash hasta que se gastan).
     */
    enum class Col(val cabecera: String, val texto: String) {
        WALLET("wallet", "Wallet (the address)"),
        UTXO("utxo", "UTXO (txid:vout)"),
        TXID("txid", "Transaction id"),
        VOUT("vout", "Output number"),
        FONDO_BTC("funds_btc", "Funds (BTC)"),
        FONDO_SAT("funds_sat", "Funds (satoshis)"),
        TIPO("wallet_type", "Wallet type"),
        CLAVE("public_key", "Public key (P2PK and Taproot only)"),
        HASH("key_hash", "Key or script hash"),
        SCRIPT("script_hex", "Locking script (hex)"),
        ALTURA("height", "Block height"),
        FECHA("tx_date", "Date of the transaction"),
        COINBASE("coinbase", "Mined coin (coinbase)"),
        CONFIRMACIONES("confirmations", "Confirmations")
    }

    /** Las seis de siempre. */
    val PREDETERMINADAS: Set<Col> = linkedSetOf(Col.WALLET, Col.UTXO, Col.FONDO_BTC, Col.TIPO, Col.CLAVE, Col.FECHA)

    /** Guardar/leer la elección como texto (ids separados por coma). Lo desconocido se ignora. */
    fun columnasATexto(c: Set<Col>): String = c.joinToString(",") { it.name }
    fun columnasDeTexto(s: String?): Set<Col> {
        val l = (s ?: "").split(',').mapNotNull { n -> Col.values().firstOrNull { it.name == n.trim() } }
        return if (l.isEmpty()) PREDETERMINADAS else l.toCollection(LinkedHashSet())
    }

    fun cabecera(c: Set<Col>): String = Col.values().filter { it in c }.joinToString(",") { it.cabecera }

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
