package com.hunter.btc

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * El DEx con la app cerrada: cada ~15 min (el mínimo de Android) el sistema
 * despierta [DexJob], que revisa con velas de 1 min desde la última revisión las
 * órdenes, TP/SL, liquidaciones, bots y Earn del simulador, y las alertas de
 * precio, y avisa con una notificación de lo que haya pasado. Con la app delante
 * no hace nada (lo hace la pantalla en vivo) para no pisar su estado.
 */
object VigilanciaDex {
    private const val JOB_ID = 4420
    /** Último momento (ms) en que el DEx estaba en primer plano; lo pone la pantalla. */
    @Volatile var primerPlanoMs = 0L

    fun programar(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, DexJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(15 * 60 * 1000L)
            .setPersisted(true)
            .build())
    }

    /** Hay algo que vigilar: posiciones, órdenes, bots, Earn o alertas. */
    private fun hayAlgo(ctx: Context, sim: Simulador) =
        sim.clavesActivas().isNotEmpty() || sim.earn > 0 || Alertas.lista(ctx).isNotEmpty()

    /** Revisión completa (hilo de fondo). Devuelve los avisos generados. */
    fun revisar(ctx: Context): List<String> {
        if (System.currentTimeMillis() - primerPlanoMs < 60_000) return emptyList()
        val prefs = ctx.getSharedPreferences("simulador", Context.MODE_PRIVATE)
        val estado = prefs.getString("estado", null)
        val sim = Simulador.deJson(estado)
        if (!hayAlgo(ctx, sim)) return emptyList()
        val ahora = System.currentTimeMillis() / 1000
        for (k in sim.clavesActivas()) {
            val a = Mercado.Activo.deJson(sim.activoDe(k)) ?: continue
            val desde = sim.revisado[k] ?: ahora
            val n = ((ahora - desde) / 60 + 3).toInt().coerceIn(3, 1000)
            val v = try { Mercado.velas(a, "1m", n) } catch (e: Throwable) { emptyList() }
            if (v.isEmpty()) continue
            for (c in v) if (c.t >= desde) sim.procesar(k, c.l.toDouble(), c.h.toDouble(), c.c.toDouble(), c.t + 60, c.t)
            val u = v.last().c.toDouble(); sim.procesar(k, u, u, u, ahora)
        }
        sim.devengar(ahora)
        val avisos = ArrayList(sim.eventos); sim.eventos.clear()
        // si mientras tanto se abrió la app, no se pisa lo que ella guardó (ella misma lo revisará)
        if (System.currentTimeMillis() - primerPlanoMs >= 60_000 && prefs.getString("estado", null) == estado) {
            prefs.edit().putString("estado", sim.aJson()).apply()
            if (avisos.isNotEmpty()) Alertas.notificarTexto(ctx, "Test exchange (VUSD)", avisos.joinToString("\n"))
        } else avisos.clear()
        // alertas de precio: último precio de 1 min de cada par
        for ((clave, js) in Alertas.claves(ctx)) {
            val a = Mercado.Activo.deJson(js) ?: continue
            val p = try { Mercado.velas(a, "1m", 2).lastOrNull()?.c?.toDouble() } catch (e: Throwable) { null } ?: continue
            Alertas.comprobar(ctx, clave, p)
        }
        return avisos
    }
}

/** Lo despierta el sistema cada ~15 min con red (ver VigilanciaDex.programar). */
class DexJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try { VigilanciaDex.revisar(this) } catch (e: Exception) { RegistroFallos.anotar("DexJob", e) }
            jobFinished(params, false)
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters) = true
}
