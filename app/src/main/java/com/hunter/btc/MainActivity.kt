package com.hunter.btc

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
            text = "$ico\n$lbl"; textSize = 9f; setTextColor(TXT_MUTED)
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
            HunterEngine.loadCsv(csvPath)
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
