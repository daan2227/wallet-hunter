package com.hunter.btc

import android.content.Context

/**
 * El nodo de Tailscale DENTRO de la app (tsnet empotrado).
 *
 * Es distinto de [Tailscale], que habla con la app de Tailscale instalada
 * aparte. Aquí la app es un aparato del tailnet por sí misma:
 *
 *   - No hace falta tener instalada la app de Tailscale.
 *   - No se enruta el móvil entero por la VPN, sólo el tráfico del cluster.
 *   - A cambio hay que autorizarlo con una clave, y el APK engorda 14 MB.
 *
 * ESTO PUEDE NO ESTAR
 *
 * libtailscale.so es opcional en la compilación: la produce
 * scripts/build-libtailscale.sh, que mete la cadena de Go y el árbol entero de
 * tailscale.com, y nada de eso puede ser motivo de que una app que funciona
 * deje de construirse. Si no está, [disponible] devuelve false y el cluster
 * sigue yendo por donde iba.
 *
 * Por eso conviene preguntar por [disponible] antes que por nada más.
 */
object TsNet {

    /**
     * La librería se carga PEREZOSAMENTE, y en un try.
     *
     * Estuvo dentro de libhunter_jni.so y rompió la app entera. libtailscale.so
     * trae el runtime de Go, que arranca al CARGAR la librería: enlazada dentro,
     * Go arrancaba al abrir la app —antes de que nadie hubiera pedido nada de
     * Tailscale— y System.loadLibrary fallaba.
     *
     * Y el fallo no se veía. La pestaña de escaneo tiene
     *
     *     try { HunterEngine.setBip39Paths(pathMask) } catch (e: Throwable) {}
     *
     * que se tragaba el UnsatisfiedLinkError sin decir nada; luego la pestaña de
     * puzzle llamaba a setBatchSize sin proteger, el Error subía hasta un
     * catch (e: Exception) —que NO atrapa Error— y mataba la app. Lo que se veía
     * era una pantalla negra con un "Building Puzzle..." colgado.
     *
     * Ahora vive en libtsbridge.so y sólo se carga cuando alguien pregunta por
     * tsnet. Si no se puede, [disponible] devuelve false y no pasa nada más: la
     * app entera sigue funcionando sin Tailscale.
     */
    @Volatile private var cargada: Boolean? = null

    /**
     * Por qué no se pudo cargar la librería. "" si cargó o si no se ha probado.
     *
     * Se guarda para poder ENSEÑARLO. Antes sólo iba al registro del sistema,
     * que desde el móvil no se ve, así que lo único que sabía el usuario era que
     * el nodo "no está disponible" — sin un solo dato con el que averiguar por
     * qué. Con librerías nativas el mensaje del enlazador suele decir
     * exactamente lo que pasa: falta un símbolo, la arquitectura no cuadra, la
     * alineación de página no vale...
     */
    @Volatile var errorCarga = ""
        private set

    @Synchronized
    private fun cargar(): Boolean {
        cargada?.let { return it }
        val ok = try {
            System.loadLibrary("tsbridge"); errorCarga = ""; true
        } catch (t: Throwable) {
            errorCarga = "${t.javaClass.simpleName}: ${t.message ?: "sin detalle"}"
            android.util.Log.w("TsNet", "libtsbridge no se pudo cargar: $errorCarga")
            false
        }
        cargada = ok
        return ok
    }

    /** Lo de verdad, sólo llamable con la librería cargada. */
    private external fun disponibleNativo(): Boolean

    /**
     * ¿Se puede usar el nodo empotrado?
     *
     * Nunca lanza. Es lo primero que pregunta la pantalla, y una excepción aquí
     * volvería a tumbar la app por algo que es perfectamente opcional.
     */
    fun disponible(): Boolean =
        if (!cargar()) false
        else try { disponibleNativo() } catch (t: Throwable) { false }

    /**
     * Levanta el nodo y ESPERA a que esté autorizado.
     *
     * @param clave  clave de autorización (tskey-auth-...), de
     *   login.tailscale.com → Settings → Keys → Generate auth key.
     * @param nombre con qué nombre aparece este móvil en el tailnet.
     * @param dir    carpeta privada donde guarda su identidad. Tiene que
     *   sobrevivir entre arranques: perderla obliga a autorizarlo otra vez.
     * @return "" si ha salido bien, o el motivo en texto. Texto y no un código
     *   porque quien lo lee es el usuario.
     *
     * BLOQUEA. Nunca desde el hilo principal — de eso se ocupa [arrancarEnHilo].
     */
    external fun arrancar(clave: String, nombre: String, dir: String): String

