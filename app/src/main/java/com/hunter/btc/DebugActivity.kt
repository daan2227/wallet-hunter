package com.hunter.btc

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class DebugActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var pidioAvisos = false
    private var logRunnable: Runnable? = null
    private var tvLiveLog: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Antes de super.onCreate: es el único momento en que el tema se
        // puede cambiar, y de él salen los colores de todos los diálogos.
        setTheme(AppTheme.estilo(this))
        super.onCreate(savedInstanceState)
        // El tema es un objeto de proceso: lo fija la primera pantalla que
        // arranca. Si Android mata el proceso y lo revive directamente aqui
        // —desde una notificacion, o al volver a la tarea—, esa primera
        // pantalla es esta, y sin esto se pintaria en oscuro aunque el
        // usuario tenga elegido el claro.
        AppTheme.init(this)
        // Cualquier pantalla puede ser la primera si Android revive el
        // proceso por una notificacion, asi que todas lo llaman. Es
        // idempotente: engancha los avisos del sistema una sola vez.
        AppLock.init(this)

        val ACCENT  = AppTheme.ACCENT
        val ACCENT2 = AppTheme.BLUE
        val BG      = AppTheme.BG_DEEP
        val SURFACE = AppTheme.BG_CARD
        val TXT     = AppTheme.TXT_PRI
        val MUTED   = AppTheme.TXT_SEC
        val RED     = AppTheme.WARN

        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(SURFACE); cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text; textSize = AppTheme.SP_CAPTION; setTextColor(MUTED)
            typeface = AppTheme.medium(context)
            letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(12), dp(0), dp(12), dp(40))
        }

        // ── HEADER ────────────────────────────────────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(16), dp(4), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        header.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(AppTheme.TXT_PRI)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) }
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Debug"; textSize = 20f; setTextColor(TXT)
            typeface = AppTheme.title(context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(header)

        // ── LIVE ENGINE LOG ───────────────────────────────────────────────
        val liveCard = card()
        liveCard.addView(label("ENGINE LOG (LIVE)"))

        val tvLive = TextView(this).apply {
            text = "No logs yet... Start a scan or a puzzle to see activity."
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_SEC)
            typeface = AppTheme.mono(context)   // son datos del sistema
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_DEEP); cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(300)
            )
            setTextIsSelectable(true)
            isVerticalScrollBarEnabled = true
            setHorizontallyScrolling(false)
            movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
        }
        tvLiveLog = tvLive
        liveCard.addView(tvLive)

        // Botones control log
        val logBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        fun actionBtn(text: String, color: Int, click: () -> Unit) = TextView(this).apply {
            this.text = text; textSize = AppTheme.SP_BODY; gravity = Gravity.CENTER
            typeface = AppTheme.medium(context)
            isAllCaps = false
            setTextColor(AppTheme.ON_ACCENT)
            background = GradientDrawable().apply {
                setColor(color); cornerRadius = dp(8).toFloat()
            }
            typeface = AppTheme.title(context)
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) }
            isClickable = true; isFocusable = true
            setOnClickListener { click() }
        }

        logBtnRow.addView(actionBtn("Refresh", ACCENT) {
            startLogRefresh()
        })
        logBtnRow.addView(actionBtn("Stop", AppTheme.TXT_SEC) {
            stopLogRefresh()
        })
        logBtnRow.addView(actionBtn("Copy", ACCENT2) {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            // Tachado: el log del motor ha llevado claves privadas —la del
            // puzzle resuelto iba entera— y "Export log" ya las tachaba. Este
            // botón copiaba el mismo texto sin tachar.
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                "log", Secretos.tachar(tvLive.text.toString())))
            android.widget.Toast.makeText(this, "Log copied", android.widget.Toast.LENGTH_SHORT).show()
        })
        logBtnRow.addView(actionBtn("Clear", RED) {
            val n = Diagnostico.archivar(this)
            tvLive.text = if (n == 0) "Nothing to archive." else "Archived $n file(s). Nothing was deleted."
        })
        liveCard.addView(logBtnRow)
        liveCard.addView(TextView(this).apply {
            text = "Check app: runs the health checks and saves the result to app_log.txt."
            textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_MUTED)
            typeface = AppTheme.body(context); setPadding(0, dp(10), 0, 0)
        })
        liveCard.addView(TextView(this).apply {
            text = "Check app"; textSize = AppTheme.SP_BODY; gravity = Gravity.CENTER
            typeface = AppTheme.title(context); setTextColor(AppTheme.ON_ACCENT)
            background = GradientDrawable().apply { setColor(ACCENT2); cornerRadius = dp(8).toFloat() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(6) }
            isClickable = true; isFocusable = true
            setOnClickListener { ejecutarComprobacion() }
        })
        root.addView(liveCard)

        // ── ENGINE STATS ──────────────────────────────────────────────────
        val statsCard = card()
        statsCard.addView(label("ENGINE STATUS"))

        fun statRow(lbl: String, value: String, color: Int = TXT): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                addView(TextView(this@DebugActivity).apply {
                    text = lbl; textSize = AppTheme.SP_CAPTION; setTextColor(MUTED)
                    typeface = AppTheme.body(context)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@DebugActivity).apply {
                    text = value; textSize = AppTheme.SP_CAPTION; setTextColor(color)
                    typeface = AppTheme.mono(context)
                })
            }
        }

        statsCard.addView(statRow("Engine running", if (HunterEngine.isRunning()) "YES" else "NO",
            if (HunterEngine.isRunning()) ACCENT else MUTED))
        statsCard.addView(statRow("CSV loaded", if (HunterEngine.isCsvLoaded()) "YES" else "NO",
            if (HunterEngine.isCsvLoaded()) ACCENT else RED))
        statsCard.addView(statRow("Speed", "%.2f M keys/s".format(HunterEngine.getWps() / 1e6), ACCENT))
        statsCard.addView(statRow("Keys scanned", HunterEngine.getCount().toString()))
        statsCard.addView(statRow("Matches", HunterEngine.getFound().toString(), ACCENT))
        root.addView(statsCard)

        // ── PRUEBA DE RENDIMIENTO ─────────────────────────────────────────
        // Mide en este móvil lo que el banco de escritorio no puede: si la
        // multiplicación en ensamblador ARM64 gana a la de C, y cuántos saltos
        // por segundo da Kangaroo en un hilo. Con el motor parado, para que
        // no compitan por la CPU.
        val benchCard = card()
        benchCard.addView(label("ENGINE BENCHMARK"))
        val tvBench = TextView(this).apply {
            text = "Measures field multiplication (C vs ARM64 assembly) and Kangaroo " +
                   "jumps per second on this phone. Takes about 5 seconds; stop any " +
                   "search first."
            textSize = AppTheme.SP_CAPTION; setTextColor(MUTED)
            typeface = AppTheme.mono(context)
            setTextIsSelectable(true)
            setPadding(0, 0, 0, dp(8))
        }
        benchCard.addView(tvBench)
        // actionBtn está pensado para una fila (ancho 0 y peso): aquí va solo,
        // así que a lo ancho, o saldría con ancho cero.
        fun aLoAncho(v: android.view.View, arriba: Int = 0) = v.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(arriba) }
        }
        benchCard.addView(aLoAncho(actionBtn("Run benchmark", ACCENT) {
            tvBench.text = "Running…"
            Thread {
                val r = try { HunterEngine.benchCampo() } catch (e: Throwable) { "Error: ${e.message}" }
                runOnUiThread { tvBench.text = r + "\n" + android.os.Build.MODEL + " · " +
                                (if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else "") }
            }.start()
        }))
        benchCard.addView(aLoAncho(actionBtn("GPU Kangaroo benchmark", ACCENT2) {
            tvBench.text = "Running Kangaroo on the GPU (about 15 s)…"
            Thread {
                val r = try { HunterEngine.benchGpuKangaroo() } catch (e: Throwable) { "Error: ${e.message}" }
                runOnUiThread { tvBench.text = r }
            }.start()
        }, arriba = 8))
        // Los avisos de hallazgo: manda uno de prueba, o si están bloqueados
        // lo dice y lleva a donde se activan.
        benchCard.addView(aLoAncho(actionBtn("Test notification", ACCENT) {
            when {
                Avisos.faltaPermiso(this) && android.os.Build.VERSION.SDK_INT >= 33 -> {
                    tvBench.text = "Notifications need permission: asking…\n" +
                        "If nothing appears, Android has stopped asking: tap again to open the settings."
                    if (!pidioAvisos) {
                        pidioAvisos = true
                        requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1004)
                    } else Avisos.abrirAjustes(this)
                }
                !Avisos.permitidos(this) -> {
                    tvBench.text = "Notifications are turned off for Wallet Hunter: opening the settings."
                    Avisos.abrirAjustes(this)
                }
                else -> {
                    val ok = Avisos.hallazgo(this, "Test — Wallet Hunter", "Notifications work. A real find says the same and the key goes to the vault.")
                    tvBench.text = if (ok) "Test notification sent." else "Could not send it."
                }
            }
        }, arriba = 8))
        root.addView(benchCard)

        // ── ARCHIVOS DE LOG ───────────────────────────────────────────────
        val filesCard = card()
        filesCard.addView(label("LOG FILES"))

        // coincidencias.txt ya no existe: los hallazgos van directos al baúl
        // cifrado, y los que dejaran versiones anteriores se pasan a él y se
        // borran al abrir la app (MatchVault.recoger).
        val logFiles = listOf(
            File(filesDir, "crash_log.txt"),
            File(filesDir, "crash_log.1.txt"),
            File(filesDir, Diagnostico.MOTOR),
            File(filesDir, Diagnostico.APP),
            File(getExternalFilesDir(null), "crash_log.txt")   // antiguo: no se borra
        )

        logFiles.forEach { f ->
            val exists = f.exists()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                background = GradientDrawable().apply {
                    setColor(AppTheme.BG_CARD); cornerRadius = dp(8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6); setPadding(dp(10), 0, dp(10), 0) }
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            row.addView(TextView(this).apply {
                text = f.name; textSize = AppTheme.SP_BODY
                setTextColor(if (exists) TXT else MUTED)
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (exists) {
                row.addView(TextView(this).apply {
                    text = "${f.length()/1024} KB"; textSize = AppTheme.SP_CAPTION; setTextColor(MUTED)
                    typeface = AppTheme.body(context)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(8) }
                })
                row.isClickable = true; row.isFocusable = true
                row.setOnClickListener {
                    // Sólo la cola: readText() cargaba el fichero entero (sin tope de
                    // tamaño) en el hilo de la pantalla.
                    tvLive.text = colaDe(f, 3000)
                }
            } else {
                row.addView(TextView(this).apply {
                    text = "does not exist"; textSize = AppTheme.SP_CAPTION; setTextColor(AppTheme.TXT_MUTED)
                    typeface = AppTheme.body(context)
                })
            }
            filesCard.addView(row)
        }
        root.addView(filesCard)


        scroll.addView(root)
        setContentView(scroll)

        // Auto-start refresh
        startLogRefresh()
    }

    private fun startLogRefresh() {
        stopLogRefresh()
        logRunnable = object : Runnable {
            override fun run() {
                refreshLogs()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(logRunnable!!)
    }

    private fun stopLogRefresh() {
        logRunnable?.let { handler.removeCallbacks(it) }
        logRunnable = null
    }

    @Volatile private var refrescando = false

    /**
     * Se monta en otro hilo: lanzaba `logcat` y leía ficheros en el hilo de la
     * pantalla cada segundo, y con el motor usando todos los núcleos la pantalla
     * iba a tirones o se daba por colgada.
     */
    private fun refreshLogs() {
        if (refrescando || tvLiveLog == null) return
        refrescando = true
        Thread {
            val txt = try { construirLogs() } catch (e: Throwable) { "" }
            runOnUiThread {
                refrescando = false
                if (!isFinishing && txt.isNotEmpty()) tvLiveLog?.text = txt
            }
        }.start()
    }

    private fun construirLogs(): String {
        val sb = StringBuilder()

        // Sistema
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val maxMb = rt.maxMemory() / 1048576
        // "hunt_prefs" no lo escribía nadie: salía siempre false/OFF/BIP39.
        val prefs = getSharedPreferences("hunter", MODE_PRIVATE)
        sb.appendLine("=== SYSTEM ===")
        sb.appendLine("RAM: ${usedMb}MB used / ${maxMb}MB max")
        sb.appendLine("Scan running: ${prefs.getBoolean("scan_was_running", false)}")
        sb.appendLine("Watchdog: ${if (prefs.getBoolean("watchdog", false)) "ON" else "OFF"}")
        val modeStr = when(prefs.getInt("scan_mode", 0)) { 0 -> "BIP39"; 2 -> "RAWKEY"; else -> "PUZZLE" }
        sb.appendLine("Mode: $modeStr")
        try {
            val t = java.io.File("/sys/class/thermal/thermal_zone0/temp")
            if (t.exists()) sb.appendLine("CPU Temp: ${(t.readText().trim().toIntOrNull() ?: 0)/1000}C")
        } catch (e: Exception) {}
        sb.appendLine()

        // Motor: lo que saca ahora se guarda en engine_log.txt y se muestra aquí.
        val motor = Diagnostico.drenarMotor(applicationContext)
        if (motor.isNotEmpty()) { sb.appendLine("=== ENGINE ==="); sb.appendLine(motor) }

        // Avisos y errores de este proceso, guardados en app_log.txt.
        Diagnostico.volcarLogcat(applicationContext)
        val app = File(filesDir, Diagnostico.APP)
        if (app.exists() && app.length() > 0) {
            sb.appendLine("=== ${Diagnostico.APP} ===")
            sb.appendLine(colaDe(app, 3000))
        }

        // Cierres.
        val crash = File(filesDir, "crash_log.txt")
        if (crash.exists() && crash.length() > 0) {
            sb.appendLine("=== crash_log.txt ===")
            sb.appendLine(colaDe(crash, 800))
        }

        return sb.toString()
    }

    private fun ejecutarComprobacion() {
        tvLiveLog?.text = "Checking…"
        Thread {
            val lineas = try { Diagnostico.comprobar(applicationContext) }
                         catch (e: Throwable) { listOf("MAL  comprobacion: ${e.message}") }
            runOnUiThread {
                if (!isFinishing) tvLiveLog?.text = lineas.joinToString("\n") +
                    "\n\n(also saved to ${Diagnostico.APP})"
            }
        }.start()
    }

    /**
     * Los últimos [n] caracteres de un fichero, sin leerlo entero.
     *
     * Antes era `f.readText().takeLast(800)`: cargaba el fichero COMPLETO en
     * memoria para quedarse con el final. Estos ficheros los escribe la app con
     * "append" y nada limita su tamaño, así que es la misma forma de morir que
     * acaba de matarla con la lista de hallazgos —244 MB de 256—. Y aquí duele
     * más: esta es la pantalla a la que se viene cuando algo ya ha fallado.
     */
    private fun colaDe(f: java.io.File, n: Int): String = try {
        java.io.RandomAccessFile(f, "r").use { r ->
            val desde = maxOf(0L, r.length() - n)
            r.seek(desde)
            val buf = ByteArray((r.length() - desde).toInt())
            r.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
    } catch (e: Exception) { "" }

    override fun onDestroy() {
        super.onDestroy()
        stopLogRefresh()
    }
}
