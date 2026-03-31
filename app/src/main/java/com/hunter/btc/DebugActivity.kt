package com.hunter.btc

import android.os.Bundle
import android.widget.*
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class DebugActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val LIME  = 0xFF39FF14.toInt()
        val BG    = 0xFF0A0E0A.toInt()
        val CARD  = 0xFF141814.toInt()
        val TXT   = 0xFFCCCCCC.toInt()
        val MUTED = 0xFF888888.toInt()
        val RED   = 0xFFFF4444.toInt()
        val GREEN = 0xFF00CC44.toInt()

        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(12), dp(12), dp(12), dp(40))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF111411.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        header.addView(Button(this).apply {
            text = "<"; textSize = 14f; setTextColor(LIME)
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(1, 0xFF2A3A2A.toInt()); cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(10), dp(2), dp(10), dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(36)).apply { marginEnd = dp(12) }
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "🐛  Debug Console"
            textSize = 18f; setTextColor(LIME)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        })
        root.addView(header)

        fun sectionCard(title: String, content: String, color: Int = TXT): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(CARD); cornerRadius = dp(12).toFloat()
                    setStroke(1, 0xFF2A3028.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            card.addView(TextView(this).apply {
                text = title; textSize = 9f; setTextColor(0xFF555555.toInt())
                typeface = Typeface.create("monospace", Typeface.BOLD)
                letterSpacing = 0.14f
                setPadding(dp(14), dp(10), dp(14), dp(4))
            })
            card.addView(View(this).apply {
                setBackgroundColor(0xFF1E2A1E.toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
            card.addView(TextView(this).apply {
                text = content.ifEmpty { "— vacío —" }
                textSize = 10f; setTextColor(color)
                typeface = Typeface.MONOSPACE
                setPadding(dp(14), dp(10), dp(14), dp(12))
            })
            return card
        }

        // Crash log
        val crashPath = (getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath) + "/crash_log.txt"
        val crashFile = File(crashPath)
        val crashText = if (crashFile.exists()) {
            val lines = crashFile.readLines()
            "Total entradas: ${lines.filter { it.startsWith("===") }.size}" + "\n\n" + lines.takeLast(60).joinToString("\n")
        } else "Sin crashes registrados ✓"
        val crashColor = if (crashFile.exists()) RED else GREEN
        root.addView(sectionCard("ULTIMO CRASH", crashText, crashColor))

        if (crashFile.exists()) {
            root.addView(Button(this).apply {
                text = "Limpiar crash log"
                textSize = 11f; setTextColor(RED)
                background = GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke(1, RED); cornerRadius = dp(8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
                ).apply { bottomMargin = dp(10) }
                setOnClickListener { crashFile.delete(); recreate() }
            })
        }

        // System info
        val rt = Runtime.getRuntime()
        val usedMB  = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val totalMB = rt.maxMemory() / 1048576
        val sysInfo = buildString {
            appendLine("Device:   ${android.os.Build.MODEL}")
            appendLine("Android:  ${android.os.Build.VERSION.RELEASE}")
            appendLine("ABI:      ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
            appendLine("RAM:      ${usedMB}MB / ${totalMB}MB")
            appendLine("Cores:    ${Runtime.getRuntime().availableProcessors()}")
            appendLine("Scanner:  ${if (HunterEngine.isRunning()) "RUN" else "STOP"}")
            appendLine("WPS:      ${HunterEngine.getWps()}")
            appendLine("Count:    ${HunterEngine.getCount()}")
            appendLine("CSV:      ${if (HunterEngine.isCsvLoaded()) "✓ loaded" else "✗ not loaded"}")
        }
        root.addView(sectionCard("SISTEMA", sysInfo, TXT))

        // Scanner logs
        val scanLog = buildString {
            repeat(30) {
                val line = try { HunterEngine.popLog() } catch (e: Exception) { "" }
                if (line.isNotEmpty()) appendLine(line)
            }
        }
        root.addView(sectionCard("SCANNER LOGS", scanLog.ifEmpty { "Sin logs recientes" }, 0xFF00CCCC.toInt()))

        // Files
        val extDir = getExternalFilesDir(null)
        val filesList = extDir?.listFiles()?.sortedByDescending { it.lastModified() }
            ?.take(20)?.joinToString("\n") { "${it.name} (${it.length()/1024}KB)" }
            ?: "Sin archivos"
        root.addView(sectionCard("ARCHIVOS", filesList, MUTED))

        // Network status
        val netInfo = buildString {
            appendLine("IP Local: ${NetworkManager.getLocalIp(this@DebugActivity)}")
            appendLine("Master:   ${NetworkManager.isMaster}")
            appendLine("Worker:   ${NetworkManager.isWorker}")
            appendLine("Running:  ${NetworkManager.isRunning.get()}")
            appendLine("Bloques global: ${NetworkManager.globalScannedBlocks.size}")
        }
        root.addView(sectionCard("RED", netInfo, TXT))

        // Refresh button
        root.addView(Button(this).apply {
            text = "↺  Actualizar"
            textSize = 13f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF00CC00.toInt(), LIME)
            ).apply { cornerRadius = dp(12).toFloat() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(8) }
            setOnClickListener { recreate() }
        })

        scroll.addView(root)
        setContentView(scroll)
    }
}
