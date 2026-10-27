package com.hunter.btc

import android.content.Context

/**
 * Dónde viven las etiquetas y su paso a/desde BIP329.
 *
 * Las de dirección ya existían ("address_labels", por dirección). Se añaden las de transacción y de
 * moneda, y un cajón para los tipos que la app no usa (xpub, pubkey, input): así un fichero de Sparrow
 * importado y vuelto a exportar no pierde nada.
 */
object Etiquetas {
    private const val DIR = "address_labels"
    private const val TX = "tx_labels"
    private const val OUT = "output_labels"
    private const val OUT_GASTABLE = "output_spendable"
    private const val OTROS = "bip329_otros"

    private fun p(ctx: Context, n: String) = ctx.getSharedPreferences(n, Context.MODE_PRIVATE)

    fun deTx(ctx: Context, txid: String): String? = p(ctx, TX).getString(txid, null)?.takeIf { it.isNotBlank() }

    fun ponerTx(ctx: Context, txid: String, texto: String) {
        val e = p(ctx, TX).edit()
        if (texto.isBlank()) e.remove(txid) else e.putString(txid, texto.trim().take(Bip329.MAX_ETIQUETA))
        e.apply()
    }

    fun deMoneda(ctx: Context, outpoint: String): String? = p(ctx, OUT).getString(outpoint, null)?.takeIf { it.isNotBlank() }

    /** Todas, en BIP329. [origen] (p. ej. "wpkh([huella/84'/0'/0'])") se pone a las de dirección y transacción si se sabe. */
    fun exportar(ctx: Context, origen: String? = null): String {
        val l = ArrayList<Bip329.Etiqueta>()
        for ((k, v) in p(ctx, DIR).all) if (v is String && v.isNotBlank()) l.add(Bip329.Etiqueta("addr", k, v, origen))
        for ((k, v) in p(ctx, TX).all) if (v is String && v.isNotBlank()) l.add(Bip329.Etiqueta("tx", k, v, origen))
        val gastables = p(ctx, OUT_GASTABLE).all
        val outs = p(ctx, OUT).all.keys + gastables.keys
        for (k in outs) {
            val v = p(ctx, OUT).getString(k, "") ?: ""
            val g = gastables[k] as? Boolean
            if (v.isNotBlank() || g != null) l.add(Bip329.Etiqueta("output", k, v, null, g))
        }
        for ((_, v) in p(ctx, OTROS).all) if (v is String) Bip329.importar(v).etiquetas.firstOrNull()?.let { l.add(it) }
        return Bip329.exportar(l)
    }

    /** Aplica un fichero BIP329. Devuelve (aplicadas, ignoradas). Una etiqueta vacía borra la que hubiera. */
    fun importar(ctx: Context, texto: String): Pair<Int, Int> {
        val r = Bip329.importar(texto)
        val dir = p(ctx, DIR).edit(); val tx = p(ctx, TX).edit(); val out = p(ctx, OUT).edit()
        val gast = p(ctx, OUT_GASTABLE).edit(); val otros = p(ctx, OTROS).edit()
        for (e in r.etiquetas) {
            fun pon(ed: android.content.SharedPreferences.Editor) {
                if (e.texto.isBlank()) ed.remove(e.ref) else ed.putString(e.ref, e.texto)
            }
            when (e.tipo) {
                "addr" -> pon(dir)
                "tx" -> pon(tx)
                "output" -> { pon(out); e.gastable?.let { gast.putBoolean(e.ref, it) } }
                else -> otros.putString(e.tipo + "|" + e.ref, Bip329.exportar(listOf(e)).trim())
            }
        }
        dir.apply(); tx.apply(); out.apply(); gast.apply(); otros.apply()
        return r.etiquetas.size to r.ignoradas
    }
}
