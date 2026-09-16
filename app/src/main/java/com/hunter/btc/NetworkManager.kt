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
    private const val MAX_LINE_BYTES  = 8 * 1024
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
        var block:   String = ""
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
    private var serverSocket: ServerSocket? = null
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

        // Servidor TCP
        executor.submit {
            try {
                serverSocket = ServerSocket(TCP_PORT)
                log("Escuchando en puerto $TCP_PORT")
                while (isRunning.get()) {
                    val client = serverSocket?.accept() ?: break
                    executor.submit { handleWorkerConnection(client) }
                }
            } catch (e: Exception) {
                if (isRunning.get()) log("Error servidor: ${e.message}")
            }
        }

        // Beacon UDP para descubrimiento
        executor.submit { broadcastBeacon(ctx) }
    }

    private fun handleWorkerConnection(socket: Socket) {
        val puzzleNum = jobPuzzle
        val rangeStart = jobIni
        val rangeEnd = jobFin
        val workerId = socket.inetAddress.hostAddress ?: return
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

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
            try { socket.close() } catch (e: Exception) {}
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
    fun startWorker(masterIp: String, token: String) {
        isWorker = true; isMaster = false
        isRunning.set(true)
        this.masterIp = masterIp.trim()
        deviceId = android.os.Build.MODEL.replace(" ", "_")
        authToken = token.trim().uppercase()
        log("Worker iniciando → Master: $masterIp")

        executor.submit {
            try {
                val socket = Socket().apply { connect(java.net.InetSocketAddress(masterIp, TCP_PORT), 10000) }
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

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
                        log("Encargo Kangaroo: puzzle #$pz, rango completo")
                        onKangaroo?.invoke(pub, ini, fin, pz)
                        arrancarBucleReparto()
                    }
                }

                socket.close()
            } catch (e: Exception) {
                log("Error conectando al master: ${e.message}")
            }
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
    /** Tope de puntos por envío. A 49 bytes cada uno, 2048 son ~100 KB. */
    private const val MAX_PUNTOS_ENVIO = 2048

    @Volatile private var bucleVivo = false
    /** El master ha dicho que el puzzle ya no tiene fondos. */
    @Volatile private var puzzleResuelto = false
    /** Aviso para la pantalla del worker: el puzzle se ha acabado. */
    var onPuzzleAgotado: (() -> Unit)? = null

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
            try {
                while (isRunning.get() && isWorker && modo == Modo.KANGAROO) {
                    if (puzzleResuelto) {
                        log("El puzzle ya no tiene fondos: se detiene la búsqueda")
                        try { HunterEngine.kangarooStop() } catch (e: Throwable) {}
                        onPuzzleAgotado?.invoke()
                        break
                    }
                    try {
                        // 1) Primero lo que quedó a deber, si quedó algo.
                        if (pendiente != null) {
                            if (enviarPuntos(pendiente!!)) pendiente = null
                        }
                        // 2) Y luego lo nuevo, sólo si no hay atasco.
                        if (pendiente == null) {
                            val blob = HunterEngine.kangarooExport(MAX_PUNTOS_ENVIO)
                            if (blob != null && blob.isNotEmpty() && !enviarPuntos(blob))
                                pendiente = blob
                        }

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
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(ip, TCP_PORT), 10000)
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                writer.println(JSONObject().apply {
                    put("type", "DP")
                    put("id",   deviceId)
                    put("auth", authToken)
                    put("data", android.util.Base64.encodeToString(
                        blob, android.util.Base64.NO_WRAP))
                }.toString())
                val resp = readLineLimited(reader)
                if (resp == null) false
                else when (val n = JSONObject(resp).optInt("n", -1)) {
                    // El puzzle ya no tiene fondos: alguien lo ha resuelto
                    // mientras buscábamos. Seguir es quemar batería contra una
                    // dirección vacía, así que se para aquí también.
                    -3 -> { puzzleResuelto = true; true }
                    // El master no tiene la búsqueda en marcha. Es pasajero:
                    // hay que guardarlos y volver a intentarlo, porque el motor
                    // ya los dio por enviados y no pueden volver a salir.
                    -2 -> { log("El master no está buscando ahora; se reintenta"); false }
                    // Rechazado por contenido: otro puzzle o mensaje roto.
                    // Reintentarlo no lo va a arreglar, así que se descarta para
                    // no atascar el bucle con algo que no va a entrar nunca.
                    -1 -> { log("El master ha rechazado los puntos: ¿otro puzzle?"); true }
                    else -> { if (n > 0) android.util.Log.d("NetworkManager","$n puntos aceptados"); true }
                }
            }
        } catch (e: Exception) {
            log("No se pudieron mandar los puntos, se reintenta: ${e.message}")
            false
        }
    }

    /** Avisa al master de que la clave ha salido aquí. */
    private fun avisarClave(claveHex: String) {
        val ip = masterIp
        if (ip.isEmpty()) return
        try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(ip, TCP_PORT), 10000)
                socket.soTimeout = SOCKET_TIMEOUT_MS
                PrintWriter(socket.getOutputStream(), true).println(JSONObject().apply {
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
                val socket = Socket().apply { connect(java.net.InetSocketAddress(masterIp, TCP_PORT), 10000) }
                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.println(JSONObject().apply {
                    put("type",  "PROGRESS")
                    put("speed", speed)
                    put("id",    deviceId)
                    put("auth",  authToken)
                }.toString())
                socket.close()
            } catch (e: Exception) {}
        }
    }

    fun reportBlockDone(masterIp: String, blockId: String) {
        executor.submit {
            try {
                val socket = Socket().apply { connect(java.net.InetSocketAddress(masterIp, TCP_PORT), 10000) }
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
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
                socket.close()
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
                Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(masterIp, TCP_PORT), 10000)
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    PrintWriter(socket.getOutputStream(), true).println(JSONObject().apply {
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
    fun discoverMasters(ctx: Context, onFound: (String, String) -> Unit) {
        executor.submit {
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
                            onFound(ip, device)
                        }
                    } catch (e: SocketTimeoutException) { break }
                }
                udp.close()
            } catch (e: Exception) { log("Discovery error: ${e.message}") }
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
                val socket = Socket().apply { connect(java.net.InetSocketAddress(masterIp, TCP_PORT), 10000) }
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

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
                socket.close()
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
        puzzleVacio = false; puzzleResuelto = false
        masterIp = ""
        jobPub = ""; jobIni = ""; jobFin = ""; jobPuzzle = 0
        puntosRecibidos.set(0)
        authToken = ""                       // invalida el código al parar
        try { serverSocket?.close() } catch (e: Exception) {}
        try { udpSocket?.close()    } catch (e: Exception) {}
        serverSocket = null
        workers.clear()
        assignedBlocks.clear()
        log("Red detenida")
    }

    private fun log(msg: String) {
        onLog?.invoke(msg)
        android.util.Log.d("NetworkManager", msg)
    }

    private fun notifyWorkers() {
        onWorkers?.invoke(workers.values.toList())
    }
}
