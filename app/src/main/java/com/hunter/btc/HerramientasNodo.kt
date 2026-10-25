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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Utilidades que Bitcoin Core ya trae y que no necesitan ni monedero ni servidores: validar una dirección,
 * pasar un descriptor a direcciones, verificar una firma y trabajar con PSBT (decodificar, analizar,
 * combinar, finalizar). Todo se calcula en el nodo del propio teléfono; sólo hace falta que conteste.
 *
 * Ninguna recibe claves privadas: son direcciones, descriptores públicos, firmas y PSBT sin firmar o firmados.
 */
object HerramientasNodo {

    private class Herramienta(
        val nombre: String, val explicacion: String, val campos: List<String>,
        val multilinea: Boolean = false,
        val ejecutar: (CoreRpc, List<String>) -> Any?
    )

    private val LISTA = listOf(
        Herramienta("Check an address", "Says whether it is valid and what kind it is.", listOf("Address")) { r, v ->
            r.llamar("validateaddress", JSONArray().put(v[0].trim()))
        },
        Herramienta("Descriptor → addresses",
            "Adds the checksum to a public descriptor and derives its addresses. Example: wpkh([…]xpub…/0/*)",
            listOf("Descriptor", "Range (0-4, empty if it has no *)")) { r, v ->
            val d = r.objeto("getdescriptorinfo", JSONArray().put(v[0].trim())).getString("descriptor")
            val args = JSONArray().put(d)
            CoreConfig.rango(v[1])?.let { args.put(JSONArray().put(it.first).put(it.second)) }
            JSONObject().put("descriptor", d).put("addresses", r.llamar("deriveaddresses", args))
        },
        Herramienta("Descriptor info", "Checksum, whether it has a range, and whether it holds private keys.",
            listOf("Descriptor")) { r, v -> r.llamar("getdescriptorinfo", JSONArray().put(v[0].trim())) },
        Herramienta("Verify a signed message",
            "Only legacy addresses (starting with 1) are supported by Bitcoin Core.",
            listOf("Address", "Signature (base64)", "Message")) { r, v ->
            val ok = r.llamar("verifymessage", JSONArray().put(v[0].trim()).put(v[1].trim()).put(v[2]))
            if (ok == true) "The signature is VALID for that address." else "The signature does NOT match."
        },
        Herramienta("Decode a PSBT", "Shows inputs, outputs, fee and who still has to sign.",
            listOf("PSBT (base64)"), true) { r, v -> r.llamar("decodepsbt", JSONArray().put(v[0].trim())) },
        Herramienta("Analyze a PSBT", "What is missing and what the next step is.",
            listOf("PSBT (base64)"), true) { r, v -> r.llamar("analyzepsbt", JSONArray().put(v[0].trim())) },
        Herramienta("Combine PSBTs", "Merges the signatures of several copies of the same PSBT. One per line.",
            listOf("PSBTs (base64, one per line)"), true) { r, v ->
            val l = CoreConfig.partirPsbts(v[0])
            if (l.size < 2) throw IllegalArgumentException("Paste at least two PSBTs")
            r.llamar("combinepsbt", JSONArray().put(JSONArray(l)))
        },
        Herramienta("Finalize a PSBT", "Turns a fully signed PSBT into the raw transaction to broadcast.",
            listOf("PSBT (base64)"), true) { r, v -> r.llamar("finalizepsbt", JSONArray().put(v[0].trim()).put(true)) },
        Herramienta("Decode a raw transaction", "Shows what a signed transaction (hex) does before you send it.",
            listOf("Transaction (hex)"), true) { r, v -> r.llamar("decoderawtransaction", JSONArray().put(v[0].trim())) }
    )

    fun mostrar(act: Activity) {
        AlertDialog.Builder(act).setTitle("Node tools")
            .setItems(LISTA.map { it.nombre }.toTypedArray()) { _, i -> pedir(act, LISTA[i]) }
            .setNegativeButton("Close", null).show()
    }

    private fun pedir(act: Activity, h: Herramienta) {
        val p = (20 * act.resources.displayMetrics.density).toInt()
        val caja = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(p, p / 2, p, 0) }
        val campos = h.campos.map { nombre ->
            EditText(act).apply {
                hint = nombre
                typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f
                if (h.multilinea) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 4; maxLines = 8 }
                else { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; setSingleLine() }
            }.also { caja.addView(it) }
        }
        AlertDialog.Builder(act).setTitle(h.nombre).setMessage(h.explicacion).setView(ScrollView(act).apply { addView(caja) })
            .setPositiveButton("Run") { _, _ -> ejecutar(act, h, campos.map { it.text.toString() }) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun ejecutar(act: Activity, h: Herramienta, valores: List<String>) {
        if (valores.first().isBlank()) { Toast.makeText(act, "Fill in the first field", Toast.LENGTH_SHORT).show(); return }
        val ui = Handler(Looper.getMainLooper())
        Thread {
            val texto = try {
                when (val r = h.ejecutar(CoreNodo.rpc(act), valores)) {
                    is JSONObject -> r.toString(2)
                    is JSONArray -> r.toString(2)
                    else -> r.toString()
                }
            } catch (e: CoreRpcError) { "Core says: ${e.message}" }
              catch (e: IllegalArgumentException) { e.message ?: "Bad input" }
              catch (e: java.io.IOException) { "The node is not answering. Start it in the Core tab." }
              catch (e: Exception) {
                  // A diferencia de los tres de arriba (mensaje de Core, validación, nodo sin contestar),
                  // este sólo enseña el nombre de la clase: sin esto no quedaba ningún rastro del porqué.
                  RegistroFallos.anotar("HerramientasNodo.ejecutar: ${h.nombre}", e)
                  "Failed: ${e.javaClass.simpleName}"
              }
            ui.post { if (!act.isFinishing) resultado(act, h.nombre, texto) }
        }.start()
    }

    private fun resultado(act: Activity, titulo: String, texto: String) {
        val p = (16 * act.resources.displayMetrics.density).toInt()
        val tv = TextView(act).apply {
            text = texto; typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f
            setTextIsSelectable(true); setPadding(p, p, p, p)
        }
        AlertDialog.Builder(act).setTitle(titulo).setView(ScrollView(act).apply { addView(tv) })
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("result", texto))
                Toast.makeText(act, "Copied", Toast.LENGTH_SHORT).show()
            }.show()
    }
}
