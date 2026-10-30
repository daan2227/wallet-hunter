package com.hunter.btc

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Cliente Stratum v1 mínimo para un pool "solo" (ckpool solo, public-pool y similares): subscribe, authorize,
 * suggest_difficulty, los trabajos de mining.notify y la dificultad, y mining.submit. Con Tor/proxy activado
 * va por él. [ev] se llama desde el hilo de lectura; [alCerrar] cuando se corta sin haberlo pedido.
 */
class Stratum(private val host: String, private val puerto: Int, val usuario: String, private val sugerida: Double?,
              private val ev: (String) -> Unit, private val alCerrar: () -> Unit) {
    @Volatile var extranonce1 = ""; @Volatile var en2Tam = 4
    @Volatile var trabajo: Minero.Trabajo? = null
    @Volatile var dificultad = 1.0
    @Volatile var generacion = 0
    @Volatile var aceptadas = 0; @Volatile var rechazadas = 0
    @Volatile var autorizado = false
    @Volatile private var abierto = true
    private var socket: java.net.Socket? = null
    private var salida: java.io.Writer? = null
    private var siguienteId = 10

    fun arrancar() = Thread {
        try {
            val s = RedPrivada.socket(host).also { socket = it }
            s.connect(RedPrivada.destino(host, puerto), 20_000); s.soTimeout = 0
            salida = s.getOutputStream().bufferedWriter()
            mandar(JSONObject().put("id", 1).put("method", "mining.subscribe").put("params", JSONArray().put("HunterBTC/1.1")))
            // Una dificultad baja para que un teléfono vea shares aceptados de vez en cuando (no todos los pools la respetan).
            sugerida?.let { mandar(JSONObject().put("id", 3).put("method", "mining.suggest_difficulty").put("params", JSONArray().put(it))) }
            mandar(JSONObject().put("id", 2).put("method", "mining.authorize").put("params", JSONArray().put(usuario).put("x")))
            ev("Connected to $host")
            val r = s.getInputStream().bufferedReader()
            while (abierto) {
                val l = r.readLine() ?: break
                try { leer(JSONObject(l)) } catch (e: Exception) { }
            }
            if (abierto) ev("The pool closed the connection")
        } catch (e: Exception) {
            if (abierto) ev("Connection failed: ${e.message ?: e.javaClass.simpleName}")
        }
        val inesperado = abierto
        abierto = false
        if (inesperado) alCerrar()
    }.apply { isDaemon = true; start() }

    private fun leer(o: JSONObject) {
        val p = o.optJSONArray("params")
        when (o.optString("method")) {
            "mining.notify" -> if (p != null) {
                val ramas = p.getJSONArray(4).let { a -> List(a.length()) { a.getString(it) } }
                trabajo = Minero.Trabajo(p.getString(0), p.getString(1), p.getString(2), p.getString(3), ramas,
                    p.getString(5), p.getString(6), p.getString(7), p.optBoolean(8))
                generacion++
                return
            }
            "mining.set_difficulty" -> if (p != null) { dificultad = p.getDouble(0); ev("Pool difficulty: ${Minero.corto(dificultad)}"); return }
            "client.show_message" -> if (p != null) { ev("Pool: ${p.optString(0)}"); return }
        }
        when (val id = o.optInt("id", -1)) {
            1 -> o.optJSONArray("result")?.let { r -> extranonce1 = r.getString(1); en2Tam = r.getInt(2) }
            2 -> if (o.optBoolean("result")) { autorizado = true; ev("Authorized as ${usuario.take(14)}…") } else ev("The pool rejected the address: ${o.opt("error")}")
            -1, 3 -> {}
            else -> if (id >= 10) {
                if (o.optBoolean("result")) { aceptadas++; ev("Share accepted ✓") } else { rechazadas++; ev("Share rejected: ${o.opt("error")}") }
            }
        }
    }

    @Synchronized private fun mandar(o: JSONObject) { salida?.apply { write(o.toString()); write("\n"); flush() } }

    fun enviar(t: Minero.Trabajo, en2: String, nonce: Int) = try {
        val id = synchronized(this) { siguienteId++ }
        mandar(JSONObject().put("id", id).put("method", "mining.submit")
            .put("params", JSONArray().put(usuario).put(t.id).put(en2).put(t.ntime).put(Minero.nonceTexto(nonce))))
    } catch (e: Exception) { }

    fun cerrar() { abierto = false; try { socket?.close() } catch (e: Exception) { } }
}

