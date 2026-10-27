package com.hunter.btc

import android.app.Activity
import android.content.Intent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * Crear una multifirma P2WSH con la clave de esta cartera y las de los demás firmantes (otros teléfonos,
 * Coldcard, Keystone, Sparrow…). La clave propia es la de BIP48 (m/48'/coin'/0'/2'), que es la que piden
 * todos los demás para una multifirma: se enseña para pasarla a los otros, y se pegan las suyas.
 * El resultado es un descriptor wsh(sortedmulti(...)) normal, que se puede llevar a cualquier cartera.
 */
object CrearMultifirma {

    fun mostrar(act: Activity, mnemonic: String, testnet: Boolean) {
        val coin = if (testnet) 1 else 0
        val propia = HunterEngine.xpubDeRuta(mnemonic, "m/48'/$coin'/0'/2'", testnet).split('|')
        if (propia.size != 2) { Toast.makeText(act, "Could not derive this wallet's multisig key.", Toast.LENGTH_LONG).show(); return }
        val propiaTxt = "[${propia[0]}/48h/${coin}h/0h/2h]${propia[1]}"
        val d = (16 * act.resources.displayMetrics.density).toInt()
        val caja = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(d, d / 2, d, 0) }
        fun t(s: String, c: Int = AppTheme.TXT_SEC) = TextView(act).apply { text = s; textSize = AppTheme.SP_CAPTION; setTextColor(c); setPadding(0, d / 3, 0, d / 4) }
        caja.addView(t("Your key (give it to the other signers):"))
        caja.addView(TextView(act).apply { text = propiaTxt; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_PRI); typeface = AppTheme.mono(act); setTextIsSelectable(true) })
        val otras = ArrayList<EditText>()
        fun otra() = Ui.input(act, "Co-signer key: [fingerprint/48h/0h/0h/2h]xpub…", mono = true).also { otras.add(it); caja.addView(it) }
        caja.addView(t("Keys of the other signers:"))
        otra(); otra()
        val etK = Ui.input(act, "Signatures needed (default 2)").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val etNombre = Ui.input(act, "Name (e.g. Family vault)")
        caja.addView(etK); caja.addView(etNombre)
        caja.addView(t("Write down the resulting descriptor (Descriptor → Copy): without it, and with only k-of-n of the seeds, recovering the coins is much harder."))
        val dlg = AlertDialog.Builder(act).setTitle("New multisig").setView(android.widget.ScrollView(act).apply { addView(caja) })
            .setNegativeButton("Cancel", null)
            .setNeutralButton("+ key", null)
            .setPositiveButton("Create", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { if (otras.size < 14) otra() }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val claves = ArrayList<Descriptor.ClaveX>()
                val (mia, _) = Descriptor.leerClaveExpr(propiaTxt)
                claves.add(mia ?: return@setOnClickListener)
                val avisos = ArrayList<String>()
                for (e in otras) {
                    val txt = e.text.toString().trim().removeSuffix("/<0;1>/*").removeSuffix("/0/*")
                    if (txt.isEmpty()) continue
                    val (ck, aviso) = Descriptor.leerClaveExpr(txt)
                    if (ck == null) { Toast.makeText(act, "A key is not valid: ${aviso ?: ""}", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                    if (ck.testnet != testnet) { Toast.makeText(act, "A key is for the other network.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                    aviso?.let { avisos.add(it) }
                    claves.add(ck)
                }
                if (claves.map { Descriptor.hex(it.clave33) }.distinct().size != claves.size) { Toast.makeText(act, "The same key appears twice.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                if (claves.size < 2) { Toast.makeText(act, "Add at least one other key.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                val k = etK.text.toString().toIntOrNull() ?: minOf(2, claves.size)
                if (k !in 1..claves.size) { Toast.makeText(act, "Signatures needed must be between 1 and ${claves.size}.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                val motor = MotorMultifirma(k, claves, true, CarterasExternas.derivador, avisos.distinct())
                val id = CarterasExternas.anadir(act, etNombre.text.toString().trim().ifEmpty { "Multisig $k-of-${claves.size}" }, motor.texto())
                dlg.dismiss()
                act.startActivity(Intent(act, CarteraExternaActivity::class.java).putExtra(CarteraExternaActivity.EXTRA_ID, id))
            }
        }
        dlg.show()
    }
}
