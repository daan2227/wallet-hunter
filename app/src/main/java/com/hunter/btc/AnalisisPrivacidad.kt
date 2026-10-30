package com.hunter.btc

/**
 * Lo que un envío deja ver de ti, antes de confirmarlo. Un analista de cadena busca cuál de las salidas es el
 * cambio (y así sigue tu dinero) y qué monedas son del mismo dueño; esto avisa de las pistas más comunes que
 * puede dar ESTE envío. No es un análisis completo: sólo lo que se sabe sin mirar más la cadena.
 * Puro: AnalisisPrivacidadTest.
 */
object AnalisisPrivacidad {

    class Aviso(val grave: Boolean, val texto: String)

    class Envio(
        val importeSat: Long,                    // lo que se paga al destino principal
        val cambioSat: Long,                     // 0 = sin cambio
        val tipoCambio: BtcAddress.Type?,        // el tipo de dirección del cambio
        val tiposDestino: List<BtcAddress.Type>, // los tipos de los destinos (principal y extra)
        val valoresEntradas: List<Long>,         // las monedas que se gastan
        val monedasEnOrigen: Int,                // cuántas monedas tiene la dirección de origen
        val cambioAlOrigen: Boolean,             // el cambio vuelve a la dirección de origen
        val conDatos: Boolean,                   // lleva un OP_RETURN
    )

    /** "Redondo" para un humano: múltiplo de 100 000 sat (0,001 BTC). */
    fun redondo(sat: Long) = sat > 0 && sat % 100_000 == 0L

    fun revisar(e: Envio): List<Aviso> {
        val a = ArrayList<Aviso>()
        if (e.valoresEntradas.any { it in 1..MonedasCongeladas.POLVO })
            a.add(Aviso(true, "It spends a tiny coin (possible dust attack): whoever sent it can now link this payment to you."))
        if (e.cambioSat > 0 && e.cambioAlOrigen)
            a.add(Aviso(true, "The change goes back to the address you pay from: anyone can see which output is yours."))
        if (e.cambioSat > 0 && redondo(e.importeSat) && !redondo(e.cambioSat))
            a.add(Aviso(false, "The payment is a round amount and the change is not: that tells which output is the change."))
        if (e.cambioSat > 0 && e.tipoCambio != null && e.tiposDestino.isNotEmpty() && e.tiposDestino.all { it != e.tipoCambio })
            a.add(Aviso(false, "The change is a ${nombre(e.tipoCambio)} address and the payment ${nombre(e.tiposDestino[0])}: the different type gives the change away."))
        if (e.monedasEnOrigen > 1)
            a.add(Aviso(false, "The source address received more than once (reused): all its payments are already linked."))
        if (e.conDatos)
            a.add(Aviso(false, "It includes data (OP_RETURN) that everyone can read forever."))
        return a.sortedByDescending { it.grave }
    }

    private fun nombre(t: BtcAddress.Type) = when (t) {
        BtcAddress.Type.P2PKH -> "legacy (1…)"; BtcAddress.Type.P2SH -> "nested (3…)"; BtcAddress.Type.P2WPKH -> "SegWit (bc1q)"
        BtcAddress.Type.P2WSH -> "script (bc1q…)"; BtcAddress.Type.P2TR -> "Taproot (bc1p)"
    }

    /** El tipo de las direcciones de una clave de la lista de la cartera (p2wpkh_3, p2tr_ch_1…). */
    fun tipoDeClave(k: String): BtcAddress.Type? = when {
        k.startsWith("p2pkh") -> BtcAddress.Type.P2PKH; k.startsWith("p2sh") -> BtcAddress.Type.P2SH
        k.startsWith("p2wpkh") -> BtcAddress.Type.P2WPKH; k.startsWith("p2tr") -> BtcAddress.Type.P2TR
        else -> null
    }
}
