package com.hunter.btc

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.io.RandomAccessFile

/**
 * El proceso `bitcoind` de Bitcoin Core que viaja dentro del APK como libbitcoind.so.
 *
 * Android sólo deja ejecutar lo que está en el directorio de librerías nativas de la app (no en filesDir),
 * y por eso el binario se llama lib*.so. Si esta compilación no lo trae (core.yml aún no lo ha publicado),
 * [binario] es null y la pestaña lo dice.
 *
 * Los datos de la cadena van en filesDir/core. La cookie de RPC va APARTE (filesDir/core_rpc): así no queda
 * dentro del directorio que se llena de cientos de MB ni en almacenamiento compartido.
 */
object CoreNodo {

    private const val PREFS = "core"
    @Volatile private var proceso: Process? = null
    /** true si la parada la pidió el usuario o el servicio (para no tomarla por una caída). */
    @Volatile var paradaPedida = false

    fun binario(ctx: Context): File? =
        File(ctx.applicationInfo.nativeLibraryDir, "libbitcoind.so").takeIf { it.isFile }

    fun datadir(ctx: Context): File = File(ctx.filesDir, "core").also { it.mkdirs() }
    fun cookie(ctx: Context): File = File(File(ctx.filesDir, "core_rpc").also { it.mkdirs() }, ".cookie")
    fun snapshot(ctx: Context): File = File(datadir(ctx), "snapshot.dat")
    fun rpc(ctx: Context) = CoreRpc(cookie(ctx))

    fun ajustes(ctx: Context): CoreConfig.Ajustes {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = CoreConfig.Ajustes()
        return CoreConfig.Ajustes(
            pruneMiB = p.getInt("prune_mib", d.pruneMiB),
            dbcacheMiB = p.getInt("dbcache_mib", d.dbcacheMiB),
            soloBloques = p.getBoolean("solo_bloques", d.soloBloques),
            soloWifi = p.getBoolean("solo_wifi", d.soloWifi)).normalizado()
    }

    fun guardarAjustes(ctx: Context, a: CoreConfig.Ajustes) {
        val n = a.normalizado()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("prune_mib", n.pruneMiB).putInt("dbcache_mib", n.dbcacheMiB)
            .putBoolean("solo_bloques", n.soloBloques).putBoolean("solo_wifi", n.soloWifi).apply()
    }

    fun procesoVivo(): Boolean = proceso?.isAlive == true

    /** Datos móviles (o cualquier red de pago): el nodo baja gigas. */
    fun redDeDatos(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val cap = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Espacio libre donde van los datos. */
    fun libre(ctx: Context): Long = datadir(ctx).usableSpace

    /**
     * Arranca bitcoind. Devuelve null si el proceso ha salido bien, o el motivo por el que no.
     * Bloquea poco (sólo escribe el conf y lanza); no espera a que el nodo esté listo.
     */
    @Synchronized
    fun arrancar(ctx: Context): String? {
        if (procesoVivo()) return null
        val bin = binario(ctx) ?: return "This build does not include Bitcoin Core."
        if (libre(ctx) < 1L shl 30) return "Less than 1 GB free: free some space first."
        val ajustes = ajustes(ctx)
        if (ajustes.soloWifi && redDeDatos(ctx)) return "Wi-Fi only is on and you are on mobile data."
        return try {
            val dir = datadir(ctx)
            val ck = cookie(ctx)
            ck.delete()   // una cookie vieja haría creer que el nodo ya contesta
            val conf = File(dir, "bitcoin.conf")
            conf.writeText(CoreConfig.conf(ajustes, ck.absolutePath))
            val cmd = listOf(bin.absolutePath) + CoreConfig.argumentos(dir.absolutePath, conf.absolutePath)
            paradaPedida = false
            val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(File(dir, "salida.txt")))
            val pr = pb.start()
            proceso = pr
            Thread {
                val code = try { pr.waitFor() } catch (e: InterruptedException) { -1 }
                ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt("ultima_salida", code).putLong("ultima_salida_ts", System.currentTimeMillis()).apply()
            }.start()
            null
        } catch (e: Exception) {
            "Could not start the node: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Pide la parada limpia por RPC y espera; si no contesta, termina el proceso. Bloquea: nunca en el hilo principal. */
    fun parar(ctx: Context) {
        paradaPedida = true
        try { rpc(ctx).llamar("stop") } catch (e: Exception) { /* ya parado, o aún sin cookie */ }
        val pr = proceso ?: return
        try {
            if (!pr.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) {
                pr.destroy()
                if (!pr.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) pr.destroyForcibly()
            }
        } catch (e: InterruptedException) { pr.destroyForcibly() }
        proceso = null
    }

    /** Las últimas [n] líneas de debug.log (lee sólo el final del fichero). */
    fun ultimasLineas(ctx: Context, n: Int = 12): String = cola(ctx, 16_384).takeLast(n).joinToString("\n")

    /** Las líneas del último [bytes] de debug.log, en orden. */
    fun cola(ctx: Context, bytes: Int): List<String> {
        val f = File(datadir(ctx), "debug.log")
        if (!f.isFile) return emptyList()
        return try {
            RandomAccessFile(f, "r").use { r ->
                val len = r.length(); val ini = (len - bytes).coerceAtLeast(0)
                r.seek(ini)
                val b = ByteArray((len - ini).toInt()); r.readFully(b)
                String(b, Charsets.UTF_8).lines().filter { it.isNotBlank() }
            }
        } catch (e: Exception) { emptyList() }
    }

    /** Cómo va la carga del snapshot según el registro (null si no hay ninguna que mirar). */
    fun cargaSnapshot(ctx: Context): CoreConfig.CargaSnapshot? {
        // «El nodo corre» no se deduce sólo de las banderas de este proceso (que se pierden si la app se
        // recrea): también vale un registro que ha escrito hace poco.
        val f = File(datadir(ctx), "debug.log")
        val reciente = f.isFile && System.currentTimeMillis() - f.lastModified() < 20 * 60_000L
        return CoreConfig.leerCargaSnapshot(cola(ctx, 512 * 1024), procesoVivo() || CoreService.activo || reciente,
                                            System.currentTimeMillis() / 1000)
    }
}
