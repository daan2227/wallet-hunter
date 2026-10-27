package com.hunter.btc

import org.json.JSONObject

/**
 * Etiquetas en el formato BIP329 (JSON Lines): una línea por etiqueta, {"type", "ref", "label", ...}.
 * Es el que entienden Sparrow, Nunchuk, BitBox y otras: exportar aquí e importar allí (y al revés)
 * conserva los nombres de direcciones, transacciones y monedas.
 *
 * Pura: se prueba en la JVM. Dónde se guardan las etiquetas lo decide quien llama.
 */
object Bip329 {
    /** Tipos del estándar; los demás se ignoran al importar. */
    val TIPOS = setOf("tx", "addr", "pubkey", "input", "output", "xpub")

    /** Tope por etiqueta: el estándar no fija ninguno; esto evita que un fichero raro llene las preferencias. */
    const val MAX_ETIQUETA = 255

    data class Etiqueta(val tipo: String, val ref: String, val texto: String,
                        val origen: String? = null, val gastable: Boolean? = null)

    data class Importado(val etiquetas: List<Etiqueta>, val ignoradas: Int)

    fun exportar(lista: List<Etiqueta>): String = buildString {
        for (e in lista) {
            if (e.tipo !in TIPOS || e.ref.isEmpty()) continue
            val o = JSONObject().put("type", e.tipo).put("ref", e.ref).put("label", e.texto)
            e.origen?.let { o.put("origin", it) }
            e.gastable?.let { o.put("spendable", it) }
            append(o.toString()).append('\n')
        }
    }

    /**
     * Lee un fichero BIP329. Las líneas que no son JSON, de un tipo desconocido, sin "ref" o con una
     * referencia que no cuadra con su tipo cuentan como ignoradas (el resto se importa igual). Una
     * etiqueta vacía se conserva: en BIP329 significa "quitar la etiqueta".
     */
    fun importar(texto: String): Importado {
        val ok = ArrayList<Etiqueta>(); var mal = 0
        for (linea0 in texto.lineSequence()) {
            val linea = linea0.trim()
            if (linea.isEmpty()) continue
            val o = try { JSONObject(linea) } catch (e: Exception) { mal++; continue }
            val tipo = o.optString("type"); val ref = o.optString("ref").trim()
            if (tipo !in TIPOS || ref.isEmpty() || !refValida(tipo, ref)) { mal++; continue }
            val label = if (o.has("label") && !o.isNull("label")) o.optString("label") else ""
            ok.add(Etiqueta(tipo, ref, label.take(MAX_ETIQUETA),
                o.optString("origin").takeIf { o.has("origin") && it.isNotEmpty() },
                if (o.has("spendable") && !o.isNull("spendable")) o.optBoolean("spendable", true) else null))
        }
        return Importado(ok, mal)
    }

    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
    private val OUTPOINT = Regex("^[0-9a-fA-F]{64}:\\d{1,10}$")

    private fun refValida(tipo: String, ref: String) = when (tipo) {
        "tx" -> HEX64.matches(ref)
        "input", "output" -> OUTPOINT.matches(ref)
        "addr" -> ref.length in 14..100 && ref.all { it.isLetterOrDigit() }
        else -> ref.length <= 200
    }
}
