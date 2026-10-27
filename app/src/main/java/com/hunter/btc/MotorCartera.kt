package com.hunter.btc

import org.json.JSONObject

/** Una moneda de una cartera externa: dónde está (rama, índice) y lo que vale. */
data class Moneda(val c: Int, val i: Int, val txid: String, val vout: Int, val valor: Long, val confirmada: Boolean = true)

/** Lo usado de una rama y la primera libre (-1 si la red no dejó saberlo), y si quedó incompleto. */
class Rama(val usadas: List<Int>, val primeraLibre: Int, val incompleta: Boolean)

/**
 * Lo común a las carteras cuyas claves están fuera de la app (vigilada por xpub, multifirma, herencia):
 * direcciones, recorrido con hueco, selección de monedas y la PSBT sin firmar. Las firma otro aparato y
 * la app las finaliza y difunde (CarteraExternaActivity).
 */
interface MotorCartera {
    val testnet: Boolean
    val descripcion: String
    val avisos: List<String>
    /** Una moneda de esta cartera necesita la transacción anterior entera en la PSBT (legacy). */
    val necesitaAnterior: Boolean get() = false
    fun texto(): String
    fun spk(c: Int, i: Int): ByteArray?
    fun direccion(c: Int, i: Int): String? = spk(c, i)?.let { SpkDireccion.de(it, testnet) }
    fun vbEntrada(): Int

    /**
     * Recorre la rama [c] con hueco [hueco] (20 por BIP44). [usada] devuelve null si no se pudo saber:
     * eso NO cuenta como libre (como en HdScanner), y seis seguidas cortan el recorrido como incompleto.
     */
    fun escanear(c: Int, usada: (String) -> Boolean?, hueco: Int = 20, tope: Int = 1000): Rama {
        val usadas = ArrayList<Int>(); var libre = -1; var seguidasLibres = 0; var dudas = 0; var i = 0
        while (i < tope && seguidasLibres < hueco) {
            val a = direccion(c, i) ?: return Rama(usadas, -1, true)
            when (usada(a)) {
                true -> { usadas.add(i); libre = -1; seguidasLibres = 0; dudas = 0 }
                false -> { if (libre < 0) libre = i; seguidasLibres++; dudas = 0 }
                null -> { seguidasLibres = 0; if (++dudas >= 6) return Rama(usadas, -1, true) }
            }
            i++
        }
        return Rama(usadas, libre, false)
    }

    fun elegir(monedas: List<Moneda>, destino: String, importe: Long, tasa: Long): Pair<List<Moneda>, CoinSelector.Plan>? {
        val js = monedas.map { m -> JSONObject().put("value", m.valor).put("txid", m.txid).put("vout", m.vout) }
        val cambioEj = direccion(1, 0) ?: return null
        val plan0 = CoinSelector.select(js, importe, tasa, vbEntrada(), CoinSelector.outputVBytes(destino),
            CoinSelector.outputVBytes(cambioEj), CoinSelector.polvoDe(cambioEj)) ?: return null
        val plan = if (plan0.changeSat in 1..CoinSelector.polvoDe(cambioEj)) plan0.copy(feeSat = plan0.feeSat + plan0.changeSat, changeSat = 0) else plan0
        val elegidas = plan.chosen.map { j -> monedas.first { it.txid == j.getString("txid") && it.vout == j.getInt("vout") } }
        return elegidas to plan
    }

    /** La PSBT sin firmar; [anteriores] da la transacción previa de una moneda si [necesitaAnterior]. */
    fun psbt(elegidas: List<Moneda>, destinoSpk: ByteArray, importe: Long, cambio: Long, cambioIndice: Int,
             locktime: Long, anteriores: (Moneda) -> ByteArray?): ByteArray?

    /** Para las P2WSH: la pila del testigo con el script y las firmas, o null si no basta (ver Psbt.finalizar). */
    fun testigoWsh(script: ByteArray, firmas: Map<String, ByteArray>): List<ByteArray>? = null
}
