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
    private var logRunnable: Runnable? = null
    private var tvLiveLog: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // El tema es un objeto de proceso: lo fija la primera pantalla que
        // arranca. Si Android mata el proceso y lo revive directamente aqui
        // —desde una notificacion, o al volver a la tarea—, esa primera
        // pantalla es esta, y sin esto se pintaria en oscuro aunque el
        // usuario tenga elegido el claro.
        AppTheme.init(this)

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
            typeface = Typeface.MONOSPACE   // son datos del sistema
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
            setTextColor(AppTheme.BG_DEEP)
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
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", tvLive.text))
            android.widget.Toast.makeText(this, "Log copied", android.widget.Toast.LENGTH_SHORT).show()
        })
        logBtnRow.addView(actionBtn("Clear", RED) {
            clearLogs()
            tvLive.text = "Logs cleared."
        })
        liveCard.addView(logBtnRow)
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
                    typeface = Typeface.MONOSPACE
                })
            }
        }

        statsCard.addView(statRow("Engine running", if (HunterEngine.isRunning()) "SÍ" else "NO",
            if (HunterEngine.isRunning()) ACCENT else MUTED))
        statsCard.addView(statRow("CSV loaded", if (HunterEngine.isCsvLoaded()) "SÍ" else "NO",
            if (HunterEngine.isCsvLoaded()) ACCENT else RED))
        statsCard.addView(statRow("Speed", "${HunterEngine.getWps()} k/s", ACCENT))
        statsCard.addView(statRow("Keys scanned", HunterEngine.getCount().toString()))
        statsCard.addView(statRow("Matches", HunterEngine.getFound().toString(), ACCENT))
        root.addView(statsCard)

        // ── ARCHIVOS DE LOG ───────────────────────────────────────────────
        val filesCard = card()
        filesCard.addView(label("LOG FILES"))

        // coincidencias.txt vive ahora en almacenamiento interno; el externo se
        // deja listado sólo para poder borrar restos de versiones anteriores.
        val logFiles = listOf(
            File(filesDir, "crash_log.txt"),
            File(filesDir, "puz_debug.txt"),
            File(filesDir, "coincidencias.txt"),
            File(getExternalFilesDir(null), "crash_log.txt"),
            File(getExternalFilesDir(null), "coincidencias.txt")
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
                    val content = f.readText().takeLast(3000)
                    tvLive.text = content
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

        // ── ENGINE LOG BUFFER ─────────────────────────────────────────────
        val engLogCard = card()
        engLogCard.addView(label("ENGINE LOG BUFFER"))
        val tvEngLog = TextView(this).apply {
            text = HunterEngine.popLog().ifEmpty { "No logs in the buffer" }
            textSize = AppTheme.SP_MICRO; setTextColor(AppTheme.TXT_SEC)
            typeface = Typeface.MONOSPACE   // es un registro
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_DEEP); cornerRadius = dp(AppTheme.R_INNER).toFloat()
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200))
            setTextIsSelectable(true)
            isVerticalScrollBarEnabled = true
            movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
        }
        engLogCard.addView(tvEngLog)
        engLogCard.addView(actionBtn("Refresh log", ACCENT2) {
            val logs = buildString {
                repeat(20) {
                    val l = HunterEngine.popLog()
                    if (l.isNotEmpty()) appendLine(l)
                }
            }
            tvEngLog.text = logs.ifEmpty { "Empty buffer" }
        })
        root.addView(engLogCard)

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

    private fun refreshLogs() {
        val tv = tvLiveLog ?: return
        val sb = StringBuilder()

        // Sistema
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val maxMb = rt.maxMemory() / 1048576
        val prefs = getSharedPreferences("hunt_prefs", MODE_PRIVATE)
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

        // Engine log
        val engineLogs = buildString {
            repeat(50) {
                val l = HunterEngine.popLog()
                if (l.isNotEmpty()) appendLine(l)
            }
        }
        if (engineLogs.isNotEmpty()) { sb.appendLine("=== ENGINE ==="); sb.appendLine(engineLogs) }

        // Logcat errores
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "30", "AndroidRuntime:E", "*:S"))
            val lines = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream)).readLines()
            if (lines.isNotEmpty()) { sb.appendLine("=== ERRORS ==="); sb.appendLine(lines.joinToString("\n")) }
        } catch (e: Exception) {}

        // Archivos de log
        listOf(filesDir to "puz_debug.txt", filesDir to "crash_log.txt").forEach { (dir, name) ->
            val f = java.io.File(dir, name)
            if (f.exists() && f.length() > 0) {
                sb.appendLine("=== $name ===")
                sb.appendLine(colaDe(f, 800))
            }
        }

        if (sb.isNotEmpty()) tv.text = sb.toString()
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

    private fun clearLogs() {
        File(filesDir, "puz_debug.txt").delete()
        File(filesDir, "crash_log.txt").delete()
        File(getExternalFilesDir(null), "crash_log.txt").delete()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLogRefresh()
    }
}
