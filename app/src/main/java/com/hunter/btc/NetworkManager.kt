package com.hunter.btc

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Modo red local multi-dispositivo.
 * Puerto TCP: 7771 (asignación de bloques)
 * Puerto UDP: 7772 (descubrimiento)
 */
object NetworkManager {

    const val TCP_PORT  = 7771
    const val UDP_PORT  = 7772
    const val APP_ID    = "WALLET_HUNTER_V2"   // V1 no tenía autenticación
    const val BLOCK_SIZE_HEX = "100000000" // 4B keys por bloque de red

    /* ── Límites defensivos ───────────────────────────────────────────────────
       El servidor escucha en 0.0.0.0 y acepta conexiones de cualquiera en la
       red, así que todo lo que venga de fuera va acotado. */

    /** Tope de puntos por envío. A 49 bytes cada uno, 2048 son ~100 KB. */
    private const val MAX_PUNTOS_ENVIO = 2048
    /* Tamaños del formato de red de kangaroo.h, para que el límite de abajo
       salga de ellos y no de un número redondo elegido a ojo. */
    private const val KG_CAB = 4 + 4 + 33 + 32 + 32 + 4 + 4   // 113
    private const val KG_ENT = 16 + 32 + 1                    // 49

    /**
     * Lo más largo que se acepta en una línea.
     *
     * Eran 8 KB fijos, y NO LLEGABAN. Un envío de puntos distinguidos son
     * 113 + n·49 bytes en base64 dentro de un JSON: con 8 KB sólo caben 123
     * puntos, pero el tope de envío son 2048, que ocupan 134 KB. O sea 16 veces
     * el límite.
     *
     * Lo que pasaba entonces: el maestro cortaba la conexión con "line too
     * long", el trabajador lo contaba como fallo de red y se quedaba
     * REINTENTANDO EL MISMO BLOQUE PARA SIEMPRE. Y como mientras hay un bloque
     * pendiente no se exporta nada más, ese móvil dejaba de aportar del todo
     * sin que nada lo dijera: en la lista del maestro seguía apareciendo con su
     * velocidad, buscando, pero sin entregar un punto nunca más.
     *
     * No ha saltado todavía por suerte: en el puzzle #140 dbits está topado en
     * 28, o sea un punto cada ~21 s a 12,8 M/s, y los envíos llevan uno o dos.
     * Salta con rangos más pequeños —dbits baja con el tamaño del rango, y con
     * 40 bits son 14, o sea cientos de puntos por segundo— y con cualquier
     * trabajador que haya estado un rato desconectado y traiga atrasos.
     *
     * Ahora sale del tope de envío, así que los dos no pueden volver a
     * separarse. El x2 es para el JSON de alrededor y para no ir justos.
     */
    private const val MAX_LINE_BYTES  = 2 * (KG_CAB + MAX_PUNTOS_ENVIO * KG_ENT) * 4 / 3
    private const val MAX_WORKERS     = 64
    private const val MAX_NET_THREADS = 16
    private const val SOCKET_TIMEOUT_MS = 15_000

    /** Secreto compartido: el master lo genera y lo muestra, el worker lo teclea. */
    @Volatile var authToken: String = ""
        private set

