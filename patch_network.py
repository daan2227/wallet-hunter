#!/usr/bin/env python3
"""
Grupo E: Modo red local multi-dispositivo
- Master: servidor TCP que divide rangos y asigna bloques
- Worker: cliente que recibe bloques y reporta progreso
- Protocolo JSON sobre TCP puerto 7771
- Descubrimiento por broadcast UDP puerto 7772
"""

import os
KT_DIR = "app/src/main/java/com/hunter/btc"

# ── NetworkManager.kt ─────────────────────────────────────────────────────────
network_kt = '''package com.hunter.btc

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

                    // Asignar nuevo bloque
                    val block = nextBlock(puzzleNum, rangeStart, rangeEnd)
                    assignedBlocks[workerId] = block
                    workers[workerId]?.block = block.blockId
                    writer.println(JSONObject().apply {
                        put("type",  "BLOCK")
                        put("block_id",  block.blockId)
                        put("start", block.rangeStart)
                        put("end",   block.rangeEnd)
                        put("puzzle", block.puzzleNum)
                    }.toString())
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
        } while (assignedBlocks.values.any { it.blockId == blockIdx.toString() } && attempts < 100)

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
                val socket = Socket(masterIp, TCP_PORT)
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
                val socket = Socket(masterIp, TCP_PORT)
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
                val socket = Socket(masterIp, TCP_PORT)
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
                val socket = Socket(masterIp, TCP_PORT)
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
'''

with open(f"{KT_DIR}/NetworkManager.kt", 'w') as f:
    f.write(network_kt)
print("✓ NetworkManager.kt creado")

