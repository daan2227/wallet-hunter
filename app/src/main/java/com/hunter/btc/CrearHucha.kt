package com.hunter.btc

import android.app.Activity
import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * Crear una hucha con fecha (ver MotorHucha) con una clave de esta cartera: m/48'/coin'/2'/2' (cuenta 2, aparte
 * de la de multifirmas y la de herencias). Se elige un día; desde ese día (UTC, ~1 h después por la mediana de
 * bloques) se puede gastar, y antes no, ni con la clave.
 */
object CrearHucha {
    private val DIA = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC"); isLenient = false
    }

    fun mostrar(act: Activity, mnemonic: String, testnet: Boolean) {
        val coin = if (testnet) 1 else 0
        val propia = HunterEngine.xpubDeRuta(mnemonic, "m/48'/$coin'/2'/2'", testnet).split('|')
        if (propia.size != 2) { Toast.makeText(act, "Could not derive this wallet's key.", Toast.LENGTH_LONG).show(); return }
        val propiaTxt = "[${propia[0]}/48h/${coin}h/2h/2h]${propia[1]}"
        val d = (16 * act.resources.displayMetrics.density).toInt()
        val caja = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(d, d / 2, d, 0) }
        fun t(s: String) = TextView(act).apply { text = s; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); setPadding(0, d / 3, 0, d / 4) }
        caja.addView(t("Coins sent to a savings vault cannot be spent before the date you choose — not even by you, not even " +
            "with the seed. The Bitcoin network enforces it (CHECKLOCKTIMEVERIFY), so there is no way to unlock it early. " +
            "Use money you will not need before then."))
        val enUnAno = DIA.format(java.util.Date(System.currentTimeMillis() + 365L * 86_400_000))
        val etFecha = Ui.input(act, "Spendable from (yyyy-MM-dd, UTC), e.g. $enUnAno")
        val etNombre = Ui.input(act, "Name (e.g. Savings 2027)")
        caja.addView(etFecha); caja.addView(etNombre)
        caja.addView(t("To spend it later you need this wallet's seed AND the vault's descriptor (Descriptor → Copy). " +
            "Keep the descriptor with your backup."))
        val dlg = AlertDialog.Builder(act).setTitle("New savings vault").setView(android.widget.ScrollView(act).apply { addView(caja) })
            .setNegativeButton("Cancel", null).setPositiveButton("Create", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val fecha = try { DIA.parse(etFecha.text.toString().trim()) } catch (e: Exception) { null }
                if (fecha == null) { Toast.makeText(act, "Write the date as yyyy-MM-dd.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                val t = fecha.time / 1000
                val dias = (t - System.currentTimeMillis() / 1000) / 86_400
                if (dias < 1) { Toast.makeText(act, "Choose a day after today.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                if (t > 0xFFFFFFFFL) { Toast.makeText(act, "That date is too far away.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                val (mia, aviso) = Descriptor.leerClaveExpr(propiaTxt)
                if (mia == null) { Toast.makeText(act, "Could not read this wallet's key.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                val crear = {
                    val m = MotorHucha(mia, t, CarterasExternas.derivador, listOfNotNull(aviso))
                    val id = CarterasExternas.anadir(act, etNombre.text.toString().trim().ifEmpty { "Savings vault" }, m.texto())
                    dlg.dismiss()
                    act.startActivity(Intent(act, CarteraExternaActivity::class.java).putExtra(CarteraExternaActivity.EXTRA_ID, id))
                }
                // Un plazo largo es la idea, pero conviene que sea a propósito.
                if (dias > 5 * 365) AlertDialog.Builder(act).setTitle("Locked for ${dias / 365} years?")
                    .setMessage("Nothing can spend these coins until ${DIA.format(fecha)}. Are you sure?")
                    .setNegativeButton("Change it", null).setPositiveButton("Yes, create it") { _, _ -> crear() }.show()
                else crear()
            }
        }
        dlg.show()
    }
}
