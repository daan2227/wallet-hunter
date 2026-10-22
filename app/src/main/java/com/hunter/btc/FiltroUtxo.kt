package com.hunter.btc

import org.json.JSONArray
import org.json.JSONObject

/**
 * Qué monedas entran en el CSV de UTXO (ver [ExportadorUtxo]). Hay un filtro posible por cada uno de los 14 campos
 * ([InstantaneaUtxo.Col]); lo que está en su valor neutro no filtra. Todas las condiciones se cumplen a la vez.
 *
 * Los de texto no distinguen mayúsculas. Sin Android, para probarlo en la JVM.
 */
data class FiltroUtxo(
    // funds_btc / funds_sat
    val minSat: Long = 0L,
    val maxSat: Long = Long.MAX_VALUE,
    // wallet_type: vacío = todos
    val tipos: Set<String> = emptySet(),
    // public_key: 0 da igual, 1 sólo con clave, 2 sólo sin clave
    val clave: Int = 0,
    val prefijoClave: String = "",
    // wallet: la dirección contiene este texto
    val direccionContiene: String = "",
    // utxo / txid: el txid empieza por esto
    val txidEmpieza: String = "",
    // vout
    val voutMin: Long = 0L,
    val voutMax: Long = Long.MAX_VALUE,
    // key_hash
    val hashEmpieza: String = "",
    // script_hex
    val scriptContiene: String = "",
    // height
    val alturaMin: Long = 0L,
    val alturaMax: Long = Long.MAX_VALUE,
    // tx_date (yyyy-MM-dd, inclusivo)
    val fechaDesde: String = "",
    val fechaHasta: String = "",
    // coinbase: 0 todas, 1 sólo minadas, 2 sólo no minadas
    val coinbase: Int = 0,
    // confirmations
    val confMin: Long = 0L,
    val confMax: Long = Long.MAX_VALUE
) {
    companion object {
        val TIPOS = listOf("P2PKH", "P2SH", "P2PK", "P2WPKH", "P2WSH", "P2TR", "Other", "Non-standard")
        private val FECHA = Regex("""\d{4}-\d{2}-\d{2}""")
        private val HEX = Regex("""[0-9a-fA-F]*""")

        /** "0.001" -> 100000 sat. null si no es un importe con hasta 8 decimales. */
        fun btcASat(s: String): Long? {
            val t = s.trim().replace(',', '.')
            if (t.isEmpty() || !Regex("""\d+(\.\d{0,8})?""").matches(t)) return null
            return try { java.math.BigDecimal(t).movePointRight(8).longValueExact() } catch (e: Exception) { null }
        }

        fun deJson(s: String?): FiltroUtxo {
            if (s.isNullOrBlank()) return FiltroUtxo()
            return try {
                val o = JSONObject(s)
                val tp = o.optJSONArray("tipos")
                FiltroUtxo(
                    minSat = o.optLong("minSat", 0L), maxSat = o.optLong("maxSat", Long.MAX_VALUE),
                    tipos = (0 until (tp?.length() ?: 0)).map { tp!!.getString(it) }.filter { it in TIPOS }.toSet(),
                    clave = o.optInt("clave", 0), prefijoClave = o.optString("prefijoClave", ""),
                    direccionContiene = o.optString("direccionContiene", ""), txidEmpieza = o.optString("txidEmpieza", ""),
                    voutMin = o.optLong("voutMin", 0L), voutMax = o.optLong("voutMax", Long.MAX_VALUE),
                    hashEmpieza = o.optString("hashEmpieza", ""), scriptContiene = o.optString("scriptContiene", ""),
                    alturaMin = o.optLong("alturaMin", 0L), alturaMax = o.optLong("alturaMax", Long.MAX_VALUE),
                    fechaDesde = o.optString("fechaDesde", ""), fechaHasta = o.optString("fechaHasta", ""),
                    coinbase = o.optInt("coinbase", 0),
                    confMin = o.optLong("confMin", 0L), confMax = o.optLong("confMax", Long.MAX_VALUE)
                ).normalizado()
            } catch (e: Exception) { FiltroUtxo() }
        }
    }

    /** Minúsculas, valores en rango y lo inválido (fechas mal escritas, hex con letras raras) vuelto neutro. */
    fun normalizado(): FiltroUtxo = copy(
        clave = clave.coerceIn(0, 2), coinbase = coinbase.coerceIn(0, 2),
        prefijoClave = prefijoClave.trim().lowercase().takeIf { HEX.matches(it) } ?: "",
        hashEmpieza = hashEmpieza.trim().lowercase().takeIf { HEX.matches(it) } ?: "",
        txidEmpieza = txidEmpieza.trim().lowercase().takeIf { HEX.matches(it) } ?: "",
        scriptContiene = scriptContiene.trim().lowercase().takeIf { HEX.matches(it) } ?: "",
        direccionContiene = direccionContiene.trim().lowercase(),
        fechaDesde = fechaDesde.trim().takeIf { FECHA.matches(it) } ?: "",
        fechaHasta = fechaHasta.trim().takeIf { FECHA.matches(it) } ?: "",
        tipos = tipos.filter { it in TIPOS }.toSet())

    fun aJson(): String = JSONObject()
        .put("minSat", minSat).put("maxSat", maxSat).put("tipos", JSONArray(tipos.toList()))
        .put("clave", clave).put("prefijoClave", prefijoClave).put("direccionContiene", direccionContiene)
        .put("txidEmpieza", txidEmpieza).put("voutMin", voutMin).put("voutMax", voutMax)
        .put("hashEmpieza", hashEmpieza).put("scriptContiene", scriptContiene)
        .put("alturaMin", alturaMin).put("alturaMax", alturaMax)
        .put("fechaDesde", fechaDesde).put("fechaHasta", fechaHasta).put("coinbase", coinbase)
        .put("confMin", confMin).put("confMax", confMax).toString()

    /** ¿Hace falta mirar el script (tipo, dirección, clave, hash…) para decidir? Si no, se ahorra el trabajo. */
    val pideScript: Boolean get() =
        tipos.isNotEmpty() || clave != 0 || prefijoClave.isNotEmpty() || direccionContiene.isNotEmpty() ||
        hashEmpieza.isNotEmpty() || scriptContiene.isNotEmpty()

    val pideFecha: Boolean get() = fechaDesde.isNotEmpty() || fechaHasta.isNotEmpty()
    val pideTxid: Boolean get() = txidEmpieza.isNotEmpty()

    /** Las condiciones que sólo necesitan los números de la moneda (baratas: se miran las primeras). */
    fun numerico(sat: Long, altura: Long, coinbaseSi: Boolean, vout: Long, confirmaciones: Long): Boolean =
        sat in minSat..maxSat && altura in alturaMin..alturaMax && vout in voutMin..voutMax &&
        confirmaciones in confMin..confMax &&
        (coinbase == 0 || (coinbase == 1) == coinbaseSi)

    /** Las que necesitan el script ya interpretado. [fecha] es "yyyy-MM-dd" (vacía si no se pidió). */
    fun textual(tipo: String, addr: String, pub: String, hash: String, script: String, txid: String, fecha: String): Boolean {
        if (tipos.isNotEmpty() && tipo !in tipos) return false
        if (clave == 1 && pub.isEmpty()) return false
        if (clave == 2 && pub.isNotEmpty()) return false
        if (prefijoClave.isNotEmpty() && !pub.startsWith(prefijoClave)) return false
        if (direccionContiene.isNotEmpty() && !addr.lowercase().contains(direccionContiene)) return false
        if (txidEmpieza.isNotEmpty() && !txid.startsWith(txidEmpieza)) return false
        if (hashEmpieza.isNotEmpty() && !hash.startsWith(hashEmpieza)) return false
        if (scriptContiene.isNotEmpty() && !script.contains(scriptContiene)) return false
        if (fechaDesde.isNotEmpty() && fecha < fechaDesde) return false
        if (fechaHasta.isNotEmpty() && fecha > fechaHasta) return false
        return true
    }

    /** Una línea por condición activa, para enseñar qué filtra. */
    fun resumen(): String {
        val l = ArrayList<String>()
        fun btc(s: Long) = InstantaneaUtxo.btc(s).trimEnd('0').trimEnd('.')
        if (minSat > 0 && maxSat != Long.MAX_VALUE) l.add("funds ${btc(minSat)}–${btc(maxSat)} BTC")
        else if (minSat > 0) l.add("funds ≥ ${btc(minSat)} BTC") else if (maxSat != Long.MAX_VALUE) l.add("funds ≤ ${btc(maxSat)} BTC")
        if (tipos.isNotEmpty()) l.add("type: " + TIPOS.filter { it in tipos }.joinToString("/"))
        if (clave == 1) l.add("with public key") else if (clave == 2) l.add("without public key")
        if (prefijoClave.isNotEmpty()) l.add("key starts $prefijoClave")
        if (direccionContiene.isNotEmpty()) l.add("address has “$direccionContiene”")
        if (txidEmpieza.isNotEmpty()) l.add("txid starts $txidEmpieza")
        if (voutMin > 0 || voutMax != Long.MAX_VALUE) l.add("vout $voutMin–${if (voutMax == Long.MAX_VALUE) "…" else voutMax.toString()}")
        if (hashEmpieza.isNotEmpty()) l.add("hash starts $hashEmpieza")
        if (scriptContiene.isNotEmpty()) l.add("script has $scriptContiene")
        if (alturaMin > 0 || alturaMax != Long.MAX_VALUE) l.add("height $alturaMin–${if (alturaMax == Long.MAX_VALUE) "…" else alturaMax.toString()}")
        if (fechaDesde.isNotEmpty() || fechaHasta.isNotEmpty()) l.add("date ${fechaDesde.ifEmpty { "…" }} → ${fechaHasta.ifEmpty { "…" }}")
        if (coinbase == 1) l.add("only mined coins") else if (coinbase == 2) l.add("no mined coins")
        if (confMin > 0 || confMax != Long.MAX_VALUE) l.add("confirmations $confMin–${if (confMax == Long.MAX_VALUE) "…" else confMax.toString()}")
        return if (l.isEmpty()) "no filter (every coin)" else l.joinToString(" · ")
    }
}
