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
 * Cliente Stratum v1 mínimo para un pool "solo" (ckpool solo y similares): subscribe, authorize, los trabajos
 * de mining.notify y la dificultad, y mining.submit. Con Tor/proxy activado va por él. [ev] se llama desde
 * el hilo de lectura.
 */
class Stratum(private val host: String, private val puerto: Int, val usuario: String, private val ev: (String) -> Unit) {
    @Volatile var extranonce1 = ""; @Volatile var en2Tam = 4
    @Volatile var trabajo: Minero.Trabajo? = null
    @Volatile var dificultad = 1.0
    @Volatile var generacion = 0
    @Volatile var aceptadas = 0; @Volatile var rechazadas = 0
    @Volatile private var abierto = true
    private var socket: java.net.Socket? = null
    private var salida: java.io.Writer? = null
    private var siguienteId = 10

    fun arrancar() = Thread {
        try {
            val s = RedPrivada.socket(host).also { socket = it }
            s.connect(RedPrivada.destino(host, puerto), 20_000); s.soTimeout = 0
            salida = s.getOutputStream().bufferedWriter()
            mandar(JSONObject().put("id", 1).put("method", "mining.subscribe").put("params", JSONArray().put("HunterBTC/1.0")))
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
        abierto = false
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
            "mining.set_difficulty" -> if (p != null) { dificultad = p.getDouble(0); ev("Pool difficulty: %,.0f".format(dificultad)); return }
            "client.show_message" -> if (p != null) { ev("Pool: ${p.optString(0)}"); return }
        }
        when (o.optInt("id", -1)) {
            1 -> o.optJSONArray("result")?.let { r -> extranonce1 = r.getString(1); en2Tam = r.getInt(2) }
            2 -> if (o.optBoolean("result")) ev("Authorized as ${usuario.take(14)}…") else ev("The pool rejected the address: ${o.opt("error")}")
            -1 -> {}
            else -> if (o.optBoolean("result")) { aceptadas++; ev("Share accepted") } else { rechazadas++; ev("Share rejected: ${o.opt("error")}") }
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
 *  - Lotería en solitario: mina para un pool "solo" (por defecto solo.ckpool.org) con tu dirección: si este
 *    teléfono encontrara un bloque, el premio iría a ella. La probabilidad es ínfima y se dice.
 * Sólo mina con la pantalla abierta y se para si el teléfono se calienta o se queda sin batería.
 */
class MineroActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_DIRECCION = "direccion"
        private const val TEMP_MAX = 42.0
        private const val POOL = "solo.ckpool.org:3333"
    }

    private lateinit var caja: LinearLayout
    private lateinit var tvEstado: TextView
    private lateinit var tvLog: TextView
    private lateinit var btPractica: TextView
    private lateinit var btSolo: TextView
    private val h = Handler(Looper.getMainLooper())
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Volatile private var minando = false
    private var modo = ""
    private val hilos = java.util.concurrent.CopyOnWriteArrayList<Thread>()
    private val hashes = AtomicLong()
    @Volatile private var mejor = 0
    private var inicio = 0L
    private var stratum: Stratum? = null
    private var objetivoCeros = 24
    private var nHilos = 1
    @Volatile private var dificultadRed = 0.0
    private val log = ArrayDeque<String>()

    override fun onCreate(s: Bundle?) {
        setTheme(AppTheme.estilo(this)); super.onCreate(s)
        AppTheme.init(this); AppLock.init(this)
        nHilos = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        val scroll = ScrollView(this).apply { setBackgroundColor(AppTheme.BG_DEEP); isFillViewport = true }
        caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(AppTheme.PAD_SIDE), dp(12), dp(AppTheme.PAD_SIDE), dp(40)) }
        scroll.addView(caja); setContentView(scroll)
        construir()
        Thread {
            val js = ChainApi.get("/v1/mining/hashrate/3d", false)
            dificultadRed = try { JSONObject(js ?: "").optDouble("currentDifficulty", 0.0) } catch (e: Exception) { 0.0 }
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

    private fun construir() {
        caja.addView(Ui.pageTitle(this, "Block miner", lados = false))
        caja.addView(texto("Mining is guessing: change a number (the nonce) in a block header until its double SHA-256 starts with " +
            "enough zeros. This phone does a few million guesses per second; the whole network does hundreds of " +
            "billions of billions. Mining warms the phone and drains the battery: it only runs with this screen open, " +
            "and stops at $TEMP_MAX °C or below 20 % battery.", AppTheme.SP_CAPTION, AppTheme.TXT_SEC))

        caja.addView(Ui.sectionLabel(this, "Threads", topGap = dp(12)))
        val tvHilos = texto("")
        val max = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        fun pintarHilos() { tvHilos.text = "$nHilos of $max cores" }
        pintarHilos()
        caja.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(tvHilos, LinearLayout.LayoutParams(0, -2, 1f))
            addView(boton("−") { if (!minando && nHilos > 1) { nHilos--; pintarHilos() } }, LinearLayout.LayoutParams(dp(56), dp(44)))
            addView(boton("+") { if (!minando && nHilos < max) { nHilos++; pintarHilos() } }, LinearLayout.LayoutParams(dp(56), dp(44)).apply { marginStart = dp(8) })
        })

