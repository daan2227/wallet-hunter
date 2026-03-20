#!/usr/bin/env python3
"""
rebuild_a.py
Paso A: Reconstruye WelcomeActivity (solo PIN) y el skeleton de MainActivity
con 4 tabs: Scan, Puzzle, Wallet, Recovery.
El contenido de cada tab se migra en rebuild_b.py y rebuild_c.py.
"""

import os, sys
PROJECT_ROOT = os.getcwd()
KT_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "hunter", "btc")

# ── 1. WelcomeActivity — solo PIN ─────────────────────────────────────────────
welcome = '''package com.hunter.btc

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*

class WelcomeActivity : Activity() {

    private val BG    get() = AppTheme.BG_DEEP
    private val GOLD  get() = AppTheme.AMBER
    private val TXT   get() = AppTheme.TXT_PRI
    private val TXT3  get() = AppTheme.TXT_MUTED
    private val BORDER get() = AppTheme.BORDER_C
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        AppTheme.init(this)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = BG

        if (WalletManager.hasPin(this)) {
            showPinEntry()
        } else {
            showPinSetup()
        }
    }

    // ── PIN ENTRY ─────────────────────────────────────────────────────────────
    private fun showPinEntry() {
        showPinDialog(isSetup = false) { ok ->
            if (ok) {
                PinAuthHelper.markAuthenticated()
                goMain()
            } else {
                finish()
            }
        }
    }

    // ── PIN SETUP ─────────────────────────────────────────────────────────────
    private fun showPinSetup() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(80), dp(32), dp(48))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Logo
        root.addView(TextView(this).apply {
            text = "WALLET\\nHUNTER"
            textSize = 42f; setTextColor(GOLD)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = -0.02f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "BITCOIN SEED SCANNER"
            textSize = 9f; setTextColor(TXT3)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            letterSpacing = 0.2f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(48))
        })

        // Descripción
        root.addView(TextView(this).apply {
            text = "Crea un PIN de seguridad\\npara proteger tus wallets"
            textSize = 14f; setTextColor(TXT)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(32))
        })

        // Botón crear PIN
        root.addView(Button(this).apply {
            text = "⚷  CREAR PIN DE SEGURIDAD"
            textSize = 13f; setTextColor(Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.06f
            background = GradientDrawable().apply {
                setColor(GOLD); cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            )
            setOnClickListener {
                showPinDialog(isSetup = true) { ok ->
                    if (ok) {
                        PinAuthHelper.markAuthenticated()
                        goMain()
                    }
                }
            }
        })

        setContentView(root)
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(400).setStartDelay(100).start()
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    // ── PIN DIALOG (mismo diseño que WalletActivity) ──────────────────────────
    private fun showPinDialog(isSetup: Boolean, onResult: (Boolean) -> Unit) {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(AppTheme.BG_PANEL)
                cornerRadius = dp(16).toFloat()
                setStroke(1, BORDER)
            }
            setPadding(dp(24), dp(20), dp(24), dp(32))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(24), 0, dp(24), 0) }
        }

        sheet.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(BORDER); cornerRadius = dp(2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(3)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(22)
            }
        })

        sheet.addView(TextView(this).apply {
            text = if (isSetup) "Crear PIN" else "Ingresar PIN"
            textSize = 16f; setTextColor(TXT)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.04f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(22))
        })

        val pinDisplay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(28))
        }
        val dots = Array(6) {
            View(this).apply {
                val sz = dp(12)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(14) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), BORDER)
                }
            }
        }
        dots.forEach { pinDisplay.addView(it) }
        sheet.addView(pinDisplay)

        val tvStatus = TextView(this).apply {
            text = if (isSetup) "Elige un PIN de 6 dígitos" else "Ingresa tu PIN"
            textSize = 10f; setTextColor(TXT3)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            letterSpacing = 0.05f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }

        val pin = StringBuilder()
        var firstPin = ""
        var dlg: android.app.AlertDialog? = null

        fun updateDots() = dots.forEachIndexed { i, d ->
            val bg = d.background as GradientDrawable
            if (i < pin.length) { bg.setColor(GOLD); bg.setStroke(0, Color.TRANSPARENT) }
            else { bg.setColor(Color.TRANSPARENT); bg.setStroke(dp(2), BORDER) }
        }

        fun handleDigit(k: String) {
            if (k == "DEL") {
                if (pin.isNotEmpty()) pin.deleteCharAt(pin.length - 1)
                updateDots(); return
            }
            if (pin.length >= 6) return
            pin.append(k); updateDots()
            if (pin.length < 6) return

            if (isSetup) {
                if (firstPin.isEmpty()) {
                    firstPin = pin.toString(); pin.clear(); updateDots()
                    tvStatus.text = "Confirma tu PIN"
                    tvStatus.setTextColor(AppTheme.CYAN)
                } else if (firstPin == pin.toString()) {
                    WalletManager.savePin(this, pin.toString())
                    dlg?.dismiss(); onResult(true)
                } else {
                    firstPin = ""; pin.clear(); updateDots()
                    tvStatus.text = "PINs no coinciden"
                    tvStatus.setTextColor(0xFFFF4444.toInt())
                }
            } else {
                if (WalletManager.checkPin(this, pin.toString())) {
                    dlg?.dismiss(); onResult(true)
                } else {
                    pin.clear(); updateDots()
                    tvStatus.text = "PIN incorrecto"
                    tvStatus.setTextColor(0xFFFF4444.toInt())
                }
            }
        }

        val numpad = android.widget.GridLayout(this).apply {
            columnCount = 3; rowCount = 4; setPadding(0, 0, 0, dp(10))
        }
        listOf("1","2","3","4","5","6","7","8","9","","0","DEL").forEach { k ->
            numpad.addView(Button(this).apply {
                text = k
                if (k == "DEL") {
                    textSize = 12f; setTextColor(0xFFFF4444.toInt())
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                } else {
                    textSize = 22f; setTextColor(TXT)
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                }
                background = GradientDrawable().apply {
                    setColor(if (k.isEmpty()) Color.TRANSPARENT else AppTheme.BG_CARD)
                    if (k.isNotEmpty()) setStroke(1, BORDER)
                    cornerRadius = dp(10).toFloat()
                }
                val sz = dp(76)
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = sz; height = sz
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                isEnabled = k.isNotEmpty()
                if (k.isNotEmpty()) setOnClickListener { handleDigit(k) }
            })
        }
        sheet.addView(numpad)
        sheet.addView(tvStatus)

        if (!isSetup) {
            val btnCancel = Button(this).apply {
                text = "Cancelar"; textSize = 12f; setTextColor(AppTheme.TXT_SEC)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                letterSpacing = 0.1f; isAllCaps = true
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); setStroke(1, BORDER)
                    cornerRadius = dp(6).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
                ).apply { topMargin = dp(8) }
            }
            sheet.addView(btnCancel)
            dlg = android.app.AlertDialog.Builder(this)
                .setView(sheet).setCancelable(false).create()
            btnCancel.setOnClickListener { dlg?.dismiss(); onResult(false) }
        } else {
            dlg = android.app.AlertDialog.Builder(this)
                .setView(sheet).setCancelable(false).create()
        }

        dlg!!.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
            attributes = attributes?.also { it.dimAmount = 0.7f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg!!.show()
    }
}
'''

