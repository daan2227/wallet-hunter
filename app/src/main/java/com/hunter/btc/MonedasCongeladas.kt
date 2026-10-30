package com.hunter.btc

import android.content.Context
import org.json.JSONObject

/**
 * Monedas congeladas: las que el envío automático (y "enviar todo") no gasta nunca. Sirve para apartar una
 * moneda que no se quiere mezclar con las demás y, sobre todo, contra el "dust attack": alguien manda
 * cantidades minúsculas a tus direcciones para que, al gastarlas junto a otras, quede a la vista qué
 * direcciones son tuyas. La primera vez que aparece una moneda de ≤ [POLVO] sat se congela sola; si el
 * usuario la descongela, no se vuelve a congelar.
 *
 * La parte de decidir es pura (MonedasCongeladasTest); el almacén son unas preferencias por red.
 */
object MonedasCongeladas {
    const val POLVO = 1_000L

    fun clave(txid: String, vout: Int) = "${txid.lowercase()}:$vout"
    fun clave(u: JSONObject) = clave(u.optString("txid"), u.optInt("vout"))

    /** Las monedas que el envío automático puede usar: ni congeladas. */
    fun utilizables(lista: List<JSONObject>, congeladas: Set<String>) = lista.filter { clave(it) !in congeladas }

    /** Las que son polvo y no se habían visto nunca: las que hay que congelar ahora. */
    fun polvoNuevo(lista: List<JSONObject>, vistas: Set<String>) =
        lista.filter { it.optLong("value") in 1..POLVO && clave(it) !in vistas }.map { clave(it) }

    // ── Almacén ──

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("monedas", Context.MODE_PRIVATE)
    private fun leer(ctx: Context, k: String) = prefs(ctx).getStringSet(k, emptySet())!!.toSet()
    private fun red(testnet: Boolean) = if (testnet) "_test" else "_main"

    fun congeladas(ctx: Context, testnet: Boolean): Set<String> = leer(ctx, "congeladas" + red(testnet))

    fun poner(ctx: Context, testnet: Boolean, clave: String, congelar: Boolean) {
        val s = congeladas(ctx, testnet).toMutableSet()
        if (congelar) s.add(clave) else s.remove(clave)
        prefs(ctx).edit().putStringSet("congeladas" + red(testnet), s).apply()
    }

    /**
     * Congela el polvo que aparece por primera vez en [lista] y devuelve cuántas congeló. Las que ya se habían
     * visto no se tocan: así una que el usuario descongeló no vuelve a congelarse.
     */
    @Synchronized
    fun revisar(ctx: Context, testnet: Boolean, lista: List<JSONObject>): Int {
        val vistasK = "polvo_visto" + red(testnet)
        val nuevas = polvoNuevo(lista, leer(ctx, vistasK))
        if (nuevas.isEmpty()) return 0
        val cong = congeladas(ctx, testnet) + nuevas
        prefs(ctx).edit().putStringSet("congeladas" + red(testnet), cong)
            .putStringSet(vistasK, leer(ctx, vistasK) + nuevas).apply()
        return nuevas.size
    }
}
