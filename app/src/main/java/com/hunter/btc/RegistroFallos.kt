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
    // Para poder anotar un error desde una clase sin Context a mano (ElectrumClient,
    // CoinSelector, Psbt...) sin tener que colar un Context por cada firma de función.
    // Se rellena en instalar(), que corre al abrir CUALQUIER pantalla (AppLock.init).
    @Volatile private var appCtx: Context? = null

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

    private fun marca() =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    /**
     * Con fecha siempre, aquí y en ningún otro sitio: antes cada llamante ponía la suya (o
     * ninguna) — el manejador de cierres sí, los otros ~415 catch del resto de la app no. Un
     * error sin fecha en un fichero que crece durante semanas no sirve de mucho como historial.
     */
    fun anotar(ctx: Context, texto: String) =
        añadir(java.io.File(ctx.applicationContext.filesDir, "crash_log.txt"), "\n[${marca()}] $texto")

    fun anotar(ctx: Context, donde: String, e: Throwable) =
        anotar(ctx, "$donde: ${e.message}\n${e.stackTraceToString()}\n")

    /**
     * Igual que las de arriba pero sin pedir un Context: para los ~150 catch silenciosos
     * fuera de una Activity (red, Core, firma, baúl) que antes no dejaban ningún rastro —
     * ver PENDIENTES.md, "¿se ven todos los errores en Debug?". Si se llama antes de que
     * cualquier pantalla haya arrancado (appCtx aún null, en la práctica no pasa: instalar()
     * corre en el primer onCreate de la app) no hace nada, en vez de reventar.
     */
    fun anotar(donde: String, e: Throwable) {
        val ctx = appCtx ?: return
        anotar(ctx, donde, e)
    }

    /**
     * Manejador global de cierres, con el contexto de la aplicación: lo instala
     * la primera pantalla que arranque (AppLock.init), no sólo MainActivity.
     */
    fun instalar(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app   // se guarda siempre, aunque ya estuviera instalado (barato, y evita una ventana sin él)
        if (instalado) return
        instalado = true
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            // La fecha la pone anotar(); aquí solo la marca de "esto no fue un error cualquiera,
            // fue un cierre" para que se distinga a simple vista del resto del historial.
            anotar(app, "=== CRASH ===\n${e.javaClass.name}\n${e.message}\n${e.stackTraceToString()}\n")
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
