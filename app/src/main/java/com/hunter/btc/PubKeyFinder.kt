package com.hunter.btc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ¿Se conoce la clave pública de esta dirección?
 *
 * De esto depende si un puzzle tiene atajo o no, y la diferencia son seis
 * órdenes de magnitud:
 *
 *  - **Sólo la dirección.** Una dirección no contiene la clave pública, sino
 *    RIPEMD160(SHA256(pub)). Es de un solo sentido. Lo único que queda es
 *    probar claves privadas una a una — O(n). Para el rango del puzzle #70 son
 *    2^69 pruebas: millones de años.
 *
 *  - **Con la clave pública.** Ya tienes un punto de la curva, y entonces sirve
 *    Pollard's Kangaroo, O(raíz(n)). Ese mismo rango baja a unas 2^35
 *    operaciones: horas.
 *
 * La clave aparece cuando la dirección GASTA: para validar el pago hay que
 * enseñarla junto a la firma, y queda en la cadena para siempre.
 *
 * Todo hace red: nunca desde el hilo principal.
 */
object PubKeyFinder {

    /** Páginas de historial que se miran como mucho. 25 transacciones cada una. */
    private const val MAX_PAGINAS = 12

    private const val PREFS = "pubkey_cache"

    sealed class Resultado {
        /**
         * La clave pública está publicada y se ha podido leer.
         * @param pubHex comprimida (33 bytes) o sin comprimir (65), en hex
         * @param txid la transacción donde quedó al descubierto
         */
        data class Encontrada(val pubHex: String, val txid: String) : Resultado()
        /** Nunca ha gastado. No hay atajo posible; esto es definitivo. */
        object NoRevelada : Resultado()
        /**
         * Ha gastado —o sea, la clave ESTÁ publicada— pero no se ha podido
         * extraer del trozo de historial que se ha mirado.
         *
         * No es lo mismo que [NoRevelada]: aquí el atajo existe, lo que falta es
         * encontrar la transacción. Decir "no hay atajo" sería mentir.
         */
        data class Publicada(val gastos: Int) : Resultado()
        /** No se pudo mirar. Tampoco es lo mismo que "no revelada". */
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
                op == 0x4c  -> {
                    if (i >= script.size) return out
                    val v = script[i].toInt() and 0xff; i += 1; v
                }
                op == 0x4d  -> {
                    if (i + 1 >= script.size) return out
                    val v = (script[i].toInt() and 0xff) or
                            ((script[i+1].toInt() and 0xff) shl 8); i += 2; v
                }
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

    private fun pareceClave(b: ByteArray) =
        (b.size == 33 && (b[0].toInt() == 2 || b[0].toInt() == 3)) ||
        (b.size == 65 && b[0].toInt() == 4)

    /**
     * Recorre una página de transacciones buscando la clave.
     *
     * No se fía de a qué dirección diga el servidor que pertenece cada entrada:
     * mira TODO push de TODO scriptSig y de TODO testigo, y se queda con el que
     * al hacerle hash160 da exactamente el de esta dirección. Si el servidor se
     * equivoca o el script se lee mal, el resultado es "no encontrada" en vez de
     * una clave que no es — que es el fallo que dejaría a Kangaroo corriendo
     * eternamente a por nada.
     *
     * @return la clave y su txid, o null; y el txid del último visto para pedir
     *   la página siguiente.
     */
    private fun escanear(arr: JSONArray, objetivo: ByteArray):
            Pair<Resultado.Encontrada?, String> {
        var ultimo = ""
        for (i in 0 until arr.length()) {
            val tx = arr.optJSONObject(i) ?: continue
            val txid = tx.optString("txid", "")
            if (txid.isNotEmpty()) ultimo = txid
            val vins = tx.optJSONArray("vin") ?: continue
            for (j in 0 until vins.length()) {
                val vin = vins.optJSONObject(j) ?: continue
                val candidatos = mutableListOf<ByteArray>()
                deHex(vin.optString("scriptsig", ""))?.let { candidatos += pushes(it) }
                vin.optJSONArray("witness")?.let { w ->
                    for (k in 0 until w.length()) deHex(w.optString(k, ""))?.let { candidatos += it }
                }
                for (c in candidatos) {
                    if (!pareceClave(c)) continue
                    if (Ripemd160.hash160(c).contentEquals(objetivo))
                        return Resultado.Encontrada(hex(c), txid) to ultimo
                }
            }
        }
        return null to ultimo
    }

