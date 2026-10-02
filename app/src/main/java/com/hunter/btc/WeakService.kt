package com.hunter.btc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Servicio mínimo en primer plano para que la auditoría de claves débiles NO se
 * pare al apagar la pantalla o minimizar la app.
 *
 * Solo mantiene el proceso vivo (primer plano) y la CPU despierta (WakeLock); el
 * trabajo sigue en WeakController, en el proceso principal. A diferencia de
 * HunterService NO lleva gobernador térmico/batería ni toca el motor: ese
 * gobernador está pensado para el motor único del scanner/puzzle y chocaría con
 * el bucle por claves del weak (podría dar por "no encontrada" una clave que
 * solo se pausó). Se arranca al empezar la auditoría y se para al terminar.
 */
class WeakService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val CANAL = "weak_fg"
        private const val NOTIF = 11

        fun iniciar(ctx: Context) {
            val i = Intent(ctx, WeakService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Throwable) {
                try { ctx.startService(i) } catch (e2: Throwable) {}
            }
        }

        fun parar(ctx: Context) {
            try { ctx.stopService(Intent(ctx, WeakService::class.java)) } catch (e: Throwable) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        // startForeground lo PRIMERO y protegido: si falla (restricción de primer
        // plano, etc.) se para solo en vez de dejar que el sistema tumbe la app.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CANAL, "Weak-key audit", NotificationManager.IMPORTANCE_LOW)
                        .apply { setSound(null, null); enableVibration(false) })
            }
            startForeground(NOTIF, notif())
        } catch (e: Throwable) {
            try { stopSelf() } catch (e2: Throwable) {}
            return
        }
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WalletHunter::WeakWakeLock")
                .also { it.acquire() }
        } catch (e: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
    // No sticky: es solo un keep-alive mientras dura la auditoría; si muere el
    // proceso, la auditoría se pierde igual (reanudar tras morir sería otra cosa).
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun notif(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CANAL)
            .setContentTitle("Weak-key audit running")
            .setContentText("Keeping the audit alive with the screen off.")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
