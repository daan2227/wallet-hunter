package com.hunter.btc

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Lo que el modo debug guarda para que no se pierda al cerrar la pantalla.
 *
 * Antes el registro del motor se leía (y se vaciaba) sólo mientras la pantalla
 * de debug estaba abierta, el logcat sólo traía los AndroidRuntime:E de los
 * últimos 30 mensajes del sistema, y "Clear" borraba los ficheros. Ahora:
 *   - engine_log.txt: lo que escupe el motor, lo drena el servicio aunque no
 *     haya pantalla. Con las claves tachadas.
 *   - app_log.txt: los avisos y errores de ESTE proceso (logcat --pid), sin
 *     repetir lo ya guardado.
 *   - Clear archiva: renombra con fecha y conserva las últimas tres copias.
 *   - comprobar(): unas cuantas comprobaciones de salud, cuyo resultado también
 *     se guarda en app_log.txt.
 *
 * Todas las escrituras pasan por RegistroFallos.añadir, que rota en vez de
 * vaciar: nada se borra por tamaño.
 */
object Diagnostico {

    const val MOTOR = "engine_log.txt"
    const val APP = "app_log.txt"
    private const val INTERVALO_LOGCAT_MS = 10_000L
    private const val LINEAS_LOGCAT = 1000

    /** Un solo hilo de fondo: nada de esto corre en el hilo principal. */
    private val fondo = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diagnostico").apply { isDaemon = true }
    }

    @Volatile private var ultimoVolcado = 0L
    @Volatile private var ultimaLinea: String? = null
    private val stamp get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    fun enSegundoPlano(bloque: () -> Unit) {
        fondo.execute { try { bloque() } catch (e: Throwable) { } }
    }

    /**
     * Saca del motor lo que haya en su cola y lo guarda. Devuelve el texto
     * sacado para que la pantalla lo muestre en vivo.
     */
    @Synchronized
    fun drenarMotor(ctx: Context): String {
        val sb = StringBuilder()
        for (k in 0 until 50) {
            val l = try { HunterEngine.popLog() } catch (e: Throwable) { "" }
            if (l.isEmpty()) break
            sb.append(l).append('\n')
        }
        if (sb.isEmpty()) return ""
        val lote = "[$stamp]\n$sb"
        RegistroFallos.añadir(File(ctx.filesDir, MOTOR), lote)
        return lote
    }

    /**
     * Copia a app_log.txt los avisos y errores de este proceso que aún no estén
     * guardados. Como mucho una vez cada diez segundos: cada llamada lanza un
     * logcat, y la pantalla de debug la pide cada segundo.
     *
     * @return cuántas líneas nuevas se han guardado.
     */
    @Synchronized
    fun volcarLogcat(ctx: Context): Int {
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoVolcado < INTERVALO_LOGCAT_MS) return 0
        ultimoVolcado = ahora
        val lineas = try {
            val pid = android.os.Process.myPid()
            val p = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", "$LINEAS_LOGCAT", "--pid=$pid", "*:W"))
            val texto = p.inputStream.bufferedReader().use { it.readLines() }
            p.waitFor()
            texto
        } catch (e: Throwable) { return 0 }
        if (lineas.isEmpty()) return 0
        val previas = ultimaLinea
        // Si la última línea guardada ya no está en el buffer, se guarda todo
        // lo que hay: puede repetir algo, pero no pierde nada.
        val desde = if (previas == null) 0 else lineas.lastIndexOf(previas) + 1
        val nuevas = lineas.subList(desde.coerceIn(0, lineas.size), lineas.size)
        ultimaLinea = lineas.last()
        if (nuevas.isEmpty()) return 0
        RegistroFallos.añadir(File(ctx.filesDir, APP), nuevas.joinToString("\n", postfix = "\n"))
        return nuevas.size
    }

    /**
     * Mueve a un fichero con la fecha lo que hay en los registros, en vez de
     * borrarlo. Se quedan las tres últimas copias de cada uno.
     *
     * @return cuántos ficheros se han archivado.
     */
    @Synchronized
    fun archivar(ctx: Context): Int {
        val dir = ctx.filesDir
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var n = 0
        for (base in BASES) {
            val f = File(dir, "$base.txt")
            if (f.exists() && f.length() > 0 && f.renameTo(File(dir, "$base.$ts.txt"))) n++
        }
        for (base in BASES) {
            val copia = Regex(Regex.escape(base) + """\.\d{8}-\d{6}\.txt""")
            dir.listFiles { f -> copia.matches(f.name) }
                ?.sortedByDescending { it.name }
                ?.drop(TRES_COPIAS)
                ?.forEach { it.delete() }
        }
        return n
    }

    /**
     * Comprobaciones de salud de la app. Llama a la red, así que no va en el
     * hilo principal. El resultado queda también en app_log.txt.
     */
    fun comprobar(ctx: Context): List<String> {
        val r = mutableListOf<String>()
        fun linea(estado: String, nombre: String, detalle: String) =
            r.add("$estado $nombre: $detalle")

        linea(
            if (runCatching { HunterEngine.isRunning() }.isSuccess) "OK  " else "MAL ",
            "motor nativo", "libreria cargada")

        val palabras = Bip39Words.WORDS.size
        linea(if (palabras == 2048) "OK  " else "MAL ", "lista BIP39", "$palabras palabras")

        linea(if (WalletManager.hasPin(ctx)) "OK  " else "AVISO",
            "PIN", if (WalletManager.hasPin(ctx)) "configurado" else "no configurado")

        val libreMb = ctx.filesDir.usableSpace / 1048576
        linea(if (libreMb >= 100) "OK  " else "MAL ", "espacio libre", "$libreMb MB")

        val maxMb = Runtime.getRuntime().maxMemory() / 1048576
        linea(if (maxMb >= 200) "OK  " else "AVISO", "memoria maxima", "$maxMb MB")

        val logsKb = (ctx.filesDir.listFiles() ?: emptyArray())
            .filter { f -> BASES.any { f.name.startsWith(it) } && f.name.endsWith(".txt") }
            .sumOf { it.length() } / 1024
        linea(if (logsKb <= 2048) "OK  " else "AVISO", "tamano de registros", "$logsKb KB")

        val errores = contarErrores(File(ctx.filesDir, APP))
        linea(if (errores == 0) "OK  " else "AVISO", "errores guardados",
            "$errores lineas de error en $APP")

        val red = try { ChainApi.hayRed(false) } catch (e: Throwable) { false }
        linea(if (red) "OK  " else "AVISO", "red", if (red) "el servidor responde" else "sin respuesta")

        val cabecera = "=== CHECK ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ==="
        RegistroFallos.añadir(File(ctx.filesDir, APP), (listOf(cabecera) + r).joinToString("\n", postfix = "\n"))
        return r
    }

    private fun contarErrores(f: File): Int = try {
        // Formato threadtime de logcat: "MM-DD HH:MM:SS.mmm  pid  tid E Tag: msg".
        // El nivel va en su columna; un " E " dentro del mensaje no cuenta.
        val nivelError = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+\d+\s+\d+\s+E\s""")
        if (!f.exists()) 0 else f.useLines { it.count { l -> nivelError.containsMatchIn(l) } }
    } catch (e: Throwable) { 0 }

    private val BASES = listOf("crash_log", "crash_log.1", MOTOR.removeSuffix(".txt"),
        MOTOR.removeSuffix(".txt") + ".1", APP.removeSuffix(".txt"), APP.removeSuffix(".txt") + ".1",
        "puz_debug")
    private const val TRES_COPIAS = 3
}