    private fun generateToken(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   // sin caracteres ambiguos
        val rnd = java.security.SecureRandom()
        return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    /** Comparación en tiempo constante para no filtrar el token carácter a carácter. */
    private fun tokenMatches(received: String?): Boolean {
        val expected = authToken
        if (expected.isEmpty() || received == null) return false
        return java.security.MessageDigest.isEqual(
            received.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    /** readLine() sin cota permite que un peer agote la memoria con una línea infinita. */
    private fun readLineLimited(reader: BufferedReader): String? {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (c != '\r'.code) {
                sb.append(c.toChar())
                if (sb.length > MAX_LINE_BYTES) throw IOException("line too long")
            }
        }
    }

    /* ── Por dónde van las conexiones ─────────────────────────────────────────
     *
     * El cluster habla siempre igual —líneas JSON, una por mensaje— pero puede
     * ir por dos caminos:
     *
     *   sockets  lo de siempre. Vale en la misma WiFi, y fuera de ella sólo si
     *            hay IP pública y puertos abiertos.
     *   tsnet    el nodo de Tailscale empotrado en la app. Atraviesa el CGNAT
     *            de la operadora por los dos lados y va cifrado.
     *
     * [Canal] es lo que tienen en común: un lector, un escritor y quién hay al
     * otro lado. Todo lo demás del protocolo no se entera de por dónde va.
     *
     * NADIE cierra el lector ni el escritor por su cuenta. En tsnet los dos
     * salen del MISMO descriptor, así que cerrar uno tumba la conexión entera
     * —incluida la dirección contraria—. Se cierra el Canal y ya.
     */
    private class Canal(
        val lector:   BufferedReader,
        val escritor: PrintWriter,
        /** Dirección del otro extremo, para identificar al trabajador. */
        val remoto:   String,
        private val cerrarlo: () -> Unit
    ) : java.io.Closeable {
        override fun close() { try { cerrarlo() } catch (e: Throwable) {} }
    }

    /**
     * Si el cluster va por tsnet en vez de por sockets.
     *
     * Por omisión NO: empotrar tsnet es aditivo a propósito. Si la compilación
     * no lo lleva, si el nodo no arranca o si la clave no vale, el cluster tiene
     * que seguir funcionando exactamente como funcionaba.
     */
    @Volatile var usarTsnet = false
        private set

    /**
     * Enciende o apaga el camino por tsnet.
     *
     * @return false si se pide encenderlo y no se puede —esta compilación no lo
     *   lleva, o el nodo no está arrancado—. Se devuelve en vez de tragarlo para
     *   que la pantalla pueda decirlo: quedarse en sockets creyendo que vas por
     *   la VPN es la clase de cosa que luego no se entiende.
     */
    fun activarTsnet(si: Boolean): Boolean {
        if (!si) { usarTsnet = false; return true }
        val hay = try { TsNet.disponible() && TsNet.arrancado } catch (e: Throwable) { false }
        usarTsnet = hay
        return hay
    }

    /**
     * Abre una conexión hacia [destino].
     *
     * LANZA si no puede, en vez de devolver null: todos los sitios que la usan
     * ya están dentro de un try/catch que trata los fallos de red, así que
     * lanzando se comportan igual que cuando esto era un Socket pelado. Con un
     * null habría que acordarse de comprobarlo en ocho sitios.
     */
    private fun abrirCanal(destino: String, puerto: Int = TCP_PORT): Canal {
        if (usarTsnet) {
            val c = TsNet.conectar("$destino:$puerto")
                ?: throw IOException("tsnet no pudo conectar con $destino" +
                                     (TsNet.ultimoFallo().let { if (it.isEmpty()) "" else ": $it" }))
            return Canal(BufferedReader(InputStreamReader(c.entrada)),
                         PrintWriter(c.salida, true),
                         destino) { c.close() }
        }
        val s = Socket()
        s.connect(java.net.InetSocketAddress(destino, puerto), 10000)
        s.soTimeout = SOCKET_TIMEOUT_MS
        return Canal(BufferedReader(InputStreamReader(s.getInputStream())),
                     PrintWriter(s.getOutputStream(), true),
                     s.inetAddress?.hostAddress ?: destino) { s.close() }
    }

    /** El lado que espera conexiones. Mismo papel que ServerSocket. */
    private interface Escuchador : java.io.Closeable {
        /** Bloquea hasta que llegue alguien. null si se ha cerrado. */
        fun aceptar(): Canal?
    }

    private fun abrirEscuchador(): Escuchador {
        if (usarTsnet) {
            val l = TsNet.escuchar(TCP_PORT)
            if (l < 0) throw IOException(
                "tsnet no pudo escuchar en $TCP_PORT" +
                (TsNet.ultimoFallo().let { if (it.isEmpty()) "" else ": $it" }))
            return object : Escuchador {
                override fun aceptar(): Canal? {
                    val fd = TsNet.aceptar(l)
                    if (fd < 0) return null
                    // Quién ha llamado. Con tsnet no hay Socket del que sacarlo,
                    // y el maestro identifica a los trabajadores por ahí.
                    val quien = try { TsNet.remoto(l, fd) } catch (e: Throwable) { "" }
                    val c = TsNet.Conexion(fd)
                    return Canal(BufferedReader(InputStreamReader(c.entrada)),
                                 PrintWriter(c.salida, true),
                                 quien.substringBeforeLast(':').ifEmpty { "tsnet" }) { c.close() }
                }
                override fun close() {
                    // No hay tailscale_listener_close: se cierra el descriptor.
                    try { android.os.ParcelFileDescriptor.adoptFd(l).close() }
                    catch (e: Throwable) {}
                }
            }
        }
        val ss = ServerSocket(TCP_PORT)
        return object : Escuchador {
            override fun aceptar(): Canal? {
                val s = try { ss.accept() } catch (e: Exception) { return null }
                s.soTimeout = SOCKET_TIMEOUT_MS
                return Canal(BufferedReader(InputStreamReader(s.getInputStream())),
                             PrintWriter(s.getOutputStream(), true),
                             s.inetAddress?.hostAddress ?: "") { s.close() }
            }
            override fun close() { try { ss.close() } catch (e: Exception) {} }
        }
    }

    data class NetBlock(
        val blockId:    String,
        val rangeStart: String,
        val rangeEnd:   String,
        val puzzleNum:  Int
    )

    data class NetWorker(
        val id:      String,
        val address: String,
        val device:  String,
        var speed:   Long   = 0,
        var status:  String = "idle",
        var block:   String = "",
        /** Cuándo se supo de él por última vez. Sin esto, un móvil que se
         *  apaga o se sale de la WiFi se quedaba listado como conectado para
         *  siempre, porque sólo se borraba al fallar una conexión suya. */
        var vistoMs: Long = System.currentTimeMillis(),
        /**
         * Lo que el maestro QUIERE que haga este trabajador, no lo que está
         * haciendo. Es un estado y no una orden de una vez a propósito: el
         * maestro lo repite en cada respuesta y el trabajador lo obedece si no
         * coincide con lo suyo. Así una orden que se pierda —porque el envío
         * falló, porque el móvil estaba reiniciándose— se aplica sola en el
         * siguiente intercambio, que es como mucho veinte segundos después. Con
         * órdenes de una vez habría que acertar a la primera o quedarse con el
         * cluster a medio pausar y sin forma de saberlo.
         */
        var pausado: Boolean = false
    )

    /**
     * Qué se reparte.
     *
     * - [BLOQUES] es fuerza bruta: el rango se parte en trozos y cada worker se
     *   come uno. Es lo correcto ahí, porque el coste es O(W) y partirlo entre
     *   N divide el tiempo entre N.
     *
     * - [KANGAROO] **no parte nada**. Con Kangaroo el coste es O(raíz(W)), y
     *   partir el intervalo lo EMPEORA: cada trozo cuesta raíz(W/N) pero hay
     *   que recorrer varios porque no se sabe en cuál está la clave. Con dos
     *   aparatos sale 1,06·raíz(W) frente a 1,00 con uno solo; con ocho, 1,59.
     *   Lo que se reparte es la tabla de puntos distinguidos: todos caminan el
     *   MISMO intervalo y mandan sus puntos al master, que los junta. Así la
     *   colisión aparece aunque sus dos mitades estén en móviles distintos.
     */
    enum class Modo { BLOQUES, KANGAROO }

    // ── Estado ────────────────────────────────────────────────────────────────
    var isMaster  = false
    var isWorker  = false
    val isRunning = AtomicBoolean(false)
    @Volatile var modo = Modo.BLOQUES
    val globalScannedBlocks = ConcurrentHashMap.newKeySet<String>()
    var onBlockScanned: ((String, Int) -> Unit)? = null  // blockId, puzzleNum
    var deviceId  = ""
    var onLog:     ((String) -> Unit)? = null
    var onWorkers: ((List<NetWorker>) -> Unit)? = null
    var onBlock:   ((NetBlock) -> Unit)? = null  // worker recibe bloque

    /**
     * IP del master, fijada al conectar.
     *
     * Antes se leía de unas prefs "net_prefs"/"master_ip" **que no escribía
     * nadie**: el worker recibía su primer bloque y ya no volvía a hablar con el
     * master nunca más, porque no sabía a dónde. Aquí la guarda quien se conecta,
     * que es el único que la conoce con seguridad.
     */
    @Volatile var masterIp: String = ""
        private set

    /** El worker recibe el encargo de Kangaroo: (pubHex, iniHex, finHex, puzzle). */
    var onKangaroo: ((String, String, String, Int) -> Unit)? = null
    /** El master recibe una clave que ha encontrado un worker. */
    var onClave: ((String, String) -> Unit)? = null   // (deviceId, claveHex)
    /** Puntos distinguidos recibidos de los workers, para enseñarlo. */
    val puntosRecibidos = java.util.concurrent.atomic.AtomicLong(0)
    /** Y los que este aparato ha mandado, si es trabajador. La pantalla del
     *  trabajador enseñaba la lista de workers —información de maestro— y
     *  ponía "ningún trabajador todavía", que parece un fallo de conexión
     *  cuando en realidad está trabajando. */
    val puntosEnviados = java.util.concurrent.atomic.AtomicLong(0)
    /** Cuándo entró el último envío. 0 = ninguno todavía. */
    @Volatile var ultimoEnvioMs = 0L
        private set

    /**
     * El puzzle que se reparte ya no tiene fondos: alguien lo ha resuelto. Lo
     * detecta el master vigilando el saldo.
     *
     * Los workers se enteran en su siguiente envío de puntos, aprovechando la
     * respuesta que ya viajaba de vuelta. No hace falta un mensaje nuevo ni que
     * el master sepa abrir conexiones hacia ellos: el worker ya pregunta cada
     * veinte segundos.
     */
    @Volatile private var puzzleVacio = false

    fun marcarPuzzleVacio() {
        if (!puzzleVacio) {
            puzzleVacio = true
            log("El puzzle ya no tiene fondos: se avisará a los workers")
        }
    }

    // El encargo en curso. Antes viajaba como parámetros de handleWorkerConnection
    // capturados en el lambda; con Kangaroo son cuatro valores y se lía.
    @Volatile private var jobPuzzle = 0
    @Volatile private var jobIni    = ""
    @Volatile private var jobFin    = ""
    @Volatile private var jobPub    = ""

    private val workers     = ConcurrentHashMap<String, NetWorker>()
    private val assignedBlocks = ConcurrentHashMap<String, NetBlock>()
    // Acotado: newCachedThreadPool() creaba un hilo por conexión entrante, así que
    // abrir muchas conexiones agotaba hilos y memoria.
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(MAX_NET_THREADS) { r ->
            Thread(r, "net").apply { isDaemon = true }
        }
    /** El que espera conexiones: ServerSocket o tsnet, segun el camino. */
    private var escuchador: Escuchador? = null
    private var udpSocket:    DatagramSocket? = null

    // ── Master ────────────────────────────────────────────────────────────────
    /**
     * Master repartiendo bloques (fuerza bruta).
     *
     * LÍMITE CONOCIDO: el master no se reserva bloque. Sigue escaneando el
     * rango entero por su cuenta, así que duplica trabajo que ya están haciendo
     * los workers y su avance no entra en el recuento global. No está mal en el
     * sentido de dar resultados falsos —si encuentra la clave, la encuentra—,
     * pero con N workers el reparto real es N y no N+1.
     *
     * No se arregla aquí porque la búsqueda del master la gobierna la pantalla,
     * no este objeto: haría falta que se pidiera bloque a sí mismo por el mismo
     * camino que un worker.
     */
    fun startMaster(ctx: Context, puzzleNum: Int, rangeStart: String, rangeEnd: String) =
        arrancarMaster(ctx, Modo.BLOQUES, puzzleNum, rangeStart, rangeEnd, "")

    /**
     * Master repartiendo Kangaroo.
     *
     * A diferencia del modo de bloques, aquí **no se parte el rango**: a todos
     * los workers se les manda el intervalo entero y la misma clave pública. Lo
     * que se junta son sus tablas de puntos distinguidos.
     *
     * Este aparato tiene que tener su propia búsqueda de Kangaroo en marcha:
     * los puntos que llegan se meten en SU tabla, y es ahí donde aparece la
     * colisión entre dos móviles.
     */
    fun startMasterKangaroo(ctx: Context, puzzleNum: Int, pubHex: String,
                            iniHex: String, finHex: String) =
        arrancarMaster(ctx, Modo.KANGAROO, puzzleNum, iniHex, finHex, pubHex)

    private fun arrancarMaster(ctx: Context, m: Modo, puzzleNum: Int,
                               rangeStart: String, rangeEnd: String, pubHex: String) {
        isMaster = true; isWorker = false
        isRunning.set(true)
        modo = m
        jobPuzzle = puzzleNum; jobIni = rangeStart; jobFin = rangeEnd; jobPub = pubHex
        puntosRecibidos.set(0)
        deviceId = android.os.Build.MODEL.replace(" ", "_")
        authToken = generateToken()
        log("Master iniciado — Puzzle #$puzzleNum (${if (m == Modo.KANGAROO) "Kangaroo" else "bloques"})")
        log("Código de acceso: $authToken")
        if (m == Modo.KANGAROO)
            log("Todos al mismo rango: $rangeStart → $rangeEnd")
        else
            log("Rango: $rangeStart → $rangeEnd")

        // Servidor
        executor.submit {
            try {
                val esc = abrirEscuchador()
                escuchador = esc
                log("Escuchando en puerto $TCP_PORT" +
                    (if (usarTsnet) " (por tsnet)" else ""))
                while (isRunning.get()) {
                    val c = esc.aceptar() ?: break
                    executor.submit { handleWorkerConnection(c) }
                }
            } catch (e: Exception) {
                if (isRunning.get()) log("Error servidor: ${e.message}")
            }
        }

        // Beacon UDP para descubrimiento
        executor.submit { broadcastBeacon(ctx) }
    }

    private fun handleWorkerConnection(canal: Canal) {
        val puzzleNum = jobPuzzle
        val rangeStart = jobIni
        val rangeEnd = jobFin
        val workerId = canal.remoto.ifEmpty { return }
        try {
            val reader = canal.lector
            val writer = canal.escritor

            val msg = JSONObject(readLineLimited(reader) ?: return)

            // Sin esto cualquiera en la red local podía operar el protocolo.
            if (!tokenMatches(msg.optString("auth", null))) {
                log("Conexión rechazada de $workerId (código inválido)")
                writer.println(JSONObject().put("type", "AUTH_FAIL").toString())
                return
            }
            if (workers.size >= MAX_WORKERS && !workers.containsKey(workerId)) {
                log("Worker $workerId rechazado: límite de $MAX_WORKERS alcanzado")
                return
            }

            workers[workerId]?.vistoMs = System.currentTimeMillis()

            when (msg.optString("type")) {
                "REGISTER" -> {
                    val device = msg.optString("device", workerId)
                    val worker = NetWorker(workerId, workerId, device)
                    workers[workerId] = worker
                    log("Worker conectado: $device ($workerId)")
                    notifyWorkers()

                    if (modo == Modo.KANGAROO) {
                        // Mismo intervalo para todos: en Kangaroo repartir el
                        // rango empeora la búsqueda en vez de acelerarla.
                        worker.block = "kangaroo"
                        worker.status = "kangaroo"
                        writer.println(JSONObject().apply {
                            put("type",   "KANG")
                            put("pub",    jobPub)
                            put("start",  rangeStart)
                            put("end",    rangeEnd)
                            put("puzzle", puzzleNum)
                        }.toString())
                        log("Encargo Kangaroo enviado a $device (rango completo)")
                    } else {
                        // Asignar bloque
                        val block = nextBlock(workerId, puzzleNum, rangeStart, rangeEnd)
                        worker.block = block.blockId
                        writer.println(JSONObject().apply {
                            put("type",  "BLOCK")
                            put("block_id",  block.blockId)
                            put("start", block.rangeStart)
                            put("end",   block.rangeEnd)
                            put("puzzle", block.puzzleNum)
                        }.toString())
                        log("Bloque ${block.blockId} asignado a $device")
                    }
                }
                "DP" -> {
                    // Puntos distinguidos de un worker. Van a la tabla de ESTE
                    // aparato, que es donde se juntan los de todos: ahí aparece
                    // la colisión aunque sus dos mitades vengan de móviles
                    // distintos. El motor comprueba dentro que el bloque es del
                    // mismo puzzle, rango y criterio; si no, lo rechaza entero.
                    //  n >= 0  entraron
                    //  n == -1  el motor lo rechaza por contenido: otro puzzle,
                    //           otro rango, mensaje roto. Reintentarlo no lo va
                    //           a arreglar.
                    //  n == -2  aquí no hay búsqueda en marcha ahora mismo. Eso
                    //           SÍ se arregla solo, así que el worker tiene que
                    //           guardarlos y volver a intentarlo: si no, parar
                    //           un momento la búsqueda del master tiraría a la
                    //           basura todo lo que llegara mientras.
                    val vivo = try { HunterEngine.kangarooRunning() } catch (e: Throwable) { false }
                    val n = if (puzzleVacio) -3 else if (!vivo) -2 else try {
                        val crudo = android.util.Base64.decode(
                            msg.optString("data", ""), android.util.Base64.NO_WRAP)
                        if (crudo.isEmpty()) -1 else HunterEngine.kangarooImport(crudo)
                    } catch (e: Throwable) { -1 }
                    when {
                        n >= 0 -> {
                            puntosRecibidos.addAndGet(n.toLong())
                            workers[workerId]?.status = "kangaroo"
                        }
                        n == -3 -> log("A $workerId se le dice que pare: el puzzle ya no tiene fondos")
                        n == -2 -> log("Puntos de $workerId en espera: aquí no hay búsqueda en marcha")
                        else    -> log("Puntos rechazados de $workerId (otro puzzle o mensaje roto)")
                    }
                    writer.println(JSONObject().apply {
                        put("type", "DP_OK"); put("n", n)
                        put("cmd", ordenPara(workerId))
                    }.toString())
                }
                // Latido de un trabajador PARADO. Uno que busca ya habla cada
                // veinte segundos al mandar sus puntos, y la orden viaja en esa
                // respuesta. Pero uno pausado no tiene puntos que mandar y se
                // quedaría mudo para siempre: sin esto se podría pausar y nunca
                // reanudar, que es la mitad inútil de un botón.
                "PING" -> {
                    workers[workerId]?.let {
                        it.status = if (it.pausado) "pausado" else "esperando"
                        notifyWorkers()
                    }
                    writer.println(JSONObject().apply {
                        put("type", "PONG")
                        put("cmd", ordenPara(workerId))
                        // El encargo va en el latido para que un trabajador que
                        // se reanuda sepa a qué ponerse aunque lo hayan matado y
                        // relanzado entre medias.
                        put("pub", jobPub); put("start", jobIni)
                        put("end", jobFin); put("puzzle", jobPuzzle)
                    }.toString())
                }
                "KEY" -> {
                    // Un worker la ha encontrado él solo.
                    //
                    // Hace falta este aviso además del intercambio de tablas:
                    // cuando un aparato cierra la colisión en su propia tabla,
                    // la entrada que la cierra NO llega a guardarse (dp_insert
                    // avisa y sale), así que por muchos puntos que mande, el
                    // master se queda siempre con media pareja y no puede
                    // repetir la cuenta. Está comprobado en tools/ec-harness/
                    // reparte.cpp, prueba 4.
                    val clave = msg.optString("key", "").trim().lowercase()
                    val dev = workers[workerId]?.device ?: workerId
                    if (clave.length == 64 && clave.all { it in "0123456789abcdef" }) {
                        log("CLAVE ENCONTRADA por $dev")
                        onClave?.invoke(dev, clave)
                    } else {
                        log("Aviso de clave inválido de $workerId")
                    }
                }
                "PROGRESS" -> {
                    val speed = msg.optLong("speed", 0)
                    workers[workerId]?.speed = speed
                    notifyWorkers()
                }
                "DONE" -> {
                    val blockId = msg.optString("block_id")
                    log("Bloque $blockId completado por $workerId")
                    assignedBlocks.remove(workerId)
                    workers[workerId]?.status = "idle"
                    // Registrar bloque globalmente
                    globalScannedBlocks.add(blockId)
                    onBlockScanned?.invoke(blockId, puzzleNum)

                    // Asignar nuevo bloque
                    val block = nextBlock(workerId, puzzleNum, rangeStart, rangeEnd)
                    workers[workerId]?.block = block.blockId
                    writer.println(JSONObject().apply {
                        put("type",     "BLOCK")
                        put("block_id", block.blockId)
                        put("start",    block.rangeStart)
                        put("end",      block.rangeEnd)
                        put("puzzle",   block.puzzleNum)
                    }.toString())
                }
                "SYNC_REQUEST" -> {
                    // Worker pide lista de bloques escaneados
                    val syncData = JSONObject().apply {
                        put("type",   "SYNC_RESPONSE")
                        put("blocks", org.json.JSONArray(globalScannedBlocks.toList()))
                        put("puzzle", puzzleNum)
                    }
                    writer.println(syncData.toString())
                    log("Sync enviado a $workerId: ${globalScannedBlocks.size} bloques")
                }
                "MATCH" -> {
                    // Sólo se notifica la dirección. La clave privada NUNCA viaja
                    // por la red: se queda en el dispositivo que la encontró.
                    val addr = msg.optString("addr")
                    log("Coincidencia de ${workers[workerId]?.device}: $addr")
                    onLog?.invoke("Coincidencia en ${workers[workerId]?.device ?: workerId}: $addr")
                }
            }
        } catch (e: Exception) {
            log("Error con worker $workerId: ${e.message}")
            workers.remove(workerId)
            notifyWorkers()
        } finally {
            canal.close()
        }
    }

    /**
     * Elige y reserva un bloque. Va sincronizado: antes se comprobaba si el
     * bloque estaba libre y se asignaba después, sin atomicidad, así que dos
     * workers que registraran a la vez podían recibir el mismo rango.
     */
    @Synchronized
    private fun nextBlock(workerId: String, puzzleNum: Int,
                          rangeStart: String, rangeEnd: String): NetBlock {
        val start = java.math.BigInteger(rangeStart.trimStart('0').ifEmpty{"0"}, 16)
        val size  = java.math.BigInteger(BLOCK_SIZE_HEX, 16)
        val end   = java.math.BigInteger(rangeEnd.trimStart('0').ifEmpty{"0"}, 16)

        // Todo en BigInteger. Antes iba a Long y se recortaba a un millón:
        //
        //   .toLong().coerceAtLeast(1).coerceAtMost(1_000_000)
        //
        // Dos fallos en una línea. El recorte dejaba al cluster repartiendo sólo
        // el primer millón de bloques, o sea 2^52 claves pasara lo que pasara:
        // en el puzzle #71 es el 0,00036 % del rango, y en el #80 el
        // 0,0000007 %. El 99,9996 % restante era inalcanzable.
        //
        // Y el .toLong() truncaba en silencio: el #160 tiene 2^127 bloques, que
        // no caben en un Long, así que se quedaba con los 64 bits bajos. El
        // recorte tapaba el destrozo, de ahí que nunca se notara.
        val totalBlocks = end.subtract(start).divide(size).max(java.math.BigInteger.ONE)
        val taken = assignedBlocks.values.mapTo(HashSet()) { it.blockId }
        val rnd = java.security.SecureRandom()
        // Un índice al azar uniforme en [0, totalBlocks). nextDouble() no vale
        // aquí: un Double sólo tiene 53 bits de mantisa y estos rangos pasan de
        // 2^100, así que la mayoría de los bloques no serían ni alcanzables.
        fun alAzar(): java.math.BigInteger {
            val bits = totalBlocks.bitLength()
            while (true) {
                val v = java.math.BigInteger(bits, rnd)
                if (v < totalBlocks) return v
            }
        }
        var blockIdx = alAzar()
        var attempts = 0
        while ((taken.contains(blockIdx.toString()) ||
                globalScannedBlocks.contains(blockIdx.toString())) && attempts < 200) {
            blockIdx = alAzar(); attempts++
        }

        val bStart = start.add(size.multiply(blockIdx))
        val bEnd   = bStart.add(size).min(end)

        val block = NetBlock(
            blockId    = blockIdx.toString(),
            rangeStart = bStart.toString(16).padStart(18, '0'),
            rangeEnd   = bEnd.toString(16).padStart(18, '0'),
            puzzleNum  = puzzleNum
        )
        assignedBlocks[workerId] = block   // reservado dentro del bloque sincronizado
        return block
    }

    // ── Worker ────────────────────────────────────────────────────────────────
    /* ── Arranque del motor ───────────────────────────────────────────────────
     *
     * Esto vivía en NetworkActivity, y ahí no podía estar: cuando Android mata
     * el proceso y vuelve a levantar el servicio (START_STICKY), no hay ninguna
     * pantalla viva que lo llame. El servicio necesita poder arrancar el motor
     * él solo, así que la función tiene que estar donde los dos la alcancen.
     *
     * @param hilos −1 usa los que tenga puestos el usuario. CERO es el modo
     *   RECOLECTOR: la tabla queda viva para juntar los puntos de los
     *   trabajadores, pero aquí no camina ningún canguro.
     */
    fun arrancarMotorKangaroo(ctx: Context, pub: String, ini: String, fin: String,
                              hilos: Int = -1): Boolean {
        if (pub.length != 66 || ini.isEmpty() || fin.isEmpty()) return false
        appCtx = ctx.applicationContext

        // ¿Ya hay un Kangaroo andando? kangarooStart devuelve false sin más si
        // lo hay, y eso esconde un caso que importa: que esté buscando OTRA
        // clave. Pasa al recuperarse de que Android mate la app —el servicio
        // relanza lo que había guardado y acto seguido el maestro manda un
        // encargo que puede ser otro— y pasaría también si el maestro cambia de
        // puzzle con el cluster en marcha.
        //
        // Sin esto el trabajador se quedaría buscando el puzzle viejo, mandando
        // puntos que el maestro rechaza por no cuadrar la cabecera, y en la
        // lista seguiría saliendo como que trabaja.
        val yaVive = try { HunterEngine.kangarooRunning() } catch (e: Throwable) { false }
        if (yaVive) {
            val actual = try { HunterEngine.kangarooPub() } catch (e: Throwable) { "" }
            val mismosHilos = hilos < 0 ||
                (try { HunterEngine.kangarooHilos() } catch (e: Throwable) { -1 }) == hilos
            // La misma clave y el mismo papel: no hay nada que hacer, y pararlo
            // para volver a arrancarlo sólo perdería tiempo.
            if (actual.equals(pub, ignoreCase = true) && mismosHilos) return true
            // kangarooStop() guarda la tabla antes de soltarla, así que cambiar
            // de puzzle no tira el trabajo del anterior: queda en su fichero.
            try { HunterEngine.kangarooStop() } catch (e: Throwable) {}
        }

        // Si ya hay fuerza bruta en marcha, se para: los dos motores compiten
        // por los mismos núcleos y juntos van peor que cualquiera por separado.
        // Recolectando no compite con nadie, así que no hay por qué pararla.
        try { if (hilos != 0 && HunterEngine.isRunning()) HunterEngine.stopHunting() } catch (e: Throwable) {}

        val prefs = ctx.getSharedPreferences("hunter", Context.MODE_PRIVATE)
        val nucleos = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val hilosReales = if (hilos >= 0) hilos
                          else (prefs.getInt("puzzle_threads", 3) + 1).coerceIn(1, nucleos)
        val cpu   = (prefs.getInt("puzzle_cpu", 70) + 10).coerceIn(10, 100)
        val porHilo = try { HunterEngine.getBatchSize() } catch (e: Throwable) { 512 }
            .coerceIn(256, 4096)
        val ruta = java.io.File(ctx.filesDir, "kangaroo_${pub.take(16)}.dat").absolutePath

        val ok = try {
            HunterEngine.kangarooStart(pub, ini, fin, hilosReales, porHilo, ruta,
                                       HunterEngine.topeTablaBits(ctx))
        } catch (e: Throwable) {
            android.util.Log.e("NetworkManager", "kangarooStart: ${e.message}", e); false
        }
        if (!ok) return false
        try { HunterEngine.kangarooSetCpu(cpu) } catch (e: Throwable) {}
        // Dejar dicho QUÉ se está buscando, para que cualquiera que encuentre el
        // motor parado pueda relanzarlo: el watchdog de la pantalla principal y
        // ahora también el servicio al volver de que lo maten.
        //
        // Recolectando NO se marca: no hay búsqueda que relanzar, y marcarlo
        // haría que se arrancara una de verdad —con todos los núcleos— en el
        // móvil al que se le acaba de pedir justo lo contrario.
        prefs.edit().putBoolean("kangaroo_corriendo", hilosReales > 0)
            .putString("kangaroo_pub", pub)
            .putString("kangaroo_ini", ini)
            .putString("kangaroo_fin", fin).apply()
        val svc = android.content.Intent(ctx, HunterService::class.java)
        try { ctx.startForegroundService(svc) } catch (e: Exception) { ctx.startService(svc) }
        return true
    }

    /* ── Sobrevivir a que Android mate la app ─────────────────────────────────
     *
     * El servicio es START_STICKY, así que Android lo vuelve a levantar. Pero
     * volvía con su notificación y nada más: ni buscando ni conectado al
     * maestro, porque el estado del cluster vivía sólo en memoria. Por fuera se
     * veía igual que si todo fuera bien — la notificación puesta— mientras el
     * móvil no aportaba nada, y en la lista del maestro desaparecía a los diez
     * minutos sin más explicación.
     *
     * Con esto en disco, el servicio puede volver a ser trabajador él solo.
     */
    /** Contexto de APLICACIÓN, para poder escribir preferencias desde aquí sin
     *  depender de que haya una pantalla viva. El de aplicación no se filtra:
     *  dura lo mismo que el proceso. */
    @Volatile private var appCtx: Context? = null

    private fun guardarSesionDeWorker(ctx: Context?, ip: String, token: String) {
        val c = ctx ?: return
        try {
            c.getSharedPreferences("hunter", Context.MODE_PRIVATE).edit()
                .putBoolean("net_worker", true)
                .putString("net_master_ip", ip)
                .putString("net_token", token).apply()
        } catch (e: Throwable) {}
    }

    private fun olvidarSesionDeWorker(ctx: Context?) {
        val c = ctx ?: return
        try {
            c.getSharedPreferences("hunter", Context.MODE_PRIVATE).edit()
                .putBoolean("net_worker", false)
                .remove("net_master_ip").remove("net_token").apply()
        } catch (e: Throwable) {}
    }

    /**
     * Volver a ser trabajador después de que Android matara la app.
     *
     * Lo llama el servicio al levantarse. Deja instalado un [onKangaroo] propio
     * si no hay ninguno, porque en este camino puede que no se haya abierto
     * ninguna pantalla: sin él, el trabajador se registraría, recibiría su
     * encargo y no arrancaría nada.
     *
     * @return true si había una sesión que reanudar.
     */
    fun reanudarSesionDeWorker(ctx: Context): Boolean {
        if (isRunning.get()) return false          // ya está en marcha
        val p = ctx.getSharedPreferences("hunter", Context.MODE_PRIVATE)
        if (!p.getBoolean("net_worker", false)) return false
        val ip = p.getString("net_master_ip", "") ?: ""
        val tk = p.getString("net_token", "") ?: ""
        if (ip.isEmpty() || tk.isEmpty()) return false
        val app = ctx.applicationContext
        if (onKangaroo == null) onKangaroo = { pub, ini, fin, _ ->
            try { arrancarMotorKangaroo(app, pub, ini, fin) }
            catch (e: Throwable) {
                android.util.Log.e("NetworkManager", "reanudar: ${e.message}", e)
            }
        }
        log("Reanudando como trabajador de $ip tras reiniciarse la app")
        startWorker(ip, tk, app)
        return true
    }

    fun startWorker(masterIp: String, token: String, ctx: Context? = null) {
        isWorker = true; isMaster = false
        isRunning.set(true)
        pausadoPorMaestro = false
        if (ctx != null) appCtx = ctx.applicationContext
        guardarSesionDeWorker(appCtx, masterIp.trim(), token.trim().uppercase())
        this.masterIp = masterIp.trim()
        deviceId = android.os.Build.MODEL.replace(" ", "_")
        authToken = token.trim().uppercase()
        log("Worker iniciando → Master: $masterIp")

        // Se intenta varias veces, espaciando. Antes era UN solo intento: si
        // pulsabas "Conectar" antes de que el maestro terminara de arrancar
        // —que es lo normal, porque el servidor tarda un momento en escuchar—
        // fallaba, lo escribia en el registro y ahi se quedaba. Habia que
        // volver a pulsar sin saber por que.
        executor.submit {
          var intento = 0
          while (isRunning.get() && isWorker && intento < 5) {
            if (intento > 0) {
                val espera = intento * 3000L
                log("Reintentando en ${espera / 1000} s… (intento ${intento + 1} de 5)")
                try { Thread.sleep(espera) } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt(); return@submit }
            }
            intento++
            try {
                val canal = abrirCanal(masterIp)
                val writer = canal.escritor
                val reader = canal.lector

                // Registrar
                writer.println(JSONObject().apply {
                    put("type",   "REGISTER")
                    put("device", android.os.Build.MODEL)
                    put("id",     deviceId)
                    put("auth",   authToken)
                }.toString())

                // Recibir bloque
                val response = JSONObject(readLineLimited(reader) ?: return@submit)
                if (response.optString("type") == "AUTH_FAIL") {
                    log("Código de acceso incorrecto")
                    isRunning.set(false); isWorker = false
                    return@submit
                }
                when (response.optString("type")) {
                    "BLOCK" -> {
                        // Pedir de una vez la lista de bloques ya barridos.
                        //
                        // syncWithMaster tampoco la llamaba nadie, asi que el
                        // progreso global que enseña un worker sólo contaba sus
                        // propios bloques y salía siempre casi a cero por mucho
                        // que llevara hecho el cluster entero.
                        syncWithMaster(masterIp) { n ->
                            if (n > 0) log("Sincronizados $n bloques ya barridos")
                        }
                        val block = NetBlock(
                            blockId    = response.getString("block_id"),
                            rangeStart = response.getString("start"),
                            rangeEnd   = response.getString("end"),
                            puzzleNum  = response.getInt("puzzle")
                        )
                        log("Bloque recibido: #${block.blockId}")
                        log("Rango: ${block.rangeStart} → ${block.rangeEnd}")
                        onBlock?.invoke(block)
                    }
                    "KANG" -> {
                        modo = Modo.KANGAROO
                        val pub = response.optString("pub", "")
                        val ini = response.optString("start", "")
                        val fin = response.optString("end", "")
                        val pz  = response.optInt("puzzle", 0)
                        // Guardarlo: sin esto el trabajador no podía volver a
                        // arrancar solo, y "reanudar" no tendría con qué.
                        jobPub = pub; jobIni = ini; jobFin = fin; jobPuzzle = pz
                        pausadoPorMaestro = false
                        log("Encargo Kangaroo: puzzle #$pz, rango completo")
                        onKangaroo?.invoke(pub, ini, fin, pz)
                        arrancarBucleReparto()
                    }
                }

                canal.close()
                return@submit                 // registrado: no hay que reintentar
            } catch (e: Exception) {
                log("No se pudo conectar con el maestro: ${e.message}")
            }
          }
          if (isRunning.get() && isWorker)
              log("El maestro no responde. Comprueba la IP, el código y que " +
                  "los dos móviles estén en la misma WiFi.")
        }
    }

    /* ── Bucle de reparto del worker ──────────────────────────────────────────
     *
     * Cada [REPARTO_MS] manda al master los puntos distinguidos que hayan
     * salido desde la última vez. Sólo lo nuevo: el motor lleva la cuenta de lo
     * que ya se mandó, así que esto no reenvía la tabla entera.
     *
     * Vive aquí y no en una pantalla porque tiene que seguir funcionando con la
     * app en segundo plano: una búsqueda de Kangaroo está días encendida y
     * nadie se queda mirándola.
     */
    private const val REPARTO_MS = 20_000L

    @Volatile private var bucleVivo = false
    /** Envios seguidos que han fallado. Sirve para avisar de que el maestro se
     *  ha ido en vez de seguir golpeando en silencio. */
    @Volatile private var fallosSeguidos = 0
    /** El master ha dicho que el puzzle ya no tiene fondos. */
    @Volatile private var puzzleResuelto = false
    /** Aviso para la pantalla del worker: el puzzle se ha acabado. */
    var onPuzzleAgotado: (() -> Unit)? = null

    /** Este trabajador está parado porque lo ha mandado el maestro (no porque
     *  haya fallado ni porque lo haya parado su dueño). La pantalla lo lee en
     *  su refresco de cada cinco segundos; no hace falta avisarla. */
    @Volatile var pausadoPorMaestro = false
        private set

    /**
     * Obedecer lo que el maestro quiere. Llega colgado de la respuesta a
     * cualquier cosa que mandemos, y llega SIEMPRE, no sólo cuando cambia: por
     * eso aquí se compara antes de actuar. Repetir la orden es lo que hace que
     * una que se perdió se aplique sola en el siguiente intercambio.
     */
    private fun aplicarOrden(cmd: String) {
        when (cmd) {
            "pausa" -> {
                if (pausadoPorMaestro) return
                pausadoPorMaestro = true
                // kangarooStop() guarda la tabla antes de soltarla: pausar no
                // tira el trabajo, y al reanudar se sigue desde donde iba.
                try { HunterEngine.kangarooStop() } catch (e: Throwable) {}
                log("El maestro ha mandado parar. El trabajo queda guardado.")
            }
            "sigue" -> {
                if (!pausadoPorMaestro) return
                pausadoPorMaestro = false
                log("El maestro ha mandado seguir.")
                // Arrancar el motor no es cosa de aquí: lo hace la pantalla,
                // que es la que sabe de hilos, CPU y afinidad de núcleos.
                if (jobPub.length == 66)
                    onKangaroo?.invoke(jobPub, jobIni, jobFin, jobPuzzle)
            }
        }
    }

    /**
     * Latido de un trabajador parado.
     *
     * Uno que busca habla solo cada veinte segundos al mandar sus puntos, y la
     * orden viaja en esa respuesta. Uno pausado no tiene puntos que mandar: sin
     * este latido se quedaría mudo y no habría forma de reanudarlo nunca, o sea
     * que el botón de pausa sería de ida y no de vuelta.
     */
    private fun latido() {
        val ip = masterIp
        if (ip.isEmpty()) return
        try {
            abrirCanal(ip).use { canal ->
                canal.escritor.println(JSONObject().apply {
                    put("type", "PING")
                    put("id",   deviceId)
                    put("auth", authToken)
                }.toString())
                val resp = readLineLimited(canal.lector) ?: return
                val j = JSONObject(resp)
                // El encargo puede haber cambiado mientras estábamos parados.
                val pub = j.optString("pub", "")
                if (pub.length == 66) {
                    jobPub = pub
                    jobIni = j.optString("start", jobIni)
                    jobFin = j.optString("end", jobFin)
                    jobPuzzle = j.optInt("puzzle", jobPuzzle)
                }
                aplicarOrden(j.optString("cmd", ""))
            }
        } catch (e: Exception) {
            // En silencio: si el maestro no está, ya lo dice el bucle de puntos.
        }
    }

    private fun arrancarBucleReparto() {
        if (bucleVivo) return
        bucleVivo = true
        Thread({
            var avisada = false
            // El motor marca los puntos como enviados EN CUANTO se exportan, y
            // eso no se puede deshacer. Si el envío falla y no se guarda el
            // bloque, ese trabajo se pierde para siempre y sin avisar. Se queda
            // aquí hasta que entre, y mientras tanto no se exporta más.
            var pendiente: ByteArray? = null
            // Desde cuándo está atascado ese bloque, y si ya se ha avisado.
            //
            // Mientras hay uno pendiente NO se exporta nada nuevo, así que un
            // atasco no es "se retrasa el reparto": es que este móvil deja de
            // aportar del todo. Y por fuera no se nota — en la lista del maestro
            // sigue apareciendo con su velocidad, buscando— así que si no se
            // dice aquí no se entera nadie.
            var atascoDesde = 0L
            var atascoAvisado = false
            try {
                while (isRunning.get() && isWorker && modo == Modo.KANGAROO) {
                    if (puzzleResuelto) {
                        log("El puzzle ya no tiene fondos: se detiene la búsqueda")
                        try { HunterEngine.kangarooStop() } catch (e: Throwable) {}
                        onPuzzleAgotado?.invoke()
                        break
                    }
                    try {
                        val antes = fallosSeguidos
                        // 0) Parados: sólo latir. No hay puntos que mandar, y sin
                        //    hablar no llegaría nunca la orden de reanudar, o sea
                        //    que el botón de pausa sería de ida y no de vuelta.
                        val motorVivo = try { HunterEngine.kangarooRunning() }
                                        catch (e: Throwable) { false }
                        if (pausadoPorMaestro || !motorVivo) {
                            latido()
                        } else {
                            // 1) Primero lo que quedó a deber, si quedó algo.
                            if (pendiente != null) {
                                if (enviarPuntos(pendiente!!)) {
                                    pendiente = null
                                    if (atascoAvisado)
                                        log("El bloque atascado ha entrado. " +
                                            "Se vuelve a repartir con normalidad.")
                                    atascoDesde = 0L; atascoAvisado = false
                                }
                            }
                            // 2) Y luego lo nuevo, sólo si no hay atasco.
                            if (pendiente == null) {
                                val blob = HunterEngine.kangarooExport(MAX_PUNTOS_ENVIO)
                                // Sin puntos nuevos tampoco hay respuesta donde
                                // venga la orden, así que se late igual. Pasa de
                                // verdad: con dbits alto puede haber minutos
                                // enteros entre un punto y el siguiente.
                                if (blob == null || blob.isEmpty()) latido()
                                else if (!enviarPuntos(blob)) pendiente = blob
                            }
                            // Un atasco que dura es un móvil que ya no aporta.
                            // Se dice UNA vez, a los cinco minutos: menos tiempo
                            // saltaría con cualquier corte de WiFi, y repetirlo
                            // llenaría el registro como pasaba con el maestro
                            // caído.
                            if (pendiente != null) {
                                val ahora = System.currentTimeMillis()
                                if (atascoDesde == 0L) atascoDesde = ahora
                                else if (!atascoAvisado && ahora - atascoDesde > 300_000L) {
                                    atascoAvisado = true
                                    log("Llevo 5 min sin poder entregar un bloque de " +
                                        "puntos. Mientras siga atascado este móvil " +
                                        "busca pero NO aporta al cluster.")
                                }
                            }
                        }
                        // Avisar UNA vez cuando el maestro deja de responder, y
                        // otra cuando vuelve. Antes el worker seguía buscando y
                        // golpeando una IP muerta cada veinte segundos sin que
                        // nada lo indicara: creías que estabas en un cluster y
                        // llevabas horas solo.
                        if (fallosSeguidos == 3 && antes < 3)
                            log("El maestro lleva un minuto sin responder. Se sigue " +
                                "buscando aquí y los puntos se guardan para cuando vuelva.")
                        if (antes >= 3 && fallosSeguidos == 0)
                            log("El maestro ha vuelto.")

                        // 3) ¿La hemos encontrado aquí? El master no puede
                        //    deducirlo de la tabla (ver el mensaje "KEY"), así
                        //    que hay que decírselo.
                        if (!avisada) {
                            val k = try { HunterEngine.kangarooResult() } catch (e: Throwable) { "" }
                            if (k.length == 64) { avisarClave(k); avisada = true }
                        }
                    } catch (e: Throwable) {
                        log("Reparto: ${e.message}")
                    }
                    Thread.sleep(REPARTO_MS)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                bucleVivo = false
            }
        }, "kang-reparto").apply { isDaemon = true }.start()
    }

    /**
     * Un envío de puntos distinguidos. Síncrono: lo llama el bucle.
     *
     * @return true si el master los ha aceptado. Si no, quien llama tiene que
     *   guardarlos y reintentar: el motor ya los ha dado por enviados y no hay
     *   forma de que vuelvan a salir.
     */
    private fun enviarPuntos(blob: ByteArray): Boolean {
        val ip = masterIp
        if (ip.isEmpty()) return false
        return try {
            abrirCanal(ip).use { canal ->
                val writer = canal.escritor
                val reader = canal.lector
                writer.println(JSONObject().apply {
                    put("type", "DP")
                    put("id",   deviceId)
                    put("auth", authToken)
                    put("data", android.util.Base64.encodeToString(
                        blob, android.util.Base64.NO_WRAP))
                }.toString())
                val resp = readLineLimited(reader)
                // Conectó pero no contestó: cuenta como fallo igual, porque el
                // problema está al otro lado.
                if (resp == null) { fallosSeguidos++; false }
                else {
                    val j = JSONObject(resp)
                    // La orden del maestro viaja colgada de esta respuesta: él
                    // no puede llamarnos, así que aprovecha que le hablamos. Se
                    // aplica antes de mirar cómo fue el envío, porque una orden
                    // de parar vale igual aunque los puntos no hayan entrado.
                    aplicarOrden(j.optString("cmd", ""))
                    when (val n = j.optInt("n", -1)) {
                        // El puzzle ya no tiene fondos: alguien lo ha resuelto
                        // mientras buscábamos. Seguir es quemar batería contra
                        // una dirección vacía, así que se para aquí también.
                        -3 -> { puzzleResuelto = true; true }
                        // El master no tiene la búsqueda en marcha. Es pasajero:
                        // hay que guardarlos y volver a intentarlo, porque el
                        // motor ya los dio por enviados y no pueden volver a
                        // salir.
                        -2 -> { log("El master no está buscando ahora; se reintenta"); false }
                        // Rechazado por contenido: otro puzzle o mensaje roto.
                        // Reintentarlo no lo va a arreglar, así que se descarta
                        // para no atascar el bucle con algo que no entrará nunca.
                        -1 -> { log("El master ha rechazado los puntos: ¿otro puzzle?"); true }
                        else -> { fallosSeguidos = 0
                                  if (n > 0) {
                                      puntosEnviados.addAndGet(n.toLong())
                                      ultimoEnvioMs = System.currentTimeMillis()
                                  }
                                  android.util.Log.d("NetworkManager","$n puntos aceptados"); true }
                    }
                }
            }
        } catch (e: Exception) {
            fallosSeguidos++
            // Sólo se escribe el primero: si no, con el maestro caído el
            // registro se llena de la misma línea cada veinte segundos.
            if (fallosSeguidos == 1)
                log("No se pudieron mandar los puntos, se reintenta: ${e.message}")
            false
        }
    }

    /** Avisa al master de que la clave ha salido aquí. */
    private fun avisarClave(claveHex: String) {
        val ip = masterIp
        if (ip.isEmpty()) return
        try {
            abrirCanal(ip).use { canal ->
                canal.escritor.println(JSONObject().apply {
                    put("type", "KEY")
                    put("key",  claveHex)
                    put("id",   deviceId)
                    put("auth", authToken)
                }.toString())
            }
            log("Clave comunicada al master")
        } catch (e: Exception) {
            log("No se pudo avisar de la clave: ${e.message}")
        }
    }

    fun reportProgress(masterIp: String, speed: Long) {
        executor.submit {
            try {
                val canal = abrirCanal(masterIp)
                canal.escritor.println(JSONObject().apply {
                    put("type",  "PROGRESS")
                    put("speed", speed)
                    put("id",    deviceId)
                    put("auth",  authToken)
                }.toString())
                canal.close()
            } catch (e: Exception) {}
        }
    }

    fun reportBlockDone(masterIp: String, blockId: String) {
        executor.submit {
            try {
                val canal = abrirCanal(masterIp)
                val writer = canal.escritor
                val reader = canal.lector
                writer.println(JSONObject().apply {
                    put("type",     "DONE")
                    put("block_id", blockId)
                    put("id",       deviceId)
                    put("auth",     authToken)
                }.toString())
                // Recibir nuevo bloque
                val resp = JSONObject(readLineLimited(reader) ?: return@submit)
                if (resp.optString("type") == "BLOCK") {
                    onBlock?.invoke(NetBlock(
                        blockId    = resp.getString("block_id"),
                        rangeStart = resp.getString("start"),
                        rangeEnd   = resp.getString("end"),
                        puzzleNum  = resp.getInt("puzzle")
                    ))
                }
                canal.close()
            } catch (e: Exception) {}
        }
    }

    /**
     * Avisa al master de un hallazgo. Deliberadamente NO transmite la clave
     * privada: la versión anterior enviaba el WIF en texto plano por TCP, de modo
     * que cualquiera que se hiciera pasar por master (el descubrimiento UDP no
     * autenticaba) se la llevaba. La clave permanece en este dispositivo.
     */
    fun reportMatch(masterIp: String, addr: String) {
        executor.submit {
            try {
                abrirCanal(masterIp).use { canal ->
                    canal.escritor.println(JSONObject().apply {
                        put("type", "MATCH")
                        put("addr", addr)
                        put("id",   deviceId)
                        put("auth", authToken)
                    }.toString())
                }
            } catch (e: Exception) {}
        }
    }

    // ── Descubrimiento UDP ────────────────────────────────────────────────────
    /**
     * Busca maestros por difusión UDP.
     *
     * Sólo funciona DENTRO DE LA MISMA WiFi: la difusión no cruza routers, ni
     * VPNs, ni datos móviles. Es una comodidad para el caso fácil, no la forma
     * general de conectarse — para lo demás se teclea la dirección.
     *
     * @param onFin cuántos ha encontrado cuando termina de buscar. Hace falta
     *   porque antes no se avisaba de NADA al no encontrar nada: la pantalla se
     *   quedaba en "Buscando masters..." para siempre. En una WiFi eso ya
     *   despistaba; con el maestro al otro lado de una VPN o de Internet, donde
     *   no lo va a encontrar nunca, era quedarse mirando un mensaje que no
     *   cambia sin saber que estabas esperando algo imposible.
     */
    fun discoverMasters(ctx: Context, onFound: (String, String) -> Unit,
                        onFin: ((Int) -> Unit)? = null) {
        executor.submit {
            var hallados = 0
            try {
                val udp = DatagramSocket(UDP_PORT)
                udp.soTimeout = 3000
                val buf = ByteArray(256)
                val packet = DatagramPacket(buf, buf.size)
                val deadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < deadline) {
                    try {
                        udp.receive(packet)
                        val msg = String(packet.data, 0, packet.length)
                        if (msg.startsWith(APP_ID)) {
                            val parts = msg.split("|")
                            val ip     = packet.address.hostAddress ?: continue
                            val device = parts.getOrNull(1) ?: "Unknown"
                            hallados++
                            onFound(ip, device)
                        }
                    } catch (e: SocketTimeoutException) { break }
                }
                udp.close()
            } catch (e: Exception) { log("Discovery error: ${e.message}") }
            onFin?.invoke(hallados)
        }
    }

    private fun broadcastBeacon(ctx: Context) {
        // El lock y el socket se liberaban al final del try: si el bucle lanzaba,
        // el MulticastLock quedaba retenido para siempre consumiendo batería.
        var lock: WifiManager.MulticastLock? = null
        var udp: DatagramSocket? = null
        try {
            val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("hunter_beacon").also { it.acquire() }
            udp = DatagramSocket().apply { broadcast = true }
            udpSocket = udp
            // El beacon sólo anuncia presencia; el código de acceso nunca se emite.
            val msg = "$APP_ID|${android.os.Build.MODEL}|master".toByteArray()
            val broadcast = InetAddress.getByName("255.255.255.255")
            while (isRunning.get()) {
                try { udp.send(DatagramPacket(msg, msg.size, broadcast, UDP_PORT)) } catch (e: Exception) {}
                Thread.sleep(2000)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            log("Beacon error: ${e.message}")
        } finally {
            try { udp?.close() } catch (e: Exception) {}
            udpSocket = null
            try { if (lock?.isHeld == true) lock.release() } catch (e: Exception) {}
        }
    }

    // ── Utils ─────────────────────────────────────────────────────────────────
    /**
     * IP local IPv4. Se enumeran las interfaces en vez de usar
     * WifiManager.connectionInfo, que está deprecado desde API 31 y devuelve
     * datos inválidos cuando la app no está en primer plano.
     */
    /**
     * TODAS las direcciones por las que se puede llegar a este móvil.
     *
     * [getLocalIp] devuelve la primera IPv4 que encuentra, y eso vale mientras
     * el cluster viva en una WiFi. No vale en cuanto sale de ahí:
     *
     *  - Por datos móviles la IPv4 está detrás del CGNAT de la operadora y no
     *    sirve para que te llamen. La IPv6 sí, porque en IPv6 no hay NAT.
     *  - Con una VPN puesta, la primera IPv4 que salga puede ser la de la WiFi
     *    o la de la VPN, según el orden en que el sistema liste las interfaces.
     *    Enseñar una de las dos sin decir cuál es peor que enseñar las dos.
     *
     * Se quitan las de enlace local (fe80:) porque no salen del cable, y las de
     * bucle. El resto se enseña tal cual: cuál sirve depende de por dónde vaya a
     * llamar el otro móvil, y eso lo sabe el usuario y no la app.
     */
    fun direccionesLocales(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            for (iface in java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in java.util.Collections.list(iface.inetAddresses)) {
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val txt = addr.hostAddress?.substringBefore('%') ?: continue
                    val tipo = if (addr is Inet4Address) "IPv4" else "IPv6"
                    // Marcar cuál es la de la VPN. Con Tailscale puesto salen
                    // tres o cuatro direcciones y sólo UNA sirve para que te
                    // llamen desde fuera; sin decir cuál, la lista informa poco
                    // más que enseñar la primera.
                    val nota = if (esVpn(iface.name, txt)) "  ← la de la VPN" else ""
                    out.add(tipo to "$txt  (${iface.name})$nota")
                }
            }
        } catch (e: Exception) {
            log("direccionesLocales: ${e.message}")
        }
        // IPv4 primero: es la que sirve en una WiFi, que es el caso normal.
        return out.sortedBy { if (it.first == "IPv4") 0 else 1 }
    }

