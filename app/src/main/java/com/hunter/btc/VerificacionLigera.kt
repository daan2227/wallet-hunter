package com.hunter.btc

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File

/**
 * "Verify with the Bitcoin network": el saldo de la cartera comprobado con nodos de
 * Bitcoin (BIP157/158), sin preguntar a ningún explorador.
 *
 * El cliente baja los filtros de bloque del rango elegido y se queda sólo con los bloques
 * que tocan a la cartera, así que los nodos no saben qué direcciones son tuyas. A cambio
 * gasta datos (unos 20 KB por bloque) y sólo ve lo recibido dentro del rango.
 *
 * Primero se sincronizan las cabeceras (la primera vez, ~70 MB) y, ya con la altura de la
 * cadena, se escanea el rango pedido: por eso son dos etapas.
 */
object VerificacionLigera {

    private const val SIN_ESCANEO = Int.MAX_VALUE / 2
    @Volatile private var cliente: LightClient? = null

    /** Cuántos bloques mira cada opción, o null para pedir una altura. */
    private val RANGOS = listOf(
        Triple("Last 2 weeks (~2,000 blocks, about 40 MB)", 2016, 0),
        Triple("Last 3 months (~13,000 blocks, about 260 MB)", 13000, 0),
        Triple("Last year (~52,000 blocks, about 1 GB)", 52560, 0),
        Triple("From a block height…", -1, 0))

