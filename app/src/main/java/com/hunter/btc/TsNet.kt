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
     * Estas funciones viven dentro de libhunter_jni.so, la misma que carga
     * HunterEngine. Hay que cargarla aquí TAMBIÉN porque este objeto se puede
     * tocar antes que aquél —la pantalla de red pregunta si hay tsnet antes de
     * que nadie haya buscado nada—, y entonces el símbolo no estaría y saldría
     * un UnsatisfiedLinkError. loadLibrary es idempotente: llamarla dos veces no
     * cuesta nada.
     *
     * libtailscale.so no se carga a mano: libhunter_jni.so la trae como
     * dependencia y el enlazador de Android la resuelve sola. Si no está en el
     * APK —compilación sin tsnet— tampoco figura como dependencia, así que no
     * falta nada.
     */
    init { System.loadLibrary("hunter_jni") }

    /** ¿Lleva esta compilación el nodo dentro? */
    external fun disponible(): Boolean

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
            val e = try { arrancar(clave.trim(), nombre, dir) }
                    catch (t: Throwable) { t.message ?: "error desconocido" }
            arrancando = false
            ultimoError = e
            arrancado = e.isEmpty()
            onFin(e.isEmpty(), e)
        }, "tsnet-up").apply { isDaemon = true }.start()
    }
}
