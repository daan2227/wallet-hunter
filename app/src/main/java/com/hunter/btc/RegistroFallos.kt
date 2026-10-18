package com.hunter.btc

import android.content.Context

/**
 * crash_log.txt: un solo sitio que escribe en él.
 *
 * Había cinco: el manejador de cierres (sólo en MainActivity, que además
 * retenía la pantalla en su cierre) y cuatro appendText() sueltos sin tope de
 * tamaño. Aquí todo pasa por el mismo tope y con las claves tachadas: un
 * mensaje de error puede llevar dentro un WIF o un hex de 64.
 */
object RegistroFallos {
    private const val TOPE = 256 * 1024
    @Volatile private var instalado = false

    /**
     * Añade [texto] a [f], con las claves tachadas. Si el fichero pasa del tope
     * se rota: el actual pasa a `<nombre>.1.txt` (sustituyendo al anterior) y
     * se empieza uno nuevo. Antes se vaciaba, y se perdía todo el historial.
     */
    @Synchronized
    fun añadir(f: java.io.File, texto: String) {
        try {
            if (f.length() > TOPE) {
                val rotado = java.io.File(f.parentFile, f.name.removeSuffix(".txt") + ".1.txt")
                rotado.delete()
                f.renameTo(rotado)
            }
            f.appendText(Secretos.tachar(texto))
        } catch (e: Throwable) { }
    }

    fun anotar(ctx: Context, texto: String) =
        añadir(java.io.File(ctx.applicationContext.filesDir, "crash_log.txt"), texto)

    fun anotar(ctx: Context, donde: String, e: Throwable) =
        anotar(ctx, "\n$donde: ${e.message}\n${e.stackTraceToString()}\n")

    /**
     * Manejador global de cierres, con el contexto de la aplicación: lo instala
     * la primera pantalla que arranque (AppLock.init), no sólo MainActivity.
     */
    fun instalar(ctx: Context) {
        if (instalado) return
        instalado = true
        val app = ctx.applicationContext
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            anotar(app, "\n=== $ts ===\n${e.javaClass.name}\n${e.message}\n${e.stackTraceToString()}\n")
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
