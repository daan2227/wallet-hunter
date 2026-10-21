package com.hunter.btc

import android.content.Context
import org.json.JSONArray

/**
 * El nodo Bitcoin Core del propio teléfono como fuente para la cartera: difundir transacciones, estimar la
 * comisión y leer la altura. Es opcional (ajuste en la pestaña Core, apagado por defecto) y siempre CON
 * respaldo: si el nodo no está en marcha, no está al día o no contesta, se usa lo de siempre.
 *
 * Sólo mainnet, que es la red del nodo. Todo hace red local: nunca desde el hilo principal.
 */
object CoreBackend {

    private const val PREFS = "core"
    private const val PREF_USAR = "usar_nodo_cartera"

    fun activado(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_USAR, false)
    fun activar(ctx: Context, si: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_USAR, si).apply()

    @Volatile private var comprobadoMs = 0L
    @Volatile private var alDia = false

    /**
     * ¿Contesta el nodo y está al día con la red? Se recuerda 30 s para no preguntar en cada llamada.
     * Con una carga de snapshot en marcha el RPC no contesta (cs_main tomado) y sale false, que es lo correcto.
     */
    fun alDia(ctx: Context): Boolean {
        val ahora = System.currentTimeMillis()
        if (ahora - comprobadoMs < 30_000) return alDia
        val v = try {
            val e = CoreConfig.leerEstado(CoreNodo.rpc(ctx).objeto("getblockchaininfo", lecturaMs = 4_000), null, null)
            !e.descargaInicial && e.bloques >= e.cabeceras - 1 && e.bloques > 0
        } catch (e: Exception) { false }
        alDia = v; comprobadoMs = ahora
        return v
    }

    /** Se puede usar el nodo para la cartera ahora mismo: el ajuste está puesto y el nodo está al día. */
    fun disponible(ctx: Context?): Boolean =
        ctx != null && CoreNodo.binario(ctx) != null && activado(ctx) && alDia(ctx)

    /**
     * Difunde por el nodo. Devuelve el txid si entró (o ya estaba), "ERROR: …" si el nodo la rechazó por
     * una razón que no cambia aunque se pruebe en otro sitio, y null si no se pudo decidir (el llamador
     * prueba entonces por los caminos de siempre).
     */
    fun difundir(ctx: Context?, rawHex: String): String? {
        if (!disponible(ctx)) return null
        return try {
            CoreNodo.rpc(ctx!!).llamar("sendrawtransaction", JSONArray().put(rawHex), 20_000) as? String
        } catch (e: CoreRpcError) {
            when {
                // -27: ya está en la cadena. -26 con "already": ya está en la mempool. Es un éxito.
                e.code == -27 || (e.message ?: "").contains("already", ignoreCase = true) ->
                    Psbt.txidDeRaw(rawHex)
                // -25: faltan entradas. Un nodo recién sincronizado puede ir un bloque por detrás: no es veredicto.
                e.code == -25 || e.code == -28 -> null
                // -26 (política: comisión baja, polvo, conflicto…) y -22 (no decodifica): el mismo resultado en cualquier nodo.
                else -> "ERROR: ${e.message}"
            }
        } catch (e: Exception) { null }
    }

    /** Comisiones por estimatesmartfee, o null si el nodo no tiene datos (p. ej. con «blocks only»). */
    fun tarifas(ctx: Context?): ChainInfo.Fees? {
        if (!disponible(ctx)) return null
        return try {
            val r = CoreNodo.rpc(ctx!!)
            val est = HashMap<Int, Double>()
            for (t in intArrayOf(1, 3, 6, 12, 144)) {
                CoreConfig.satPorVb(r.objeto("estimatesmartfee", JSONArray().put(t), 8_000))?.let { est[t] = it }
            }
            Tarifas.desdeEsplora(est, ChainInfo.TARIFA_MAX)?.let {
                ChainInfo.Fees(it.fastest, it.halfHour, it.hour, it.economy, 1)
            }
        } catch (e: Exception) { null }
    }

    /** Altura del nodo (sólo si está al día). */
    fun altura(ctx: Context?): Int? {
        if (!disponible(ctx)) return null
        return try { (CoreNodo.rpc(ctx!!).llamar("getblockcount", JSONArray(), 4_000) as? Number)?.toInt() }
        catch (e: Exception) { null }
    }
}
