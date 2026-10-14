package com.hunter.btc

/**
 * Clave de una dirección en la lista de la cartera (p2wpkh_3, p2wpkh_ch_3) y su
 * ruta BIP32. Una sola implementación para todo lo que firma.
 *
 * Había dos copias y las dos fallaban igual:
 *   - Sólo conocían la rama de recepción (…/0/i). El cambio de cada envío va a
 *     …/1/k, y esas direcciones no se podían gastar desde la app.
 *   - Un índice ilegible se convertía en 0: firmaba con la clave de OTRA
 *     dirección. Ahora un índice ilegible es null, y no se firma.
 *   - El tipo de moneda iba fijo a 0'. En testnet las direcciones se derivan con
 *     1' (BIP44), así que todo envío de testnet firmaba con otra clave.
 *
 * Pura: se prueba en la JVM.
 */
object RutaClave {
    private const val CAMBIO = "ch"

    fun clave(tipo: String, indice: Int, cambio: Boolean): String =
        if (cambio) "${tipo}_${CAMBIO}_$indice" else "${tipo}_$indice"

    fun ruta(k: String, testnet: Boolean): String? {
        val p = k.split('_')
        val purpose = when (p.firstOrNull()) {
            "p2pkh" -> 44; "p2sh" -> 49; "p2wpkh" -> 84; "p2tr" -> 86
            else -> return null
        }
        val cambio = when {
            p.size == 2 -> false
            p.size == 3 && p[1] == CAMBIO -> true
            else -> return null
        }
        val i = p.last().toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val coin = if (testnet) 1 else 0
        return "m/$purpose'/$coin'/0'/${if (cambio) 1 else 0}/$i"
    }
}