with open(os.path.join(KT_DIR, "WelcomeActivity.kt"), "w") as f:
    f.write(welcome)
print("✓ WelcomeActivity.kt reconstruida")

# ── 2. MainActivity skeleton con 4 tabs ───────────────────────────────────────
# Leer el MainActivity actual para extraer variables y lógica que reutilizaremos
current_main = open(os.path.join(KT_DIR, "MainActivity.kt")).read()

# Extraer bloques que necesitamos preservar
import re

# Extraer puzzles list
puzzles_match = re.search(r'(private val puzzles = listOf\(.*?\))', current_main, re.DOTALL)
puzzles_block = puzzles_match.group(1) if puzzles_match else ""

# Extraer PuzzleInfo data class
puzzle_info_match = re.search(r'(data class PuzzleInfo\(.*?\))', current_main, re.DOTALL)
puzzle_info_block = puzzle_info_match.group(1) if puzzle_info_match else \
    'data class PuzzleInfo(val num: Int, val addr: String, val start: String, val end: String, val btc: String)'

main_skeleton = '''package com.hunter.btc

import android.app.*
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import java.io.File
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import java.io.*
import com.hunter.btc.recovery.RecoveryEngine
import com.hunter.btc.recovery.RecoveryParser
import com.hunter.btc.recovery.ParseResult

class MainActivity : Activity() {

    // ── Tema ──────────────────────────────────────────────────────────────────
    private val BG_DEEP   get() = AppTheme.BG_DEEP
    private val BG_CARD   get() = AppTheme.BG_CARD
    private val BG_PANEL  get() = AppTheme.BG_PANEL
    private val BG_ELEV   get() = AppTheme.BG_ELEV
    private val AMBER     get() = AppTheme.AMBER
    private val CYAN      get() = AppTheme.CYAN
    private val GREEN     get() = AppTheme.GREEN
    private val TXT_PRI   get() = AppTheme.TXT_PRI
    private val TXT_SEC   get() = AppTheme.TXT_SEC
    private val TXT_MUTED get() = AppTheme.TXT_MUTED
    private val BORDER_C  get() = AppTheme.BORDER_C
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun cardBg() = GradientDrawable().apply {
        setColor(BG_CARD); cornerRadius = dp(8).toFloat(); setStroke(1, BORDER_C)
    }

    // ── Tab system ────────────────────────────────────────────────────────────
    private var tabPages = listOf<android.view.View>()
    private var tabBtns  = listOf<android.widget.TextView>()
    private fun goTab(idx: Int) {
        tabPages.forEachIndexed { i, v ->
            v.visibility = if (i == idx) android.view.View.VISIBLE else android.view.View.GONE
        }
        tabBtns.forEachIndexed { i, b ->
            b.setTextColor(if (i == idx) AMBER else TXT_MUTED)
        }
    }

    // ── Scan vars ─────────────────────────────────────────────────────────────
    private lateinit var tvStatus: TextView
    private lateinit var tvWps: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvRam: TextView
    private lateinit var tvBattery: TextView
    private lateinit var sbThreads: SeekBar
    private lateinit var sbCpu: SeekBar
    private lateinit var tvLog: TextView
    private val logBuf = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runStripe: View
    private val recentAddrs = mutableListOf<String>()

    // ── Puzzle vars ───────────────────────────────────────────────────────────
    private lateinit var tvPuzzleStatus: TextView
    private lateinit var puzzleSpinner: Spinner
    private var suppressPuzzleListener = false
    private lateinit var rbPuzzle: Button
    private var puzzleMode = false
    ${puzzle_info_block}
    ${puzzles_block}

    // ── Recovery vars ─────────────────────────────────────────────────────────
    private lateinit var recoveryEngine: RecoveryEngine

    // ── Stats vars ────────────────────────────────────────────────────────────
    private lateinit var tvStatsSec: LinearLayout

    // ── Misc ──────────────────────────────────────────────────────────────────
    private lateinit var tvQuickThreads: TextView
    private lateinit var tvQuickCpu: TextView
    private lateinit var tvQuickCsv: TextView
    private val prefs get() = getSharedPreferences("hunter", MODE_PRIVATE)
    private lateinit var modeToggleBtn: Button

    private val updater = object : Runnable {
        override fun run() {
            updateUI()
            handler.postDelayed(this, 800)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        AppTheme.init(this)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.statusBarColor = BG_DEEP

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DEEP)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ── FrameLayout contenedor de tabs ────────────────────────────────────
        val cf = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        // ── Construir cada tab ────────────────────────────────────────────────
        val scanScroll    = buildScanTab()
        val puzzleScroll  = buildPuzzleTab()
        val walletScroll  = buildWalletTab()
        val recoveryScroll= buildRecoveryTab()

        cf.addView(scanScroll)
        cf.addView(puzzleScroll)
        cf.addView(walletScroll)
        cf.addView(recoveryScroll)

        root.addView(cf)

        // ── TabBar ────────────────────────────────────────────────────────────
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG_PANEL)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(62)
            )
        }
        fun tabBtn(ico: String, lbl: String): TextView = TextView(this).apply {
            text = "$ico\\n$lbl"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.1f; gravity = Gravity.CENTER; isAllCaps = true
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
        }

        val tb0 = tabBtn("⊙", "Scan")
        val tb1 = tabBtn("⬡", "Puzzle")
        val tb2 = tabBtn("◈", "Wallet")
        val tb3 = tabBtn("⚷", "Recovery")

        listOf(tb0, tb1, tb2, tb3).forEach { tabBar.addView(it) }
        root.addView(tabBar)
        setContentView(root)

        tabPages = listOf(scanScroll, puzzleScroll, walletScroll, recoveryScroll)
        tabBtns  = listOf(tb0, tb1, tb2, tb3)
        listOf(tb0, tb1, tb2, tb3).forEachIndexed { i, b ->
            b.setOnClickListener {
                if (i == 2) {
                    // Tab Wallet: pedir PIN si no hay sesión válida
                    if (WalletManager.hasPin(this) && !PinAuthHelper.isSessionValid()) {
                        PinAuthHelper.show(this) { ok -> if (ok) goTab(2) }
                    } else {
                        goTab(2)
                    }
                } else {
                    goTab(i)
                }
            }
        }
        goTab(0)

        // Init services
        initScanConfig()
        setupNotificationChannel()
        registerBatteryReceiver()
    }

    // ── PLACEHOLDER TABS (se completan en rebuild_b y rebuild_c) ─────────────

    private fun buildScanTab(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(80))
        }
        // CONTENIDO SCAN — se agrega en rebuild_b.py
        page.addView(TextView(this).apply {
            text = "SCAN"; textSize = 20f; setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.BOLD)
        })
        scroll.addView(page)
        return scroll
    }

    private fun buildPuzzleTab(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(80))
        }
        // CONTENIDO PUZZLE — se agrega en rebuild_b.py
        page.addView(TextView(this).apply {
            text = "PUZZLE"; textSize = 20f; setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.BOLD)
        })
        scroll.addView(page)
        return scroll
    }

    private fun buildWalletTab(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(80))
        }
        // CONTENIDO WALLET — se agrega en rebuild_c.py
        page.addView(TextView(this).apply {
            text = "WALLET"; textSize = 20f; setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.BOLD)
        })
        scroll.addView(page)
        return scroll
    }

    private fun buildRecoveryTab(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG_DEEP) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(80))
        }
        // CONTENIDO RECOVERY — se agrega en rebuild_b.py
        page.addView(TextView(this).apply {
            text = "RECOVERY"; textSize = 20f; setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.BOLD)
        })
        scroll.addView(page)
        return scroll
    }

    // ── STUBS que se completan en rebuild_b ───────────────────────────────────
    private fun initScanConfig() {
        val csvPath = prefs.getString("csvPath", "") ?: ""
        if (csvPath.isNotEmpty() && File(csvPath).exists() && !HunterEngine.isCsvLoaded()) {
            HunterEngine.loadCsv(this, csvPath)
        }
    }

    private fun updateUI() {
        if (HunterEngine.isRunning()) {
            tvWps.text   = "%.1f".format(HunterEngine.getWps())
            tvCount.text = formatCount(HunterEngine.getCount())
        }
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        tvRam.text = "RAM ${used}MB"
    }

    private fun formatCount(v: Long) = when {
        v >= 1_000_000_000L -> "%.2fB".format(v / 1e9)
        v >= 1_000_000L     -> "%.2fM".format(v / 1e6)
        v >= 1_000L         -> "%.2fK".format(v / 1e3)
        else                -> "$v"
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = android.app.NotificationChannel(
                "hunter", "Hunter", android.app.NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private var batteryReceiver: BroadcastReceiver? = null
    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, i: Intent?) {
                val pct = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return
                tvBattery.text = "BAT $pct%"
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onResume() {
        super.onResume()
        handler.post(updater)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updater)
    }

    override fun onDestroy() {
        super.onDestroy()
        batteryReceiver?.let { unregisterReceiver(it) }
    }

    // ── checkPuzzleBalance stub ───────────────────────────────────────────────
    private fun checkPuzzleBalance(addr: String, onResult: (Long) -> Unit) {
        Thread {
            try {
                val url = java.net.URL("https://blockchain.info/q/addressbalance/$addr")
                val bal = url.readText().trim().toLongOrNull() ?: 0L
                runOnUiThread { onResult(bal) }
            } catch (e: Exception) {
                runOnUiThread { onResult(0L) }
            }
        }.start()
    }
}
'''.replace("    ${puzzle_info_block}", f"    {puzzle_info_block}") \
   .replace("    ${puzzles_block}", f"    {puzzles_block}")

with open(os.path.join(KT_DIR, "MainActivity.kt"), "w") as f:
    f.write(main_skeleton)
print("✓ MainActivity.kt skeleton creado")

print("""
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Rebuild A completado:

  WelcomeActivity.kt  ← solo PIN setup/entry
  MainActivity.kt     ← skeleton 4 tabs

La app compila pero las tabs muestran
placeholders. rebuild_b.py migra el
contenido de Scan, Puzzle y Recovery.

Siguiente: git add + push → verificar compilación
Luego: rebuild_b.py
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
""")