/**
 * Minero de bloques. Dos modos:
 *  - Práctica: busca, aquí, un nonce que dé un hash con N ceros delante sobre una cabecera de verdad (la
 *    punta actual de la cadena como bloque anterior). Es lo mismo que hace un minero, a una dificultad de juguete.
 *  - Lotería en solitario: mina para un pool "solo" con tu dirección: si este teléfono encontrara un bloque,
 *    el premio iría a ella. La probabilidad es ínfima y se dice.
 * El hash lo hace el motor nativo con las instrucciones SHA-256 del procesador (si pasa la autoprueba con el
 * bloque 1; si no, Kotlin). Sólo mina con la pantalla abierta; con calor baja los hilos y a TEMP_MAX para.
 */
class MineroActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_DIRECCION = "direccion"
        private const val TEMP_MAX = 43.0
        private const val TEMP_TIBIO = 40.0
        private const val PREFS = "minero"
        /** (host:puerto, descripción) de pools solo conocidos. */
        private val POOLS = listOf(
            "solo.ckpool.org:3333" to "ckpool solo · 2 % fee · the classic",
            "eusolo.ckpool.org:3333" to "ckpool solo, Europe",
            "public-pool.io:21496" to "public-pool · 0 % fee · open source, shows low-difficulty shares")
    }

    private lateinit var caja: LinearLayout
    private lateinit var tvEstado: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvRecord: TextView
    private lateinit var btPractica: TextView
    private lateinit var btSolo: TextView
    private val h = Handler(Looper.getMainLooper())
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var minando = false
    private var modo = ""
    private val hilos = java.util.concurrent.CopyOnWriteArrayList<Thread>()
    private val hashes = AtomicLong()
    @Volatile private var mejor = 0                  // ceros del mejor hash de la sesión
    @Volatile private var mejorDif = 0.0             // su dificultad
    private var inicio = 0L
    @Volatile private var stratum: Stratum? = null
    private var objetivoCeros = 24
    private var nHilos = 1
    @Volatile private var hilosActivos = 1
    @Volatile private var dificultadRed = 0.0
    private var soloCargando = false
    private var reintentos = 0
    private var poolActual: Triple<String, Int, String>? = null
    private val log = ArrayDeque<String>()

    /** La tanda que se usa: la nativa si pasa la autoprueba, si no la de Kotlin. */
    private var tanda: Minero.Tanda = Minero.tandaKotlin
    private var motorTexto = "checking…"
    private val lote get() = if (tanda === Minero.tandaKotlin) 1 shl 16 else 1 shl 20

    /** La GPU (Vulkan), opcional: sólo se usa si pasa la autoprueba del bloque 1. */
    @Volatile private var gpuLista = false
    /** Por encima de TEMP_TIBIO (hasta bajar 2 °C): menos hilos y la GPU en pausa. */
    @Volatile private var caliente = false
    private var gpuNombre = ""
    private val hashesGpu = AtomicLong()
    private lateinit var btGpu: TextView

    /**
     * Quien mina: un hilo de CPU (k) o la GPU (k = nHilos). La GPU ajusta su tanda para que dure ~0,1 s
     * (Android corta los trabajos de GPU que tardan segundos) y, si falla, se apaga y sigue la CPU.
     */
    private inner class Obrero(val k: Int, val gpu: Boolean) {
        var lote = if (gpu) 1 shl 20 else this@MineroActivity.lote
        /** Con calor se para primero la GPU, luego los hilos de más. */
        val pausado get() = if (gpu) caliente else k >= hilosActivos
        fun hacer(cab: ByteArray, desde: Long, cuantos: Int, obj: ByteArray, sal: LongArray): Long? {
            if (!gpu) return tanda.hacer(cab, desde.toInt(), cuantos, obj, sal)
            val t0 = System.nanoTime()
            val h = try { HunterEngine.gpuMinar(cab, desde.toInt(), cuantos, obj, sal) } catch (e: Throwable) { -1L }
            if (h < 0) { gpuFallo(); return null }
            val ms = (System.nanoTime() - t0) / 1e6
            if (ms < 40 && lote < (1 shl 28)) lote *= 2 else if (ms > 150 && lote > (1 shl 14)) lote /= 2
            hashesGpu.addAndGet(h)
            return h
        }
    }

    private fun obreros() = (0 until nHilos).map { Obrero(it, false) } + if (gpuLista) listOf(Obrero(nHilos, true)) else emptyList()

    private fun gpuFallo() {
        gpuLista = false
        if (nHilos == 0) runOnUiThread { nHilos = 1; pintarHilos(); parar("The GPU stopped working and no CPU threads were on") }
        anotar("The GPU stopped working: going on with the CPU")
        runOnUiThread { prefs().edit().putBoolean("gpu", false).apply(); pintarGpu() }
    }

    override fun onCreate(s: Bundle?) {
        setTheme(AppTheme.estilo(this)); super.onCreate(s)
        AppTheme.init(this); AppLock.init(this)
        nHilos = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        soloCargando = prefs().getBoolean("solo_cargando", false)
        val scroll = ScrollView(this).apply { setBackgroundColor(AppTheme.BG_DEEP); isFillViewport = true }
        caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(AppTheme.PAD_SIDE), dp(12), dp(AppTheme.PAD_SIDE), dp(40)) }
        scroll.addView(caja); setContentView(scroll)
        construir()
        if (prefs().getBoolean("gpu", false)) encenderGpu(false)
        Thread {
            val nativa = Minero.Tanda { c, d, n, o, sal -> HunterEngine.minar(c, d, n, o, sal) }
            val ok = try { Minero.autoprueba(nativa) } catch (e: Throwable) { false }
            val hw = ok && try { HunterEngine.minarConHw() } catch (e: Throwable) { false }
            runOnUiThread {
                if (ok) tanda = nativa
                motorTexto = if (!ok) "Kotlin (the native engine failed its self-test)" else if (hw) "native, with the CPU's SHA-256 instructions" else "native, software SHA-256"
                pintarEstado()
            }
            val js = ChainApi.get("/v1/mining/hashrate/3d", false)
            dificultadRed = try { JSONObject(js ?: "").optDouble("currentDifficulty", 0.0) } catch (e: Exception) { 0.0 }
            runOnUiThread { pintarRecord() }
        }.start()
    }

    private fun texto(t: String, tam: Float = AppTheme.SP_BODY, color: Int = AppTheme.TXT_PRI, mono: Boolean = false) = TextView(this).apply {
        text = t; textSize = tam; setTextColor(color); typeface = if (mono) AppTheme.mono(context) else AppTheme.body(context)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun boton(t: String, principal: Boolean = false, accion: () -> Unit) = TextView(this).apply {
        text = t; textSize = AppTheme.SP_BODY; gravity = Gravity.CENTER
        setTextColor(if (principal) AppTheme.ON_ACCENT else AppTheme.TXT_PRI)
        typeface = if (principal) AppTheme.bold(context) else AppTheme.medium(context)
        background = GradientDrawable().apply { setColor(if (principal) AppTheme.ACCENT else AppTheme.BG_ELEV); cornerRadius = dp(AppTheme.R_INNER).toFloat() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) }
        isClickable = true; foreground = Ui.toque(); setOnClickListener { accion() }
    }

    private fun fila(etiqueta: TextView, menos: () -> Unit, mas: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(etiqueta, LinearLayout.LayoutParams(0, -2, 1f))
        addView(boton("−") { menos() }, LinearLayout.LayoutParams(dp(56), dp(44)))
        addView(boton("+") { mas() }, LinearLayout.LayoutParams(dp(56), dp(44)).apply { marginStart = dp(8) })
    }

    private fun construir() {
        caja.addView(Ui.pageTitle(this, "Block miner", lados = false))
        caja.addView(texto("Mining is guessing: change a number (the nonce) in a block header until its double SHA-256 starts with " +
            "enough zeros. This phone does millions of guesses per second; the whole network does hundreds of " +
            "billions of billions. It only runs with this screen open, slows down when the phone gets warm and stops " +
            "at $TEMP_MAX °C or below 20 % battery.", AppTheme.SP_CAPTION, AppTheme.TXT_SEC))

        caja.addView(Ui.sectionLabel(this, "Threads", topGap = dp(12)))
        tvHilos = texto("")
        val max = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        pintarHilos()
        // Con la GPU encendida se puede bajar a 0 hilos: mina sólo la GPU (para medirla sin que la CPU le quite potencia).
        caja.addView(fila(tvHilos, { if (!minando && nHilos > (if (gpuLista) 0 else 1)) { nHilos--; pintarHilos() } },
            { if (!minando && nHilos < max) { nHilos++; pintarHilos() } }))
        caja.addView(android.widget.CheckBox(this).apply {
            text = "Only while charging"; setTextColor(AppTheme.TXT_PRI); isChecked = soloCargando
            setOnCheckedChangeListener { _, v -> soloCargando = v; prefs().edit().putBoolean("solo_cargando", v).apply() }
        })
        btGpu = boton("GPU: off") {
            if (minando) Toast.makeText(this, "Stop mining first.", Toast.LENGTH_SHORT).show()
            else if (gpuLista) apagarGpu() else encenderGpu(true)
        }
        caja.addView(btGpu)
        caja.addView(texto("Experimental: also mine on the graphics chip (Vulkan). It is checked with block 1 before use; it heats " +
            "the phone more than the CPU.", AppTheme.SP_CAPTION, AppTheme.TXT_SEC))

        // ── Práctica ──
        caja.addView(Ui.sectionLabel(this, "Practice block (on this phone)", topGap = dp(16)))
        caja.addView(texto("Mine a real-format header on top of the current chain tip, at a toy difficulty you choose. " +
            "Each extra zero bit doubles the work.", AppTheme.SP_CAPTION, AppTheme.TXT_SEC))
        val tvCeros = texto("")
        fun pintarCeros() { tvCeros.text = "$objetivoCeros zero bits · ~${Minero.corto(Math.pow(2.0, objetivoCeros.toDouble()))} tries" }
        pintarCeros()
        caja.addView(fila(tvCeros, { if (!minando && objetivoCeros > 12) { objetivoCeros -= 2; pintarCeros() } },
            { if (!minando && objetivoCeros < 32) { objetivoCeros += 2; pintarCeros() } }))
        btPractica = boton("Mine a practice block", principal = true) { if (minando) parar("Stopped") else practicar() }
        caja.addView(btPractica)

        // ── Lotería en solitario ──
        caja.addView(Ui.sectionLabel(this, "Solo lottery (real network)", topGap = dp(20)))
        caja.addView(texto("Mines for a solo pool with your address as the user. If this phone ever found a block, the whole " +
            "reward (minus the pool's fee) would go straight to that address. Realistically it never will: see the odds. " +
            "The pool sees your IP unless Tor/proxy is on.", AppTheme.SP_CAPTION, AppTheme.TXT_SEC))
        val etPool = Ui.input(this, "Pool host:port", mono = true).apply { setText(prefs().getString("pool", POOLS[0].first)) }
        val etDir = Ui.input(this, "Your bitcoin address (bc1…)", mono = true).apply {
            setText(intent.getStringExtra(EXTRA_DIRECCION) ?: prefs().getString("direccion", ""))
        }
        caja.addView(etPool)
        caja.addView(texto("Choose a pool…", AppTheme.SP_CAPTION, AppTheme.ACCENT).apply {
            setOnClickListener {
                AlertDialog.Builder(this@MineroActivity).setTitle("Solo pools")
                    .setItems(POOLS.map { "${it.first}\n${it.second}" }.toTypedArray()) { _, i -> etPool.setText(POOLS[i].first) }.show()
            }
        })
        caja.addView(etDir)
        btSolo = boton("Start solo mining") {
            if (minando) parar("Stopped") else solo(etPool.text.toString().trim(), etDir.text.toString().trim())
        }
        caja.addView(btSolo)

        caja.addView(Ui.sectionLabel(this, "Status", topGap = dp(20)))
        tvEstado = texto("Idle", AppTheme.SP_CAPTION, AppTheme.TXT_PRI, mono = true)
        tvRecord = texto("", AppTheme.SP_CAPTION, AppTheme.TXT_SEC)
        tvLog = texto("", AppTheme.SP_CAPTION, AppTheme.TXT_SEC, mono = true)
        caja.addView(tvEstado); caja.addView(tvRecord); caja.addView(tvLog)
        pintarEstado(); pintarRecord()
    }

    private lateinit var tvHilos: TextView
    private fun pintarHilos() {
        val max = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        tvHilos.text = if (nHilos == 0) "No CPU threads · GPU only" else "$nHilos of $max cores"
    }

    private fun pintarGpu() {
        // Sin GPU no se puede minar con 0 hilos.
        if (!gpuLista && nHilos == 0) { nHilos = 1; pintarHilos() }
        btGpu.text = if (gpuLista) "GPU: on · $gpuNombre" else "GPU: off"
    }

    private fun encenderGpu(avisar: Boolean) {
        btGpu.text = "GPU: checking…"
        Thread {
            val err = try { HunterEngine.gpuMinarAbrir() } catch (e: Throwable) { e.message ?: "error" }
            val ok = err.isEmpty() && Minero.autoprueba { c, d, n, o, sal -> HunterEngine.gpuMinar(c, d, n, o, sal) }
            val nombre = if (ok) try { HunterEngine.gpuMinarNombre() } catch (e: Throwable) { "" } else ""
            if (!ok) try { HunterEngine.gpuMinarCerrar() } catch (e: Throwable) { }
            runOnUiThread {
                gpuLista = ok; gpuNombre = nombre.ifEmpty { "GPU" }
                prefs().edit().putBoolean("gpu", ok).apply()
                pintarGpu()
                if (!ok && avisar) Toast.makeText(this, if (err.isNotEmpty()) "No usable GPU: $err" else
                    "The GPU failed its self-test (block 1): mining stays on the CPU.", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun apagarGpu() {
        gpuLista = false; prefs().edit().putBoolean("gpu", false).apply(); pintarGpu()
        Thread { try { HunterEngine.gpuMinarCerrar() } catch (e: Throwable) { } }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (gpuLista) Thread { try { HunterEngine.gpuMinarCerrar() } catch (e: Throwable) { } }.start()
    }

    private fun anotar(s: String) = runOnUiThread {
        log.addFirst(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + s)
        while (log.size > 12) log.removeLast()
        tvLog.text = log.joinToString("\n")
    }

    /** El mejor hash de una tanda: se apunta y, si es récord, se guarda (como el "best difficulty" de un Bitaxe). */
    private fun apuntarMejor(cab: ByteArray, ceros: Int, nonce: Long) {
        if (ceros <= mejor && mejorDif > 0) return
        val d = Minero.dificultadDe(cab, nonce)
        synchronized(this) {
            if (d > mejorDif) { mejorDif = d; mejor = maxOf(mejor, ceros) }
            if (d > prefs().getFloat("record", 0f)) { prefs().edit().putFloat("record", d.toFloat()).apply(); runOnUiThread { pintarRecord() } }
        }
    }

    private fun pintarRecord() {
        val r = prefs().getFloat("record", 0f).toDouble()
        tvRecord.text = if (r <= 0) "" else "Best share ever on this phone: difficulty ${Minero.corto(r)}" +
            (if (dificultadRed > 0) " · a block needs ${Minero.corto(dificultadRed)} (%s times more)".format(Minero.corto(dificultadRed / r)) else "")
    }

    // ── Práctica ──

    private fun practicar() {
        if (!bateriaOk(true)) return
        empezar("practica")
        val ceros = objetivoCeros
        Thread {
            // La punta de la cadena como bloque anterior, si hay red; si no, ceros (como el génesis).
            val tip = ChainApi.get("/blocks/tip/hash", false)?.trim()?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } } ?: "00".repeat(32)
            val merkle = Minero.dsha("HunterBTC practice block ${System.nanoTime()}".toByteArray())
            val t = System.currentTimeMillis() / 1000
            val base = byteArrayOf(0, 0, 0, 0x20) + Descriptor.bytes(tip).reversedArray() + merkle +
                byteArrayOf(t.toByte(), (t shr 8).toByte(), (t shr 16).toByte(), (t shr 24).toByte()) +
                byteArrayOf(0xff.toByte(), 0xff.toByte(), 0, 0x1d) + ByteArray(4)
            val objetivo = Minero.objetivoBytes(Minero.objetivoCeros(ceros))
            anotar("Mining on top of ${tip.take(16)}… for $ceros zero bits")
            // Todos (hilos y GPU) toman trozos de un mismo contador: cada uno al ritmo que puede.
            val siguiente = AtomicLong(0)
            val lista = obreros()
            val vivos = java.util.concurrent.atomic.AtomicInteger(lista.size)
            for (o in lista) hilos.add(Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val sal = LongArray(3)
                try {
                    while (minando) {
                        if (o.pausado) { Thread.sleep(500); continue }
                        val l = o.lote.toLong()
                        val desde = siguiente.getAndAdd(l)
                        if (desde >= 0x1_0000_0000L) break
                        val hechos = o.hacer(base, desde, minOf(l, 0x1_0000_0000L - desde).toInt(), objetivo, sal) ?: break
                        hashes.addAndGet(hechos)
                        apuntarMejor(base, sal[1].toInt(), sal[2])
                        if (sal[0] >= 0) {
                            val nonce = sal[0]
                            val b = Minero.Buscador(base); b.hash(nonce.toInt()); val hash = b.hashTexto(); val total = hashes.get()
                            runOnUiThread { encontradoPractica(nonce, hash, total, ceros) }
                            return@Thread
                        }
                    }
                } catch (e: InterruptedException) { return@Thread }
                // El último en acabar sin premio (los 4 300 millones de nonces vistos) lo dice.
                if (vivos.decrementAndGet() == 0 && minando && siguiente.get() >= 0x1_0000_0000L)
                    runOnUiThread { parar("No luck in all the nonces: try again") }
            }.apply { isDaemon = true; start() })
        }.start()
    }

    private fun encontradoPractica(nonce: Long, hash: String, total: Long, ceros: Int) {
        if (!minando) return
        parar("Found!")
        val seg = (System.currentTimeMillis() - inicio) / 1000.0
        AlertDialog.Builder(this).setTitle("Block found (practice)")
            .setMessage("Nonce: $nonce\nHash: $hash\n\n%,d tries in %.1f s.\n\n".format(total, seg) +
                (if (dificultadRed > 0) "A real block needs about %.0f zero bits right now: %s times more work than this.".format(
                    Math.log(dificultadRed * 4294967296.0) / Math.log(2.0),
                    Minero.corto(dificultadRed * 4294967296.0 / Math.pow(2.0, ceros.toDouble()))) else ""))
            .setPositiveButton("OK", null).show()
    }

    // ── Lotería en solitario ──

    private fun solo(pool: String, dir: String) {
        val i = pool.lastIndexOf(':')
        val host = if (i > 0) pool.substring(0, i) else ""; val puerto = pool.substring(i + 1).toIntOrNull() ?: 0
        if (host.isEmpty() || puerto !in 1..65535) { Toast.makeText(this, "Write the pool as host:port.", Toast.LENGTH_LONG).show(); return }
        if (BtcAddress.validate(dir.substringBefore('.'), false) !is BtcAddress.Result.Valid) {
            Toast.makeText(this, "Write a valid mainnet address: the reward would go there.", Toast.LENGTH_LONG).show(); return
        }
        if (!bateriaOk(true)) return
        prefs().edit().putString("pool", pool).putString("direccion", dir).apply()
        val hr = estimacion()
        val calc = if (dificultadRed > 0) {
            "\n\nAt ~${Minero.corto(hr)}H/s, with today's difficulty, you would find a block once every %s on average; the chance in a whole day is about 1 in %s."
                .format(java.util.Locale.US, Minero.escala(Minero.segundosPorBloque(hr, dificultadRed)),
                    Minero.corto(1 / Minero.probabilidad(hr, dificultadRed, 86_400.0)))
        } else ""
        AlertDialog.Builder(this).setTitle("Solo lottery")
            .setMessage("This connects to $host and mines with your address. It is a lottery with almost no chance of winning, " +
                "it heats the phone and uses battery, and nothing is earned unless a whole block is found.$calc")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start") { _, _ -> poolActual = Triple(host, puerto, dir); reintentos = 0; empezar("solo"); conectar(); arrancarHilosSolo() }.show()
    }

    /** H/s esperados: lo medido la última vez, o una cuenta prudente por hilo. */
    private fun estimacion(): Double {
        val m = prefs().getFloat("hr_hilo", 0f).toDouble()
        return ((if (m > 0) m else if (tanda === Minero.tandaKotlin) 0.5e6 else 3e6) * nHilos +
            (if (gpuLista) prefs().getFloat("hr_gpu", 0f).toDouble() else 0.0)).takeIf { it > 0 } ?: 3e6
    }

    private fun conectar() {
        val (host, puerto, dir) = poolActual ?: return
        // Un share cada ~30 s a la velocidad esperada.
        val sugerida = (estimacion() * 30 / 4_294_967_296.0).coerceAtLeast(0.0001)
        var este: Stratum? = null
        este = Stratum(host, puerto, dir, sugerida, { anotar(it) }) {
            // Se cortó: los hilos dejan su trabajo (ya no se podría enviar) y se reintenta con espera creciente
            // (5, 10, 20… hasta 60 s) mientras se siga minando.
            if (stratum === este) stratum = null
            if (minando && modo == "solo") {
                val espera = minOf(60, 5 shl minOf(reintentos, 4)); reintentos++
                anotar("Reconnecting in $espera s…")
                h.postDelayed({ if (minando && modo == "solo") conectar() }, espera * 1000L)
            }
        }
        stratum = este; este!!.arrancar()
    }

    private fun arrancarHilosSolo() {
        for (o in obreros()) hilos.add(Thread {
            val k = o.k
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            var cuenta = 0L
            val sal = LongArray(3)
            try { while (minando) {
                val st = stratum
                val t = st?.trabajo; val en1 = st?.extranonce1.orEmpty()
                if (st == null || t == null || en1.isEmpty() || o.pausado) { Thread.sleep(300); continue }
                val gen = st.generacion
                // extranonce2 distinto por hilo y vuelta: hilo en el byte alto, contador en el resto.
                val bits = st.en2Tam * 8
                val v = ((k.toLong() shl (bits - 8).coerceAtLeast(0)) or (cuenta++ and ((1L shl (bits - 8).coerceIn(0, 56)) - 1)))
                val en2 = "%0${st.en2Tam * 2}x".format(v).takeLast(st.en2Tam * 2)
                val cab = try { Minero.cabecera(t, en1, en2) } catch (e: Exception) { anotar("Bad job from the pool"); Thread.sleep(1000); continue }
                val objetivo = Minero.objetivoBytes(Minero.objetivoDeDificultad(st.dificultad))
                var n = 0L
                while (minando && n <= 0xffffffffL && stratum === st) {
                    val hechos = o.hacer(cab, n, minOf(o.lote.toLong(), 0x1_0000_0000L - n).toInt(), objetivo, sal) ?: return@Thread
                    hashes.addAndGet(hechos)
                    apuntarMejor(cab, sal[1].toInt(), sal[2])
                    if (sal[0] >= 0) {
                        st.enviar(t, en2, sal[0].toInt())
                        anotar("Share found (difficulty ${Minero.corto(Minero.dificultadDe(cab, sal[0]))}), sent")
                        n = sal[0] + 1
                    } else n += hechos
                    if (st.generacion != gen && st.trabajo?.limpiar != false) break
                    if (o.pausado) break
                }
            } } catch (e: InterruptedException) {
            } catch (e: Exception) { RegistroFallos.anotar("Minero.solo", e); anotar("Miner thread stopped: ${e.message}") }
        }.apply { isDaemon = true; start() })
    }

    // ── Común ──

    private fun empezar(m: String) {
        modo = m; minando = true; mejor = 0; mejorDif = 0.0; hashes.set(0); hashesGpu.set(0); inicio = System.currentTimeMillis()
        hilosActivos = nHilos; caliente = false
        hilos.clear()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btPractica.text = if (m == "practica") "Stop" else "Mine a practice block"
        btSolo.text = if (m == "solo") "Stop" else "Start solo mining"
        h.post(tic)
    }

    private fun parar(motivo: String) {
        if (!minando) return
        minando = false
        // La velocidad medida por hilo, para la próxima estimación y la dificultad sugerida al pool.
        val seg = (System.currentTimeMillis() - inicio) / 1000.0
        val cpu = hashes.get() - hashesGpu.get()
        if (seg > 10 && cpu > 0) prefs().edit().putFloat("hr_hilo", (cpu / seg / hilosActivos.coerceAtLeast(1)).toFloat()).apply()
        if (seg > 10 && hashesGpu.get() > 0) prefs().edit().putFloat("hr_gpu", (hashesGpu.get() / seg).toFloat()).apply()
        stratum?.cerrar(); stratum = null
        hilos.forEach { it.interrupt() }; hilos.clear()
        h.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btPractica.text = "Mine a practice block"; btSolo.text = "Start solo mining"
        anotar(motivo)
        pintarEstado()
    }

    private val tic = object : Runnable {
        var vueltas = 0
        override fun run() {
            if (!minando) return
            pintarEstado()
            if (++vueltas % 5 == 0) bateriaOk(false)
            h.postDelayed(this, 1000)
        }
    }

    private fun pintarEstado() {
        val seg = ((System.currentTimeMillis() - inicio) / 1000.0).coerceAtLeast(0.001)
        val hr = if (minando) hashes.get() / seg else 0.0
        val st = stratum
        tvEstado.text = buildString {
            append(if (minando) (if (modo == "solo") "Solo mining" else "Practice") else "Idle")
            append(" · engine: $motorTexto\n")
            if (minando || hashes.get() > 0) {
                append("${Minero.corto(hr)}H/s · ${Minero.corto(hashes.get().toDouble())} hashes · %.0f s".format(java.util.Locale.US, seg))
                if (gpuLista) append("\nGPU: ${Minero.corto(hashesGpu.get() / seg)}H/s of that")
                if (caliente) append(" · warm: $hilosActivos of $nHilos threads" + if (gpuLista) ", GPU paused" else "")
                append("\nBest hash this run: $mejor zero bits")
                if (mejorDif > 0) append(" (difficulty ${Minero.corto(mejorDif)})")
            }
            if (st != null) {
                append("\nPool difficulty ${Minero.corto(st.dificultad)} · shares ${st.aceptadas} accepted, ${st.rechazadas} rejected")
                if (hr > 0) {
                    // Cada cuánto, de media, sale un share a la dificultad que ha puesto el pool.
                    val cada = Minero.segundosPorBloque(hr, st.dificultad)
                    append("\nA share every ~${Minero.escala(cada)} at this speed")
                    if (cada > 86_400) append(" (this pool ignores the low difficulty asked for; public-pool.io honours it)")
                }
                if (st.trabajo == null) append("\nWaiting for work from the pool…")
            }
            if (dificultadRed > 0 && hr > 0 && modo == "solo")
                append("\nNetwork difficulty ${Minero.corto(dificultadRed)} · one block every ${Minero.escala(Minero.segundosPorBloque(hr, dificultadRed))} at this speed")
        }
    }

    /**
     * Batería y temperatura: con calor baja los hilos a la mitad (y los recupera al enfriarse); a TEMP_MAX, con
     * poca batería o al desenchufar (si se pidió "sólo cargando") para, o no deja empezar, y lo dice.
     */
    private fun bateriaOk(alEmpezar: Boolean): Boolean {
        val i: Intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val nivel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val cargando = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val motivo = when {
            temp >= TEMP_MAX -> "The battery is at %.0f °C".format(temp)
            nivel < 20 && !cargando -> "Battery below 20 %"
            soloCargando && !cargando -> "Not charging"
            else -> null
        }
        if (motivo != null) {
            if (alEmpezar) Toast.makeText(this, "$motivo: not starting.", Toast.LENGTH_LONG).show() else parar("$motivo: stopped")
            return false
        }
        if (!alEmpezar) {
            val antes = hilosActivos
            caliente = if (temp >= TEMP_TIBIO) true else if (temp < TEMP_TIBIO - 2) false else caliente
            hilosActivos = when {
                temp >= TEMP_TIBIO -> (nHilos / 2).coerceAtLeast(minOf(1, nHilos))
                temp < TEMP_TIBIO - 2 -> nHilos
                else -> hilosActivos
            }
            if (hilosActivos != antes) anotar(if (hilosActivos < antes) "%.0f °C: down to %d thread(s)".format(temp, hilosActivos) else "Cooler: back to $hilosActivos thread(s)")
        }
        return true
    }

    override fun onPause() { super.onPause(); parar("Stopped: the screen was left") }
}
