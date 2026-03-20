#!/usr/bin/env python3
"""
rebuild_b.py
Reemplaza el MainActivity.kt skeleton con la versión completa,
migrando el contenido original de Scan, Config/Puzzle y Recovery
a las nuevas funciones buildScanTab(), buildPuzzleTab(), buildRecoveryTab().
"""

import os, sys
PROJECT_ROOT = os.getcwd()
KT_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "java", "com", "hunter", "btc")

# Leer bloques originales
home = os.path.expanduser("~")
scan_block     = open(os.path.join(home, "scan_block.kt")).read()
cfg_block      = open(os.path.join(home, "cfg_block.kt")).read()
recovery_block = open(os.path.join(home, "recovery_block.kt")).read()
original       = open(os.path.join(home, "main_original.kt")).read()

# Extraer funciones auxiliares del original (líneas 1-422 aprox)
# SpeedChartView, doToggle, updateUI, mkSbl, secLbl, etc.
import re

# Extraer SpeedChartView (líneas 1-55 aprox)
speedchart_match = re.search(
    r'(class SpeedChartView.*?^})',
    original, re.DOTALL | re.MULTILINE
)
speedchart = speedchart_match.group(1) if speedchart_match else ""

# Extraer todas las funciones privadas después de onCreate (líneas ~1000+)
funcs_match = re.search(
    r'(private fun doToggle.*)',
    original, re.DOTALL
)
funcs_block = funcs_match.group(1) if funcs_match else ""
# Limpiar el bloque — quitar el último "}" de la clase si aparece
funcs_block = funcs_block.rstrip()
if funcs_block.endswith("}"):
    funcs_block = funcs_block[:-1].rstrip()

# Extraer checkPuzzleBalance
puzzle_balance_match = re.search(
    r'(private fun checkPuzzleBalance.*?^\s*})',
    original, re.DOTALL | re.MULTILINE
)
puzzle_balance = puzzle_balance_match.group(1) if puzzle_balance_match else ""

# Extraer autoSelectPuzzle
auto_puzzle_match = re.search(
    r'(private fun autoSelectPuzzle.*?^\s*})',
    original, re.DOTALL | re.MULTILINE
)
auto_puzzle = auto_puzzle_match.group(1) if auto_puzzle_match else ""

# Extraer applyPuzzle
apply_puzzle_match = re.search(
    r'(private fun applyPuzzle.*?^\s*})',
    original, re.DOTALL | re.MULTILINE
)
apply_puzzle = apply_puzzle_match.group(1) if apply_puzzle_match else ""

# Limpiar referencias a cf.addView en los bloques (ahora retornan ScrollView)
def clean_block(block):
    block = re.sub(r'\s*cf\.addView\(\w+\)\s*', '\n', block)
    block = re.sub(r'\s*col\.addView\(\w+\)\s*', '\n', block)
    return block

scan_clean     = clean_block(scan_block)
cfg_clean      = clean_block(cfg_block)
recovery_clean = clean_block(recovery_block)

# Extraer líneas del scan que crean el ScrollView (no las necesitamos, buildScanTab ya lo hace)
scan_inner = re.sub(r'val scanScroll\s*=.*?layoutParams.*?\}\s*\n', '', scan_clean, flags=re.DOTALL)
scan_inner = re.sub(r'scanScroll\.addView\(scanPage\)', '', scan_inner)

cfg_inner = re.sub(r'val cfgScroll\s*=.*?layoutParams.*?\}\s*\n', '', cfg_clean, flags=re.DOTALL)
cfg_inner = re.sub(r'cfgScroll\.addView\(cfgPage\)', '', cfg_inner)

recovery_inner = re.sub(r'val recoveryScroll\s*=.*?\}\s*\n', '', recovery_clean, flags=re.DOTALL)
recovery_inner = re.sub(r'recoveryScroll\.addView\(recoveryPage\)', '', recovery_inner)

# Stats block - extraer del original
stats_inner = ""
stats_match = re.search(r'val statsPage\s*=.*?statsScroll\.addView\(statsPage\)', original, re.DOTALL)
if stats_match:
    stats_inner = stats_match.group(0)
    stats_inner = re.sub(r'statsScroll\.addView\(statsPage\)', '', stats_inner)

