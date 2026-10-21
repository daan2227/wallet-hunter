package com.hunter.btc

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

/**
 * Mantiene vivo el nodo con la pantalla apagada: servicio en primer plano (como HunterService) y un
 * wake lock con caducidad mientras el nodo se está poniendo al día.
 *
 * No se reinicia solo (START_NOT_STICKY) ni arranca con el móvil: un nodo que baja gigas lo enciende
 * el usuario a propósito. Android 15 limita los servicios dataSync a unas 6 h seguidas; si lo para, basta
 * volver a pulsar Start: bitcoind retoma donde estaba.
 */
class CoreService : Service() {

    companion object {
        const val CHANNEL = "wh_core"
        const val NOTIF = 12
        const val ACCION_PARAR = "com.hunter.btc.CORE_PARAR"
        @Volatile var activo = false

        fun arrancar(ctx: Context) {
            val i = Intent(ctx, CoreService::class.java)
            try { ctx.startForegroundService(i) } catch (e: Exception) {
                android.util.Log.e("CoreService", "start: ${e.javaClass.simpleName}")
            }
        }
        fun parar(ctx: Context) {
            try { ctx.startService(Intent(ctx, CoreService::class.java).setAction(ACCION_PARAR)) }
            catch (e: Exception) { android.util.Log.e("CoreService", "stop: ${e.javaClass.simpleName}") }
        }
    }

    private var hilo: Thread? = null
    @Volatile private var terminar = false
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == ACCION_PARAR) {
            terminar = true
            Thread {
                CoreNodo.parar(applicationContext)
                hilo?.join(5_000)
                stopSelf()
            }.start()
            return START_NOT_STICKY
        }
        crearCanal()
        startForeground(NOTIF, notif("Bitcoin Core", "Starting…"))
        if (hilo?.isAlive == true) return START_NOT_STICKY
        activo = true; terminar = false
        hilo = Thread { bucle() }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun bucle() {
        val err = CoreNodo.arrancar(this)
        if (err != null) {
            aviso("Bitcoin Core did not start", err)
            soltar(); activo = false; stopForeground(STOP_FOREGROUND_DETACH); stopSelf(); return
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WalletHunter::Core").also { it.setReferenceCounted(false) }
        try {
            while (!terminar && CoreNodo.procesoVivo()) {
                try { Thread.sleep(8_000) } catch (e: InterruptedException) { break }
                if (CoreNodo.ajustes(this).soloWifi && CoreNodo.redDeDatos(this)) {
                    aviso("Bitcoin Core paused", "Mobile data detected: Wi-Fi only is on.")
                    CoreNodo.parar(this); break
                }
                try {
                    val r = CoreNodo.rpc(this)
                    val e = CoreConfig.leerEstado(r.objeto("getblockchaininfo"), null, null)
                    val atras = e.descargaInicial || e.bloques < e.cabeceras
                    // El wake lock sólo mientras se pone al día; al día, el nodo apenas trabaja.
                    if (atras) wake?.acquire(10 * 60 * 1000L) else soltar()
                    val txt = if (atras) "Syncing ${"%.1f".format(e.progreso * 100)} % · block ${e.bloques}"
                              else "Synced · block ${e.bloques}"
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(NOTIF, notif("Bitcoin Core", txt))
                } catch (e: CoreRpcError) { /* aún arrancando */ }
                  catch (e: java.io.IOException) { /* aún sin puerto */ }
            }
        } finally {
            soltar(); activo = false
            if (!CoreNodo.paradaPedida && !terminar)
                aviso("Bitcoin Core stopped", "The node exited. Open the Core tab to see why.")
            stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
        }
    }

    private fun soltar() { try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) {} }

    private fun crearCanal() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Bitcoin Core", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false) })
    }

    private fun notif(titulo: String, texto: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setContentTitle(titulo).setContentText(texto)
            .setSmallIcon(R.drawable.ic_notif).setContentIntent(pi).setOngoing(true)
            .setOnlyAlertOnce(true).build()
    }

    private fun aviso(titulo: String, texto: String) {
        crearCanal()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF + 1,
            Notification.Builder(this, CHANNEL).setContentTitle(titulo).setContentText(texto)
                .setSmallIcon(R.drawable.ic_notif).build())
    }

    override fun onDestroy() {
        // Si Android destruye el servicio sin que nadie pidiera parar, que el nodo cierre limpio.
        if (CoreNodo.procesoVivo() && !CoreNodo.paradaPedida) Thread { CoreNodo.parar(applicationContext) }.start()
        soltar(); activo = false
        super.onDestroy()
    }
}
