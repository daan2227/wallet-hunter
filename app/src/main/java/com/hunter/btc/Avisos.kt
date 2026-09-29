package com.hunter.btc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Los avisos de hallazgo, en un solo sitio.
 *
 * Desde Android 13 una app no puede mostrar notificaciones sin el permiso
 * POST_NOTIFICATIONS concedido en tiempo de ejecución. El manifiesto lo
 * declaraba pero nadie lo pedía, así que en los dos móviles (Android 14)
 * todos los avisos se descartaban en silencio: ni al resolver un puzzle ni al
 * recuperar una frase sonaba nada.
 */
object Avisos {
    const val CANAL = "hunter_match"
    private const val ID = 4242

    fun crearCanal(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CANAL) != null) return
        nm.createNotificationChannel(NotificationChannel(CANAL, "Match Found!",
            NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            enableLights(true)
        })
    }

    /** ¿Puede esta app mostrar avisos ahora mismo? */
    fun permitidos(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            return false
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = nm.getNotificationChannel(CANAL)
            if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    /** Hace falta pedir el permiso (Android 13+ y aún no concedido). */
    fun faltaPermiso(ctx: Context) = Build.VERSION.SDK_INT >= 33 &&
        ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED

    /** Los ajustes de notificaciones de la app, para cuando el permiso se negó. */
    fun abrirAjustes(ctx: Context) {
        val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + ctx.packageName))
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
    }

    /**
     * Aviso de hallazgo. Sólo lo que se puede enseñar: la clave está en el
     * baúl cifrado; en la pantalla de bloqueo, ni eso.
     * @return false si no se pudo mostrar (sin permiso o desactivadas).
     */
    fun hallazgo(ctx: Context, titulo: String, texto: String): Boolean {
        crearCanal(ctx)
        try {
            val vib = ctx.getSystemService(android.os.Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vib?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
        } catch (e: Exception) {}
        if (!permitidos(ctx)) return false
        return try {
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            val publica = NotificationCompat.Builder(ctx, CANAL)
                .setSmallIcon(android.R.drawable.star_on)
                .setContentTitle("Wallet Hunter")
                .setContentText("Match found — unlock to see it")
                .build()
            val n = NotificationCompat.Builder(ctx, CANAL)
                .setSmallIcon(android.R.drawable.star_on)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publica)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(ID, n)
            true
        } catch (e: Exception) { false }
    }
}