main_kt = f'''package com.hunter.btc

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

{speedchart}

class MainActivity : Activity() {{

    // ── Tema ──────────────────────────────────────────────────────────────────
    private val BG_DEEP   get() = AppTheme.BG_DEEP
    private val BG_PANEL  get() = AppTheme.BG_PANEL
    private val BG_CARD   get() = AppTheme.BG_CARD
    private val BG_ELEV   get() = AppTheme.BG_ELEV
    private val AMBER     get() = AppTheme.AMBER
    private val GREEN     get() = AppTheme.GREEN
    private val RED       get() = AppTheme.RED
    private val CYAN      get() = AppTheme.CYAN
    private val TXT_PRI   get() = AppTheme.TXT_PRI
    private val TXT_SEC   get() = AppTheme.TXT_SEC
    private val TXT_MUTED get() = AppTheme.TXT_MUTED
    private val BORDER_C  get() = AppTheme.BORDER_C
    private val YELLOW    get() = AppTheme.YELLOW
    private val ORANGE    get() = AppTheme.ORANGE
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun cardBg() = GradientDrawable().apply {{
        setColor(BG_CARD); cornerRadius = dp(8).toFloat(); setStroke(1, BORDER_C)
    }}

    // ── Tab system ────────────────────────────────────────────────────────────
    private var tabPages = listOf<android.view.View>()
    private var tabBtns  = listOf<android.widget.TextView>()
    private fun goTab(idx: Int) {{
        tabPages.forEachIndexed {{ i, v ->
            v.visibility = if (i == idx) android.view.View.VISIBLE else android.view.View.GONE
        }}
        tabBtns.forEachIndexed {{ i, b ->
            b.setTextColor(if (i == idx) AMBER else TXT_MUTED)
        }}
    }}

    // ── Variables ─────────────────────────────────────────────────────────────
    private var sessionStartTime = 0L
    private var sessionStartCount = 0L
    private var batteryReceiver: android.content.BroadcastReceiver? = null
    private var lastFoundCount = 0L
    private val NOTIF_CHANNEL = "hunter_match"
    private val NOTIF_ID = 42
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvStatus: TextView
    private var tvQuickCsv: TextView? = null
    private var tvQuickMatches: TextView? = null
    private lateinit var tvWps: TextView
    private lateinit var tvKps: TextView
    private var tvQuickThreads: TextView? = null
    private var tvQuickCpu: TextView? = null
    private var fastModeEnabled = false
    private var filterP2PKH  = true
    private var filterP2SH   = true
    private var filterP2WPKH = true
    private var lblDataset: TextView? = null
    private var lblPerformance: TextView? = null
    private var lblMode: TextView? = null
    private var lblWallet: TextView? = null
    private var lblLog: TextView? = null
    private var lblStats: TextView? = null
    private lateinit var tvLiveSec: LinearLayout
    private lateinit var tvMatchSec: LinearLayout
    private lateinit var tvLogSec: LinearLayout
    private lateinit var tvLangLbl: TextView
    private lateinit var tvCount: TextView
    private lateinit var chartView: SpeedChartView
    private lateinit var tvPuzzleStatus: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvMatches: TextView
    private lateinit var tvMatchList: TextView
    private lateinit var tvAddrFeed: TextView
    private lateinit var tvRam: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvLog: TextView
    private lateinit var puzzleSpinner: Spinner
    private var suppressPuzzleListener = false
    private lateinit var tvFooter: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnSwitch: Button
    private lateinit var sbThreads: SeekBar
    private lateinit var sbCpu: SeekBar
    private lateinit var tvThreads: TextView
    private lateinit var tvCpu: TextView
    private lateinit var tvCsvSec: LinearLayout
    private lateinit var tvConfigSec: LinearLayout
    private lateinit var tvStatsSec: LinearLayout
    private lateinit var btnCsv: Button
    private lateinit var csvSecView: LinearLayout
    private lateinit var etRangeStart: EditText
    private lateinit var etRangeEnd: EditText
    private lateinit var etTarget: EditText
    private lateinit var layoutPuzzle: LinearLayout
    private lateinit var rbBip39: Button
    private lateinit var rbPuzzle: Button
    private var csvPath: String = ""
    private var s = Strings.EN
    private var puzzleMode = false
    private val recentAddrs = mutableListOf<String>()
    private lateinit var recoveryEngine: RecoveryEngine
    private val prefs get() = getSharedPreferences("hunter", MODE_PRIVATE)

    data class PuzzleInfo(val num: Int, val addr: String, val start: String, val end: String, val btc: String)
    private val puzzles = listOf(
        PuzzleInfo(71,"1PWo3JeB9jrGwfHDNpdGK54CRas7fsVzXU","400000000000000000","7fffffffffffffffff","7.1 BTC"),
        PuzzleInfo(72,"1JTK7s9YVYywfm5XUH7RNhHJH1LshCaRFR","800000000000000000","ffffffffffffffffff","7.2 BTC"),
        PuzzleInfo(73,"12VVRNPi4SJqUTsp6FmqDqY5sGosDtysn4","1000000000000000000","1ffffffffffffffffff","7.3 BTC"),
        PuzzleInfo(74,"1FWGcVDK3JGzCC3WtkYetULPszMaK2Jksv","2000000000000000000","3ffffffffffffffffff","7.4 BTC"),
        PuzzleInfo(76,"1DJh2eHFYQfACPmrvpyWc8MSTYKh7w9eRF","8000000000000000000","fffffffffffffffffff","7.6 BTC"),
        PuzzleInfo(77,"1Bxk4CQdqL9p22JEtDfdXMsng1XacifUtE","10000000000000000000","1fffffffffffffffffff","7.7 BTC"),
        PuzzleInfo(78,"15qF6X51huDjqTmF9BJgxXdt1xcj46Jmhb","20000000000000000000","3fffffffffffffffffff","7.8 BTC"),
        PuzzleInfo(79,"1ARk8HWJMn8js8tQmGUJeQHjSE7KRkn2t8","40000000000000000000","7fffffffffffffffffff","7.9 BTC"),
        PuzzleInfo(81,"15qsCm78whspNQFydGJQk5rexzxTQopnHZ","100000000000000000000","1fffffffffffffffffff","8.1 BTC")
    )

    private val updater = object : Runnable {{
        override fun run() {{
            updateUI()
            handler.postDelayed(this, 800)
        }}
    }}

    override fun onCreate(savedState: Bundle?) {{
        super.onCreate(savedState)
        AppTheme.init(this)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.statusBarColor = BG_DEEP
        s = Strings.EN
        csvPath = prefs.getString("csvPath", "") ?: ""

        val root = LinearLayout(this).apply {{
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DEEP)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }}

        val cf = FrameLayout(this).apply {{
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }}

        val scanScroll     = buildScanTab()
        val puzzleScroll   = buildPuzzleTab()
        val walletScroll   = buildWalletTab()
        val recoveryScroll = buildRecoveryTab()

        cf.addView(scanScroll)
        cf.addView(puzzleScroll)
        cf.addView(walletScroll)
        cf.addView(recoveryScroll)
        root.addView(cf)

        // ── TabBar ────────────────────────────────────────────────────────────
        val tabBar = LinearLayout(this).apply {{
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG_PANEL)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(62)
            )
        }}
        fun tabBtn(ico: String, lbl: String): TextView = TextView(this).apply {{
            text = "$ico\\n$lbl"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.1f; gravity = Gravity.CENTER; isAllCaps = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }}

        val tb0 = tabBtn("⊙", "Scan")
        val tb1 = tabBtn("⬡", "Puzzle")
        val tb2 = tabBtn("◈", "Wallet")
        val tb3 = tabBtn("⚷", "Recovery")
        listOf(tb0, tb1, tb2, tb3).forEach {{ tabBar.addView(it) }}
        root.addView(tabBar)
        setContentView(root)

        tabPages = listOf(scanScroll, puzzleScroll, walletScroll, recoveryScroll)
        tabBtns  = listOf(tb0, tb1, tb2, tb3)
        listOf(tb0, tb1, tb2, tb3).forEachIndexed {{ i, b ->
            b.setOnClickListener {{
                if (i == 2) {{
                    if (WalletManager.hasPin(this) && !PinAuthHelper.isSessionValid()) {{
                        PinAuthHelper.show(this) {{ ok -> if (ok) goTab(2) }}
                    }} else goTab(2)
                }} else goTab(i)
            }}
        }}
        goTab(0)

        // Init
        if (csvPath.isNotEmpty() && File(csvPath).exists() && !HunterEngine.isCsvLoaded())
            HunterEngine.loadCsv(csvPath)
        setupNotificationChannel()
        registerBatteryReceiver()
        updateLabels()

        val uiSp = getSharedPreferences("ui_state", MODE_PRIVATE)
        if (uiSp.contains("puzzleMode")) {{
            sbThreads.progress = uiSp.getInt("threads", 3)
            sbCpu.progress = uiSp.getInt("cpu", 70)
        }}
    }}

    // ── BUILD SCAN TAB ────────────────────────────────────────────────────────
    private fun buildScanTab(): ScrollView {{
        val scanScroll = ScrollView(this).apply {{
            setBackgroundColor(BG_DEEP)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }}
        val scanPage = LinearLayout(this).apply {{ orientation = LinearLayout.VERTICAL }}
        {scan_inner}
        scanScroll.addView(scanPage)
        return scanScroll
    }}

    // ── BUILD PUZZLE TAB ──────────────────────────────────────────────────────
    private fun buildPuzzleTab(): ScrollView {{
        val cfgScroll = ScrollView(this).apply {{
            setBackgroundColor(BG_DEEP)
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }}
        val cfgPage = LinearLayout(this).apply {{
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }}
        fun cfgCard() = LinearLayout(this).apply {{
            orientation = LinearLayout.VERTICAL; background = cardBg()
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {{ bottomMargin = dp(8) }}
        }}
        fun secLbl(t: String) = TextView(this).apply {{
            text = t.uppercase(); textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.16f; setPadding(0, 0, 0, dp(10))
        }}
        fun mkSbl(cb: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {{
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {{ cb() }}
            override fun onStartTrackingTouch(s: SeekBar?) {{}}
            override fun onStopTrackingTouch(s: SeekBar?) {{}}
        }}
        fun themedAdapter(items: List<String>): ArrayAdapter<String> {{
            val a = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            return a
        }}
        {cfg_inner}
        cfgScroll.addView(cfgPage)
        return cfgScroll
    }}

    // ── BUILD WALLET TAB ──────────────────────────────────────────────────────
    private fun buildWalletTab(): ScrollView {{
        val scroll = ScrollView(this).apply {{
            setBackgroundColor(BG_DEEP)
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }}
        val page = LinearLayout(this).apply {{
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(80))
        }}

        page.addView(TextView(this).apply {{
            text = "◈  WALLET"; textSize = 13f; setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(16))
        }})

        // Botones de acción
        fun walletBtn(label: String, sub: String, click: () -> Unit): LinearLayout {{
            val r = LinearLayout(this).apply {{
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = cardBg(); isClickable = true
                setOnClickListener {{ click() }}
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {{ bottomMargin = dp(8) }}
            }}
            val lc = LinearLayout(this).apply {{
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }}
            lc.addView(TextView(this).apply {{ text = label; textSize = 13f; setTextColor(TXT_PRI) }})
            lc.addView(TextView(this).apply {{
                text = sub; textSize = 10f; setTextColor(TXT_MUTED)
                typeface = Typeface.MONOSPACE; setPadding(0, dp(2), 0, 0)
            }})
            r.addView(lc)
            r.addView(TextView(this).apply {{ text = "›"; textSize = 18f; setTextColor(TXT_MUTED) }})
            return r
        }}

        page.addView(walletBtn("Ver Wallet", "Balances y direcciones") {{
            startActivity(Intent(this, WalletActivity::class.java))
        }})
        page.addView(walletBtn("Importar Seed", "Restaurar desde frase semilla") {{
            startActivity(Intent(this, WalletActivity::class.java))
        }})
        page.addView(walletBtn("Exportar Log", "Guardar matches en archivo") {{
            exportLog()
        }})

        scroll.addView(page)
        return scroll
    }}

    // ── BUILD RECOVERY TAB ────────────────────────────────────────────────────
    private fun buildRecoveryTab(): ScrollView {{
        val recoveryScroll = ScrollView(this).apply {{
            setBackgroundColor(BG_CARD)
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }}
        val recoveryPage = LinearLayout(this).apply {{
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(80))
        }}
        {recovery_inner}
        recoveryScroll.addView(recoveryPage)
        return recoveryScroll
    }}

    // ── FUNCIONES AUXILIARES ──────────────────────────────────────────────────
    private fun secLbl(t: String) = TextView(this).apply {{
        text = t.uppercase(); textSize = 9f; setTextColor(TXT_MUTED)
        typeface = Typeface.create("monospace", Typeface.BOLD)
        letterSpacing = 0.16f; setPadding(0, 0, 0, dp(10))
    }}

    private fun mkSbl(cb: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {{
        override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {{ cb() }}
        override fun onStartTrackingTouch(s: SeekBar?) {{}}
        override fun onStopTrackingTouch(s: SeekBar?) {{}}
    }}

    private fun themedAdapter(items: List<String>): ArrayAdapter<String> {{
        val a = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return a
    }}

    private fun updateLabels() {{
        val t = sbThreads.progress + 1
        val c = sbCpu.progress + 10
        tvThreads.text = "Threads: $t"
        tvCpu.text = "CPU limit: $c%"
        tvQuickThreads?.text = "$t"
        tvQuickCpu?.text = "$c%"
        prefs.edit().putInt("threads", sbThreads.progress).putInt("cpu", sbCpu.progress).apply()
    }}

    private fun formatCount(v: Long) = when {{
        v >= 1_000_000_000L -> "%.2fB".format(v / 1e9)
        v >= 1_000_000L     -> "%.2fM".format(v / 1e6)
        v >= 1_000L         -> "%.2fK".format(v / 1e3)
        else -> "$v"
    }}

    private fun updateUI() {{
        if (HunterEngine.isRunning()) {{
            val wps = HunterEngine.getWps()
            tvWps.text = "%.1f".format(wps)
            tvCount.text = formatCount(HunterEngine.getCount())
            val elapsed = (System.currentTimeMillis() - sessionStartTime) / 1000
            val h = elapsed / 3600; val m = (elapsed % 3600) / 60; val sc = elapsed % 60
            tvTime.text = "%02d:%02d:%02d".format(h, m, sc)
            chartView.addPoint(wps.toFloat())
            val found = HunterEngine.getCount() - sessionStartCount
            tvMatches.text = "$found"
            tvQuickMatches?.text = "$found"
        }}
        val rt = Runtime.getRuntime()
        tvRam.text = "RAM ${{(rt.totalMemory()-rt.freeMemory())/1048576}}MB"
    }}

    private fun doToggle() {{
        if (HunterEngine.isRunning()) {{
            HunterEngine.stopHunting()
            getSharedPreferences("ui_state", MODE_PRIVATE).edit()
                .putBoolean("puzzleMode", puzzleMode)
                .putInt("threads", sbThreads.progress)
                .putInt("cpu", sbCpu.progress)
                .apply()
            val bg = btnToggle.tag as? Array<*>
            btnToggle.text = s.start
            btnToggle.background = bg?.get(0) as? GradientDrawable
        }} else {{
            if (!HunterEngine.isCsvLoaded() && !puzzleMode) {{
                Toast.makeText(this, s.noDataset, Toast.LENGTH_SHORT).show(); return
            }}
            sessionStartTime = System.currentTimeMillis()
            sessionStartCount = HunterEngine.getCount()
            val threads = sbThreads.progress + 1
            val cpu = sbCpu.progress + 10
            if (puzzleMode) {{
                HunterEngine.setRange(etRangeStart.text.toString(), etRangeEnd.text.toString())
            }}
            HunterEngine.startHunting(threads, cpu)
            val bg = btnToggle.tag as? Array<*>
            btnToggle.text = s.stop
            btnToggle.background = bg?.get(1) as? GradientDrawable
            startForegroundService(Intent(this, HunterService::class.java))
        }}
    }}

    private fun pickCsv() {{
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {{
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }}
        startActivityForResult(i, 1001)
    }}

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {{
        super.onActivityResult(req, res, data)
        if (req == 1001 && res == RESULT_OK) {{
            val uri = data?.data ?: return
            val dest = File(getExternalFilesDir(null), "dataset.csv")
            contentResolver.openInputStream(uri)?.use {{ it.copyTo(dest.outputStream()) }}
            csvPath = dest.absolutePath
            prefs.edit().putString("csvPath", csvPath).apply()
            HunterEngine.loadCsv(csvPath)
            tvStatus.text = dest.name
            tvQuickCsv?.text = dest.nameWithoutExtension.take(7)
        }}
    }}

    private fun exportLog() {{
        val dir = getExternalFilesDir(null) ?: filesDir
        val f = File(dir, "hunter_log_${{java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}}.txt")
        val log = StringBuilder("WALLET HUNTER LOG\\n${{java.util.Date()}}\\n\\n")
        f.writeText(log.toString())
        Toast.makeText(this, "Log guardado: ${{f.name}}", Toast.LENGTH_SHORT).show()
    }}

    private fun showWalletSelector() {{
        startActivity(Intent(this, WalletActivity::class.java))
    }}

    private fun checkPuzzleBalance(addr: String, onResult: (Long) -> Unit) {{
        Thread {{
            try {{
                val url = java.net.URL("https://blockchain.info/q/addressbalance/$addr")
                val bal = url.readText().trim().toLongOrNull() ?: 0L
                runOnUiThread {{ onResult(bal) }}
            }} catch (e: Exception) {{ runOnUiThread {{ onResult(0L) }} }}
        }}.start()
    }}

    private fun applyPuzzle(p: PuzzleInfo) {{
        etRangeStart.setText(p.start)
        etRangeEnd.setText(p.end)
        etTarget.setText(p.addr)
        HunterEngine.setRange(p.start, p.end)
        tvPuzzleStatus.text = "Puzzle #${{p.num}} — ${{p.btc}}"
    }}

    private fun autoSelectPuzzle() {{
        tvPuzzleStatus.text = "Checking puzzles..."; tvPuzzleStatus.setTextColor(TXT_SEC)
        Thread {{
            var bestIdx = 0
            for ((idx, p) in puzzles.withIndex()) {{
                checkPuzzleBalance(p.addr) {{ bal ->
                    if (bal > 0) {{
                        bestIdx = idx
                        runOnUiThread {{
                            suppressPuzzleListener = true
                            puzzleSpinner.setSelection(bestIdx)
                            applyPuzzle(puzzles[bestIdx])
                            tvPuzzleStatus.text = "Auto-selected #${{p.num}} — ${{bal/100_000_000.0}} BTC"
                            tvPuzzleStatus.setTextColor(AppTheme.GREEN)
                            suppressPuzzleListener = false
                        }}
                    }}
                }}
            }}
        }}.start()
    }}

    private fun setupNotificationChannel() {{
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {{
            val ch = android.app.NotificationChannel(
                "hunter", "Hunter", android.app.NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .createNotificationChannel(ch)
        }}
    }}

    private fun registerBatteryReceiver() {{
        batteryReceiver = object : BroadcastReceiver() {{
            override fun onReceive(ctx: Context?, i: Intent?) {{
                val pct = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return
                tvBattery.text = "BAT $pct%"
            }}
        }}
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }}

    override fun onResume() {{
        super.onResume()
        handler.post(updater)
    }}

    override fun onPause() {{
        super.onPause()
        handler.removeCallbacks(updater)
    }}

    override fun onDestroy() {{
        super.onDestroy()
        batteryReceiver?.let {{ unregisterReceiver(it) }}
    }}
}}
'''

out_path = os.path.join(KT_DIR, "MainActivity.kt")
with open(out_path, "w") as f:
    f.write(main_kt)

lines = len(main_kt.splitlines())
print(f"✓ MainActivity.kt reconstruido — {lines} líneas")
print("""
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Rebuild B listo.

Siguiente: git add + push → verificar compilación
Si hay errores de variables faltantes los
corregimos antes de rebuild_c.py (Wallet tab).
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
""")