    /**
     * Busca la clave pública de [addr].
     *
     * Antes tardaba lo que tardara la red EN CADA TOQUE de puzzle, y encima
     * miraba sólo la primera página del historial. Las dos cosas están
     * arregladas aquí:
     *
     *  1. **Se pregunta primero cuántas veces ha gastado.** Una sola llamada
     *     barata a /address/{addr}, y si el contador es CERO ya está: nunca ha
     *     gastado, no hay clave publicada, y no hay que mirar ni una
     *     transacción. Para la mayoría de los puzzles éste es el caso.
     *
     *  2. **Se guarda el resultado.** Una clave publicada lo está para siempre,
     *     así que la respuesta no cambia: la segunda vez que toques ese puzzle
     *     es instantánea y sin red.
     *
     * Y de paso se arregla un fallo de verdad: /address/{addr}/txs devuelve sólo
     * las 25 confirmadas más recientes. En una dirección de puzzle, que recibe
     * polvo constantemente, el gasto que reveló la clave puede quedar muy atrás
     * — y entonces decía "no revelada" sobre una dirección que sí lo está. Ahora
     * pagina.
     */
    fun buscar(ctx: Context?, addr: String, testnet: Boolean = false): Resultado {
        val info = (BtcAddress.validate(addr, testnet) as? BtcAddress.Result.Valid)?.info
            ?: return Resultado.NoRevelada
        // En Taproot la dirección ES la clave (x-only): no hay nada que buscar.
        if (info.type == BtcAddress.Type.P2TR)
            return Resultado.Encontrada(hex(info.program), "")
        val objetivo = info.program

        val prefs = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs?.getString(addr, null)?.let { guardado ->
            when {
                guardado == "N" -> return Resultado.NoRevelada
                guardado.startsWith("P|") -> {
                    val p = guardado.split("|")
                    if (p.size >= 3) return Resultado.Encontrada(p[1], p[2])
                }
            }
        }

        // 1) ¿Ha gastado alguna vez? Una llamada, y resuelve el caso mayoritario.
        val resumen = ChainApi.get("/address/$addr", testnet) ?: return Resultado.SinRed
        val gastos = try {
            val o = JSONObject(resumen)
            fun n(b: String) = o.optJSONObject(b)?.optInt("spent_txo_count", 0) ?: 0
            n("chain_stats") + n("mempool_stats")
        } catch (e: Exception) { return Resultado.SinRed }

        if (gastos == 0) {
            prefs?.edit()?.putString(addr, "N")?.apply()
            return Resultado.NoRevelada
        }

        // 2) Ha gastado: la clave está publicada. Sólo falta dar con ella.
        var ruta = "/address/$addr/txs"
        for (pagina in 0 until MAX_PAGINAS) {
            val cuerpo = ChainApi.get(ruta, testnet) ?: return Resultado.SinRed
            val arr = try { JSONArray(cuerpo) } catch (e: Exception) { return Resultado.SinRed }
            if (arr.length() == 0) break
            val (hallada, ultimo) = escanear(arr, objetivo)
            if (hallada != null) {
                prefs?.edit()?.putString(addr, "P|${hallada.pubHex}|${hallada.txid}")?.apply()
                return hallada
            }
            if (ultimo.isEmpty()) break
            ruta = "/address/$addr/txs/chain/$ultimo"
        }
        // Ha gastado pero no se ha dado con la entrada. El atajo EXISTE; lo que
        // falta es la transacción. No se guarda: la próxima vez puede salir.
        return Resultado.Publicada(gastos)
    }
}
