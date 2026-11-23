package com.hunter.btc

/**
 * Bot IA del simulador (VUSD): en cada revisión baja las velas de la temporalidad
 * de cada bot, quita la vela que aún se está formando y pasa el motor local de
 * [AnalisisIA] por las cerradas. La decisión la aplica [Simulador.senalIA] en el
 * hilo de la pantalla (o del job), una vez por vela cerrada.
 */
object BotIA {
    class Tarea(val id: Long, val clave: String, val activo: Mercado.Activo, val iv: String, val ultVela: Long)
    class Senal(val id: Long, val clave: String, val dir: Int, val confianza: Int, val stop: Double, val objetivo: Double,
                val apal: Int, val precio: Double, val velaT: Long)

    fun esScalp(iv: String) = iv == "1m" || iv == "5m"
    fun duracion(iv: String): Long = when (iv) { "1m" -> 60L; "5m" -> 300L; "15m" -> 900L; "1h" -> 3600L; "4h" -> 14_400L; "1d" -> 86_400L; else -> 3600L }

    /** Qué bots IA toca revisar ahora (hilo de la UI: lee el simulador). */
    fun tareas(sim: Simulador, ahora: Long = System.currentTimeMillis() / 1000): List<Tarea> =
        sim.bots.filter { it.tipo == "IA" }.mapNotNull { b ->
            // la siguiente vela cerrada termina en ultVela + 2·duración: antes no hay nada nuevo
            if (b.ultVela > 0 && ahora < b.ultVela + 2 * duracion(b.iv)) return@mapNotNull null
            Mercado.Activo.deJson(b.activo)?.let { Tarea(b.id, b.clave, it, b.iv, b.ultVela) }
        }

    /** Decisión pura sobre velas ya CERRADAS (la última es la más reciente cerrada). */
    fun senalDe(id: Long, clave: String, cerradas: List<GraficoPrecio.Vela>, esCripto: Boolean = true): Senal? {
        if (cerradas.size < 60) return null
        val r = AnalisisIA.evaluar(AnalisisIA.Datos(cerradas, esCripto = esCripto))
        val dir = when (r.veredicto) { AnalisisIA.Veredicto.LONG -> 1; AnalisisIA.Veredicto.SHORT -> -1; else -> 0 }
        val p = r.plan
        return Senal(id, clave, dir, r.confianza, p?.stop ?: 0.0, p?.objetivo1 ?: 0.0, p?.apalancamientoMax ?: 1,
            cerradas.last().c.toDouble(), cerradas.last().t)
    }

    /** Red (hilo de fondo). */
    fun calcular(tareas: List<Tarea>): List<Senal> = tareas.mapNotNull { k ->
        val v = try { Mercado.velas(k.activo, k.iv, if (esScalp(k.iv)) 201 else 251) } catch (e: Throwable) { emptyList() }
        if (v.size < 2) return@mapNotNull null
        val cerradas = v.dropLast(1)
        if (cerradas.last().t <= k.ultVela) return@mapNotNull null
        if (esScalp(k.iv)) {
            val libro = try { Scalping.desequilibrio(Mercado.libro(k.activo, 50)) } catch (e: Throwable) { null }
            senalScalp(k.id, k.clave, cerradas, libro)
        } else senalDe(k.id, k.clave, cerradas, k.activo.tipo == Mercado.Tipo.CRYPTO)
    }

    /** Modo scalping (1m/5m): señal rápida de [Scalping] con el desequilibrio del libro. */
    fun senalScalp(id: Long, clave: String, cerradas: List<GraficoPrecio.Vela>, libro: Double?): Senal? {
        val s = Scalping.senal(cerradas, libro) ?: return null
        return Senal(id, clave, s.dir, s.confianza, s.stop, s.objetivo, s.apal, cerradas.last().c.toDouble(), cerradas.last().t)
    }

    /** Aplica las señales (mismo hilo que el resto del simulador). */
    fun aplicar(sim: Simulador, senales: List<Senal>): Boolean {
        var cambio = false
        for (s in senales) {
            val precio = sim.precios[s.clave]?.takeIf { it > 0 } ?: s.precio
            cambio = sim.senalIA(s.id, s.dir, s.confianza, s.stop, s.objetivo, s.apal, precio, s.velaT) or cambio
        }
        return cambio
    }
}
