package com.hunter.btc

import android.content.Context
import java.io.File

/**
 * Difunde una transacción ya firmada hablando directamente con nodos de Bitcoin, sin
 * pasar por ningún servidor de la app. Es el último recurso cuando la API y Electrum no
 * responden. Usa el cliente ligero en modo "sólo difusión": vale cualquier nodo y no se
 * sincroniza nada.
 *
 * No pasa por Tor aunque el nodo propio esté en modo "Tor only": es una conexión P2P directa
 * aparte de bitcoind. Con ese ajuste activado, se rinde en vez de difundir por un camino que
 * delataría la IP justo al enviar (ver el comentario dentro de difundir()).
 */
object P2pBroadcast {

    /**
     * @return el txid si algún nodo pidió la transacción y se la dimos; null si no se
     *   consiguió. Bloquea hasta ~60 s: nunca desde el hilo principal.
     */
    fun difundir(ctx: Context, rawHex: String, maxMs: Long = 60_000): String? {
        // "Tor only" (CoreConfig.Ajustes.red == 2) sólo hace que EL NODO hable únicamente con
        // pares .onion — el ajuste nació para bitcoind, y este cliente ligero es un camino aparte
        // que nunca se enteró de que existe. Sin esto, la transacción recién firmada —que delata
        // justo qué direcciones son tuyas— saldría por una conexión P2P directa, sin Tor, en el
        // peor momento posible para que se note: cuando ya fallaron la API y Electrum y este es
        // el último recurso. Mejor fallar aquí (el llamante ya tiene un mensaje para "no se pudo
        // difundir") que difundir de verdad por un camino que el usuario cree que no existe.
        if (try { CoreNodo.ajustes(ctx).red == 2 } catch (e: Throwable) { false }) return null
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
