package com.hunter.btc

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * El formulario de filtros del CSV de UTXO: un filtro por cada campo. Lo que se deja vacío no filtra.
 * El importe mínimo no está aquí: va en el selector de la tarjeta.
 */
object FiltroUtxoDialogo {

    fun mostrar(act: Activity, actual: FiltroUtxo, alAceptar: (FiltroUtxo) -> Unit) {
        fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
        val caja = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        fun titulo(s: String) = caja.addView(TextView(act).apply {
            text = s; textSize = AppTheme.SP_BODY; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.bold(act)
            setPadding(0, dp(14), 0, dp(4))
        })
        fun campo(pista: String, valor: String, numerico: Boolean = false, mono: Boolean = true): EditText =
            Ui.input(act, pista, mono).apply {
                setText(valor); setSingleLine()
                inputType = if (numerico) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(4)
            }.also { caja.addView(it) }
        fun par(a: EditText, b: EditText) { /* ya añadidos uno tras otro */ }
        fun txt(n: Long, neutro: Long) = if (n == neutro) "" else n.toString()
        fun btc(sat: Long) = if (sat == Long.MAX_VALUE) "" else InstantaneaUtxo.btc(sat).trimEnd('0').trimEnd('.')

        titulo("Funds: at most (BTC)")
        val eMax = campo("e.g. 10 — empty for no limit", btc(actual.maxSat), true, false)

        titulo("Wallet type")
        val tipos = FiltroUtxo.TIPOS.map { t ->
            CheckBox(act).apply {
                text = t; isChecked = t in actual.tipos; setTextColor(AppTheme.TXT_SEC)
            }.also { caja.addView(it) }
        }
        caja.addView(TextView(act).apply {
            text = "None ticked = every type."; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_MUTED)
        })

        titulo("Public key")
        var clave = actual.clave
        caja.addView(Ui.segmented(act, listOf("Any" to null, "With one" to null, "Without" to null), initial = clave) { clave = it })
        val ePrefijo = campo("starts with (hex), e.g. 04 for uncompressed", actual.prefijoClave)

        titulo("Wallet (address) contains")
        val eDir = campo("text, any case", actual.direccionContiene)

        titulo("Transaction id starts with")
        val eTxid = campo("hex", actual.txidEmpieza)

        titulo("Output number (vout): from / to")
        val eVoutMin = campo("from", txt(actual.voutMin, 0L), true, false)
        val eVoutMax = campo("to", txt(actual.voutMax, Long.MAX_VALUE), true, false)

        titulo("Key or script hash starts with")
        val eHash = campo("hex", actual.hashEmpieza)

        titulo("Locking script contains")
        val eScript = campo("hex", actual.scriptContiene)

        titulo("Block height: from / to")
        val eAltMin = campo("from", txt(actual.alturaMin, 0L), true, false)
        val eAltMax = campo("to", txt(actual.alturaMax, Long.MAX_VALUE), true, false)

        titulo("Date of the transaction: from / to")
        val eDesde = campo("yyyy-MM-dd", actual.fechaDesde)
        val eHasta = campo("yyyy-MM-dd", actual.fechaHasta)

        titulo("Mined coins (coinbase)")
        var coinbase = actual.coinbase
        caja.addView(Ui.segmented(act, listOf("Any" to null, "Only mined" to null, "Not mined" to null), initial = coinbase) { coinbase = it })

        titulo("Confirmations: from / to")
        val eConfMin = campo("from", txt(actual.confMin, 0L), true, false)
        val eConfMax = campo("to", txt(actual.confMax, Long.MAX_VALUE), true, false)

        val errores = StringBuilder()
        fun entero(e: EditText, neutro: Long, nombre: String): Long {
            val t = e.text.toString().trim()
            if (t.isEmpty()) return neutro
            return t.toLongOrNull()?.takeIf { it >= 0 } ?: run { errores.append("• $nombre is not a whole number\n"); neutro }
        }

        AlertDialog.Builder(act).setTitle("Filters for the CSV")
            .setView(ScrollView(act).apply { addView(caja) })
            .setPositiveButton("Apply") { _, _ ->
                errores.setLength(0)
                val maxTxt = eMax.text.toString().trim()
                val maxSat = if (maxTxt.isEmpty()) Long.MAX_VALUE
                             else FiltroUtxo.btcASat(maxTxt) ?: run { errores.append("• the funds limit is not an amount\n"); Long.MAX_VALUE }
                fun fecha(e: EditText, n: String): String {
                    val t = e.text.toString().trim()
                    if (t.isEmpty()) return ""
                    return if (Regex("""\d{4}-\d{2}-\d{2}""").matches(t)) t else { errores.append("• $n must look like 2021-05-03\n"); "" }
                }
                fun hex(e: EditText, n: String): String {
                    val t = e.text.toString().trim()
                    return if (Regex("[0-9a-fA-F]*").matches(t)) t else { errores.append("• $n must be hexadecimal\n"); "" }
                }
                val f = FiltroUtxo(
                    maxSat = maxSat,
                    tipos = FiltroUtxo.TIPOS.filterIndexed { i, _ -> tipos[i].isChecked }.toSet(),
                    clave = clave, prefijoClave = hex(ePrefijo, "The key prefix"),
                    direccionContiene = eDir.text.toString(), txidEmpieza = hex(eTxid, "The transaction id"),
                    voutMin = entero(eVoutMin, 0L, "vout from"), voutMax = entero(eVoutMax, Long.MAX_VALUE, "vout to"),
                    hashEmpieza = hex(eHash, "The hash"), scriptContiene = hex(eScript, "The script"),
                    alturaMin = entero(eAltMin, 0L, "height from"), alturaMax = entero(eAltMax, Long.MAX_VALUE, "height to"),
                    fechaDesde = fecha(eDesde, "The start date"), fechaHasta = fecha(eHasta, "The end date"),
                    coinbase = coinbase,
                    confMin = entero(eConfMin, 0L, "confirmations from"), confMax = entero(eConfMax, Long.MAX_VALUE, "confirmations to")
                ).normalizado()
                if (errores.isNotEmpty()) Toast.makeText(act, "Ignored:\n" + errores.toString().trim(), Toast.LENGTH_LONG).show()
                alAceptar(f)
            }
            .setNeutralButton("Clear all") { _, _ -> alAceptar(FiltroUtxo()) }
            .setNegativeButton("Cancel", null).show()
    }
}
