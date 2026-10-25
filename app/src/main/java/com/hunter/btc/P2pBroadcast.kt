package com.hunter.btc

import android.content.Context
import java.io.File

/**
 * Difunde una transacción ya firmada hablando directamente con nodos de Bitcoin, sin
 * pasar por ningún servidor de la app. Es el último recurso cuando la API y Electrum no
 * responden. Usa el cliente ligero en modo "sólo difusión": vale cualquier nodo y no se
 * sincroniza nada.
 */
object P2pBroadcast {

    /**
     * @return el txid si algún nodo pidió la transacción y se la dimos; null si no se
     *   consiguió. Bloquea hasta ~60 s: nunca desde el hilo principal.
     */
    fun difundir(ctx: Context, rawHex: String, maxMs: Long = 60_000): String? {
        val c = LightClient(File(ctx.filesDir, "ligero_difusion"), 0, "", 1, 6, soloDifusion = true)
        return try {
            c.iniciar(emptyList(), 0)
            val txid = c.difundir(rawHex)
            if (txid.startsWith("ERR:")) return null
            val fin = System.currentTimeMillis() + maxMs
            var enviadaDesde = 0L
            while (System.currentTimeMillis() < fin) {
                val e = c.estadoDifusion(txid).substringBefore(':')
                if (e == "propagada" || e == "confirmada") return txid
                if (e == "enviada") {
                    if (enviadaDesde == 0L) enviadaDesde = System.currentTimeMillis()
                    // Entregada a un nodo: se da por buena si no se llega a ver propagarse.
                    if (System.currentTimeMillis() - enviadaDesde > 15_000) return txid
                }
                Thread.sleep(500)
            }
            null
        } catch (e: Throwable) {
            RegistroFallos.anotar("P2pBroadcast.difundir", e)
            null
        } finally {
            c.detener()
        }
    }
}