    fun mostrar(act: Activity, saldoApiSat: Long, scripts: () -> List<String>) {
        if (cliente != null) { Toast.makeText(act, "A check is already running", Toast.LENGTH_SHORT).show(); return }
        val nodo = EditText(act).apply {
            hint = "host:port (optional, your own node)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val caja = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * act.resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(nodo)
        }
        AlertDialog.Builder(act).setTitle("Verify with the Bitcoin network")
            .setMessage("Checks your coins by asking Bitcoin nodes directly, not an explorer. " +
                "The nodes never learn which addresses are yours: the app downloads each block's " +
                "filter and only the blocks that matter.\n\n" +
                "It uses mobile data, and it only sees coins received in the range you choose. " +
                "The first time it also downloads all block headers (~70 MB).\n\n" +
                "Few public nodes serve these filters. If none is found, enter a node of your own " +
                "(it needs blockfilterindex=1 and peerblockfilters=1).")
            .setView(caja)
            .setPositiveButton("Next") { _, _ -> elegirRango(act, nodo.text.toString().trim(), saldoApiSat, scripts) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun elegirRango(act: Activity, nodoTxt: String, saldoApiSat: Long, scripts: () -> List<String>) {
        // Sin nodo escrito, y con el nodo propio sirviendo filtros, se usa el del teléfono.
        val propio = nodoTxt.isEmpty() && CoreNodo.binario(act) != null && CoreNodo.ajustes(act).servirFiltros &&
                     (CoreNodo.procesoVivo() || CoreService.activo)
        val nodo: LightClient.Direccion? = if (propio) LightClient.Direccion("127.0.0.1", CoreConfig.PUERTO_P2P_LOCAL)
        else if (nodoTxt.isEmpty()) null else {
            val i = nodoTxt.lastIndexOf(':')
            val host = if (i > 0) nodoTxt.substring(0, i) else nodoTxt
            val port = if (i > 0) nodoTxt.substring(i + 1).toIntOrNull() else 8333
            if (host.isEmpty() || port == null || port !in 1..65535) {
                Toast.makeText(act, "That is not a valid host:port", Toast.LENGTH_LONG).show(); return
            }
            LightClient.Direccion(host, port)
        }
        AlertDialog.Builder(act).setTitle("How far back?")
            .setItems(RANGOS.map { it.first }.toTypedArray()) { _, i ->
                val atras = RANGOS[i].second
                if (atras > 0) empezar(act, nodo, atras, -1, saldoApiSat, scripts)
                else pedirAltura(act) { alt -> empezar(act, nodo, 0, alt, saldoApiSat, scripts) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pedirAltura(act: Activity, alAceptar: (Int) -> Unit) {
        val campo = EditText(act).apply { hint = "Block height"; inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine() }
        val caja = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * act.resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(campo)
        }
        AlertDialog.Builder(act).setTitle("Start from block")
            .setMessage("The wallet's coins received before this block will not be seen. Use the height of the first transaction, or lower.")
            .setView(caja)
            .setPositiveButton("Start") { _, _ ->
                val n = campo.text.toString().trim().toIntOrNull()
                if (n == null || n < 0) Toast.makeText(act, "Enter a block height", Toast.LENGTH_SHORT).show() else alAceptar(n)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** `atras` bloques antes de la punta, o la altura `absoluta` si no es -1. */
    private fun empezar(act: Activity, nodo: LightClient.Direccion?, atras: Int, absoluta: Int,
                        saldoApiSat: Long, scripts: () -> List<String>) {
        Thread {
            val sc = try { scripts() } catch (e: Exception) { emptyList() }
            if (sc.isEmpty()) {
                act.runOnUiThread { Toast.makeText(act, "This wallet has no addresses to check", Toast.LENGTH_LONG).show() }
                return@Thread
            }
            val dir = File(act.filesDir, "ligero")
            val c = LightClient(dir, 0, "", if (nodo != null) 1 else 2, 4,
                resolver = { if (nodo != null) listOf(nodo) else LightClient.Seeds.resolver(dir) })
            try { c.iniciar(sc, SIN_ESCANEO) } catch (e: Exception) {
                act.runOnUiThread { Toast.makeText(act, "Could not start: ${e.message}", Toast.LENGTH_LONG).show() }
                return@Thread
            }
            cliente = c
            act.runOnUiThread { progreso(act, c, sc, atras, absoluta, nodo != null, saldoApiSat) }
        }.start()
    }

    private fun nombreFase(f: String) = when (f) {
        "conectando" -> "looking for nodes"
        "cabeceras" -> "downloading headers"
        "cabeceras_de_filtro" -> "checking filter headers with the nodes"
        "filtros" -> "downloading filters"
        "bloques" -> "downloading blocks"
        "listo" -> "done"
        else -> f
    }

    private fun progreso(act: Activity, c: LightClient, sc: List<String>, atras: Int, absoluta: Int,
                         nodoPropio: Boolean, saldoApiSat: Long) {
        val texto = TextView(act).apply {
            val p = (20 * act.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p / 2); textSize = 15f
        }
        val manejador = Handler(Looper.getMainLooper())
        var etapa = 1
        var desde = 0
        var tip = 0
        val inicio = System.currentTimeMillis()
        var terminado = false
        lateinit var dialogo: AlertDialog

        fun terminar(json: JSONObject, error: String?) {
            terminado = true
            val ultimo = json
            Thread { try { c.detener() } catch (e: Exception) { } }.start()
            cliente = null
            dialogo.dismiss()
            resultado(act, ultimo, error, desde, tip, nodoPropio, saldoApiSat)
        }

        lateinit var tarea: Runnable
        tarea = Runnable {
            if (terminado) return@Runnable
            val j = try { JSONObject(c.estadoJson()) } catch (e: Exception) { JSONObject() }
            val fase = j.optString("fase")
            val alturaCab = j.optInt("alturaCabeceras")
            when {
                fase == "error" -> { terminar(j, j.optString("error")); return@Runnable }
                etapa == 1 && fase == "listo" && alturaCab > 0 -> {
                    tip = alturaCab
                    desde = if (absoluta >= 0) minOf(absoluta, tip) else maxOf(0, tip - atras)
                    etapa = 2
                    c.reescanear(sc, desde)
                }
                etapa == 2 && fase == "listo" && j.optInt("alturaProcesada") >= tip -> { terminar(j, null); return@Runnable }
            }
            val sb = StringBuilder()
            if (etapa == 1) {
                sb.append("Step 1 of 2 — ${nombreFase(fase)}\nHeaders: $alturaCab")
            } else {
                val total = tip - desde + 1
                val hechos = (j.optInt("alturaProcesada") - desde + 1).coerceIn(0, total)
                sb.append("Step 2 of 2 — ${nombreFase(fase)}\nBlocks $desde–$tip: $hechos of $total\n")
                sb.append("Blocks downloaded: ${j.optInt("bloques")}\n")
                sb.append("Balance so far: %.8f BTC".format(j.optLong("saldo") / 1e8))
            }
            sb.append("\nNodes: ${j.optInt("paresListos")} ready, ${j.optInt("paresVivos")} connected")
            if (j.optInt("paresListos") == 0 && System.currentTimeMillis() - inicio > 30_000) {
                sb.append("\n\nNo node that serves block filters has answered yet. Many public nodes do not; " +
                    "this can take a few minutes, or you can cancel and enter a node of your own.")
            }
            texto.text = sb.toString()
            manejador.postDelayed(tarea, 1000)
        }

        dialogo = AlertDialog.Builder(act).setTitle("Verifying…").setView(texto)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ ->
                terminado = true
                Thread { try { c.detener() } catch (e: Exception) { } }.start()
                cliente = null
            }
            .create()
        dialogo.show()
        manejador.post(tarea)
    }

    private fun resultado(act: Activity, j: JSONObject, error: String?, desde: Int, tip: Int,
                          nodoPropio: Boolean, saldoApiSat: Long) {
        val sb = StringBuilder()
        if (error != null) {
            sb.append("The check stopped: $error\n\n")
            sb.append("No balance was taken as good. This can mean the nodes disagreed or sent something wrong.")
        } else {
            val saldo = j.optLong("saldo")
            sb.append("Balance seen on the network: %.8f BTC\n".format(saldo / 1e8))
            val monedas = j.optJSONArray("utxos")
            sb.append("Coins: ${monedas?.length() ?: 0}\n")
            sb.append("Blocks checked: $desde–$tip (${j.optInt("bloques")} downloaded)\n")
            sb.append(if (j.optBoolean("verificado")) "Cross-checked between several nodes: yes\n"
                      else if (nodoPropio) "Asked only your own node.\n"
                      else "Cross-checked between several nodes: NO (only one answered)\n")
            if (saldoApiSat >= 0) {
                sb.append("\nThe explorer showed %.8f BTC.\n".format(saldoApiSat / 1e8))
                sb.append(if (saldoApiSat == saldo) "Both match."
                          else "They differ. That is normal if the wallet received coins before block $desde; " +
                               "otherwise do not trust either number and check again.")
            }
            if (monedas != null && monedas.length() > 0) {
                sb.append("\n\n")
                for (i in 0 until minOf(monedas.length(), 30)) {
                    val m = monedas.getJSONObject(i)
                    sb.append("• %.8f BTC  block %d  %s…:%d\n".format(
                        m.optLong("valor") / 1e8, m.optInt("altura"), m.optString("txid").take(10), m.optInt("vout")))
                }
            }
        }
        val texto = sb.toString()
        AlertDialog.Builder(act).setTitle(if (error != null) "Check stopped" else "Check finished")
            .setMessage(texto)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("verification", texto))
                Toast.makeText(act, "Copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