    /** Direcciones del nodo en el tailnet, separadas por coma. "" si no hay. */
    external fun direcciones(): String

    /** Para el nodo. Se puede llamar aunque no esté arrancado. */
    external fun parar()

    /**
     * Darle a tsnet la lista de interfaces de red. @return cuántas ha aceptado.
     *
     * Hace falta porque **Android 11+ le prohíbe a Go preguntárselas al kernel**
     * por netlink. Sin esto, levantar el nodo muere con:
     *
     *     tsnet.Up: tsnet: route ip+net: netlinkrib: permission denied
     *
     * No es un permiso que se pueda pedir: el sistema lo bloquea y ya. Tailscale
     * deja el gancho a propósito para esto —lo usa su propia app de Android— y
     * `java.net.NetworkInterface` sí funciona, así que se las damos hechas.
     */
    external fun ponerInterfaces(spec: String): Int

    /**
     * Enumera las interfaces y se las pasa a tsnet.
     *
     * Formato, una por línea: `nombre|índice|mtu|banderas|ip/prefijo,...`
     *
     * Se llama antes de levantar el nodo. Si la red cambia después —cambiar de
     * WiFi a datos— habría que volver a llamarla; de momento no se hace, y la
     * consecuencia es que tsnet seguiría creyendo lo de antes hasta el próximo
     * arranque. Queda dicho porque es un límite real y no un olvido.
     */
    fun refrescarInterfaces(): Int {
        val sb = StringBuilder()
        try {
            for (i in java.util.Collections.list(
                    java.net.NetworkInterface.getNetworkInterfaces())) {
                val banderas = ArrayList<String>()
                try { if (i.isUp) banderas.add("up") } catch (e: Throwable) {}
                try { if (i.isLoopback) banderas.add("loopback") } catch (e: Throwable) {}
                try { if (i.isPointToPoint) banderas.add("ptp") } catch (e: Throwable) {}
                try { if (i.supportsMulticast()) banderas.add("multicast") } catch (e: Throwable) {}
                val dirs = i.interfaceAddresses.mapNotNull { ia ->
                    val a = ia.address?.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                    "$a/${ia.networkPrefixLength}"
                }
                val mtu = try { i.mtu } catch (e: Throwable) { 1500 }
                sb.append(i.name).append('|').append(i.index).append('|')
                  .append(if (mtu > 0) mtu else 1500).append('|')
                  .append(banderas.joinToString(",")).append('|')
                  .append(dirs.joinToString(",")).append('\n')
            }
        } catch (e: Throwable) {
            android.util.Log.w("TsNet", "interfaces: ${e.message}")
        }
        return try { ponerInterfaces(sb.toString()) } catch (e: Throwable) { -1 }
    }

    /* ── Conexiones ─────────────────────────────────────────────────────────
     *
     * Lo que devuelven son DESCRIPTORES DE FICHERO. La cabecera de libtailscale
     * lo dice tal cual: "it is a pipe(2) on which you can use read(2), write(2),
     * and close(2)". Por eso empotrar tsnet no obliga a reescribir el protocolo
     * del cluster — las mismas líneas JSON, por otro descriptor.
     *
     * Negativo es fallo: −1 es "no hay tsnet o el nodo no está arrancado" y −2
     * es "tsnet ha dicho que no", para poder distinguir "no se puede" de "no ha
     * podido".
     *
     * [aceptar] y [marcar] BLOQUEAN. Nunca desde el hilo principal.
     */
    external fun escuchar(puerto: Int): Int
    external fun aceptar(escuchador: Int): Int
    external fun marcar(destino: String): Int
    /** Quién hay al otro lado de una conexión aceptada. "" si no se sabe. */
    external fun remoto(escuchador: Int, con: Int): String
    /** El último error del nodo, para poder enseñarlo. */
    external fun ultimoFallo(): String

    /**
     * Una conexión de tsnet vestida de flujos de Java.
     *
     * OJO CON EL CIERRE, que es donde esto se rompe de forma difícil de ver.
     *
     * [entrada] y [salida] salen del MISMO descriptor. Cerrar cualquiera de los
     * dos lo cierra, y con él el otro. Así que aquí no se cierra ninguno: se
     * cierra el ParcelFileDescriptor y ya está.
     *
     * Eso tiene una consecuencia para quien lo use: envolver [entrada] en un
     * BufferedReader y cerrarlo —o pasarlo a algo que lo cierre— tumba la
     * conexión entera, incluida la escritura. En el reparto del cluster se lee y
     * se escribe por la misma conexión, así que hay que dejar que cierre esto y
     * no los envoltorios.
     */
    class Conexion(fd: Int) : java.io.Closeable {
        private val pfd = android.os.ParcelFileDescriptor.adoptFd(fd)
        val entrada: java.io.InputStream = java.io.FileInputStream(pfd.fileDescriptor)
        val salida:  java.io.OutputStream = java.io.FileOutputStream(pfd.fileDescriptor)
        override fun close() {
            // Vaciar antes de soltar: lo que quede en el búfer de salida se
            // perdería, y en este protocolo la última línea suele ser la
            // respuesta que el otro está esperando.
            try { salida.flush() } catch (e: Throwable) {}
            try { pfd.close() }   catch (e: Throwable) {}
        }
    }

