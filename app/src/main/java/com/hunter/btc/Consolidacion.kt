package com.hunter.btc

/**
 * ¿Merece la pena juntar ahora las monedas de una dirección en una sola? Cada moneda que se gasta paga su
 * tamaño en comisión; juntarlas cuando la red está barata ahorra pagar ese tamaño luego, cuando esté cara.
 * Puro: ConsolidacionTest.
 */
object Consolidacion {
    /** La tasa con la que se compara "luego": un día con la red ocupada. */
    const val TASA_REFERENCIA = 20L

    class Plan(
        val usar: List<Long>,        // los valores de las monedas que se juntan
        val dejar: List<Long>,       // las que no compensa gastar (valen menos que su comisión)
        val vsize: Long,
        val comision: Long,          // lo que cuesta ahora
        val recibe: Long,            // lo que queda en la moneda nueva
        val ahorroLuego: Long,       // lo que se ahorra gastándolas luego a TASA_REFERENCIA (neto de lo de ahora)
        val compensa: Boolean,
    )

    /**
     * [valores] de las monedas (sin congeladas), [tasa] la de ahora (sat/vB), [vbEntrada]/[vbSalida] los tamaños
     * de una entrada y de la salida de ese tipo de dirección. null si hay menos de dos que compense juntar.
     */
    fun evaluar(valores: List<Long>, tasa: Long, vbEntrada: Long, vbSalida: Long, ref: Long = TASA_REFERENCIA): Plan? {
        require(tasa > 0 && vbEntrada > 0)
        // Una moneda que vale menos que lo que cuesta gastarla, aunque sea luego a la tasa de ahora, se deja.
        val (usar, dejar) = valores.partition { it > tasa * vbEntrada * 2 }
        if (usar.size < 2) return null
        val vsize = vbEntrada * usar.size + vbSalida + 11
        val comision = tasa * vsize
        val recibe = usar.sum() - comision
        if (recibe <= CoinSelector.DUST) return null
        // Luego: gastar n entradas a ref contra gastar 1 (la consolidada) a ref y haber pagado la consolidación.
        val ahorro = (usar.size - 1) * vbEntrada * ref - comision
        return Plan(usar, dejar, vsize, comision, recibe, ahorro, ahorro > 0 && tasa < ref)
    }
}