    /**
     * ¿Esta dirección es de una VPN?
     *
     * Se piden las DOS cosas —interfaz de túnel Y rango 100.64.0.0/10— porque
     * por separado cada una se equivoca: hay VPN de empresa que no usan ese
     * rango, y sobre todo 100.64/10 es el rango del CGNAT de las operadoras, o
     * sea que una IP de datos móviles puede caer ahí sin ser ninguna VPN.
     * Juntas aciertan en el caso que importa, que es Tailscale.
     */
    private fun esVpn(iface: String, dir: String): Boolean {
        val tunel = iface.startsWith("tun") || iface.startsWith("ts") ||
                    iface.startsWith("wg")  || iface.startsWith("ppp")
        if (!tunel) return false
        val p = dir.split(".")
        if (p.size != 4) return false
        val a = p[0].toIntOrNull() ?: return false
        val b = p[1].toIntOrNull() ?: return false
        return a == 100 && b in 64..127          // 100.64.0.0/10
    }

    fun getLocalIp(ctx: Context): String {
        try {
            for (iface in java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in java.util.Collections.list(iface.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (e: Exception) {
            log("getLocalIp: ${e.message}")
        }
        return "0.0.0.0"
    }


    fun syncWithMaster(masterIp: String, onComplete: (Int) -> Unit) {
        executor.submit {
            try {
                val canal = abrirCanal(masterIp)
                val writer = canal.escritor
                val reader = canal.lector

                // Pedir sync
                writer.println(JSONObject().apply {
                    put("type", "SYNC_REQUEST")
                    put("id",   deviceId)
                    put("auth", authToken)
                }.toString())

                val resp = JSONObject(readLineLimited(reader) ?: return@submit)
                if (resp.optString("type") == "SYNC_RESPONSE") {
                    val blocks = resp.getJSONArray("blocks")
                    var count = 0
                    for (i in 0 until blocks.length()) {
                        globalScannedBlocks.add(blocks.getString(i))
                        count++
                    }
                    val puzzleNum = resp.optInt("puzzle", 71)
                    log("Sync recibido: $count bloques del puzzle #$puzzleNum")
                    onComplete(count)
                }
                canal.close()
            } catch (e: Exception) {
                log("Sync error: ${e.message}")
                onComplete(0)
            }
        }
    }

    fun getGlobalProgress(rangeStart: String, rangeEnd: String): String {
        return try {
            val start = java.math.BigInteger(rangeStart.trimStart('0').ifEmpty{"0"}, 16)
            val end   = java.math.BigInteger(rangeEnd.trimStart('0').ifEmpty{"0"}, 16)
            val size  = java.math.BigInteger(BLOCK_SIZE_HEX, 16)
            // Mismo arreglo que en nextBlock: el total se recortaba a un
            // millón, así que el porcentaje se calculaba contra una cifra
            // inventada y salía optimista por varios órdenes de magnitud.
            val total = end.subtract(start).divide(size).max(java.math.BigInteger.ONE)
            val done  = globalScannedBlocks.size
            val pct   = java.math.BigDecimal(done).multiply(java.math.BigDecimal(100))
                            .divide(java.math.BigDecimal(total), 10, java.math.RoundingMode.HALF_UP)
            "Global: $done/$total bloques (%s%%)".format(pct.toPlainString())
        } catch (e: Exception) { "Global: ${globalScannedBlocks.size} bloques" }
    }

    fun stop() {
        isRunning.set(false)
        isMaster = false; isWorker = false
        modo = Modo.BLOQUES                  // el bucle de reparto mira esto
        puzzleVacio = false; puzzleResuelto = false; fallosSeguidos = 0
        // Si no, salir de la red y volver a entrar dejaba el móvil convencido de
        // que seguía pausado por un maestro que ya no existe.
        pausadoPorMaestro = false
        // Parar es a propósito. Sin esto, el servicio volvería a conectarlo al
        // maestro la próxima vez que Android lo levantara.
        olvidarSesionDeWorker(appCtx)
        puntosEnviados.set(0); ultimoEnvioMs = 0L
        masterIp = ""
        jobPub = ""; jobIni = ""; jobFin = ""; jobPuzzle = 0
        puntosRecibidos.set(0)
        authToken = ""                       // invalida el código al parar
        try { escuchador?.close() } catch (e: Exception) {}
        try { udpSocket?.close()    } catch (e: Exception) {}
        escuchador = null
        workers.clear()
        assignedBlocks.clear()
        log("Red detenida")
    }

    private fun log(msg: String) {
        onLog?.invoke(msg)
        android.util.Log.d("NetworkManager", msg)
    }

    /** Sin noticias durante este tiempo, se considera mudo. */
    private const val MUDO_MS    = 90_000L
    /** Y pasado este, se quita de la lista. */
    private const val CADUCA_MS  = 10 * 60_000L

    /** La lista de trabajadores, ya caducada. Para que la pantalla pueda
     *  repintarse sola sin esperar a que cambie algo. */
    fun listaWorkers(): List<NetWorker> {
        val ahora = System.currentTimeMillis()
        workers.entries.removeAll { ahora - it.value.vistoMs > CADUCA_MS }
        for (w in workers.values)
            if (ahora - w.vistoMs > MUDO_MS) w.status = "sin noticias"
        return workers.values.toList()
    }

    private fun notifyWorkers() {
        // Los que llevan mucho callados se van. Un worker que sigue trabajando
        // habla al menos cada 30 s (progreso) o cada 20 s (puntos), asi que
        // diez minutos de silencio es que no esta.
        onWorkers?.invoke(listaWorkers())
    }

    /* ── Mando a distancia de los trabajadores ────────────────────────────────
     *
     * El maestro no puede llamar al trabajador: el trabajador abre la conexión,
     * manda una cosa, lee la respuesta y cierra. Así que la orden no se envía,
     * se CUELGA de la respuesta al siguiente mensaje que llegue de él —sus
     * puntos, cada veinte segundos, o su latido si está parado.
     *
     * Consecuencia que conviene tener clara: una orden tarda hasta veinte
     * segundos en surtir efecto. No es un fallo, es el precio de no abrir un
     * puerto de escucha en cada trabajador, que sería una superficie de ataque
     * más en una red donde ya viaja material de clave.
     */

    /** Lo que el maestro quiere que haga [workerId] ahora mismo. */
    private fun ordenPara(workerId: String): String =
        if (workers[workerId]?.pausado == true) "pausa" else "sigue"

    /**
     * Pausar o reanudar UN trabajador. La orden no viaja ahora: viaja en la
     * respuesta a lo próximo que mande, como mucho veinte segundos después.
     *
     * @return false si ese trabajador ya no está en la lista.
     */
    fun mandarPausa(workerId: String, pausado: Boolean): Boolean {
        val w = workers[workerId] ?: return false
        if (w.pausado == pausado) return true
        w.pausado = pausado
        log(if (pausado) "Se le pide a ${w.device} que pare"
            else "Se le pide a ${w.device} que siga")
        notifyWorkers()
        return true
    }

    /** Pausar o reanudar todos a la vez. @return a cuántos afecta. */
    fun mandarPausaATodos(pausado: Boolean): Int {
        val lista = listaWorkers()
        lista.forEach { it.pausado = pausado }
        if (lista.isNotEmpty()) {
            log(if (pausado) "Se les pide a ${lista.size} trabajador(es) que paren"
                else "Se les pide a ${lista.size} trabajador(es) que sigan")
            notifyWorkers()
        }
        return lista.size
    }
}