    /** @return la conexión, o null si no se pudo. */
    fun conectar(destino: String): Conexion? {
        val fd = try { marcar(destino) } catch (t: Throwable) { -1 }
        return if (fd < 0) null else Conexion(fd)
    }

    /* ── Envoltorio cómodo ──────────────────────────────────────────────── */

    /** Dónde guarda tsnet su identidad. Privado de la app y estable. */
    fun carpetaEstado(ctx: Context): String =
        java.io.File(ctx.filesDir, "tsnet").apply { mkdirs() }.absolutePath

    /**
     * ¿Este móvil ya está dado de alta en el tailnet?
     *
     * La clave de autorización hace falta UNA vez. A partir de ahí la identidad
     * del nodo vive en [carpetaEstado] y arrancar con clave vacía funciona. Por
     * eso se guarda que ya se hizo, en vez de guardar la clave: una credencial
     * que no hace falta conservar es una credencial que no conviene conservar.
     *
     * Y por eso [olvidarNodo] borra también la carpeta: dejar la bandera a false
     * con la identidad todavía ahí daría un nodo duplicado en el tailnet.
     */
    fun autorizado(ctx: Context): Boolean =
        ctx.getSharedPreferences("hunter", Context.MODE_PRIVATE)
            .getBoolean("tsnet_autorizado", false)

    private fun marcarAutorizado(ctx: Context) {
        ctx.getSharedPreferences("hunter", Context.MODE_PRIVATE).edit()
            .putBoolean("tsnet_autorizado", true).apply()
    }

    /** Da de baja este nodo: para, olvida la identidad y la marca. */
    fun olvidarNodo(ctx: Context) {
        try { parar() } catch (e: Throwable) {}
        arrancado = false; ultimoError = ""
        try { java.io.File(carpetaEstado(ctx)).deleteRecursively() } catch (e: Throwable) {}
        ctx.getSharedPreferences("hunter", Context.MODE_PRIVATE).edit()
            .putBoolean("tsnet_autorizado", false).apply()
    }

    @Volatile var arrancando = false
        private set
    /** Lo último que dijo [arrancar]. "" si fue bien o si no se ha intentado. */
    @Volatile var ultimoError = ""
        private set
    @Volatile var arrancado = false
        private set

    /**
     * Arranca en segundo plano y avisa al terminar.
     *
     * [arrancar] bloquea hasta que el nodo está autorizado, lo que puede tardar
     * bastante la primera vez, así que llamarlo desde la pantalla la congelaría.
     *
     * @param onFin (ok, mensaje). Se llama en el hilo del que arranca, NO en el
     *   de la pantalla: quien lo use para pintar tiene que saltar él.
     */
    fun arrancarEnHilo(ctx: Context, clave: String, nombre: String,
                       onFin: (Boolean, String) -> Unit) {
        if (!disponible()) { onFin(false, "Esta versión no lleva tsnet dentro"); return }
        if (arrancado)     { onFin(true, "");  return }
        if (arrancando)    { onFin(false, "Ya se está arrancando"); return }
        arrancando = true
        val dir = carpetaEstado(ctx)
        Thread({
            // ANTES de levantar nada: sin las interfaces, tailscale_up muere
            // con "netlinkrib: permission denied". Va aquí y no en el hilo de
            // la pantalla porque enumerar interfaces puede tardar un poco.
            val n = refrescarInterfaces()
            android.util.Log.i("TsNet", "interfaces pasadas a tsnet: $n")
            val e = try { arrancar(clave.trim(), nombre, dir) }
                    catch (t: Throwable) { t.message ?: "error desconocido" }
            arrancando = false
            ultimoError = e
            arrancado = e.isEmpty()
            // Sólo al salir bien: si se marcara antes, una clave rechazada
            // dejaría el móvil convencido de estar dado de alta y el siguiente
            // arranque iría sin clave, que también falla, y sin decir por qué.
            if (e.isEmpty()) marcarAutorizado(ctx)
            onFin(e.isEmpty(), e)
        }, "tsnet-up").apply { isDaemon = true }.start()
    }
}
