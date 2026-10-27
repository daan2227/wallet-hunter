package com.hunter.btc

import android.app.Activity
import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * Crear una cartera de herencia (ver MotorHerencia) con la clave de esta cartera como dueña y la de un
 * heredero (su teléfono con esta app, Sparrow, una cartera física…). La clave propia es m/48'/coin'/1'/2'
 * (cuenta 1, para no mezclarla con la de las multifirmas). El plazo va en bloques: ~144 al día, como
 * mucho 65535 (unos 15 meses), que es lo que permite older() por bloques.
 */
object CrearHerencia {
    fun mostrar(act: Activity, mnemonic: String, testnet: Boolean) {
        val coin = if (testnet) 1 else 0
        val propia = HunterEngine.xpubDeRuta(mnemonic, "m/48'/$coin'/1'/2'", testnet).split('|')
        if (propia.size != 2) { Toast.makeText(act, "Could not derive this wallet's key.", Toast.LENGTH_LONG).show(); return }
        val propiaTxt = "[${propia[0]}/48h/${coin}h/1h/2h]${propia[1]}"
        val d = (16 * act.resources.displayMetrics.density).toInt()
        val caja = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(d, d / 2, d, 0) }
        fun t(s: String) = TextView(act).apply { text = s; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC); setPadding(0, d / 3, 0, d / 4) }
        caja.addView(t("You (owner) can spend at any time. The heir can spend a coin only after it stays unmoved for the chosen time. " +
            "Your key:"))
        caja.addView(TextView(act).apply { text = propiaTxt; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.mono(act); setTextIsSelectable(true) })
        val etHeredero = Ui.input(act, "Heir's key: [fingerprint/path]xpub…", mono = true)
        val etMeses = Ui.input(act, "Months without movement before the heir can spend (1–15)").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val etNombre = Ui.input(act, "Name (e.g. Inheritance)")
        caja.addView(t("Heir:")); caja.addView(etHeredero); caja.addView(etMeses); caja.addView(etNombre)
        caja.addView(t("Give the heir the descriptor (Descriptor → Copy) and keep a copy with your backup: without it, the heir cannot find the coins."))
        val dlg = AlertDialog.Builder(act).setTitle("New inheritance wallet").setView(android.widget.ScrollView(act).apply { addView(caja) })
            .setNegativeButton("Cancel", null).setPositiveButton("Create", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val (mia, _) = Descriptor.leerClaveExpr(propiaTxt)
                val (her, aviso) = Descriptor.leerClaveExpr(etHeredero.text.toString().trim().removeSuffix("/<0;1>/*").removeSuffix("/0/*"))
                if (mia == null || her == null) { Toast.makeText(act, "The heir's key is not valid: ${aviso ?: ""}", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                if (her.testnet != testnet) { Toast.makeText(act, "The heir's key is for the other network.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                if (her.clave33.contentEquals(mia.clave33)) { Toast.makeText(act, "The heir's key cannot be yours.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                val meses = etMeses.text.toString().toIntOrNull() ?: 0
                if (meses !in 1..15) { Toast.makeText(act, "Choose between 1 and 15 months.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                val bloques = minOf(65535, meses * 30 * 144)
                val m = MotorHerencia(mia, her, bloques, CarterasExternas.derivador, listOfNotNull(aviso))
                val id = CarterasExternas.anadir(act, etNombre.text.toString().trim().ifEmpty { "Inheritance" }, m.texto())
                dlg.dismiss()
                act.startActivity(Intent(act, CarteraExternaActivity::class.java).putExtra(CarteraExternaActivity.EXTRA_ID, id))
            }
        }
        dlg.show()
    }
}
