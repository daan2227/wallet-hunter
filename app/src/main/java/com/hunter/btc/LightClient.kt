package com.hunter.btc

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue

/**
 * Cliente ligero de Bitcoin (BIP157/158): comprueba el saldo hablando con nodos de la
 * red en vez de preguntar a un explorador, sin fiarse de ninguno.
 *
 * Este fichero sólo hace de red y de disco: abre conexiones, reparte los bytes que
 * llegan a la máquina de estados nativa ([LightNative]) y escribe lo que ésta devuelve.
 * Todo lo que decide (qué es válido, a quién expulsar, qué bloques bajar) está en C++
 * y se prueba sin red. No usa nada de Android, para poder probarlo también en el
 * escritorio contra un nodo simulado por TCP.
 *
 * Los nodos públicos que sirven filtros son pocos: la mayoría de los que dan los DNS
 * seeds se descartan en el saludo. Los que sí sirven se recuerdan para la próxima vez.
 */
class LightClient(
    private val dir: File,
    private val red: Int = 0,
    private val genesisHex: String = "",
    private val minPares: Int = 2,
    private val objetivoPares: Int = 4,
    private val resolver: () -> List<Direccion> = { Seeds.resolver(dir) },
) {
    data class Direccion(val host: String, val port: Int = 8333) {
        override fun toString() = "$host:$port"
    }

    private class Conexion(val id: Int, val dir: Direccion, val socket: Socket, val relay: Boolean) {
        val cola = LinkedBlockingQueue<ByteArray>()
        @Volatile var cerrada = false
    }

    private val lock = Any()
    private var h = 0L
    @Volatile private var activo = false
    private val conexiones = HashMap<Int, Conexion>()
    private var siguienteId = 1
    private val baneadas = HashSet<String>()
    private val intentando = HashSet<String>()
    private val falladas = HashMap<String, Long>()
    private var pool = ArrayDeque<Direccion>()
    private var persistida = 0
    private var hilo: Thread? = null
    @Volatile private var quiereMonitor = false
    private var pausadaDesde = 0L
    private var ultimoRefresco = 0L

    private val ficheroCabeceras get() = File(dir, "cabeceras_btc.bin")

    /** Arranca. Los scripts van en hex. `desde` es la primera altura a escanear. */
    fun iniciar(scriptsHex: List<String>, desde: Int) {
        synchronized(lock) {
            if (activo) return
            dir.mkdirs()
            h = LightNative.nuevo(red, genesisHex, minPares, desde, SecureRandom().nextLong(),
                scriptsHex.joinToString(","))
            if (h == 0L) throw IllegalStateException("no se pudo crear el cliente ligero")
            cargarCabeceras()
            activo = true
        }
        hilo = Thread({ bucle() }, "ligero").also { it.isDaemon = true; it.start() }
    }

    fun detener() {
        val t = hilo
        synchronized(lock) {
            if (!activo) return
            activo = false
            guardarCabeceras()
            for (c in conexiones.values.toList()) cerrar(c)
            conexiones.clear()
            LightNative.liberar(h)
            h = 0L
        }
        try { t?.join(2000) } catch (e: InterruptedException) { }
    }

    /** JSON con la fase, el saldo, las monedas y el registro. Ver `estado` en ligero_jni.cpp. */
    fun estadoJson(): String = synchronized(lock) { if (h == 0L) "{}" else LightNative.estado(h) }

    fun actualizar() = synchronized(lock) { if (h != 0L) { LightNative.actualizar(h, ahora()); volcar() } }

    fun reescanear(scriptsHex: List<String>, desde: Int) = synchronized(lock) {
        if (h != 0L) { LightNative.reescanear(h, scriptsHex.joinToString(","), desde, ahora()); volcar() }
    }

    /**
     * Difunde una transacción ya firmada. Devuelve el txid, o "ERR:motivo".
     *
     * Para ver si se propaga hace falta una conexión aparte con relay (BIP37) que ya esté
     * lista cuando la transacción salga: si se anuncia antes, esa conexión nunca verá el
     * aviso. Por eso, si no la hay, la transacción espera (hasta 20 s) a que se abra.
     */
    fun difundir(rawHex: String): String = synchronized(lock) {
        if (h == 0L) return "ERR:cliente parado"
        val hayMonitor = conexiones.values.any { it.relay && !it.cerrada && LightNative.parListo(h, it.id) }
        if (!hayMonitor) { LightNative.pausarDifusion(h, true, ahora()); pausadaDesde = ahora() }
        val r = LightNative.difundir(h, rawHex, ahora())
        if (r.startsWith("ERR:")) { if (!hayMonitor && pausadaDesde > 0) { LightNative.pausarDifusion(h, false, ahora()); pausadaDesde = 0 } }
        else quiereMonitor = true
        volcar()
        r
    }

    /** Reanuda la difusión cuando la conexión con relay está lista, o al pasar 20 s. */
    private fun reanudarSiListo() {
        if (pausadaDesde == 0L || h == 0L) return
        val lista = conexiones.values.any { it.relay && !it.cerrada && LightNative.parListo(h, it.id) }
        if (lista || ahora() - pausadaDesde > 20_000) {
            LightNative.pausarDifusion(h, false, ahora()); pausadaDesde = 0; volcar()
        }
    }

    fun estadoDifusion(txid: String): String = synchronized(lock) { if (h == 0L) "desconocida:0" else LightNative.estadoDifusion(h, txid) }

    // ---------------------------------------------------------------- hilo principal

    private fun ahora() = System.currentTimeMillis()

    private fun bucle() {
        while (activo) {
            try {
                synchronized(lock) {
                    if (h != 0L) { LightNative.tick(h, ahora()); volcar(); reanudarSiListo() }
                }
                guardarPeriodico()
                mantenerPares()
            } catch (e: Throwable) {
                android_log("bucle: ${e.message}")
            }
            try { Thread.sleep(300) } catch (e: InterruptedException) { return }
        }
    }

    private fun android_log(s: String) { System.err.println("LightClient: $s") }

    /** Pasa a las colas de escritura lo que la máquina nativa quiere enviar y cierra lo que ha expulsado. */
    private fun volcar() {
        if (h == 0L) return
        for (c in conexiones.values.toList()) {
            val o = LightNative.salida(h, c.id)
            if (o.isNotEmpty() && !c.cerrada) c.cola.put(o)
        }
        for (id in LightNative.cierres(h)) {
            val c = conexiones[id] ?: continue
            baneadas.add(c.dir.toString())
            cerrar(c)
        }
    }

    private fun cerrar(c: Conexion) {
        c.cerrada = true
        try { c.socket.close() } catch (e: Exception) { }
        c.cola.offer(ByteArray(0))
    }

    // ---------------------------------------------------------------- conexiones

    private fun mantenerPares() {
        val (faltan, monitor) = synchronized(lock) {
            val normales = conexiones.values.count { !it.relay && !it.cerrada } + intentando.size
            val hayMonitor = conexiones.values.any { it.relay && !it.cerrada }
            Pair(objetivoPares - normales, quiereMonitor && !hayMonitor)
        }
        if (faltan <= 0 && !monitor) return
        if (pool.isEmpty() && ahora() - ultimoRefresco > 30_000) {
            ultimoRefresco = ahora()
            pool = ArrayDeque(try { resolver() } catch (e: Exception) { emptyList() })
        }
        var lanzar = maxOf(faltan, 0) + (if (monitor) 1 else 0)
        var esMonitor = monitor
        var intentos = 0
        while (lanzar > 0 && pool.isNotEmpty() && intentos++ < 64) {
            val d = pool.removeFirst()
            val k = d.toString()
            val saltar = synchronized(lock) {
                k in baneadas || k in intentando || conexiones.values.any { it.dir == d } ||
                    (falladas[k]?.let { ahora() - it < 60_000 } ?: false)
            }
            if (saltar) continue
            val relay = esMonitor; esMonitor = false
            synchronized(lock) { intentando.add(k) }
            Thread({ conectar(d, relay) }, "ligero-con").also { it.isDaemon = true; it.start() }
            lanzar--
        }
    }

    private fun conectar(d: Direccion, relay: Boolean) {
        val k = d.toString()
        var c: Conexion? = null
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(d.host, d.port), 5000)
            s.soTimeout = 0
            synchronized(lock) {
                if (!activo || h == 0L) { s.close(); return }
                c = Conexion(siguienteId++, d, s, relay)
                conexiones[c!!.id] = c!!
                LightNative.parNuevo(h, c!!.id, ahora(), relay)
                volcar()
            }
        } catch (e: Exception) {
            synchronized(lock) { falladas[k] = ahora() }
        } finally {
            synchronized(lock) { intentando.remove(k) }
        }
        val con = c ?: return
        val escritor = Thread({ escribir(con) }, "ligero-w${con.id}").also { it.isDaemon = true; it.start() }
        leer(con)
        con.cola.offer(ByteArray(0))
        try { escritor.join(500) } catch (e: InterruptedException) { }
    }

    private fun leer(c: Conexion) {
        val buf = ByteArray(65536)
        try {
            val ins = c.socket.getInputStream()
            while (activo && !c.cerrada) {
                val n = ins.read(buf)
                if (n < 0) break
                synchronized(lock) {
                    if (!activo || h == 0L) return
                    LightNative.recibido(h, c.id, buf, n, ahora())
                    volcar()
                }
            }
        } catch (e: Exception) {
        } finally {
            synchronized(lock) {
                cerrar(c)
                conexiones.remove(c.id)
                if (h != 0L) { LightNative.parCerrado(h, c.id, ahora()); volcar() }
            }
        }
    }

    private fun escribir(c: Conexion) {
        try {
            val out = c.socket.getOutputStream()
            while (!c.cerrada) {
                val b = c.cola.take()
                if (b.isEmpty()) { if (c.cerrada) break else continue }
                out.write(b); out.flush()
            }
        } catch (e: Exception) {
            synchronized(lock) { cerrar(c) }
        }
    }

    // ---------------------------------------------------------------- disco

    /** El fichero son las cabeceras 1..n de 80 bytes; al cargar se vuelve a validar todo. */
    private fun cargarCabeceras() {
        val f = ficheroCabeceras
        if (!f.exists()) { persistida = 0; return }
        var total = 0
        try {
            f.inputStream().buffered(1 shl 20).use { ins ->
                val trozo = ByteArray(80 * 10_000)
                while (true) {
                    var n = 0
                    while (n < trozo.size) { val r = ins.read(trozo, n, trozo.size - n); if (r < 0) break; n += r }
                    val completas = n / 80
                    if (completas == 0) break
                    val buenas = LightNative.cargar(h, trozo.copyOf(completas * 80))
                    total += buenas
                    if (buenas < completas || n < trozo.size) break
                }
            }
        } catch (e: Exception) { android_log("cargar cabeceras: ${e.message}") }
        persistida = total
        // Lo que no se aceptó (cortado o alterado) se recorta, para no volver a leerlo.
        try { RandomAccessFile(f, "rw").use { if (it.length() != 80L * total) it.setLength(80L * total) } } catch (e: Exception) { }
    }

    private var ultimoGuardado = 0L
    private fun guardarPeriodico() {
        if (ahora() - ultimoGuardado < 2000) return
        ultimoGuardado = ahora()
        synchronized(lock) { if (h != 0L) { guardarCabeceras(); recordarBuenos() } }
    }

    private fun guardarCabeceras() {
        if (h == 0L) return
        try {
            val rm = LightNative.reorgMin(h)
            val alt = LightNative.altura(h)
            if (rm < 0 && alt <= persistida) return
            RandomAccessFile(ficheroCabeceras, "rw").use { raf ->
                if (rm >= 0 && rm < persistida) { persistida = rm.toInt(); raf.setLength(80L * persistida) }
                if (alt > persistida) {
                    val datos = LightNative.exportar(h, persistida + 1)
                    raf.seek(80L * persistida)
                    raf.write(datos)
                    persistida += datos.size / 80
                }
            }
        } catch (e: Exception) { android_log("guardar cabeceras: ${e.message}") }
    }

    /** Los pares que han servido (siguen conectados cuando ya hay varios listos) se guardan para la próxima vez. */
    private fun recordarBuenos() {
        val vivos = conexiones.values.filter { !it.cerrada && !it.relay }.map { it.dir }
        if (vivos.size < minPares) return
        Seeds.recordar(dir, vivos)
    }

    /** Direcciones de los nodos, de los DNS seeds y de los que ya sirvieron. */
    object Seeds {
        private val DNS = listOf(
            "seed.bitcoin.sipa.be", "dnsseed.bluematt.me", "seed.bitcoin.jonasschnelli.ch",
            "seed.btc.petertodd.net", "seed.bitcoin.sprovoost.nl", "dnsseed.emzy.de",
            "seed.bitcoin.wiz.biz", "seed.mainnet.achownodes.xyz")

        private fun fichero(dir: File) = File(dir, "pares_btc.txt")

        fun leerBuenos(dir: File): List<Direccion> = try {
            fichero(dir).readLines().mapNotNull { l ->
                val i = l.lastIndexOf(':')
                if (i <= 0) null else l.substring(i + 1).toIntOrNull()?.let { Direccion(l.substring(0, i), it) }
            }
        } catch (e: Exception) { emptyList() }

        fun recordar(dir: File, pares: List<Direccion>) {
            try {
                val todos = (pares + leerBuenos(dir)).distinct().take(40)
                fichero(dir).writeText(todos.joinToString("\n") { it.toString() })
            } catch (e: Exception) { }
        }

        fun resolver(dir: File): List<Direccion> {
            val out = ArrayList<Direccion>(leerBuenos(dir))
            val dns = ArrayList<Direccion>()
            for (s in DNS.shuffled()) {
                try { for (a in InetAddress.getAllByName(s)) a.hostAddress?.let { dns.add(Direccion(it, 8333)) } }
                catch (e: Exception) { }
                if (dns.size >= 200) break
            }
            dns.shuffle()
            out.addAll(dns)
            return out.distinct()
        }
    }
}