# ── NetworkActivity.kt ────────────────────────────────────────────────────────
network_activity = '''package com.hunter.btc

import android.os.Bundle
import android.widget.*
import android.graphics.Typeface
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AppCompatActivity

class NetworkActivity : AppCompatActivity() {

    private var tvLog: TextView? = null
    private var tvWorkers: TextView? = null
    private var tvIp: TextView? = null
    private var etMasterIp: EditText? = null
    private var btnMaster: Button? = null
    private var btnWorker: Button? = null
    private var btnStop: Button? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val BG    = AppTheme.BG_DEEP
        val AMBER = AppTheme.AMBER
        val TXT   = AppTheme.TXT_PRI
        val MUTED = AppTheme.TXT_MUTED
        val CARD  = AppTheme.BG_CARD

        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        // Header
        root.addView(TextView(this).apply {
            text = "🌐 Red Multi-Dispositivo"
            textSize = 18f; setTextColor(AMBER)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "Coordina múltiples dispositivos en red local WiFi"
            textSize = 11f; setTextColor(MUTED)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(16))
        })

        // IP local
        tvIp = TextView(this).apply {
            text = "IP: ${NetworkManager.getLocalIp(this@NetworkActivity)}"
            textSize = 12f; setTextColor(AppTheme.CYAN)
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(0x1100C8D4); setStroke(1, AppTheme.CYAN)
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        root.addView(tvIp)

        // Sección Master
        root.addView(sectionLabel("MODO MASTER"))
        root.addView(TextView(this).apply {
            text = "Este dispositivo coordina el rango y asigna bloques a los workers"
            textSize = 10f; setTextColor(MUTED); typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(8))
        })
        btnMaster = actionButton("⭐ Iniciar como Master", AMBER).also {
            it.setOnClickListener { startAsMaster() }
            root.addView(it)
        }

        // Sección Worker
        root.addView(sectionLabel("MODO WORKER"))
        root.addView(TextView(this).apply {
            text = "IP del Master:"
            textSize = 11f; setTextColor(TXT)
            setPadding(0, dp(8), 0, dp(4))
        })
        etMasterIp = EditText(this).apply {
            hint = "192.168.1.100"
            setTextColor(TXT); setHintTextColor(MUTED)
            textSize = 13f; typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(CARD); setStroke(1, 0xFF2A3028.toInt())
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        root.addView(etMasterIp)

        val btnDiscover = actionButton("🔍 Buscar Masters en red", AppTheme.CYAN).also {
            it.setOnClickListener { discoverMasters() }
            root.addView(it)
        }

        btnWorker = actionButton("📡 Conectar como Worker", 0xFF60A5FA.toInt()).also {
            it.setOnClickListener { startAsWorker() }
            root.addView(it)
        }

        // Stop
        btnStop = actionButton("⛔ Detener Red", AppTheme.RED).also {
            it.visibility = android.view.View.GONE
            it.setOnClickListener { stopNetwork() }
            root.addView(it)
        }

        // Workers conectados
        root.addView(sectionLabel("WORKERS CONECTADOS"))
        tvWorkers = TextView(this).apply {
            text = "Sin workers"
            textSize = 11f; setTextColor(MUTED); typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(CARD); cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(12) }
        }
        root.addView(tvWorkers)

        // Log
        root.addView(sectionLabel("LOG"))
        tvLog = TextView(this).apply {
            text = "—"
            textSize = 10f; setTextColor(TXT); typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(CARD); cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        root.addView(tvLog)

        scroll.addView(root)
        setContentView(scroll)

        // Callbacks
        NetworkManager.onLog = { msg ->
            runOnUiThread {
                val current = tvLog?.text?.toString() ?: ""
                val lines = current.lines().takeLast(20)
                tvLog?.text = (lines + listOf(msg)).joinToString("\n")
            }
        }
        NetworkManager.onWorkers = { list ->
            runOnUiThread {
                if (list.isEmpty()) {
                    tvWorkers?.text = "Sin workers"
                } else {
                    tvWorkers?.text = list.joinToString("\n") {
                        "• ${it.device} (${it.address}) — ${it.speed/1000}K/s [${it.status}]"
                    }
                }
            }
        }
    }

    private fun startAsMaster() {
        val puzzleNum  = 71
        val rangeStart = "400000000000000000"
        val rangeEnd   = "7fffffffffffffffff"
        NetworkManager.startMaster(this, puzzleNum, rangeStart, rangeEnd)
        btnMaster?.isEnabled = false
        btnStop?.visibility = android.view.View.VISIBLE
        Toast.makeText(this, "Master iniciado — IP: ${NetworkManager.getLocalIp(this)}", Toast.LENGTH_LONG).show()
    }

    private fun startAsWorker() {
        val ip = etMasterIp?.text?.toString()?.trim() ?: ""
        if (ip.isEmpty()) {
            Toast.makeText(this, "Ingresa la IP del Master", Toast.LENGTH_SHORT).show()
            return
        }
        NetworkManager.onBlock = { block ->
            runOnUiThread {
                Toast.makeText(this,
                    "Bloque asignado: #${block.blockId}\n${block.rangeStart}",
                    Toast.LENGTH_LONG).show()
                // Aplicar rango en HunterEngine
                HunterEngine.setRange(block.rangeStart, block.rangeEnd)
            }
        }
        NetworkManager.startWorker(ip)
        btnWorker?.isEnabled = false
        btnStop?.visibility = android.view.View.VISIBLE
    }

    private fun discoverMasters() {
        tvLog?.text = "Buscando masters..."
        NetworkManager.discoverMasters(this) { ip, device ->
            runOnUiThread {
                etMasterIp?.setText(ip)
                tvLog?.append("\n✓ Master encontrado: $device ($ip)")
            }
        }
    }

    private fun stopNetwork() {
        NetworkManager.stop()
        btnMaster?.isEnabled = true
        btnWorker?.isEnabled = true
        btnStop?.visibility = android.view.View.GONE
        tvWorkers?.text = "Sin workers"
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 9f; setTextColor(AppTheme.TXT_MUTED)
        typeface = Typeface.create("monospace", Typeface.BOLD)
        letterSpacing = 0.16f
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun actionButton(label: String, color: Int) = Button(this).apply {
        text = label; textSize = 12f
        setTextColor(if (color == AppTheme.AMBER) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        background = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(8).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { bottomMargin = dp(8) }
    }

    override fun onDestroy() {
        super.onDestroy()
        NetworkManager.onLog     = null
        NetworkManager.onWorkers = null
        NetworkManager.onBlock   = null
    }
}
'''

with open(f"{KT_DIR}/NetworkActivity.kt", 'w') as f:
    f.write(network_activity)
print("✓ NetworkActivity.kt creado")
print("✓ Grupo E completo")
