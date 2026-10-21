package com.hunter.btc

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray

/**
 * "Verify with my node": el saldo de la cartera comprobado con el conjunto de UTXO del Bitcoin Core del
 * propio teléfono (scantxoutset), sin preguntar a ningún explorador. A diferencia del cliente ligero,
 * ve TODO lo que tiene la cartera sin gastar, no sólo lo recibido en un rango.
 *
 * El escaneo recorre el conjunto entero de UTXO: unos minutos en un teléfono. Sólo cuenta lo confirmado.
 */
object VerificacionNodo {

    @Volatile private var enMarcha = false

    fun mostrar(act: Activity, saldoApiSat: Long, scripts: () -> List<String>) {
        if (enMarcha) { Toast.makeText(act, "A scan is already running", Toast.LENGTH_SHORT).show(); return }
        if (CoreNodo.binario(act) == null) {
            Toast.makeText(act, "This build does not include Bitcoin Core.", Toast.LENGTH_LONG).show(); return
        }
        enMarcha = true
        val h = Handler(Looper.getMainLooper())
        val cancelado = java.util.concurrent.atomic.AtomicBoolean(false)
        val dlg = AlertDialog.Builder(act).setTitle("Checking with your node")
            .setMessage("Preparing…").setCancelable(false)
            .setNegativeButton("Cancel") { _, _ ->
                cancelado.set(true)
                Thread { try { CoreNodo.rpc(act).llamar("scantxoutset", JSONArray().put("abort")) } catch (e: Exception) {} }.start()
            }.create()
        dlg.show()
        fun texto(s: String) { h.post { dlg.setMessage(s) } }
        fun terminar(titulo: String, s: String) {
            h.post {
                enMarcha = false
                try { dlg.dismiss() } catch (e: Exception) {}
                if (!act.isFinishing) AlertDialog.Builder(act).setTitle(titulo).setMessage(s)
                    .setPositiveButton("OK", null).show()
            }
        }
        Thread {
            try {
                val sc = try { scripts() } catch (e: Exception) { emptyList() }
                if (sc.isEmpty()) return@Thread terminar("Nothing to check", "This wallet has no addresses to check.")
                val est = try { CoreConfig.leerEstado(CoreNodo.rpc(act).objeto("getblockchaininfo", lecturaMs = 6_000), null, null) }
                          catch (e: Exception) { null }
                if (est == null)
                    return@Thread terminar("Your node is not answering",
                        "Start it in the Core tab and wait until it has finished loading the snapshot.")
                if (est.descargaInicial || est.bloques < est.cabeceras - 1)
                    return@Thread terminar("Your node is still catching up",
                        "It is at block %,d of %,d. A scan now would miss recent coins; try again when it is synced."
                            .format(est.bloques, est.cabeceras))
                val descs = JSONArray(); for (s in sc) descs.put("raw($s)")
                // Progreso: scantxoutset status contesta aunque haya un escaneo en curso.
                val sondeo = Thread {
                    while (enMarcha && !cancelado.get()) {
                        try { Thread.sleep(3_000) } catch (e: InterruptedException) { break }
                        try {
                            val p = CoreNodo.rpc(act).llamar("scantxoutset", JSONArray().put("status"), 5_000)
                            val n = (p as? org.json.JSONObject)?.optInt("progress", -1) ?: -1
                            if (n >= 0) texto("Scanning the whole set of coins… $n %\n\nThis takes a few minutes.")
                        } catch (e: Exception) { }
                    }
                }.also { it.isDaemon = true; it.start() }
                texto("Scanning the whole set of coins…\n\nThis takes a few minutes.")
                val r = try {
                    CoreNodo.rpc(act).objeto("scantxoutset", JSONArray().put("start").put(descs), 45 * 60_000)
                } catch (e: CoreRpcError) {
                    sondeo.interrupt()
                    return@Thread terminar(if (cancelado.get()) "Cancelled" else "Your node refused",
                        if (cancelado.get()) "The scan was stopped." else (e.message ?: "error"))
                } finally { sondeo.interrupt() }
                val e = CoreConfig.leerEscaneo(r)
                    ?: return@Thread terminar(if (cancelado.get()) "Cancelled" else "Scan not finished",
                        "The node did not complete the scan.")
                val dif = e.sat - saldoApiSat
                val cmp = when {
                    saldoApiSat < 0 -> ""
                    dif == 0L -> "\n\nIt matches the balance shown in the wallet."
                    else -> "\n\nThe wallet shows %s BTC. The difference can be unconfirmed coins, or coins on addresses the wallet does not list."
                        .format("%.8f".format(saldoApiSat / 1e8))
                }
                terminar("Your node says", ("%.8f BTC in %d coin(s)\nUTXO set at block %,d%s")
                    .format(e.sat / 1e8, e.monedas.size, e.altura, cmp))
            } catch (t: Throwable) {
                terminar("Could not check", t.javaClass.simpleName)
            }
        }.start()
    }
}
