package com.hunter.btc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Volver a arrancar después de reiniciar el móvil.
 *
 * Sin esto, un reinicio —el que hace Android solo al actualizarse, o el que
 * haces tú— dejaba el móvil parado para siempre. En un aparato que se deja días
 * trabajando para un cluster eso es todo lo que hace falta para perderlo, y no
 * hay nada que lo diga: el maestro se limita a verlo desaparecer de la lista.
 *
 * Aquí NO se decide nada. Sólo se levanta el servicio; él mira en preferencias
 * si había una búsqueda y un sitio en el cluster, y los recupera o no. Así la
 * decisión está en un único sitio en vez de repetida por cada camino de
 * arranque, que es como acaban divergiendo.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context?, intent: Intent?) {
        val c = ctx ?: return
        val acc = intent?.action ?: return
        if (acc != Intent.ACTION_BOOT_COMPLETED &&
            acc != "android.intent.action.QUICKBOOT_POWERON" &&
            acc != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Levantar el servicio cuesta batería, así que sólo si había algo que
        // recuperar. Se mira aquí y no dentro para no arrancarlo por nada.
        val p = c.getSharedPreferences("hunter", Context.MODE_PRIVATE)
        val hayBusqueda = p.getBoolean("kangaroo_corriendo", false)
        val hayCluster  = p.getBoolean("net_worker", false)
        if (!hayBusqueda && !hayCluster) return
        val svc = Intent(c, HunterService::class.java)
        try { c.startForegroundService(svc) } catch (e: Exception) {
            try { c.startService(svc) } catch (e2: Exception) {
                android.util.Log.e("BootReceiver", "could not start: ${e2.message}")
            }
        }
    }
}
