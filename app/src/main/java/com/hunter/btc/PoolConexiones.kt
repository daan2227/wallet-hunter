package com.hunter.btc

/**
 * Conexiones ya abiertas, a la espera de la siguiente consulta al mismo servidor.
 *
 * Cada consulta a Electrum abría un TLS entero (varios viajes de ida y vuelta) para una sola línea de
 * respuesta; con el recorrido HD en paralelo y por Tor eran decenas de apretones de manos. Aquí se
 * guardan unos pocos por servidor y se tiran si llevan parados más de [inactivoMs] (los servidores
 * cierran las que no usan). Genérica y sin red, para probarla en la JVM.
 */
class PoolConexiones<C : Any>(
    private val maxPorClave: Int,
    private val inactivoMs: Long,
    private val reloj: () -> Long = { System.currentTimeMillis() },
    private val cerrar: (C) -> Unit,
) {
    private class Libre<C>(val c: C, val desde: Long)
    private val libres = HashMap<String, ArrayDeque<Libre<C>>>()

    /** La más reciente libre de [clave], o null. Quien la toma es su dueño hasta que la devuelva o la cierre. */
    fun tomar(clave: String): C? {
        val caducadas = ArrayList<C>()
        val c = synchronized(this) {
            purgar(caducadas)
            libres[clave]?.removeLastOrNull()?.c
        }
        caducadas.forEach(cerrar)
        return c
    }

    /** Deja [c] libre para otra consulta. Si ya hay [maxPorClave], se cierra la más antigua. */
    fun devolver(clave: String, c: C) {
        val sobran = ArrayList<C>()
        synchronized(this) {
            purgar(sobran)
            val q = libres.getOrPut(clave) { ArrayDeque() }
            q.addLast(Libre(c, reloj()))
            while (q.size > maxPorClave) sobran.add(q.removeFirst().c)
        }
        sobran.forEach(cerrar)
    }

    /** Cierra todas (p. ej. al cambiar de red o de proxy: no deben seguir saliendo por el camino viejo). */
    fun vaciar() {
        val todas = ArrayList<C>()
        synchronized(this) { libres.values.forEach { q -> q.forEach { todas.add(it.c) } }; libres.clear() }
        todas.forEach(cerrar)
    }

    fun libres(): Int = synchronized(this) { libres.values.sumOf { it.size } }

    private fun purgar(salida: MutableList<C>) {
        val ahora = reloj()
        val it = libres.values.iterator()
        while (it.hasNext()) {
            val q = it.next()
            while (q.isNotEmpty() && ahora - q.first().desde >= inactivoMs) salida.add(q.removeFirst().c)
            if (q.isEmpty()) it.remove()
        }
    }
}
