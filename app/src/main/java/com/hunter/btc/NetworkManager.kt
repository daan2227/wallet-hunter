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
    const val APP_ID    = "WALLET_HUNTER_V1"
    const val BLOCK_SIZE_HEX = "100000000" // 4B keys por bloque de red

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
    private val executor    = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null
    private var udpSocket:    DatagramSocket? = null

    // ── Master ────────────────────────────────────────────────────────────────
    fun startMaster(ctx: Context, puzzleNum: Int, rangeStart: String, rangeEnd: String) {
        isMaster = true; isWorker = false
        isRunning.set(true)
        deviceId = android.os.Build.MODEL.replace(" ", "_")
        log("Master iniciado — Puzzle #$puzzleNum")
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
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            val msg = JSONObject(reader.readLine() ?: return)
            when (msg.optString("type")) {
                "REGISTER" -> {
                    val device = msg.optString("device", workerId)
                    val worker = NetWorker(workerId, workerId, device)
                    workers[workerId] = worker
                    log("Worker conectado: $device ($workerId)")
                    notifyWorkers()

                    // Asignar bloque
                    val block = nextBlock(puzzleNum, rangeStart, rangeEnd)
                    assignedBlocks[workerId] = block
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
                    val block = nextBlock(puzzleNum, rangeStart, rangeEnd)
                    assignedBlocks[workerId] = block
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
                    val addr = msg.optString("addr")
                    val wif  = msg.optString("wif")
                    log("🎯 MATCH de ${workers[workerId]?.device}: $addr")
                    onLog?.invoke("🎯 MATCH ENCONTRADO: $addr | WIF: $wif")
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

    private var blockCounter = 0L
    private fun nextBlock(puzzleNum: Int, rangeStart: String, rangeEnd: String): NetBlock {
        val start = java.math.BigInteger(rangeStart.trimStart('0').ifEmpty{"0"}, 16)
        val size  = java.math.BigInteger(BLOCK_SIZE_HEX, 16)
        val end   = java.math.BigInteger(rangeEnd.trimStart('0').ifEmpty{"0"}, 16)

        // Bloque aleatorio no asignado
        val range = end.subtract(start)
        val totalBlocks = range.divide(size).toLong().coerceAtMost(1_000_000)
        var blockIdx: Long
        var attempts = 0
        do {
            blockIdx = (Math.random() * totalBlocks).toLong()
            attempts++
        } while ((assignedBlocks.values.any { it.blockId == blockIdx.toString() } ||
                  globalScannedBlocks.contains(blockIdx.toString())) && attempts < 200)

        val bStart = start.add(size.multiply(java.math.BigInteger.valueOf(blockIdx)))
        val bEnd   = bStart.add(size).min(end)
        blockCounter++

        return NetBlock(
            blockId    = blockIdx.toString(),
            rangeStart = bStart.toString(16).padStart(18, '0'),
            rangeEnd   = bEnd.toString(16).padStart(18, '0'),
            puzzleNum  = puzzleNum
        )
    }

    // ── Worker ────────────────────────────────────────────────────────────────
    fun startWorker(masterIp: String) {
        isWorker = true; isMaster = false
        isRunning.set(true)
        deviceId = android.os.Build.MODEL.replace(" ", "_")
        log("Worker iniciando → Master: $masterIp")

        executor.submit {
            try {
                val socket = Socket().apply { connect(java.net.InetSocketAddress(masterIp, TCP_PORT), 10000) }
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                // Registrar
                writer.println(JSONObject().apply {
                    put("type",   "REGISTER")
                    put("device", android.os.Build.MODEL)
                    put("id",     deviceId)
                }.toString())

                // Recibir bloque
                val response = JSONObject(reader.readLine() ?: return@submit)
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
                }.toString())
                // Recibir nuevo bloque
                val resp = JSONObject(reader.readLine() ?: return@submit)
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

    fun reportMatch(masterIp: String, addr: String, wif: String) {
        executor.submit {
            try {
                val socket = Socket().apply { connect(java.net.InetSocketAddress(masterIp, TCP_PORT), 10000) }
                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.println(JSONObject().apply {
                    put("type", "MATCH")
                    put("addr", addr)
                    put("wif",  wif)
                    put("id",   deviceId)
                }.toString())
                socket.close()
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
        try {
            val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("hunter_beacon")
            lock.acquire()
            val udp = DatagramSocket()
            udp.broadcast = true
            val msg = "$APP_ID|${android.os.Build.MODEL}|master".toByteArray()
            val broadcast = InetAddress.getByName("255.255.255.255")
            while (isRunning.get()) {
                val packet = DatagramPacket(msg, msg.size, broadcast, UDP_PORT)
                try { udp.send(packet) } catch (e: Exception) {}
                Thread.sleep(2000)
            }
            udp.close()
            lock.release()
        } catch (e: Exception) { log("Beacon error: ${e.message}") }
    }

    // ── Utils ─────────────────────────────────────────────────────────────────
    fun getLocalIp(ctx: Context): String {
        return try {
            val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifi.connectionInfo.ipAddress
            "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        } catch (e: Exception) { "0.0.0.0" }
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
                }.toString())

                val resp = JSONObject(reader.readLine() ?: return@submit)
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
        try { serverSocket?.close() } catch (e: Exception) {}
        try { udpSocket?.close()    } catch (e: Exception) {}
        workers.clear()
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
