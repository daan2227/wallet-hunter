package com.hunter.btc

import org.json.JSONArray

/**
 * ¿Se conoce la clave pública de esta dirección?
 *
 * De esto depende si un puzzle tiene atajo o no, y la diferencia son seis
 * órdenes de magnitud:
 *
 *  - **Sólo la dirección.** Una dirección no contiene la clave pública, sino
 *    RIPEMD160(SHA256(pub)). Es de un solo sentido: no hay forma de volver
 *    atrás. Lo único que queda es probar claves privadas una a una — O(n).
 *    Para el rango del puzzle #70 son 2^69 pruebas: millones de años.
 *
 *  - **Con la clave pública.** Ya tienes un punto de la curva, y entonces sirve
 *    Pollard's Kangaroo, que resuelve el logaritmo discreto en O(raiz(n)). Para
 *    ese mismo rango son unas 2^35 operaciones: horas.
 *
 * La clave pública aparece cuando la dirección GASTA. Para que la red valide el
 * pago hay que enseñarla junto a la firma, así que queda escrita en la cadena
 * para siempre. Una dirección que sólo ha recibido no la ha revelado nunca.
 *
 * Todo hace red: nunca desde el hilo principal.
 */
object PubKeyFinder {

    sealed class Resultado {
        /**
         * La clave pública está publicada.
         * @param pubHex comprimida (33 bytes) o sin comprimir (65), en hex
         * @param txid la transacción donde se gastó y quedó al descubierto
         */
        data class Encontrada(val pubHex: String, val txid: String) : Resultado()
        /** La dirección existe pero nunca ha gastado: no hay atajo posible. */
        object NoRevelada : Resultado()
        /** No se pudo mirar. NO es lo mismo que "no revelada". */
        object SinRed : Resultado()
    }

    /**
     * Saca todos los "pushes" de un script.
     *
     * Un scriptSig de P2PKH es `<push firma> <push clave>`. Se recorren los
     * opcodes en vez de partir por longitudes fijas porque la firma DER mide
     * entre 70 y 72 bytes según los ceros que lleven r y s.
     */
    private fun pushes(script: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i < script.size) {
            val op = script[i].toInt() and 0xff
            i++
            val n = when {
                op in 1..75 -> op
                op == 0x4c  -> { // OP_PUSHDATA1
                    if (i >= script.size) return out
                    val v = script[i].toInt() and 0xff; i += 1; v
                }
                op == 0x4d  -> { // OP_PUSHDATA2
                    if (i + 1 >= script.size) return out
                    val v = (script[i].toInt() and 0xff) or
                            ((script[i+1].toInt() and 0xff) shl 8); i += 2; v
                }
                // Cualquier otro opcode no empuja datos que nos interesen.
                else -> 0
            }
            if (n <= 0) continue
            if (i + n > script.size) return out
            out.add(script.copyOfRange(i, i + n))
            i += n
        }
        return out
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun deHex(s: String): ByteArray? = try {
        if (s.length % 2 != 0) null
        else ByteArray(s.length / 2) { s.substring(it*2, it*2+2).toInt(16).toByte() }
    } catch (e: Exception) { null }

    /** ¿Tiene forma de clave pública de secp256k1? */
    private fun pareceClave(b: ByteArray) =
        (b.size == 33 && (b[0].toInt() == 2 || b[0].toInt() == 3)) ||
        (b.size == 65 && b[0].toInt() == 4)

    /**
     * Busca la clave pública de [addr] en su historial.
     *
     * No se fía de que el servidor diga a qué dirección pertenece cada entrada:
     * se recorre TODO push de TODO scriptSig y de TODO testigo, y se queda con
     * el que al hacerle hash160 da exactamente el de esta dirección. Así, si el
     * servidor se equivoca o miente, o si el script se lee mal, el resultado es
     * "no encontrada" en vez de una clave que no es — que es el fallo que
     * dejaría a una búsqueda corriendo eternamente a por nada.
     */
    fun buscar(addr: String, testnet: Boolean = false): Resultado {
        val info = (BtcAddress.validate(addr, testnet) as? BtcAddress.Result.Valid)?.info
            ?: return Resultado.NoRevelada
        // En Taproot la dirección ES la clave (x-only, 32 bytes): no hay que
        // buscar nada, ya está a la vista.
        if (info.type == BtcAddress.Type.P2TR)
            return Resultado.Encontrada(hex(info.program), "")
        val objetivo = info.program   // hash160 para P2PKH y P2SH, o el programa

        val cuerpo = ChainApi.get("/address/$addr/txs", testnet) ?: return Resultado.SinRed
        val arr = try { JSONArray(cuerpo) } catch (e: Exception) { return Resultado.SinRed }

        for (i in 0 until arr.length()) {
            val tx = arr.optJSONObject(i) ?: continue
            val txid = tx.optString("txid", "")
            val vins = tx.optJSONArray("vin") ?: continue
            for (j in 0 until vins.length()) {
                val vin = vins.optJSONObject(j) ?: continue
                val candidatos = mutableListOf<ByteArray>()
                deHex(vin.optString("scriptsig", ""))?.let { candidatos += pushes(it) }
                // SegWit no lleva la clave en el scriptSig sino en el testigo,
                // donde es el segundo elemento.
                vin.optJSONArray("witness")?.let { w ->
                    for (k in 0 until w.length()) deHex(w.optString(k, ""))?.let { candidatos += it }
                }
                for (c in candidatos) {
                    if (!pareceClave(c)) continue
                    if (Ripemd160.hash160(c).contentEquals(objetivo))
                        return Resultado.Encontrada(hex(c), txid)
                }
            }
        }
        // Hubo respuesta y en ninguna entrada aparece una clave que encaje:
        // esta dirección no ha gastado nunca.
        return Resultado.NoRevelada
    }
}
