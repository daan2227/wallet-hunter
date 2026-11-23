package com.hunter.btc

/**
 * Cartera y riesgo de la cuenta de prueba (cálculo puro, probado en la JVM):
 * reparto del patrimonio, exposición larga/corta/neta, pérdida máxima si saltan
 * todos los stops (o liquidación si no hay stop) y correlación de rendimientos
 * diarios entre los activos que se tienen.
 */
object Riesgo {
    class Parte(val nombre: String, val valor: Double)
    class Exposicion(val larga: Double, val corta: Double) {
        val neta get() = larga - corta
        val bruta get() = larga + corta
    }
    /** [perdida] ≤ 0 en VUSD si se tocan todos los stops; [sinStop] posiciones que se cerrarían por liquidación; [spotSinProteger] valor spot sin stop. */
    class PeorCaso(val perdida: Double, val sinStop: Int, val spotSinProteger: Double)

    fun reparto(sim: Simulador, maxPartes: Int = 6): List<Parte> {
        val partes = ArrayList<Parte>()
        val caja = sim.spot + sim.fut + sim.ordenes.filter { it.mercado == Simulador.Mercado.FUT || it.compra }.sumOf { it.reservado }
        partes.add(Parte("VUSD cash", caja))
        if (sim.earn > 0) partes.add(Parte("Earn", sim.earn))
        val porActivo = LinkedHashMap<String, Double>()
        for (t in sim.tenencias) porActivo[t.simbolo] = (porActivo[t.simbolo] ?: 0.0) + t.qty * (sim.precios[t.clave] ?: (t.coste / t.qty.coerceAtLeast(1e-18)))
        for (o in sim.ordenes) if (o.mercado == Simulador.Mercado.SPOT && !o.compra)
            porActivo[o.simbolo] = (porActivo[o.simbolo] ?: 0.0) + o.reservado * (sim.precios[o.clave] ?: o.precio)
        for (b in sim.bots) if (b.tipo == "GRID") porActivo["${b.simbolo} grid"] = b.valor(sim.precios[b.clave] ?: 0.0)
        val fut = sim.posiciones.sumOf { it.margen } + sim.pnlNoRealizado()
        val ordenadas = porActivo.entries.filter { it.value > 1e-9 }.sortedByDescending { it.value }
        val hueco = (maxPartes - partes.size - (if (fut > 1e-9) 1 else 0)).coerceAtLeast(1)
        ordenadas.take(hueco).forEach { partes.add(Parte(it.key, it.value)) }
        val resto = ordenadas.drop(hueco).sumOf { it.value }
        if (resto > 1e-9) partes.add(Parte("Other", resto))
        if (fut > 1e-9) partes.add(Parte("Futures margin", fut))
        return partes.filter { it.valor > 1e-9 }.sortedByDescending { it.valor }
    }

    fun exposicion(sim: Simulador): Exposicion {
        var l = 0.0; var c = 0.0
        for (p in sim.posiciones) { val v = p.qty * (sim.precios[p.clave] ?: p.entrada); if (p.long) l += v else c += v }
        l += sim.valorSpot() + sim.bots.filter { it.tipo == "GRID" }.sumOf { it.base * (sim.precios[it.clave] ?: 0.0) }
        return Exposicion(l, c)
    }

    /** Resultado desde AHORA si cada posición se cierra en su stop (o se liquida sin él). */
    fun peorCaso(sim: Simulador): PeorCaso {
        var perdida = 0.0; var sinStop = 0
        for (p in sim.posiciones) {
            val ahora = sim.precios[p.clave] ?: p.entrada
            if (p.sl > 0) perdida += p.pnl(p.sl) - p.pnl(ahora) - p.sl * p.qty * Simulador.COM_TAKER
            else { sinStop++; perdida += -(p.margen + p.pnl(ahora)).coerceAtLeast(0.0) }
        }
        return PeorCaso(perdida, sinStop, sim.valorSpot())
    }

    /** Correlación de Pearson de los rendimientos simples de dos series de cierres alineadas. */
    fun correlacion(a: DoubleArray, b: DoubleArray): Double? {
        val n = minOf(a.size, b.size)
        if (n < 6) return null
        val x = DoubleArray(n - 1) { a[a.size - n + it + 1] / a[a.size - n + it] - 1 }
        val y = DoubleArray(n - 1) { b[b.size - n + it + 1] / b[b.size - n + it] - 1 }
        val mx = x.average(); val my = y.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in x.indices) { val dx = x[i] - mx; val dy = y[i] - my; sxy += dx * dy; sxx += dx * dx; syy += dy * dy }
        if (sxx <= 0 || syy <= 0) return null
        return sxy / Math.sqrt(sxx * syy)
    }

    /** Activos en riesgo (spot, posiciones, grid): clave → activo (JSON). */
    fun activos(sim: Simulador): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (t in sim.tenencias) m[t.clave] = t.activo
        for (p in sim.posiciones) m[p.clave] = p.activo
        for (b in sim.bots) if (b.tipo == "GRID") m[b.clave] = b.activo
        return m.filterValues { it.isNotEmpty() }
    }
}