        // ── Práctica ──
        caja.addView(Ui.sectionLabel(this, "Practice block (on this phone)", topGap = dp(16)))
        caja.addView(texto("Mine a real-format header on top of the current chain tip, at a toy difficulty you choose. " +
            "Each extra zero bit doubles the work.", AppTheme.SP_CAPTION, AppTheme.TXT_SEC))
        val tvCeros = texto("")
        fun pintarCeros() { tvCeros.text = "$objetivoCeros zero bits · ~%,d tries".format(1L shl objetivoCeros) }
        pintarCeros()
        caja.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(tvCeros, LinearLayout.LayoutParams(0, -2, 1f))
            addView(boton("−") { if (!minando && objetivoCeros > 12) { objetivoCeros -= 2; pintarCeros() } }, LinearLayout.LayoutParams(dp(56), dp(44)))
            addView(boton("+") { if (!minando && objetivoCeros < 30) { objetivoCeros += 2; pintarCeros() } }, LinearLayout.LayoutParams(dp(56), dp(44)).apply { marginStart = dp(8) })
        })
        btPractica = boton("Mine a practice block", principal = true) { if (minando) parar("Stopped") else practicar() }
        caja.addView(btPractica)

        // ── Lotería en solitario ──
        caja.addView(Ui.sectionLabel(this, "Solo lottery (real network)", topGap = dp(20)))
        caja.addView(texto("Mines for a solo pool with your address as the user. If this phone ever found a block, the reward " +
            "(minus the pool's fee, 2 % on ckpool) would go straight to that address. Realistically it never will: see the odds " +
            "below. The pool sees your IP unless Tor/proxy is on.", AppTheme.SP_CAPTION, AppTheme.TXT_SEC))
        val etPool = Ui.input(this, "Pool host:port", mono = true).apply { setText(POOL) }
        val etDir = Ui.input(this, "Your bitcoin address (bc1…)", mono = true).apply { setText(intent.getStringExtra(EXTRA_DIRECCION) ?: "") }
        caja.addView(etPool); caja.addView(etDir)
        btSolo = boton("Start solo mining") {
            if (minando) parar("Stopped") else solo(etPool.text.toString().trim(), etDir.text.toString().trim())
        }
        caja.addView(btSolo)

        caja.addView(Ui.sectionLabel(this, "Status", topGap = dp(20)))
        tvEstado = texto("Idle", AppTheme.SP_CAPTION, AppTheme.TXT_PRI, mono = true)
        tvLog = texto("", AppTheme.SP_CAPTION, AppTheme.TXT_SEC, mono = true)
        caja.addView(tvEstado); caja.addView(tvLog)
    }

    private fun anotar(s: String) = runOnUiThread {
        log.addFirst(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + s)
        while (log.size > 12) log.removeLast()
        tvLog.text = log.joinToString("\n")
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
            anotar("Mining on top of ${tip.take(16)}… for $ceros zero bits")
            for (k in 0 until nHilos) hilos.add(Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val b = Minero.Buscador(base)
                var n = k.toLong(); var c = 0
                while (minando && n <= 0xffffffffL) {
                    b.hash(n.toInt())
                    val z = Integer.numberOfLeadingZeros(Integer.reverseBytes(b.h2[7]))
                    val zz = if (z == 32) b.ceros() else z
                    if (zz > mejor) mejor = zz
                    if (zz >= ceros) {
                        val nonce = n.toInt(); val hash = b.hashTexto(); val total = hashes.get()
                        runOnUiThread {
                            if (!minando) return@runOnUiThread
                            parar("Found!")
                            val seg = (System.currentTimeMillis() - inicio) / 1000.0
                            AlertDialog.Builder(this).setTitle("Block found (practice)")
                                .setMessage("Nonce: ${nonce.toLong() and 0xffffffffL}\nHash: $hash\n\n%,d tries in %.1f s.\n\n".format(total, seg) +
                                    (if (dificultadRed > 0) "A real block needs about %.0f zero bits right now: %s more work than this.".format(
                                        Math.log(dificultadRed * 4294967296.0) / Math.log(2.0),
                                        "%.1e times".format(dificultadRed * 4294967296.0 / Math.pow(2.0, ceros.toDouble()))) else ""))
                                .setPositiveButton("OK", null).show()
                        }
                        return@Thread
                    }
                    n += nHilos
                    if ((++c and 0x3fff) == 0) hashes.addAndGet(0x4000)
                }
                // Con ≤ 30 bits casi nunca se acaba el espacio de nonces; si pasa, se dice.
                if (minando && n > 0xffffffffL) runOnUiThread { parar("No luck in this thread's nonces: try again") }
            }.apply { isDaemon = true; start() })
        }.start()
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
        val calc = if (dificultadRed > 0) {
            val hr = 1e6 * nHilos
            "\n\nAt ~%.0f MH/s, with today's difficulty, you would find a block once every %s on average; the chance in a whole day is about 1 in %s."
                .format(java.util.Locale.US, hr / 1e6, Minero.escala(Minero.segundosPorBloque(hr, dificultadRed)),
                    "%.1e".format(java.util.Locale.US, 1 / Minero.probabilidad(hr, dificultadRed, 86_400.0)))
        } else ""
        AlertDialog.Builder(this).setTitle("Solo lottery")
            .setMessage("This connects to $host and mines with your address. It is a lottery with almost no chance of winning, " +
                "it heats the phone and uses battery, and nothing is earned unless a whole block is found.$calc")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start") { _, _ -> arrancarSolo(host, puerto, dir) }.show()
    }

    private fun arrancarSolo(host: String, puerto: Int, dir: String) {
        empezar("solo")
        val st = Stratum(host, puerto, dir) { anotar(it) }.also { stratum = it }
        st.arrancar()
        for (k in 0 until nHilos) hilos.add(Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            var cuenta = 0L
            try { while (minando) {
                val t = st.trabajo; val en1 = st.extranonce1
                if (t == null || en1.isEmpty()) { try { Thread.sleep(300) } catch (e: InterruptedException) { return@Thread }; continue }
                val gen = st.generacion
                // extranonce2 distinto por hilo y vuelta: hilo en el byte alto, contador en el resto.
                val bits = st.en2Tam * 8
                val v = ((k.toLong() shl (bits - 8).coerceAtLeast(0)) or (cuenta++ and ((1L shl (bits - 8).coerceIn(0, 56)) - 1)))
                val en2 = "%0${st.en2Tam * 2}x".format(v).takeLast(st.en2Tam * 2)
                val cab = try { Minero.cabecera(t, en1, en2) } catch (e: Exception) { anotar("Bad job from the pool"); Thread.sleep(1000); continue }
                val b = Minero.Buscador(cab)
                val objetivo = Minero.objetivoDeDificultad(st.dificultad)
                var n = 0L
                while (minando && n <= 0xffffffffL) {
                    b.hash(n.toInt())
                    if (b.h2[7] == 0) {
                        val zz = b.ceros(); if (zz > mejor) mejor = zz
                        if (b.cumple(objetivo)) { st.enviar(t, en2, n.toInt()); anotar("Share found (${zz} zero bits), sent") }
                    } else { val z = Integer.numberOfLeadingZeros(Integer.reverseBytes(b.h2[7])); if (z > mejor) mejor = z }
                    n++
                    if ((n and 0xffff) == 0L) { hashes.addAndGet(0x10000); if (st.generacion != gen && st.trabajo?.limpiar != false) break }
                }
            } } catch (e: InterruptedException) {
            } catch (e: Exception) { RegistroFallos.anotar("Minero.solo", e); anotar("Miner thread stopped: ${e.message}") }
        }.apply { isDaemon = true; start() })
    }

    // ── Común ──

    private fun empezar(m: String) {
        modo = m; minando = true; mejor = 0; hashes.set(0); inicio = System.currentTimeMillis()
        hilos.clear()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btPractica.text = if (m == "practica") "Stop" else "Mine a practice block"
        btSolo.text = if (m == "solo") "Stop" else "Start solo mining"
        h.post(tic)
    }

    private fun parar(motivo: String) {
        if (!minando) return
        minando = false
        stratum?.cerrar(); stratum = null
        hilos.forEach { it.interrupt() }; hilos.clear()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btPractica.text = "Mine a practice block"; btSolo.text = "Start solo mining"
        h.removeCallbacks(tic)
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
        val hr = hashes.get() / seg
        val st = stratum
        tvEstado.text = buildString {
            append(if (minando) (if (modo == "solo") "Solo mining" else "Practice") else "Idle"); append('\n')
            append("%.2f MH/s · %,d hashes · %.0f s\n".format(java.util.Locale.US, hr / 1e6, hashes.get(), seg))
            append("Best hash: $mejor zero bits")
            if (st != null) {
                append("\nPool difficulty %,.0f · shares %d accepted, %d rejected".format(java.util.Locale.US, st.dificultad, st.aceptadas, st.rechazadas))
                if (st.trabajo == null) append("\nWaiting for work from the pool…")
            }
            if (dificultadRed > 0 && hr > 0 && modo == "solo")
                append("\nNetwork difficulty %.3g · one block every %s at this speed".format(java.util.Locale.US, dificultadRed, Minero.escala(Minero.segundosPorBloque(hr, dificultadRed))))
        }
    }

    /** Batería y temperatura: si se pasa, para (o no deja empezar) y lo dice. */
    private fun bateriaOk(alEmpezar: Boolean): Boolean {
        val i: Intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val nivel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val cargando = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val motivo = when {
            temp >= TEMP_MAX -> "The battery is at %.0f °C".format(temp)
            nivel < 20 && !cargando -> "Battery below 20 %"
            else -> return true
        }
        if (alEmpezar) Toast.makeText(this, "$motivo: not starting.", Toast.LENGTH_LONG).show() else parar("$motivo: stopped")
        return false
    }

    override fun onPause() { super.onPause(); parar("Stopped: the screen was left") }
}
