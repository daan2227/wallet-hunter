package com.hunter.btc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * Avisos cuando entra o sale dinero de las direcciones que se vigilan (carteras vigiladas y externas):
 * cada ~30 min, con red, el sistema despierta MovimientosJob, que pregunta por cada dirección y avisa
 * de lo que cambió desde la vez anterior.
 *
 * Apagado por defecto: consultar periódicamente todas las direcciones le cuenta a un servidor cuáles
 * son las tuyas. Con Tor activado (More → Privacy) las consultas van por Tor.
 */
object AvisosMovimientos {
    private const val PREFS = "avisos_movimientos"
    private const val K_ON = "on"
    private const val K_DIRS = "dirs"       // JSON {direccion: etiqueta}
    private const val K_EST = "estados"     // JSON {direccion: "txs|saldo"}
    private const val JOB_ID = 4410
    private const val CANAL = "movimientos"
    private const val MAX_DIRS = 300

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun activos(ctx: Context) = p(ctx).getBoolean(K_ON, false)

    fun poner(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(K_ON, on).apply()
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (!on) { js.cancel(JOB_ID); return }
        js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, MovimientosJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(30 * 60 * 1000L)
            .setPersisted(true)
            .build())
    }

    /** Apunta direcciones a vigilar (la etiqueta es lo que sale en el aviso). */
    @Synchronized
    fun registrar(ctx: Context, dirs: Map<String, String>) {
        if (dirs.isEmpty()) return
        val o = try { JSONObject(p(ctx).getString(K_DIRS, "{}")) } catch (e: Exception) { JSONObject() }
        var cambia = false
        for ((d, e) in dirs) if (o.optString(d) != e && (o.has(d) || o.length() < MAX_DIRS)) { o.put(d, e); cambia = true }
        if (cambia) p(ctx).edit().putString(K_DIRS, o.toString()).apply()
    }

    @Synchronized
    fun quitar(ctx: Context, dirs: Collection<String>) {
        val o = try { JSONObject(p(ctx).getString(K_DIRS, "{}")) } catch (e: Exception) { JSONObject() }
        dirs.forEach { o.remove(it) }
        p(ctx).edit().putString(K_DIRS, o.toString()).apply()
    }

    /** Una pasada: consulta cada dirección y avisa de lo que cambió. Devuelve cuántos avisos dio. */
    fun revisar(ctx: Context): Int {
        val dirs = try { JSONObject(p(ctx).getString(K_DIRS, "{}")) } catch (e: Exception) { JSONObject() }
        val est = try { JSONObject(p(ctx).getString(K_EST, "{}")) } catch (e: Exception) { JSONObject() }
        // Además de las apuntadas, las vigiladas sueltas de la cartera.
        val todas = LinkedHashMap<String, String>()
        dirs.keys().forEach { todas[it] = dirs.optString(it) }
        WalletManager.listWatchers(ctx).forEach { (_, a, l) -> todas.putIfAbsent(a, l) }
        var avisos = 0
        for ((d, etiqueta) in todas) {
            val testnet = d.startsWith("tb1") || d.startsWith("m") || d.startsWith("n") || d.startsWith("2")
            val js = ChainApi.get("/address/$d", testnet) ?: continue
            val ahora = Movimientos.deEsplora(js) ?: continue
            val c = Movimientos.comparar(Movimientos.Estado.de(est.optString(d, null)), ahora)
            est.put(d, ahora.texto())
            if (c != null && notificar(ctx, etiqueta.ifBlank { d.take(10) + "…" }, c, avisos)) avisos++
        }
        p(ctx).edit().putString(K_EST, est.toString()).apply()
        return avisos
    }

    private fun notificar(ctx: Context, quien: String, c: Movimientos.Cambio, n: Int): Boolean {
        if (!Avisos.notifOn(ctx) || !Avisos.permitidos(ctx)) return false
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CANAL) == null)
            nm.createNotificationChannel(NotificationChannel(CANAL, "Payments on watched addresses", NotificationManager.IMPORTANCE_DEFAULT))
        val (titulo, texto) = when (c) {
            is Movimientos.Cambio.Entra -> "Incoming payment" to "+%.8f BTC on %s".format(c.sat / 1e8, quien)
            is Movimientos.Cambio.Sale -> "Outgoing payment" to "−%.8f BTC from %s".format(c.sat / 1e8, quien)
            Movimientos.Cambio.Otro -> "New transaction" to "Activity on $quien"
        }
        return try {
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, WelcomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(4420 + (n % 50), NotificationCompat.Builder(ctx, CANAL)
                .setSmallIcon(R.drawable.ic_notif).setContentTitle(titulo).setContentText(texto)
                .setAutoCancel(true).setContentIntent(pi).build())
            true
        } catch (e: Exception) { false }
    }
}

/** Lo despierta el sistema cada ~30 min con red (ver AvisosMovimientos.poner). */
class MovimientosJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        if (!AvisosMovimientos.activos(this)) return false
        Thread {
            try { AvisosMovimientos.revisar(this) } catch (e: Exception) { RegistroFallos.anotar("MovimientosJob", e) }
            jobFinished(params, false)
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters) = true
}
