package com.hunter.btc

import android.os.Bundle
import android.widget.*
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class DebugActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val BG   = AppTheme.BG_DEEP
        val AMBER = AppTheme.AMBER
        val TXT  = AppTheme.TXT_PRI
        val MUTED = AppTheme.TXT_MUTED
        val RED  = AppTheme.RED
        val GREEN = AppTheme.GREEN

        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        header.addView(Button(this).apply {
            text = "<"; textSize = 14f; setTextColor(AMBER)
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(1, AppTheme.BORDER_C); cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(10), dp(2), dp(10), dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(36)).apply { marginEnd = dp(12) }
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Debug / Logs"
            textSize = 18f; setTextColor(AMBER)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        })
        root.addView(header)

        fun section(title: String) = TextView(this).apply {
            text = title; textSize = 9f; setTextColor(MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.16f
            setPadding(0, dp(12), 0, dp(6))
        }

        fun logBox(content: String, color: Int = TXT) = TextView(this).apply {
            text = content.ifEmpty { "— vacío —" }
            textSize = 10f; setTextColor(color)
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_CARD); cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        // ── Crash Log ────────────────────────────────────────────────────
        root.addView(section("ULTIMO CRASH"))
        val crashFile = File(filesDir.absolutePath + "/crash_log.txt")
        val crashText = if (crashFile.exists()) crashFile.readText() else "Sin crashes registrados"
        val crashColor = if (crashFile.exists()) RED else GREEN
        root.addView(logBox(crashText, crashColor))

        if (crashFile.exists()) {
            root.addView(Button(this).apply {
                text = "Borrar crash log"
                textSize = 11f; setTextColor(RED)
                background = GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke(1, RED); cornerRadius = dp(6).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(40)
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    crashFile.delete()
                    Toast.makeText(this@DebugActivity, "Crash log borrado", Toast.LENGTH_SHORT).show()
                    recreate()
                }
            })
        }

        // ── Estado del sistema ───────────────────────────────────────────
        root.addView(section("ESTADO DEL SISTEMA"))
        val rt = Runtime.getRuntime()
        val usedMB = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val totalMB = rt.totalMemory() / 1048576
        val maxMB = rt.maxMemory() / 1048576
        val sysInfo = buildString {
            appendLine("Device:  ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE}")
            appendLine("ABI:     ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
            appendLine("RAM:     ${usedMB}MB / ${totalMB}MB (max ${maxMB}MB)")
            appendLine("Cores:   ${Runtime.getRuntime().availableProcessors()}")
            appendLine("Scanner: ${if (HunterEngine.isRunning()) "RUNNING" else "STOPPED"}")
            appendLine("WPS:     ${HunterEngine.getWps()}")
            appendLine("Count:   ${HunterEngine.getCount()}")
            appendLine("CSV:     ${if (HunterEngine.isCsvLoaded()) "loaded" else "not loaded"}")
        }
        root.addView(logBox(sysInfo, TXT))

        // ── Logs internos del scanner ────────────────────────────────────
        root.addView(section("LOGS DEL SCANNER"))
        val scanLog = buildString {
            repeat(20) {
                val line = try { HunterEngine.popLog() } catch (e: Exception) { "" }
                if (line.isNotEmpty()) appendLine(line)
            }
        }
        root.addView(logBox(scanLog.ifEmpty { "Sin logs recientes" }, AppTheme.CYAN))

        // ── Archivos de la app ───────────────────────────────────────────
        root.addView(section("ARCHIVOS"))
        val extDir = getExternalFilesDir(null)
        val filesList = extDir?.listFiles()?.joinToString("\n") {
            "${it.name} (${it.length()/1024}KB)"
        } ?: "Sin archivos"
        root.addView(logBox(filesList, MUTED))

        // ── Botón refresh ────────────────────────────────────────────────
        root.addView(Button(this).apply {
            text = "Actualizar"
            textSize = 12f; setTextColor(android.graphics.Color.BLACK)
            background = GradientDrawable().apply {
                setColor(AMBER); cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(16) }
            setOnClickListener { recreate() }
        })

        scroll.addView(root)
        setContentView(scroll)
    }
}
