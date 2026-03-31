#!/usr/bin/env python3
"""
Rediseño de buildRecoveryTab() y DebugActivity con tema dark/neon
"""
import re, os

path = "app/src/main/java/com/hunter/btc/MainActivity.kt"
content = open(path).read()

# ── REBUILD RECOVERY TAB ──────────────────────────────────────────────────────
start = content.find("    // ── BUILD RECOVERY TAB")
end   = content.find("    // ── BUILD NETWORK")
if end == -1:
    end = content.find("    private fun goTab(")
if end == -1:
    end = content.find("    private fun updateUI(")

print(f"Recovery: {content[:start].count(chr(10))+1} → {content[:end].count(chr(10))+1}")

new_recovery = '''    // ── BUILD RECOVERY TAB ──────────────────────────────────────────────────────
    private fun buildRecoveryTab(): ScrollView {
        val LIME = 0xFF39FF14.toInt()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG_DEEP)
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DEEP)
            setPadding(dp(12), dp(12), dp(12), dp(80))
        }

        // ── HEADER ────────────────────────────────────────────────────────
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111411.toInt())
            setPadding(dp(20), dp(16), dp(20), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        headerCard.addView(TextView(this).apply {
            text = "🩹  Recuperación de Semilla"
            textSize = 18f; setTextColor(LIME)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        headerCard.addView(TextView(this).apply {
            text = "Recupera seeds con palabras faltantes (???)"
            textSize = 11f; setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
        })
        page.addView(headerCard)

        // Helper section
        fun section(icon: String, title: String, expanded: Boolean = true, build: LinearLayout.() -> Unit): LinearLayout {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF141814.toInt()); cornerRadius = dp(14).toFloat()
                    setStroke(1, 0xFF2A3028.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isClickable = true; isFocusable = true
            }
            val arrow = TextView(this).apply { text = if (expanded) "∧" else "∨"; textSize = 16f; setTextColor(0xFF666666.toInt()) }
            header.addView(TextView(this).apply { text = icon; textSize = 18f; layoutParams = LinearLayout.LayoutParams(dp(32),dp(32)).apply { marginEnd = dp(10) }; gravity = Gravity.CENTER })
            header.addView(TextView(this).apply {
                text = title; textSize = 14f; setTextColor(0xFFEEEEEE.toInt())
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            header.addView(arrow)
            container.addView(header)
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (expanded) android.view.View.VISIBLE else android.view.View.GONE
                setPadding(dp(16), 0, dp(16), dp(14))
            }
            body.build()
            container.addView(body)
            header.setOnClickListener {
                body.visibility = if (body.visibility == android.view.View.GONE) android.view.View.VISIBLE else android.view.View.GONE
                arrow.text = if (body.visibility == android.view.View.VISIBLE) "∧" else "∨"
            }
            return container
        }

        // ── SECTION: Input ────────────────────────────────────────────────
        val etSeedInput: EditText
        val etTargetAddr: EditText

        page.addView(section("📝", "Seed Phrase") {
            addView(TextView(this@MainActivity).apply {
                text = "Ingresa la seed con ??? para palabras desconocidas"
                textSize = 10f; setTextColor(0xFF888888.toInt())
                setPadding(0, dp(4), 0, dp(6))
            })
            val etSeed = EditText(this@MainActivity).apply {
                hint = "word1 word2 ??? word4 ..."
                setHintTextColor(0xFF444444.toInt())
                setTextColor(0xFFCCCCCC.toInt()); textSize = 12f; typeface = Typeface.MONOSPACE
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF1A2A1A.toInt()); setStroke(1, 0xFF2A3A2A.toInt())
                    cornerRadius = dp(8).toFloat()
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                minLines = 3; maxLines = 6
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            etSeedInput = etSeed
            addView(etSeed)

            addView(TextView(this@MainActivity).apply {
                text = "Dirección objetivo (opcional)"
                textSize = 10f; setTextColor(0xFF888888.toInt())
                setPadding(0, 0, 0, dp(4))
            })
            val etAddr = EditText(this@MainActivity).apply {
                hint = "1BTC..."
                setHintTextColor(0xFF444444.toInt())
                setTextColor(LIME); textSize = 11f; typeface = Typeface.MONOSPACE
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF1A2A1A.toInt()); setStroke(1, 0xFF2A3A2A.toInt())
                    cornerRadius = dp(8).toFloat()
                }
                setPadding(dp(12), dp(8), dp(12), dp(8))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            etTargetAddr = etAddr
            addView(etAddr)
        })

        // ── SECTION: Progress ─────────────────────────────────────────────
        val tvRecoveryStatus: TextView
        val pbRecovery: android.widget.ProgressBar
        val tvRecoveryResult: TextView

        page.addView(section("📊", "Estado", expanded = true) {
            val pb = android.widget.ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; progress = 0
                progressDrawable = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xFF006600.toInt(), LIME)
                ).apply { cornerRadius = dp(10).toFloat() }
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF1A2A1A.toInt()); cornerRadius = dp(10).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(12)
                ).apply { bottomMargin = dp(8) }
                visibility = android.view.View.GONE
            }
            pbRecovery = pb
            addView(pb)

            val tvStatus = TextView(this@MainActivity).apply {
                text = "Listo para iniciar"
                textSize = 11f; setTextColor(0xFF888888.toInt()); typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            tvRecoveryStatus = tvStatus
            addView(tvStatus)

            val tvResult = TextView(this@MainActivity).apply {
                text = ""
                textSize = 12f; setTextColor(LIME); typeface = Typeface.MONOSPACE
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF0D1A0D.toInt()); setStroke(1, 0xFF1A3A1A.toInt())
                    cornerRadius = dp(8).toFloat()
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                visibility = android.view.View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            tvRecoveryResult = tvResult
            addView(tvResult)
        })

        // ── BUTTONS ───────────────────────────────────────────────────────
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, dp(8)) }
        }

        val btnStartRecovery = Button(this).apply {
            text = "▶  INICIAR"
            textSize = 14f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF00CC00.toInt(), LIME)
            ).apply { cornerRadius = dp(12).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) }
        }

        val btnCancelRecovery = Button(this).apply {
            text = "■  CANCELAR"
            textSize = 12f; setTextColor(LIME)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(2, LIME); cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            visibility = android.view.View.GONE
        }

        val btnSaveWallet = Button(this).apply {
            text = "⬇  GUARDAR EN WALLET"
            textSize = 12f; setTextColor(android.graphics.Color.BLACK)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF00E600.toInt()); cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(6) }
            visibility = android.view.View.GONE
        }

        btnRow.addView(btnStartRecovery); btnRow.addView(btnCancelRecovery)
        page.addView(btnRow)
        page.addView(btnSaveWallet)

        // Inicializar RecoveryEngine
        recoveryEngine = com.hunter.btc.recovery.RecoveryEngine(this)
        val wordlistLoaded = recoveryEngine?.loadWordlist() ?: false

        recoveryEngine?.listener = object : com.hunter.btc.recovery.RecoveryEngine.ProgressListener {
            override fun onProgress(attempts: Long, total: Long, currentWord: String) {
                runOnUiThread {
                    val pct = ((attempts.toFloat() / total) * 1000).toInt()
                    pbRecovery.progress = pct
                    tvRecoveryStatus.text = "Probando: $currentWord  ($attempts / $total)"
                    tvRecoveryStatus.setTextColor(0xFFCCCCCC.toInt())
                }
            }
            override fun onFoundWithAddress(mnemonic: String, address: String) {
                runOnUiThread {
                    pbRecovery.visibility = android.view.View.GONE
                    tvRecoveryStatus.visibility = android.view.View.GONE
                    btnCancelRecovery.visibility = android.view.View.GONE
                    btnStartRecovery.visibility = android.view.View.VISIBLE
                    tvRecoveryResult.text = "DIRECCIÓN:\\n$address\\n\\nFRASE:\\n$mnemonic"
                    tvRecoveryResult.visibility = android.view.View.VISIBLE
                    sendMatchNotification(address, mnemonic.take(20))
                }
            }
            override fun onFound(mnemonic: String) {
                runOnUiThread {
                    pbRecovery.visibility = android.view.View.GONE
                    tvRecoveryStatus.visibility = android.view.View.GONE
                    btnCancelRecovery.visibility = android.view.View.GONE
                    btnStartRecovery.visibility = android.view.View.VISIBLE
                    tvRecoveryResult.text = "✓ ENCONTRADO\\n\\n$mnemonic"
                    tvRecoveryResult.visibility = android.view.View.VISIBLE
                    btnSaveWallet.tag = mnemonic
                    btnSaveWallet.visibility = android.view.View.VISIBLE
                    val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                    val f = java.io.File(getExternalFilesDir(null), "recovery_$ts.txt")
                    f.writeText("RECOVERY MATCH\\n$mnemonic\\n")
                    sendMatchNotification(mnemonic.take(30), "RECOVERY")
                }
            }
            override fun onNotFound() {
                runOnUiThread {
                    pbRecovery.visibility = android.view.View.GONE
                    btnCancelRecovery.visibility = android.view.View.GONE
                    btnStartRecovery.visibility = android.view.View.VISIBLE
                    tvRecoveryStatus.text = "✗ No encontrado"
                    tvRecoveryStatus.setTextColor(0xFFFF4444.toInt())
                }
            }
            override fun onError(msg: String) {
                runOnUiThread {
                    tvRecoveryStatus.text = "Error: $msg"
                    tvRecoveryStatus.setTextColor(0xFFFF4444.toInt())
                }
            }
        }

        btnStartRecovery.setOnClickListener {
            val seedText = etSeedInput.text.toString().trim()
            if (seedText.isEmpty()) {
                tvRecoveryStatus.text = "Ingresa una seed phrase"
                tvRecoveryStatus.setTextColor(0xFFFF8800.toInt())
                return@setOnClickListener
            }
            if (!wordlistLoaded) {
                tvRecoveryStatus.text = "Error: wordlist no cargada"
                return@setOnClickListener
            }
            val parseResult = com.hunter.btc.recovery.RecoveryParser.parse(seedText)
            if (!parseResult.isValid) {
                tvRecoveryStatus.text = "Seed inválida: ${parseResult.errorMsg}"
                tvRecoveryStatus.setTextColor(0xFFFF8800.toInt())
                return@setOnClickListener
            }
            val wl = recoveryEngine?.getWordlistSet() ?: emptySet()
            val unknownWords = parseResult.knownWords.filter { it != "???" && it !in wl }
            if (unknownWords.isNotEmpty()) {
                tvRecoveryStatus.text = "Palabras desconocidas: ${unknownWords.take(3).joinToString()}"
                tvRecoveryStatus.setTextColor(0xFFFF8800.toInt())
                return@setOnClickListener
            }
            tvRecoveryStatus.text = "Iniciando búsqueda..."
            tvRecoveryStatus.setTextColor(LIME)
            tvRecoveryStatus.visibility = android.view.View.VISIBLE
            pbRecovery.visibility = android.view.View.VISIBLE
            pbRecovery.progress = 0
            tvRecoveryResult.visibility = android.view.View.GONE
            btnSaveWallet.visibility = android.view.View.GONE
            btnStartRecovery.visibility = android.view.View.GONE
            btnCancelRecovery.visibility = android.view.View.VISIBLE
            recoveryEngine?.startRecovery(parseResult.parsed, etTargetAddr.text.toString().trim())
        }

        btnCancelRecovery.setOnClickListener {
            recoveryEngine?.cancel()
            pbRecovery.visibility = android.view.View.GONE
            btnCancelRecovery.visibility = android.view.View.GONE
            btnStartRecovery.visibility = android.view.View.VISIBLE
            tvRecoveryStatus.text = "Cancelado"
            tvRecoveryStatus.setTextColor(0xFF888888.toInt())
        }

        btnSaveWallet.setOnClickListener {
            val foundMnemonic = it.tag as? String ?: return@setOnClickListener
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Guardar en Wallet")
                .setMessage("¿Guardar esta seed phrase?\\n\\n$foundMnemonic")
                .setPositiveButton("Guardar") { _, _ ->
                    if (PinAuthHelper.isSessionValid()) {
                        WalletManager.saveSeed(this, foundMnemonic)
                        btnSaveWallet.visibility = android.view.View.GONE
                        tvRecoveryStatus.text = "✓ Seed guardada"
                        tvRecoveryStatus.visibility = android.view.View.VISIBLE
                    } else {
                        PinAuthHelper.show(this) { ok ->
                            if (ok) {
                                WalletManager.saveSeed(this, foundMnemonic)
                                btnSaveWallet.visibility = android.view.View.GONE
                                tvRecoveryStatus.text = "✓ Seed guardada"
                                tvRecoveryStatus.visibility = android.view.View.VISIBLE
                            }
                        }
                    }
                }
                .setNegativeButton("Cancelar", null).show()
        }

        scroll.addView(page)
        return scroll
    }

'''

content = content[:start] + new_recovery + content[end:]
open(path, 'w').write(content)
print(f"✓ buildRecoveryTab rediseñado. Líneas: {len(content.splitlines())}")

# ── REBUILD DebugActivity ─────────────────────────────────────────────────────
debug_path = "app/src/main/java/com/hunter/btc/DebugActivity.kt"

new_debug = '''package com.hunter.btc

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
            "Total entradas: ${lines.filter { it.startsWith("===") }.size}\\n\\n" +
            lines.takeLast(60).joinToString("\\n")
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
            appendLine("Scanner:  ${if (HunterEngine.isRunning()) "▶ RUNNING" else "■ STOPPED"}")
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
            ?.take(20)?.joinToString("\\n") { "${it.name} (${it.length()/1024}KB)" }
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
'''

with open(debug_path, 'w') as f:
    f.write(new_debug)
print("✓ DebugActivity rediseñado")
