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

    // ── Estado ────────────────────────────────────────────────────────────────
    var isMaster  = false
    var isWorker  = false
    val isRunning = AtomicBoolean(false)
    val globalScannedBlocks = ConcurrentHashMap.newKeySet<String>()
    var onBlockScanned: ((String, Int) -> Unit)? = null  // blockId, puzzleNum
    var deviceId  = ""
    var onLog:     ((String) -> Unit)? = null
    var onWorkers: ((List<NetWorker>) -> Unit)? = null
    var onBlock:   ((NetBlock) -> Unit)? = null  // worker recibe bloque

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
    fun startMaster(ctx: Context, puzzleNum: Int, rangeStart: String, rangeEnd: String) {
        isMaster = true; isWorker = false
        isRunning.set(true)
        deviceId = android.os.Build.MODEL.replace(" ", "_")
        authToken = generateToken()
        log("Master iniciado — Puzzle #$puzzleNum")
        log("Código de acceso: $authToken")
        log("Rango: $rangeStart → $rangeEnd")

        // Servidor TCP
        executor.submit {
            try {
                serverSocket = ServerSocket(TCP_PORT)
                log("Escuchando en puerto $TCP_PORT")
                while (isRunning.get()) {
                    val client = serverSocket?.accept() ?: break
                    executor.submit { handleWorkerConnection(client, puzzleNum, rangeStart, rangeEnd) }
                }
            } catch (e: Exception) {
                if (isRunning.get()) log("Error servidor: ${e.message}")
            }
        }

        // Beacon UDP para descubrimiento
        executor.submit { broadcastBeacon(ctx) }
    }

    private fun handleWorkerConnection(socket: Socket, puzzleNum: Int,
                                        rangeStart: String, rangeEnd: String) {
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
                    log("🎯 MATCH de ${workers[workerId]?.device}: $addr")
                    onLog?.invoke("🎯 MATCH en ${workers[workerId]?.device ?: workerId}: $addr")
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

        val totalBlocks = end.subtract(start).divide(size).toLong().coerceAtLeast(1).coerceAtMost(1_000_000)
        val taken = assignedBlocks.values.mapTo(HashSet()) { it.blockId }
        val rnd = java.security.SecureRandom()
        var blockIdx = 0L
        var attempts = 0
        do {
            blockIdx = (rnd.nextDouble() * totalBlocks).toLong()
            attempts++
        } while ((taken.contains(blockIdx.toString()) ||
                  globalScannedBlocks.contains(blockIdx.toString())) && attempts < 200)

        val bStart = start.add(size.multiply(java.math.BigInteger.valueOf(blockIdx)))
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
                if (response.optString("type") == "BLOCK") {
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

                socket.close()
            } catch (e: Exception) {
                log("Error conectando al master: ${e.message}")
            }
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
            val total = end.subtract(start).divide(size).toLong().coerceAtMost(1_000_000)
            val done  = globalScannedBlocks.size
            val pct   = if (total > 0) done * 100.0 / total else 0.0
            "Global: $done/$total bloques (%.4f%%)".format(pct)
        } catch (e: Exception) { "Global: ${globalScannedBlocks.size} bloques" }
    }

    fun stop() {
        isRunning.set(false)
        isMaster = false; isWorker = false
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
