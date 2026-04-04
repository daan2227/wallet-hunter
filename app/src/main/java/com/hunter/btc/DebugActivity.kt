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

        val ACCENT  = 0xFF00C896.toInt()
        val ACCENT2 = 0xFF0087FF.toInt()
        val BG      = 0xFF0B0E14.toInt()
        val SURFACE = 0xFF111520.toInt()
        val BORDER  = 0xFF1E2540.toInt()
        val TXT     = 0xFFE8EAF0.toInt()
        val MUTED   = 0xFF5A607A.toInt()
        val RED     = 0xFFFF6B35.toInt()

        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(SURFACE); cornerRadius = dp(14).toFloat()
                setStroke(1, BORDER)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text; textSize = 9f; setTextColor(MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD)
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
        header.addView(Button(this).apply {
            text = "←"; textSize = 16f; setTextColor(ACCENT)
            background = GradientDrawable().apply {
                setColor(SURFACE); setStroke(1, BORDER); cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Debug"; textSize = 20f; setTextColor(TXT)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(header)

        // ── LIVE ENGINE LOG ───────────────────────────────────────────────
        val liveCard = card()
        liveCard.addView(label("ENGINE LOG (TIEMPO REAL)"))

        val tvLive = TextView(this).apply {
            text = "Sin logs aun... Inicia un scan o puzzle para ver actividad."
            textSize = 10f; setTextColor(ACCENT)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            background = GradientDrawable().apply {
                setColor(0xFF0B0E14.toInt()); cornerRadius = dp(8).toFloat()
                setStroke(1, BORDER)
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(200)
            )
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
            this.text = text; textSize = 11f; gravity = Gravity.CENTER
            setTextColor(0xFF000000.toInt())
            background = GradientDrawable().apply {
                setColor(color); cornerRadius = dp(8).toFloat()
            }
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) }
            isClickable = true; isFocusable = true
            setOnClickListener { click() }
        }

        logBtnRow.addView(actionBtn("▶ AUTO REFRESH", ACCENT) {
            startLogRefresh()
        })
        logBtnRow.addView(actionBtn("⏹ PARAR", 0xFF5A607A.toInt()) {
            stopLogRefresh()
        })
        logBtnRow.addView(actionBtn("🗑 LIMPIAR", RED) {
            clearLogs()
            tvLive.text = "Logs limpiados."
        })
        liveCard.addView(logBtnRow)
        root.addView(liveCard)

        // ── ENGINE STATS ──────────────────────────────────────────────────
        val statsCard = card()
        statsCard.addView(label("ESTADO DEL ENGINE"))

        fun statRow(lbl: String, value: String, color: Int = TXT): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                addView(TextView(this@DebugActivity).apply {
                    text = lbl; textSize = 10f; setTextColor(MUTED)
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@DebugActivity).apply {
                    text = value; textSize = 10f; setTextColor(color)
                    typeface = Typeface.create("monospace", Typeface.BOLD)
                })
            }
        }

        statsCard.addView(statRow("Engine running", if (HunterEngine.isRunning()) "SÍ" else "NO",
            if (HunterEngine.isRunning()) ACCENT else MUTED))
        statsCard.addView(statRow("CSV cargado", if (HunterEngine.isCsvLoaded()) "SÍ" else "NO",
            if (HunterEngine.isCsvLoaded()) ACCENT else RED))
        statsCard.addView(statRow("Velocidad", "${HunterEngine.getWps()} k/s", ACCENT))
        statsCard.addView(statRow("Keys escaneadas", HunterEngine.getCount().toString()))
        statsCard.addView(statRow("Matches", HunterEngine.getFound().toString(), ACCENT))
        root.addView(statsCard)

        // ── ARCHIVOS DE LOG ───────────────────────────────────────────────
        val filesCard = card()
        filesCard.addView(label("ARCHIVOS DE LOG"))

        val logFiles = listOf(
            File(filesDir, "crash_log.txt"),
            File(filesDir, "puz_debug.txt"),
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
                    setColor(0xFF171C2C.toInt()); cornerRadius = dp(8).toFloat()
                    setStroke(1, BORDER)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6); setPadding(dp(10), 0, dp(10), 0) }
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            row.addView(TextView(this).apply {
                text = f.name; textSize = 11f
                setTextColor(if (exists) TXT else MUTED)
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (exists) {
                row.addView(TextView(this).apply {
                    text = "${f.length()/1024}KB"; textSize = 10f; setTextColor(MUTED)
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
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
                    text = "no existe"; textSize = 10f; setTextColor(MUTED)
                    typeface = Typeface.create("monospace", Typeface.ITALIC)
                })
            }
            filesCard.addView(row)
        }
        root.addView(filesCard)

        // ── ENGINE LOG BUFFER ─────────────────────────────────────────────
        val engLogCard = card()
        engLogCard.addView(label("LOG BUFFER DEL ENGINE"))
        val tvEngLog = TextView(this).apply {
            text = HunterEngine.popLog().ifEmpty { "Sin logs en buffer" }
            textSize = 10f; setTextColor(ACCENT2)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            background = GradientDrawable().apply {
                setColor(0xFF0B0E14.toInt()); cornerRadius = dp(8).toFloat(); setStroke(1, BORDER)
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150))
        }
        engLogCard.addView(tvEngLog)
        engLogCard.addView(actionBtn("↻ REFRESCAR LOG", ACCENT2) {
            val logs = buildString {
                repeat(20) {
                    val l = HunterEngine.popLog()
                    if (l.isNotEmpty()) appendLine(l)
                }
            }
            tvEngLog.text = logs.ifEmpty { "Buffer vacío" }
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

        // Engine popLog
        val engineLogs = buildString {
            repeat(30) {
                val l = HunterEngine.popLog()
                if (l.isNotEmpty()) appendLine(l)
            }
        }
        if (engineLogs.isNotEmpty()) sb.appendLine("=== ENGINE ===
$engineLogs")

        // puz_debug.txt
        val puzFile = File(filesDir, "puz_debug.txt")
        if (puzFile.exists()) {
            val content = puzFile.readText().takeLast(1500)
            sb.appendLine("=== PUZ DEBUG ===
$content")
        }

        // crash_log.txt
        val crashFile = File(filesDir, "crash_log.txt")
        if (crashFile.exists()) {
            val content = crashFile.readText().takeLast(1000)
            sb.appendLine("=== CRASH LOG ===
$content")
        }

        if (sb.isNotEmpty()) tv.text = sb.toString()
        else if (tv.text.startsWith("Sin logs")) {
            // keep placeholder
        }
    }

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
