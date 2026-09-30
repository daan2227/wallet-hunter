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
    // Canal nuevo (v2): silencioso y sin vibración propias, porque ahora la
    // vibración y el sonido los controla el usuario con sus interruptores y los
    // hacemos a mano. Un canal ya creado no cambia sus ajustes, así que hace
    // falta un id nuevo para que esto se aplique en instalaciones antiguas.
    const val CANAL = "hunter_match2"
    // El resumen del grupo lleva un id fijo; cada hallazgo, uno único, para que
    // se ACUMULEN en vez de pisarse. Antes todos usaban 4242 y solo se veía uno.
    private const val ID_RESUMEN = 4242
    private const val GRUPO = "hunter_finds"
    private val contador = java.util.concurrent.atomic.AtomicInteger(0)

    private const val PREFS = "avisos"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun notifOn(ctx: Context) = prefs(ctx).getBoolean("notif", true)
    fun vibrarOn(ctx: Context) = prefs(ctx).getBoolean("vibrar", true)
    fun sonidoOn(ctx: Context) = prefs(ctx).getBoolean("sonido", true)
    fun setNotif(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("notif", v).apply()
    fun setVibrar(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("vibrar", v).apply()
    fun setSonido(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("sonido", v).apply()

    fun crearCanal(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        // Borra el canal viejo (vibraba y sonaba por su cuenta) si sigue ahí.
        try { nm.deleteNotificationChannel("hunter_match") } catch (e: Exception) {}
        if (nm.getNotificationChannel(CANAL) != null) return
        nm.createNotificationChannel(NotificationChannel(CANAL, "Match Found!",
            NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(false)
            setSound(null, null)
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
        // Vibración y sonido son independientes del aviso visual y de que haya
        // permiso de notificaciones: cada uno lo enciende su propio interruptor.
        if (vibrarOn(ctx)) try {
            val vib = ctx.getSystemService(android.os.Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vib?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
        } catch (e: Exception) {}
        if (sonidoOn(ctx)) try { Sonido.moneda(ctx) } catch (e: Throwable) {}
        if (!notifOn(ctx)) return false        // el usuario desactivó el aviso visual
        if (!permitidos(ctx)) return false
        return try {
            val total = contador.incrementAndGet()   // cuántos van en esta sesión
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            val publica = NotificationCompat.Builder(ctx, CANAL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Wallet Hunter")
                .setContentText("Match found — unlock to see it")
                .build()
            // Cada hallazgo, su propia notificación (id único) dentro del grupo,
            // para que se acumulen en vez de pisarse.
            val n = NotificationCompat.Builder(ctx, CANAL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publica)
                .setGroup(GRUPO)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            // Resumen del grupo: uno solo, con el total; así el sistema colapsa
            // los individuales bajo "N found" cuando hay varios.
            val resumen = NotificationCompat.Builder(ctx, CANAL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Wallet Hunter")
                .setContentText("$total key(s) found — in the finds vault")
                .setStyle(NotificationCompat.BigTextStyle()
                    .setSummaryText("$total found").bigText("$total key(s) found — in the finds vault"))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publica)
                .setGroup(GRUPO)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.notify(ID_RESUMEN + total, n)   // individual, id único
            nm.notify(ID_RESUMEN, resumen)     // resumen, id fijo
            true
        } catch (e: Exception) { false }
    }
}
