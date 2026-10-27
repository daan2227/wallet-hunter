package com.hunter.btc

import android.app.Activity

/**
 * Pone en una PSBT de multifirma (o de herencia) la firma de las claves que son de las carteras de semilla
 * de este teléfono: se reconocen por la huella del maestro en la derivación BIP32 de cada entrada. Pide el
 * código; las privadas se derivan y usan en el motor y no se guardan.
 */
object FirmaPropia {

    /** Las semillas de la app: la principal y las demás. Sólo con la sesión abierta. */
    private fun semillas(act: Activity): List<String> =
        (listOfNotNull(WalletManager.loadSeed(act)) +
         WalletManager.listWallets(act).mapNotNull { (id, _) -> WalletManager.loadWalletSeed(act, id) }).distinct()

    /** Firma lo que pueda; [listo] recibe cuántas firmas puso (0 si ninguna clave de la PSBT es de aquí). */
    fun firmar(act: Activity, d: Psbt.Doc, listo: (Int) -> Unit) {
        // Antes de nada, qué se firma: a quién paga, cuánto y la comisión. Una PSBT que llega de fuera
        // podría vaciar la cartera hacia otra dirección; se firma sólo si el usuario lo ve y lo acepta.
        val rev = try { Psbt.revisar(d) } catch (e: Exception) { null }
        if (rev == null || rev.problemas.isNotEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(act).setTitle("Not signed")
                .setMessage(rev?.problemas?.joinToString("\n") ?: "The PSBT could not be read completely.")
                .setPositiveButton("OK") { _, _ -> listo(-1) }.show()
            return
        }
        val txt = buildString {
            appendLine("It pays:")
            for ((dir, v) in rev.salidas) appendLine("• %.8f BTC → %s".format(v / 1e8, dir) + Fiat.entre(act, v))
            appendLine()
            append(if (rev.comision != null) "Fee: %,d sat".format(rev.comision) + Fiat.entre(act, rev.comision) else "Fee: unknown")
            append("\n\nOutputs that return to the same wallet are its change. Sign only if this is what you expect.")
        }
        androidx.appcompat.app.AlertDialog.Builder(act).setTitle("Sign with this phone?").setMessage(txt)
            .setNegativeButton("Cancel") { _, _ -> listo(-1) }
            .setPositiveButton("Sign") { _, _ -> firmarYa(act, d, listo) }.show()
    }

    private fun firmarYa(act: Activity, d: Psbt.Doc, listo: (Int) -> Unit) {
        PinAuthHelper.show(act, autoBiometric = false) { ok ->
            if (!ok) { listo(-1); return@show }
            Thread {
                var n = 0
                try {
                    for (mn in semillas(act)) {
                        val fp = HunterEngine.infoDeRuta(mn, "m/0", false).split('|').getOrNull(1) ?: continue
                        if (fp.length != 8) continue
                        for ((pub, ruta) in Psbt.clavesDe(d, Descriptor.bytes(fp))) {
                            val priv = HunterEngine.deriveRuta(mn, Psbt.rutaTexto(ruta))
                            if (priv.length != 64 || HunterEngine.pubDeHex(priv) != Descriptor.hex(pub)) continue
                            n += Psbt.firmarWsh(d, pub) { z -> HunterEngine.firmarHash(priv, z) }
                        }
                    }
                } catch (e: Exception) { RegistroFallos.anotar("FirmaPropia", e) }
                act.runOnUiThread { listo(n) }
            }.start()
        }
    }
}
