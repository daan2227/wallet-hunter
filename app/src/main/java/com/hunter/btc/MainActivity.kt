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

class SpeedChartView(context: android.content.Context) : android.view.View(context) {
    private val maxPoints = 60
    private val wpsPoints = ArrayDeque<Float>()
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA8FF00.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFA8FF00.toInt() }
    private val paintLbl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF556050.toInt(); textSize = 18f; typeface = Typeface.MONOSPACE
    }
    fun addPoint(wps: Float) { wpsPoints.addLast(wps); if(wpsPoints.size>maxPoints) wpsPoints.removeFirst(); postInvalidate() }
    fun reset() { wpsPoints.clear(); postInvalidate() }
    fun getPoints(): List<Float> = wpsPoints.toList()
    override fun onDraw(canvas: Canvas) {
        val w=width.toFloat(); val h=height.toFloat()
        if(w<=0||h<=0||wpsPoints.size<2) return
        val pad=4f; val mx=wpsPoints.max().coerceAtLeast(1f)
        val pts=wpsPoints.mapIndexed{i,v->PointF(pad+(w-pad*2)*i/(maxPoints-1),h-pad-(h-pad*2)*(v/mx))}
        val fill=Path(); fill.moveTo(pts[0].x,h); pts.forEach{fill.lineTo(it.x,it.y)}
        fill.lineTo(pts.last().x,h); fill.close()
        canvas.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply{
            shader=LinearGradient(0f,0f,0f,h,0x40A8FF00,0x00A8FF00,Shader.TileMode.CLAMP)
            style=Paint.Style.FILL })
        val lp=Path(); pts.forEachIndexed{i,p->if(i==0)lp.moveTo(p.x,p.y) else lp.lineTo(p.x,p.y)}
        canvas.drawPath(lp,paintLine)
        canvas.drawCircle(pts.last().x,pts.last().y,4f,paintDot)
        val wpsMax=wpsPoints.maxOrNull()?:0f
        val lbl=if(wpsMax>=1e6)"%.1fM".format(wpsMax/1e6) else if(wpsMax>=1000)"%.0fK".format(wpsMax/1000) else "%.0f".format(wpsMax)
        canvas.drawText(lbl,pad+2,22f,paintLbl)
    }
}

class MainActivity : androidx.appcompat.app.AppCompatActivity() {

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
    private fun cardBg() = GradientDrawable().apply {
        setColor(BG_CARD); cornerRadius = dp(8).toFloat(); setStroke(1, BORDER_C)
    }

    // ── Tab system ────────────────────────────────────────────────────────────
    private var tabPages:    List<android.view.View>          = emptyList()
    private var tabBtns:     List<android.widget.TextView>    = emptyList()
    private var contentFrame: android.widget.FrameLayout?     = null
    private var drawerOpen:   Boolean                         = false
    private var menuBtn:      android.view.View?              = null
    private var drawerView:   android.view.View?              = null
    private var overlayView:  android.view.View?              = null
    private fun goTab(idx: Int) {
        tabPages.forEachIndexed { i, v ->
            v.visibility = if (i == idx) android.view.View.VISIBLE else android.view.View.GONE
        }
        updateDrawerSelection(idx)
    }

    // ── Variables ─────────────────────────────────────────────────────────────
    private var sessionStartTime = 0L
    private var sessionStartCount = 0L
    private var batteryReceiver: android.content.BroadcastReceiver? = null
    private var lastFoundCount = 0L
    private val NOTIF_CHANNEL = "hunter_match"
    private val REQ_IMPORT_CONFIG = 2002
    private val NOTIF_ID = 42
    private val handler = Handler(Looper.getMainLooper())
    private var tvStatus: TextView? = null
    private var tvCsvName: TextView? = null
    private var tvQuickCsv: TextView? = null
    private var tvQuickMatches: TextView? = null
    private var tvWps: TextView? = null
    private var tvKps: TextView? = null
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
    private var tvLiveSec: LinearLayout? = null
    private var tvMatchSec: LinearLayout? = null
    private var tvLogSec: LinearLayout? = null
    private var tvLangLbl: TextView? = null
    private var tvCount: TextView? = null
    private var chartView: SpeedChartView? = null
    private var tvPuzzleStatus: TextView? = null
    private var tvTime: TextView? = null
    private var tvMatches: TextView? = null
    private var tvMatchList: TextView? = null
    private var tvAddrFeed: TextView? = null
    private var tvRam: TextView? = null
    private var tvTemp: TextView? = null
    private var tvBattery: TextView? = null
    private var tvLog: TextView? = null
    private var puzzleSpinner: Spinner? = null
    private var suppressPuzzleListener = false
    private var puzzleTabReady = false
    private var tvFooter: TextView? = null
    private var btnToggle: Button? = null
    private var btnSwitch: Button? = null
    private var sbThreads: SeekBar? = null
    private var sbCpu: SeekBar? = null
    // Puzzle tiene sus propios sliders independientes
    private var sbThreadsPuzzle: SeekBar? = null
    private var sbCpuPuzzle: SeekBar? = null
    private var tvThreadsPuzzle: TextView? = null
    private var tvCpuPuzzle: TextView? = null
    private var tvThreads: TextView? = null
    private var tvCpu: TextView? = null
    private var tvCsvSec: LinearLayout? = null
    private var tvConfigSec: LinearLayout? = null
    private var tvStatsSec: LinearLayout? = null
    private var btnCsv: Button? = null
    private var csvSecView: LinearLayout? = null
    private var etRangeStart: EditText? = null
    private var etRangeEnd: EditText? = null
    private var currentRangeStart: String = ""
    private var currentRangeEnd: String = ""
    private val BLOCK_SIZE = java.math.BigInteger("1000000000") // 1B keys por bloque
    private var currentBlockId: String = ""
    private var tvBlockProgress: TextView? = null
    private var etTarget: EditText? = null
    private var layoutPuzzle: LinearLayout? = null
    private var rbBip39: Button? = null
    private var rbPuzzle: Button? = null
    private var csvPath: String = ""
    private var s = Strings.EN
    private var puzzleMode = false
    private var activeToggleBtn: Button? = null
    private var tvWpsPuzzle: TextView? = null
    private var tvPctPuzzle: TextView? = null
    private var tvCheckpointLive: TextView? = null
    private var tvCountPuzzle: TextView? = null
    private var tvTimePuzzle: TextView? = null
    private var btnPuzzleToggle: Button? = null
    private val recentAddrs = mutableListOf<String>()
    private var recoveryEngine: RecoveryEngine? = null
    private val prefs get() = getSharedPreferences("hunter", MODE_PRIVATE)

    data class PuzzleInfo(val num: Int, val addr: String, val start: String, val end: String, val btc: String)
    private val puzzles = listOf(
        PuzzleInfo(66, "13zb1hQbWVsc2S7ZTZnP2G4undNNpdh5so", "2000000000000000",  "3fffffffffffffffff",  "6.6 BTC"),
        PuzzleInfo(67, "1BY8GQbnueYofwSuFAT3USAhGjPrkxDdW9", "4000000000000000",  "7fffffffffffffffff",  "6.7 BTC"),
        PuzzleInfo(68, "1MVDYgVaSN6iKKEsbzRUAYFrYJadLYZvvZ", "8000000000000000",  "ffffffffffffffffff",  "6.8 BTC"),
        PuzzleInfo(69, "19vkiEajfhuZ8bs8Zu2jgmC6oqZbWqhxhG", "10000000000000000", "1ffffffffffffffffff",  "6.9 BTC"),
        PuzzleInfo(70, "1PWo3JeB9jrGwfHDNpdGK54CRas7fsVzXU", "20000000000000000", "3ffffffffffffffffff",  "7.0 BTC"),
        PuzzleInfo(71, "1JTK7s9YVYywfm5XUH7RNhHJH1LshCaRFR", "40000000000000000", "7ffffffffffffffffff",  "7.1 BTC"),
        PuzzleInfo(72, "12VVRNPi4SJqUTsp6FmqDqY5sGosDtysn4", "80000000000000000", "fffffffffffffffffff",  "7.2 BTC"),
        PuzzleInfo(73, "1FWGcVDK3JGzCC3WtkYetULPszMaK2Jksv", "100000000000000000","1fffffffffffffffffff",  "7.3 BTC"),
        PuzzleInfo(74, "1Me6EfpwZK5kQziBwBfvLiHjaPGG5dneUd", "200000000000000000","3fffffffffffffffffff",  "7.4 BTC"),
        PuzzleInfo(75, "1DJh2eHFYQfACPmrvpyWc8MSTYKh7w9eRF", "400000000000000000","7fffffffffffffffffff",  "7.5 BTC"),
        PuzzleInfo(76, "1Bxk4CQdqL9p22JEtDfdXMsng1XacifUtE", "800000000000000000","fffffffffffffffffffffff","7.6 BTC"),
        PuzzleInfo(77, "15qF6X51huDjqTmF9BJgxXdt1xcj46Jmhb", "1000000000000000000","1ffffffffffffffffffffff","7.7 BTC"),
        PuzzleInfo(78, "1ARk8HWJMn8js8tQmGUJeQHjSE7KRkn2t8","2000000000000000000","3ffffffffffffffffffffff","7.8 BTC"),
        PuzzleInfo(79, "1AoeP37TmHdFh8uN72fu9AqgtLrUwcv2wJ", "4000000000000000000","7ffffffffffffffffffffff","7.9 BTC"),
        PuzzleInfo(80, "15qsCm78whspNQFydGJQk5rexzxTQopnHZ", "8000000000000000000","fffffffffffffffffffffff","8.0 BTC"),
        PuzzleInfo(81, "1CfZWK1QTQE3eS9qn61dQjV89KDjZzfNcv","10000000000000000000","1fffffffffffffffffffffff","8.1 BTC"),
        PuzzleInfo(82, "1L2GM8eE7mJWLdo3HZS6su1832NX2txaac", "20000000000000000000","3fffffffffffffffffffffff","8.2 BTC"),
        PuzzleInfo(83, "1rSnXMr63jdCuegJFuidJqWxUPV7AtUf7",  "40000000000000000000","7fffffffffffffffffffffff","8.3 BTC"),
        PuzzleInfo(84, "15ANYzzCp5BFHcCnVFzXqyibpzgPLWaD8b","80000000000000000000","ffffffffffffffffffffffff","8.4 BTC"),
        PuzzleInfo(85, "15utf8aHAAZnHmzgDKW3tpkpnEQmcvWJJ4","100000000000000000000","1ffffffffffffffffffffffff","8.5 BTC"),
        PuzzleInfo(86, "1DNkyZhere7mLzt3D5bM3G2ycg4tBnkMTX","200000000000000000000","3ffffffffffffffffffffffff","8.6 BTC"),
        PuzzleInfo(87, "1HduPEXZRdG26SUT5Yk83mLkPyjnZuJ7Bm","400000000000000000000","7ffffffffffffffffffffffff","8.7 BTC"),
        PuzzleInfo(88, "1AmU5jNb65sqresUqwnz5tFcKou4YK2dzv","800000000000000000000","fffffffffffffffffffffffff","8.8 BTC"),
        PuzzleInfo(89, "1FowZsFmaee5ozfGSCKoVs7fMdnE2B9Ex", "1000000000000000000000","1fffffffffffffffffffffffff","8.9 BTC"),
        PuzzleInfo(90, "1McVt1vMtCC7yn5b9wgX1833yCcLXzueeC","2000000000000000000000","3fffffffffffffffffffffffff","9.0 BTC"),
        PuzzleInfo(95, "1HBtApAFA9B2YZw3G2YKSMCtb3dVnjuNe2","40000000000000000000000000","7ffffffffffffffffffffffffffffff","9.5 BTC"),
        PuzzleInfo(100,"1CaBVPrwUxbQYYswu32oBQAlMAbEkG4v8f","1000000000000000000000000000","1fffffffffffffffffffffffffffffff","10.0 BTC"),
        PuzzleInfo(105,"1KwntMbt59bzvpHKQQe5KFznY9Bk9DhSwp","20000000000000000000000000000000","3fffffffffffffffffffffffffffffff","10.5 BTC"),
        PuzzleInfo(110,"1LHtnpd8nU5VHEMkG2TMYYNUjjLc992bXs","400000000000000000000000000000000","7fffffffffffffffffffffffffffffff","11.0 BTC"),
        PuzzleInfo(115,"1QAT7jVJQMoMGSXBuJbKKANFR8M8E8UVZz","8000000000000000000000000000000000","ffffffffffffffffffffffffffffffff","11.5 BTC"),
        PuzzleInfo(120,"1MHC7nLqPkjFnNtLY12Jkiir4Du6t2f3H4","100000000000000000000000000000000000","1ffffffffffffffffffffffffffffffff","12.0 BTC"),
        PuzzleInfo(125,"1NpnQyZ7x24ud82b7WiRNvPm6N8bqGQnaS","2000000000000000000000000000000000000","3ffffffffffffffffffffffffffffffff","12.5 BTC"),
        PuzzleInfo(130,"1NLbHuJebVwUZ1XqDjsAyfTRUPwDQbemfv","40000000000000000000000000000000000000","7ffffffffffffffffffffffffffffffff","13.0 BTC"),
        PuzzleInfo(135,"16jY7qLJnxb7CHZyqBP8qca9d51gAjyXQN","800000000000000000000000000000000000000","fffffffffffffffffffffffffffffffff","13.5 BTC"),
        PuzzleInfo(140,"18ZMbwUFLMHoZBbfpCjUJQTCMCbktshgpe","10000000000000000000000000000000000000000","1fffffffffffffffffffffffffffffffff","14.0 BTC"),
        PuzzleInfo(145,"1Bu4G6Rb8CMW5GnB6WMoV9k82DhLFnVfXH","200000000000000000000000000000000000000000","3fffffffffffffffffffffffffffffffff","14.5 BTC"),
        PuzzleInfo(150,"1PXAyUB8ZoH3WD8n5zoAQKovelENNoYzqm","4000000000000000000000000000000000000000000","7fffffffffffffffffffffffffffffffff","15.0 BTC"),
        PuzzleInfo(155,"1Fo65aKq8s8iquMt6weF1rku1moWVEd5Ua", "80000000000000000000000000000000000000000000","ffffffffffffffffffffffffffffffffff","15.5 BTC"),
        PuzzleInfo(160,"1H8ANdafjpqYntniT3Ddxh4xPBMCSz33pj","1000000000000000000000000000000000000000000000","1ffffffffffffffffffffffffffffffffff","16.0 BTC")
    )

    private val updater = object : Runnable {
        override fun run() {
            updateUI()
            handler.postDelayed(this, 800)
        }
    }

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        // Capturar crashes globales
        val crashLogPath = (getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath) + "/crash_log.txt"
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                val msg = "\n=== $ts ===\n${e.javaClass.name}\n${e.message}\n${e.stackTraceToString()}\n"
                java.io.File(crashLogPath).appendText(msg)
                // También guardar en internal storage como backup
                java.io.File(filesDir, "crash_log.txt").appendText(msg)
            } catch (ex: Exception) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        AppTheme.init(this)
        AdManager.init(this)
        AdManager.loadInterstitial(this)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.statusBarColor = BG_DEEP
        s = Strings.EN
        csvPath = prefs.getString("csvPath", "") ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DEEP)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val cf = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        var scanScroll: ScrollView? = null
        var puzzleScroll: ScrollView? = null
        var walletScroll: ScrollView? = null
        var recoveryScroll: ScrollView? = null
        try {
            android.widget.Toast.makeText(this, "Building Scan...", android.widget.Toast.LENGTH_SHORT).show()
            scanScroll = buildScanTab()
            android.widget.Toast.makeText(this, "Building Puzzle...", android.widget.Toast.LENGTH_SHORT).show()
            try {
                puzzleScroll = buildPuzzleTab()
            } catch (e: Exception) {
                val msg = "PUZZLE_BUILD: ${e.javaClass.simpleName}: ${e.message}"
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
                java.io.File(filesDir, "crash_log.txt").appendText("\n$msg\n${e.stackTraceToString()}\n")
            }
            android.widget.Toast.makeText(this, "Building Wallet...", android.widget.Toast.LENGTH_SHORT).show()
            walletScroll = buildWalletTab()
            android.widget.Toast.makeText(this, "Building Recovery...", android.widget.Toast.LENGTH_SHORT).show()
            recoveryScroll = buildRecoveryTab()
            android.widget.Toast.makeText(this, "All tabs built OK", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Escribir error a archivo para diagnóstico
            try {
                val errFile = java.io.File(filesDir, "crash_log.txt")
                errFile.writeText("CRASH: ${e.javaClass.simpleName}\n${e.message}\n${e.stackTraceToString()}")
            } catch (ex: Exception) {}
            android.widget.Toast.makeText(this, "CRASH guardado en crash_log.txt", android.widget.Toast.LENGTH_LONG).show()
            finish(); return
        }

        scanScroll?.let { cf.addView(it) }
        puzzleScroll?.let { cf.addView(it) }
        walletScroll?.let { cf.addView(it) }
        recoveryScroll?.let { cf.addView(it) }
        contentFrame = cf

        // ── Header + Drawer ───────────────────────────────────────────────────
        val header = buildHeader()
        root.addView(header)

        val drawerLayout = buildDrawerLayout()
        root.addView(drawerLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)

        tabPages = listOfNotNull(scanScroll, puzzleScroll, walletScroll, recoveryScroll)
        tabBtns  = listOf<TextView>()
        goTab(0)

        // Init
        try {
            if (csvPath.isNotEmpty() && File(csvPath).exists() && !HunterEngine.isCsvLoaded())
                HunterEngine.loadCsv(csvPath)
            setupNotificationChannel()
            registerBatteryReceiver()
            // Auto-detectar hardware en primera ejecución
            // Restaurar scheduler si estaba activo
            scheduledStart = prefs.getInt("sched_start", -1)
            scheduledStop  = prefs.getInt("sched_stop",  -1)
            if (scheduledStart >= 0) startScheduler()

            if (!prefs.getBoolean("hw_detected", false)) {
                val profile = detectHardware()
                applyHardwareProfile(profile)
                prefs.edit().putBoolean("hw_detected", true).apply()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    showHardwareInfo()
                }, 1000)
            }
            updateLabels()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Init error: ${e.message}", e)
        }

        val uiSp = getSharedPreferences("ui_state", MODE_PRIVATE)
        if (uiSp.contains("puzzleMode")) {
            sbThreads?.progress = uiSp.getInt("threads", 3)
            sbCpu?.progress = uiSp.getInt("cpu", 70)
        }
    }


    // ── HEADER ───────────────────────────────────────────────────────────────────
    private fun buildHeader(): LinearLayout {
        val ACCENT = 0xFF00C896.toInt()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0B0E14.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            )
            setPadding(dp(16), 0, dp(16), 0)
            elevation = dp(4).toFloat()
        }

        // Logo icon
        val logoIcon = TextView(this).apply {
            text = "₿"
            textSize = 16f
            setTextColor(0xFF000000.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                colors = intArrayOf(ACCENT, 0xFF0087FF.toInt())
                gradientType = android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
            }
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).also {
                it.gravity = Gravity.CENTER_VERTICAL
            }
        }
        header.addView(logoIcon)

        // Logo text
        val logoText = TextView(this).apply {
            text = android.text.SpannableString("Wallet Hunter").also { sp ->
                sp.setSpan(
                    android.text.style.ForegroundColorSpan(ACCENT),
                    6, 13,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            textSize = 17f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(0xFFE8EAF0.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                it.gravity = Gravity.CENTER_VERTICAL
                it.marginStart = dp(10)
            }
        }
        header.addView(logoText)

        // Status dot
        val dot = android.view.View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF5A607A.toInt())
                setSize(dp(8), dp(8))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).also {
                it.gravity = Gravity.CENTER_VERTICAL
                it.marginEnd = dp(12)
            }
            tag = "statusDot"
        }
        header.addView(dot)

        // Menu button
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(0xFF111520.toInt())
                setStroke(1, 0xFF1E2540.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).also {
                it.gravity = Gravity.CENTER_VERTICAL
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleDrawer() }
        }
        repeat(3) {
            val bar = android.view.View(this).apply {
                setBackgroundColor(0xFFE8EAF0.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(2)).also {
                    it.setMargins(0, dp(2), 0, dp(2))
                }
            }
            menu.addView(bar)
        }
        menuBtn = menu
        header.addView(menu)

        // Bottom border
        val border = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1E2540.toInt())
        }

        return header
    }

    // ── DRAWER ───────────────────────────────────────────────────────────────────
    private fun buildDrawerLayout(): FrameLayout {
        val ACCENT = 0xFF00C896.toInt()
        val frame = FrameLayout(this)

        // Content frame (tabs go here)
        val cf = contentFrame ?: FrameLayout(this).also { contentFrame = it }
        frame.addView(cf, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Overlay
        val overlay = android.view.View(this).apply {
            setBackgroundColor(0xB3000000.toInt())
            alpha = 0f
            visibility = android.view.View.GONE
            setOnClickListener { closeDrawer() }
        }
        overlayView = overlay
        frame.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Drawer panel
        val drawerWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()
        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111520.toInt())
            translationX = -drawerWidth.toFloat()
            elevation = dp(16).toFloat()
        }
        drawerView = drawer
        frame.addView(drawer, FrameLayout.LayoutParams(drawerWidth, FrameLayout.LayoutParams.MATCH_PARENT))

        // Drawer header
        val drawerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF111520.toInt())
                setStroke(0, 0)
            }
        }
        val dTitle = TextView(this).apply {
            text = android.text.SpannableString("WalletHunter").also { sp ->
                sp.setSpan(
                    android.text.style.ForegroundColorSpan(ACCENT),
                    6, 12,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(0xFFE8EAF0.toInt())
        }
        val dSub = TextView(this).apply {
            text = "com.hunter.btc · ARM64"
            textSize = 10f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextColor(0xFF5A607A.toInt())
            setPadding(0, dp(4), 0, 0)
        }
        drawerHeader.addView(dTitle)
        drawerHeader.addView(dSub)

        // Divider
        val divider = android.view.View(this).apply {
            setBackgroundColor(0xFF1E2540.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
        }
        drawer.addView(drawerHeader)
        drawer.addView(divider)

        // Nav items
        val navContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        data class NavItem(val icon: String, val label: String, val idx: Int, val special: Boolean = false)
        val items = listOf(
            NavItem("⚡", "Scan", 0),
            NavItem("🧩", "Puzzle", 1),
            NavItem("◈", "Wallet", 2),
            NavItem("⚷", "Recovery", 3),
            NavItem("🌐", "Network", -1, true),
            NavItem("🐛", "Debug", -2, true)
        )

        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, dp(4)) }
                isClickable = true
                isFocusable = true
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (item.idx == 0) 0x1400C896.toInt() else 0x00000000.toInt())
                    if (item.idx == 0) setStroke(1, 0x3300C896.toInt())
                }
                tag = "nav_${item.idx}"
                setOnClickListener {
                    when {
                        item.idx == -1 -> {
                            startActivity(android.content.Intent(this@MainActivity, NetworkActivity::class.java))
                            closeDrawer()
                        }
                        item.idx == -2 -> {
                            startActivity(android.content.Intent(this@MainActivity, DebugActivity::class.java))
                            closeDrawer()
                        }
                        item.idx == 2 -> {
                            if (WalletManager.hasPin(this@MainActivity) && !PinAuthHelper.isSessionValid()) {
                                PinAuthHelper.show(this@MainActivity) { ok -> if (ok) { goTab(2); closeDrawer() } }
                            } else { goTab(item.idx); closeDrawer() }
                        }
                        else -> { goTab(item.idx); closeDrawer() }
                    }
                }
            }

            val iconTv = TextView(this).apply {
                text = item.icon
                textSize = 18f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).also {
                    it.gravity = Gravity.CENTER_VERTICAL
                }
            }
            val labelTv = TextView(this).apply {
                text = item.label
                textSize = 14f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(if (item.idx == 0) ACCENT else 0xFF5A607A.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                    it.gravity = Gravity.CENTER_VERTICAL
                    it.marginStart = dp(14)
                }
            }
            row.addView(iconTv)
            row.addView(labelTv)
            navContainer.addView(row)
        }

        drawer.addView(navContainer)

        // Drawer footer
        val footerDiv = android.view.View(this).apply {
            setBackgroundColor(0xFF1E2540.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        val footer = TextView(this).apply {
            text = "v2.4 · Wallet Hunter"
            textSize = 10f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextColor(0xFF3A4060.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(24))
        }
        drawer.addView(footerDiv)
        drawer.addView(footer)

        return frame
    }

    // ── DRAWER CONTROLS ──────────────────────────────────────────────────────────
    private fun toggleDrawer() {
        if (drawerOpen) closeDrawer() else openDrawer()
    }

    private fun openDrawer() {
        val d = drawerView ?: return
        val o = overlayView ?: return
        drawerOpen = true
        o.visibility = android.view.View.VISIBLE
        d.animate().translationX(0f).setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        o.animate().alpha(1f).setDuration(300).start()
    }

    fun closeDrawer() {
        val d = drawerView ?: return
        val o = overlayView ?: return
        drawerOpen = false
        val w = (resources.displayMetrics.widthPixels * 0.72f)
        d.animate().translationX(-w).setDuration(280)
            .setInterpolator(android.view.animation.AccelerateInterpolator()).start()
        o.animate().alpha(0f).setDuration(280).withEndAction {
            o.visibility = android.view.View.GONE
        }.start()
    }

    private fun updateDrawerSelection(idx: Int) {
        val ACCENT = 0xFF00C896.toInt()
        val drawer = drawerView as? LinearLayout ?: return
        // Find navContainer (3rd child: header, divider, navContainer)
        val navContainer = drawer.getChildAt(2) as? LinearLayout ?: return
        for (i in 0 until navContainer.childCount) {
            val row = navContainer.getChildAt(i) as? LinearLayout ?: continue
            val tag = row.tag as? String ?: continue
            val rowIdx = tag.removePrefix("nav_").toIntOrNull() ?: continue
            val isActive = rowIdx == idx
            val bg = row.background as? android.graphics.drawable.GradientDrawable
            bg?.setColor(if (isActive) 0x1400C896.toInt() else 0x00000000.toInt())
            bg?.setStroke(if (isActive) 1 else 0, if (isActive) 0x3300C896.toInt() else 0x00000000.toInt())
            val label = row.getChildAt(1) as? TextView
            label?.setTextColor(if (isActive) ACCENT else 0xFF5A607A.toInt())
        }
    }

    // ── BUILD SCAN TAB ────────────────────────────────────────────────────────
    private fun buildScanTab(): ScrollView {
        val ACCENT  = 0xFF00C896.toInt()
        val ACCENT2 = 0xFF0087FF.toInt()
        val LIME    = 0xFF39FF14.toInt()  // kept for engine compat

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF0B0E14.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0E14.toInt())
            setPadding(0, 0, 0, dp(80))
        }

        // ── HERO SPEED CARD ───────────────────────────────────────────────
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF111520.toInt())
                cornerRadius = dp(0).toFloat()
            }
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        // Label
        heroCard.addView(TextView(this).apply {
            text = "VELOCIDAD DE ESCANEO"
            textSize = 10f
            setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        })

        // Big speed number
        tvWps = TextView(this).apply {
            text = "0.0"
            textSize = 52f
            setTextColor(ACCENT)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        heroCard.addView(tvWps)

        heroCard.addView(TextView(this).apply {
            text = "kKeys / segundo"
            textSize = 11f
            setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        })

        // ── STAT GRID 2x2 ─────────────────────────────────────────────────
        fun statCard(accentColor: Int, build: LinearLayout.() -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(0xFF111520.toInt())
                    cornerRadius = dp(16).toFloat()
                    setStroke(1, 0xFF1E2540.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                setPadding(dp(14), dp(14), dp(14), dp(14))
                // top accent line via foreground would need API23+, use inner view
                build()
            }
        }

        fun statLabel(text: String) = TextView(this).apply {
            this.text = text
            textSize = 9f
            setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.1f
        }

        fun statValue(initial: String, color: Int) = TextView(this).apply {
            text = initial
            textSize = 22f
            setTextColor(color)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = -0.02f
        }

        tvCount = statValue("0", 0xFF0087FF.toInt())
        val tvBlocksStat = statValue("0", 0xFFE8EAF0.toInt())
        val tvProgressStat = statValue("0.00%", ACCENT)
        tvTime = statValue("00:00", 0xFFE8EAF0.toInt())

        val gridRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val gridRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        gridRow1.addView(statCard(ACCENT2) {
            addView(statLabel("TOTAL KEYS"))
            addView(tvCount)
        })
        gridRow1.addView(statCard(0xFF1E2540.toInt()) {
            addView(statLabel("BLOQUES"))
            addView(tvBlocksStat)
        })
        gridRow2.addView(statCard(ACCENT) {
            addView(statLabel("PROGRESO"))
            addView(tvProgressStat)
        })
        gridRow2.addView(statCard(0xFF1E2540.toInt()) {
            addView(statLabel("TIEMPO"))
            addView(tvTime)
        })

        heroCard.addView(gridRow1)
        heroCard.addView(gridRow2)

        // ── PROGRESS BAR ──────────────────────────────────────────────────
        val progressBarContainer = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(0xFF1A2030.toInt()); cornerRadius = dp(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
            ).apply { setMargins(0, dp(16), 0, 0) }
        }
        chartView = SpeedChartView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val progressFill = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 10000; progress = 0
            progressDrawable = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(ACCENT2, ACCENT)
            ).apply { cornerRadius = dp(4).toFloat() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        progressBarContainer.addView(progressFill)
        heroCard.addView(progressBarContainer)
        page.addView(heroCard)

        // Invisible views para compatibilidad
        tvKps       = tvWps
        tvRam       = TextView(this).apply { visibility = android.view.View.GONE }
        tvBattery   = TextView(this).apply { visibility = android.view.View.GONE }
        tvTemp      = TextView(this).apply { visibility = android.view.View.GONE }
        tvMatches   = TextView(this).apply { visibility = android.view.View.GONE }
        tvFooter    = TextView(this).apply { visibility = android.view.View.GONE }
        tvStatus    = TextView(this).apply { visibility = android.view.View.GONE }
        tvAddrFeed  = TextView(this).apply { visibility = android.view.View.GONE }
        tvMatchList = TextView(this).apply { visibility = android.view.View.GONE }
        page.addView(tvRam); page.addView(tvBattery); page.addView(tvFooter); page.addView(tvStatus)

        // ── HELPER: Collapsible Section ───────────────────────────────────
        fun collapsibleSection(icon: String, title: String, build: LinearLayout.() -> Unit): LinearLayout {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(0xFF111520.toInt()); cornerRadius = dp(14).toFloat()
                    setStroke(1, 0xFF1E2540.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(12), dp(10), dp(12), 0) }
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isClickable = true; isFocusable = true
            }
            val iconTv = TextView(this).apply {
                text = icon; textSize = 17f
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) }
                gravity = Gravity.CENTER
            }
            val titleTv = TextView(this).apply {
                text = title; textSize = 13f
                setTextColor(0xFFE8EAF0.toInt())
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val arrowTv = TextView(this).apply {
                text = "›"; textSize = 18f; setTextColor(0xFF3A4060.toInt())
            }
            header.addView(iconTv); header.addView(titleTv); header.addView(arrowTv)
            container.addView(header)

            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = android.view.View.GONE
                setPadding(dp(16), 0, dp(16), dp(14))
            }
            body.build()
            container.addView(body)

            header.setOnClickListener {
                if (body.visibility == android.view.View.GONE) {
                    body.visibility = android.view.View.VISIBLE
                    arrowTv.text = "∨"
                } else {
                    body.visibility = android.view.View.GONE
                    arrowTv.text = "›"
                }
            }
            return container
        }

        // ── SECTION: Config Hardware ──────────────────────────────────────
        page.addView(collapsibleSection("⚙", "Configuración del Motor (Hardware)") {
            addView(TextView(this@MainActivity).apply {
                text = "Dataset"; textSize = 10f; setTextColor(0xFF5A607A.toInt())
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                setPadding(0, dp(4), 0, dp(4))
            })
            val dataRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            btnCsv = Button(this@MainActivity).apply {
                text = "Load CSV"; textSize = 10f
                setTextColor(android.graphics.Color.BLACK)
                background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(8).toFloat() }
                setPadding(dp(12), 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
                setOnClickListener { pickCsv() }
            }
            val tvCsvLocal = TextView(this@MainActivity).apply {
                text = if (csvPath.isNotEmpty() && java.io.File(csvPath).exists())
                    java.io.File(csvPath).name else "Sin archivo"
                setTextColor(0xFF5A607A.toInt()); textSize = 10f; typeface = Typeface.MONOSPACE
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(10), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            tvCsvName = tvCsvLocal
            dataRow.addView(btnCsv); dataRow.addView(tvCsvLocal)
            addView(dataRow)

            addView(TextView(this@MainActivity).apply {
                text = "Threads"; textSize = 10f; setTextColor(0xFF5A607A.toInt())
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                setPadding(0, dp(10), 0, dp(2))
            })
            tvThreads = TextView(this@MainActivity).apply { setTextColor(0xFFE8EAF0.toInt()); textSize = 11f }
            addView(tvThreads)
            sbThreads = SeekBar(this@MainActivity).apply {
                max = 7; progress = prefs.getInt("threads", 3)
                setOnSeekBarChangeListener(mkSbl { updateLabels() })
            }
            addView(sbThreads)

            addView(TextView(this@MainActivity).apply {
                text = "CPU Limit"; textSize = 10f; setTextColor(0xFF5A607A.toInt())
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                setPadding(0, dp(8), 0, dp(2))
            })
            tvCpu = TextView(this@MainActivity).apply { setTextColor(0xFFE8EAF0.toInt()); textSize = 11f }
            addView(tvCpu)
            sbCpu = SeekBar(this@MainActivity).apply {
                max = 90; progress = prefs.getInt("cpu", 70)
                setOnSeekBarChangeListener(mkSbl {
                    updateLabels()
                    if (HunterEngine.isRunning()) HunterEngine.setCpuLimit((sbCpu?.progress ?: 70) + 10)
                })
            }
            addView(sbCpu)

            val fastRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            fastRow.addView(TextView(this@MainActivity).apply {
                text = "Fast Scan Mode"; textSize = 12f; setTextColor(0xFFE8EAF0.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val fastSwitch = android.widget.Switch(this@MainActivity).apply {
                isChecked = prefs.getBoolean("fastMode", false)
                setOnCheckedChangeListener { _, c ->
                    fastModeEnabled = c
                    HunterEngine.setPbkdf2Mode(if (c) 1 else 0)
                    prefs.edit().putBoolean("fastMode", c).apply()
                }
            }
            fastModeEnabled = prefs.getBoolean("fastMode", false)
            fastRow.addView(fastSwitch)
            addView(fastRow)

            listOf(
                Triple("⏰", "Programar Scan", { showSchedulerDialog() }),
                Triple("⚙", "Auto-configurar Hardware", { showHardwareInfo() }),
                Triple("📤", "Exportar Config", { exportConfig() }),
                Triple("📥", "Importar Config", { importConfig() })
            ).forEach { (ic, lbl, action) ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, 0); isClickable = true; isFocusable = true
                    setOnClickListener { action() }
                }
                row.addView(TextView(this@MainActivity).apply {
                    text = ic; textSize = 15f; gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) }
                })
                row.addView(TextView(this@MainActivity).apply {
                    text = lbl; textSize = 12f; setTextColor(0xFFE8EAF0.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(this@MainActivity).apply { text = "›"; textSize = 16f; setTextColor(0xFF3A4060.toInt()) })
                addView(row)
            }
        })

        // ── SECTION: Red Multi-Dispositivo ────────────────────────────────
        page.addView(collapsibleSection("🌐", "Red Multi-Dispositivo (Cluster)") {
            addView(TextView(this@MainActivity).apply {
                text = "MASTER_IP: ${NetworkManager.getLocalIp(this@MainActivity)}"
                textSize = 11f; setTextColor(0xFF5A607A.toInt()); typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, dp(10))
            })
            val row1 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            val netBtn = { txt: String, action: () -> Unit ->
                Button(this@MainActivity).apply {
                    text = txt; textSize = 11f; setTextColor(0xFFE8EAF0.toInt())
                    background = GradientDrawable().apply {
                        setColor(0xFF171C2C.toInt()); setStroke(1, 0xFF1E2540.toInt())
                        cornerRadius = dp(10).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
                    setOnClickListener { action() }
                }
            }
            row1.addView(netBtn("Mode: Master") { NetworkManager.startMaster(this@MainActivity, 71, "400000000000000000", "7fffffffffffffffff") })
            row1.addView(netBtn("Search Masters") {
                NetworkManager.discoverMasters(this@MainActivity) { ip, _ ->
                    runOnUiThread { android.widget.Toast.makeText(this@MainActivity, "Master: $ip", android.widget.Toast.LENGTH_SHORT).show() }
                }
            })
            addView(row1)
            val row2 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            row2.addView(netBtn("Mode: Worker") { startActivity(android.content.Intent(this@MainActivity, NetworkActivity::class.java)) })
            row2.addView(netBtn("Connect") { startActivity(android.content.Intent(this@MainActivity, NetworkActivity::class.java)) })
            addView(row2)
            val tvNetLog = TextView(this@MainActivity).apply {
                text = "Log:"
                textSize = 10f; setTextColor(0xFF5A607A.toInt()); typeface = Typeface.MONOSPACE
                background = GradientDrawable().apply { setColor(0xFF0D1020.toInt()); cornerRadius = dp(8).toFloat() }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(80)).apply { topMargin = dp(8) }
            }
            NetworkManager.onLog = { msg ->
                runOnUiThread {
                    val cur = tvNetLog.text.toString().lines().takeLast(5)
                    tvNetLog.text = (cur + listOf(msg)).joinToString("\n")
                }
            }
            addView(tvNetLog)
        })

        // ── SECTION: Modo Scan ────────────────────────────────────────────
        page.addView(collapsibleSection("🧩", "Modo Scan") {
            addView(TextView(this@MainActivity).apply {
                text = "Seed Phrase Scanner activo"; textSize = 11f
                setTextColor(0xFF5A607A.toInt()); typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, dp(4))
            })
        })

        // ── SECTION: Recovery ─────────────────────────────────────────────
        page.addView(collapsibleSection("🩹", "Recuperación de Semilla (Recovery)") {
            addView(TextView(this@MainActivity).apply {
                text = "Recupera seeds con palabras faltantes"; textSize = 11f
                setTextColor(0xFF5A607A.toInt()); typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, dp(4))
            })
            addView(Button(this@MainActivity).apply {
                text = "Abrir Recovery"
                textSize = 12f; setTextColor(android.graphics.Color.BLACK)
                background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(10).toFloat() }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) }
                setOnClickListener { goTab(3) }
            })
        })

        // ── START / STOP BUTTON ───────────────────────────────────────────
        val startBg = GradientDrawable().apply {
            colors = intArrayOf(ACCENT, ACCENT2)
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            cornerRadius = dp(16).toFloat()
        }
        val stopRed = GradientDrawable().apply {
            colors = intArrayOf(0xFFFF6B35.toInt(), 0xFFFF3B6B.toInt())
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            cornerRadius = dp(16).toFloat()
        }

        btnToggle = Button(this).apply {
            text = s.start
            textSize = 16f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.1f; isAllCaps = true
            background = startBg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60)
            ).apply { setMargins(dp(12), dp(16), dp(12), dp(8)) }
            setOnClickListener {
                puzzleMode = false
                HunterEngine.setMode(0)
                doToggle(btnToggle)
            }
        }
        btnToggle?.tag = arrayOf(startBg, stopRed)
        page.addView(btnToggle)

        scroll.addView(page)
        return scroll
    }

    // ── BUILD PUZZLE TAB ──────────────────────────────────────────────────────
    private fun buildPuzzleTab(): ScrollView {
        val ACCENT  = 0xFF00C896.toInt()
        val ACCENT2 = 0xFF0087FF.toInt()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF0B0E14.toInt())
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0E14.toInt())
            setPadding(dp(12), dp(16), dp(12), dp(80))
        }

        // ── HELPERS ───────────────────────────────────────────────────────
        fun pCard(marginTop: Int = 10): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF111520.toInt()); cornerRadius = dp(16).toFloat()
                setStroke(1, 0xFF1E2540.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(marginTop), 0, 0) }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        fun sectionLabel(text: String) = TextView(this).apply {
            this.text = text; textSize = 9f; setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        fun styledInput(hint: String, color: Int = 0xFFE8EAF0.toInt()): EditText =
            EditText(this).apply {
                this.hint = hint; setTextColor(color); setHintTextColor(0xFF3A4060.toInt())
                textSize = 11f; typeface = Typeface.MONOSPACE
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF171C2C.toInt()); setStroke(1, 0xFF1E2540.toInt())
                    cornerRadius = dp(10).toFloat()
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

        fun collapsibleSection(icon: String, title: String, build: LinearLayout.() -> Unit): LinearLayout {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF111520.toInt()); cornerRadius = dp(14).toFloat()
                    setStroke(1, 0xFF1E2540.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(10), 0, 0) }
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isClickable = true; isFocusable = true
            }
            val iconTv = TextView(this).apply {
                text = icon; textSize = 17f
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) }
                gravity = Gravity.CENTER
            }
            val titleTv = TextView(this).apply {
                text = title; textSize = 13f; setTextColor(0xFFE8EAF0.toInt())
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val arrowTv = TextView(this).apply {
                text = "›"; textSize = 18f; setTextColor(0xFF3A4060.toInt())
            }
            header.addView(iconTv); header.addView(titleTv); header.addView(arrowTv)
            container.addView(header)
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = android.view.View.GONE
                setPadding(dp(16), 0, dp(16), dp(14))
            }
            body.build()
            container.addView(body)
            header.setOnClickListener {
                if (body.visibility == android.view.View.GONE) {
                    body.visibility = android.view.View.VISIBLE; arrowTv.text = "∨"
                } else {
                    body.visibility = android.view.View.GONE; arrowTv.text = "›"
                }
            }
            return container
        }

        // ── HEADER ────────────────────────────────────────────────────────
        page.addView(TextView(this).apply {
            text = "Puzzle Mode"
            textSize = 22f; setTextColor(0xFFE8EAF0.toInt())
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        })
        page.addView(TextView(this).apply {
            text = "Selecciona el puzzle objetivo"
            textSize = 12f; setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        })

        // ── PUZZLE CHIP SELECTOR ──────────────────────────────────────────
        val hiddenPuzzles = getSharedPreferences("hidden_puzzles", MODE_PRIVATE)
        val visiblePuzzles = puzzles.filter { p ->
            !hiddenPuzzles.getBoolean("hidden_${p.num}", false)
        }.toMutableList()

        // Track selected puzzle
        var selectedPuzzleIdx = 0

        // Container for chip rows
        val chipSection = pCard(0)
        chipSection.addView(sectionLabel("SELECCIONAR PUZZLE"))

        // Horizontal scroll for group chips
        val groupScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val groupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        groupScroll.addView(groupRow)
        chipSection.addView(groupScroll)

        // Individual chips container (shown below group)
        val indivScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val indivRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        indivScroll.addView(indivRow)
        chipSection.addView(indivScroll)
        page.addView(chipSection)

        // ── STATUS ────────────────────────────────────────────────────────
        tvPuzzleStatus = TextView(this).apply {
            text = "Selecciona un puzzle"
            textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(ACCENT)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x1400C896.toInt()); setStroke(1, 0x2A00C896.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        page.addView(tvPuzzleStatus)

        // Balance indicator - debajo del puzzle seleccionado
        val tvBalResult = TextView(this).apply {
            text = "Verificando balance..."
            textSize = 11f; setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF171C2C.toInt()); cornerRadius = dp(10).toFloat()
                setStroke(1, 0xFF1E2540.toInt())
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        page.addView(tvBalResult)

        // ── RANGE CONFIG ──────────────────────────────────────────────────
        page.addView(collapsibleSection("🎯", "Rango Hex") {
            val rangeRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            }
            val colStart = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
            }
            colStart.addView(TextView(this@MainActivity).apply { text = "Start"; textSize = 9f; setTextColor(0xFF5A607A.toInt()); typeface = Typeface.create("monospace", Typeface.NORMAL); setPadding(0,0,0,dp(4)) })
            etRangeStart = styledInput("0x...")
            colStart.addView(etRangeStart)
            val colEnd = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            colEnd.addView(TextView(this@MainActivity).apply { text = "End"; textSize = 9f; setTextColor(0xFF5A607A.toInt()); typeface = Typeface.create("monospace", Typeface.NORMAL); setPadding(0,0,0,dp(4)) })
            etRangeEnd = styledInput("0x...")
            colEnd.addView(etRangeEnd)
            rangeRow.addView(colStart); rangeRow.addView(colEnd)
            addView(rangeRow)
            addView(TextView(this@MainActivity).apply { text = "Target Address"; textSize = 9f; setTextColor(0xFF5A607A.toInt()); typeface = Typeface.create("monospace", Typeface.NORMAL); setPadding(0,0,0,dp(4)) })
            etTarget = styledInput("1A2B3C...", 0xFF00C896.toInt())
            addView(etTarget)
        })

        // ── CHECKPOINT ────────────────────────────────────────────────────
        tvCheckpointLive = TextView(this).apply {
            text = ""
            textSize = 10f; setTextColor(0xFF00C896.toInt()); typeface = Typeface.MONOSPACE
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x0A00C896.toInt()); cornerRadius = dp(10).toFloat()
                setStroke(1, 0x1500C896.toInt())
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        page.addView(tvCheckpointLive)

        // ── STATS ─────────────────────────────────────────────────────────
        val statsCard = pCard()
        statsCard.addView(sectionLabel("RENDIMIENTO EN VIVO"))
        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        }
        val tvWpsP = TextView(this).apply {
            text = "0"; textSize = 40f; setTextColor(ACCENT)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvWpsPuzzle = tvWpsP
        speedRow.addView(tvWpsP)
        val speedUnit = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        speedUnit.addView(TextView(this).apply { text = "kKeys"; textSize = 11f; setTextColor(0xFF5A607A.toInt()); typeface = Typeface.create("monospace", Typeface.NORMAL) })
        speedUnit.addView(TextView(this).apply { text = "por seg"; textSize = 10f; setTextColor(0xFF3A4060.toInt()); typeface = Typeface.create("monospace", Typeface.NORMAL) })
        speedRow.addView(speedUnit)
        statsCard.addView(speedRow)

        fun miniStat(label: String, tv: TextView): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF171C2C.toInt()); cornerRadius = dp(10).toFloat(); setStroke(1, 0xFF1E2540.toInt())
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(TextView(this@MainActivity).apply { text = label; textSize = 8f; setTextColor(0xFF5A607A.toInt()); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.1f })
            addView(tv)
        }

        val tvCntP = TextView(this).apply { text = "0"; textSize = 16f; setTextColor(0xFFE8EAF0.toInt()); typeface = Typeface.create("monospace", Typeface.BOLD) }
        val tvTmP  = TextView(this).apply { text = "00:00:00"; textSize = 16f; setTextColor(0xFFE8EAF0.toInt()); typeface = Typeface.create("monospace", Typeface.BOLD) }
        tvCountPuzzle = tvCntP; tvTimePuzzle = tvTmP
        tvPctPuzzle = TextView(this).apply { text = "0.000%"; textSize = 13f; setTextColor(ACCENT2); typeface = Typeface.create("monospace", Typeface.BOLD) }
        tvBlockProgress = TextView(this).apply { text = "0/—"; textSize = 13f; setTextColor(0xFFE8EAF0.toInt()); typeface = Typeface.MONOSPACE }

        val miniRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        val miniRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) } }
        miniRow1.addView(miniStat("SCANNED", tvCntP).also { (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        miniRow1.addView(miniStat("ELAPSED", tvTmP))
        val pctLocal = tvPctPuzzle!!
        val blkLocal = tvBlockProgress!!
        miniRow2.addView(miniStat("PROGRESO", pctLocal).also { (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        miniRow2.addView(miniStat("BLOQUES", blkLocal))
        statsCard.addView(miniRow1); statsCard.addView(miniRow2)
        page.addView(statsCard)

        // ── POTENCIA: LOW / MEDIUM / HIGH ─────────────────────────────────
        val powerCard = pCard()
        powerCard.addView(sectionLabel("POTENCIA"))

        val powerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // Hidden sliders (kept for logic compatibility)
        tvThreadsPuzzle = TextView(this).apply { visibility = android.view.View.GONE }
        tvCpuPuzzle     = TextView(this).apply { visibility = android.view.View.GONE }
        sbThreadsPuzzle = SeekBar(this).apply {
            visibility = android.view.View.GONE; max = 7
            progress = prefs.getInt("puzzle_threads", 3)
            setOnSeekBarChangeListener(mkSbl { updatePuzzleLabels() })
        }
        sbCpuPuzzle = SeekBar(this).apply {
            visibility = android.view.View.GONE; max = 90
            progress = prefs.getInt("puzzle_cpu", 70)
            setOnSeekBarChangeListener(mkSbl { updatePuzzleLabels() })
        }
        powerCard.addView(tvThreadsPuzzle); powerCard.addView(sbThreadsPuzzle)
        powerCard.addView(tvCpuPuzzle); powerCard.addView(sbCpuPuzzle)

        data class PowerLevel(val label: String, val threads: Int, val cpu: Int)
        val levels = listOf(
            PowerLevel("LOW",    1, 30),
            PowerLevel("MEDIUM", 3, 60),
            PowerLevel("HIGH",   7, 90)
        )

        var selectedPower = 1 // MEDIUM default
        val powerBtns = mutableListOf<TextView>()

        levels.forEachIndexed { idx, level ->
            val btn = TextView(this).apply {
                text = level.label
                textSize = 13f; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (idx == 1) 0x1400C896.toInt() else 0xFF171C2C.toInt())
                    cornerRadius = dp(12).toFloat()
                    setStroke(1, if (idx == 1) 0x3300C896.toInt() else 0xFF1E2540.toInt())
                }
                setTextColor(if (idx == 1) ACCENT else 0xFF5A607A.toInt())
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    if (idx < 2) marginEnd = dp(8)
                }
                isClickable = true; isFocusable = true
                setOnClickListener {
                    selectedPower = idx
                    powerBtns.forEachIndexed { i, b ->
                        val active = i == idx
                        (b.background as android.graphics.drawable.GradientDrawable).apply {
                            setColor(if (active) 0x1400C896.toInt() else 0xFF171C2C.toInt())
                            setStroke(1, if (active) 0x3300C896.toInt() else 0xFF1E2540.toInt())
                        }
                        b.setTextColor(if (active) ACCENT else 0xFF5A607A.toInt())
                    }
                    // Apply to seekbars
                    sbThreadsPuzzle?.progress = level.threads - 1
                    sbCpuPuzzle?.progress = level.cpu - 10
                    prefs.edit().putInt("puzzle_threads", level.threads - 1).putInt("puzzle_cpu", level.cpu - 10).apply()
                    updatePuzzleLabels()
                }
            }
            powerBtns.add(btn)
            powerRow.addView(btn)
        }
        // Apply medium by default
        sbThreadsPuzzle?.progress = 3
        sbCpuPuzzle?.progress = 50
        powerCard.addView(powerRow)

        // ── BATCH SIZE SLIDER ─────────────────────────────────────────────
        val batchLabels = listOf(64, 128, 256, 512, 1024, 2048, 4096)

        val batchHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        batchHeaderRow.addView(TextView(this).apply {
            text = "BATCH SIZE"; textSize = 9f; setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val tvBatchVal = TextView(this).apply {
            text = "1000 keys"; textSize = 11f; setTextColor(ACCENT2)
            typeface = Typeface.create("monospace", Typeface.BOLD)
        }
        batchHeaderRow.addView(tvBatchVal)
        powerCard.addView(batchHeaderRow)

        val sbBatch = SeekBar(this).apply {
            max = batchLabels.size - 1
            progress = 2 // default 1000
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    val size = batchLabels[p]
                    tvBatchVal.text = "$size keys"
                    HunterEngine.setBatchSize(size)
                    prefs.edit().putInt("puzzle_batch", p).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        // Restaurar valor guardado
        sbBatch.progress = prefs.getInt("puzzle_batch", 2)
        HunterEngine.setBatchSize(batchLabels[sbBatch.progress])
        powerCard.addView(sbBatch)

        // Labels del slider
        val batchLabelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }
        listOf("64", "", "256", "", "1K", "", "4K").forEach { lbl ->
            batchLabelRow.addView(TextView(this).apply {
                text = lbl; textSize = 8f; setTextColor(0xFF3A4060.toInt())
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        powerCard.addView(batchLabelRow)

        page.addView(powerCard)
        updatePuzzleLabels()

        // ── HERRAMIENTAS ──────────────────────────────────────────────────
        page.addView(collapsibleSection("🔧", "Herramientas") {
            listOf(
                Triple("⏰", "Programar Puzzle", { showSchedulerDialog() }),
                Triple("⚙", "Auto-configurar Hardware", { showHardwareInfo() }),
                Triple("📤", "Exportar Config", { exportConfig() }),
                Triple("📥", "Importar Config", { importConfig() })
            ).forEach { (icon, label, action) ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, dp(10)); isClickable = true; isFocusable = true
                    setOnClickListener { action() }
                }
                row.addView(TextView(this@MainActivity).apply { text = icon; textSize = 16f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) } })
                row.addView(TextView(this@MainActivity).apply { text = label; textSize = 12f; setTextColor(0xFFE8EAF0.toInt()); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                row.addView(TextView(this@MainActivity).apply { text = "›"; textSize = 16f; setTextColor(0xFF3A4060.toInt()) })
                addView(row)
                addView(android.view.View(this@MainActivity).apply { setBackgroundColor(0xFF1E2540.toInt()); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1) })
            }
        })

        // ── THERMAL & BALANCE ─────────────────────────────────────────────
        val tvThermalPuzzle = TextView(this).apply {
            text = ""; textSize = 10f; setTextColor(ACCENT); typeface = Typeface.MONOSPACE
            visibility = android.view.View.GONE
        }
        tvThermal = tvThermalPuzzle
        page.addView(tvThermalPuzzle)

        // ── START BUTTON ──────────────────────────────────────────────────
        val startBg = android.graphics.drawable.GradientDrawable().apply {
            colors = intArrayOf(ACCENT, ACCENT2); orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT; cornerRadius = dp(16).toFloat()
        }
        val stopRed = android.graphics.drawable.GradientDrawable().apply {
            colors = intArrayOf(0xFFFF6B35.toInt(), 0xFFFF3B6B.toInt()); orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT; cornerRadius = dp(16).toFloat()
        }
        btnPuzzleToggle = Button(this).apply {
            text = "▶  START PUZZLE"; textSize = 16f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD); letterSpacing = 0.1f; isAllCaps = true
            background = startBg
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)).apply { setMargins(0, dp(12), 0, dp(8)) }
            setOnClickListener {
                try {
                    puzzleMode = true
                    HunterEngine.setMode(1)
                    doToggle(btnPuzzleToggle)
                } catch (e: Exception) {
                    val msg = "${e.javaClass.simpleName}: ${e.message}"
                    android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_LONG).show()
                    java.io.File(filesDir, "crash_log.txt").appendText("\nPUZZLE_BTN: $msg\n${e.stackTraceToString()}\n")
                }
            }
        }
        btnPuzzleToggle?.tag = arrayOf(startBg, stopRed)
        page.addView(btnPuzzleToggle)

        // ── BUILD CHIP GROUPS ─────────────────────────────────────────────
        // Group puzzles by ranges of 10
        val groupSize = 10
        val groups = visiblePuzzles.chunked(groupSize)
        var activeGroupIdx = 0
        val groupChips = mutableListOf<TextView>()

        fun applyPuzzleAndCheckBalance(p: PuzzleInfo, chipView: TextView? = null) {
            applyPuzzle(p)
            val puzzlePrefs2 = getSharedPreferences("puzzle_checkpoint", MODE_PRIVATE)
            val savedKey = puzzlePrefs2.getString("last_key_${p.num}", null)
            val savedTime = puzzlePrefs2.getLong("last_time_${p.num}", 0)
            if (savedKey != null && savedTime > 0) {
                val ts = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(savedTime))
                tvCheckpointLive?.text = "✓ Checkpoint #${p.num}: $ts  ${savedKey.take(12)}...${savedKey.takeLast(6)}"
            } else {
                tvCheckpointLive?.text = ""
            }
            tvBalResult.text = "Verificando #${p.num}..."
            checkPuzzleBalance(p.addr) { bal ->
                runOnUiThread {
                    when {
                        bal > 0L -> {
                            tvBalResult.text = "✓ ${bal / 100_000_000.0} BTC disponibles"
                            tvBalResult.setTextColor(ACCENT)
                        }
                        bal == 0L -> {
                            hiddenPuzzles.edit().putBoolean("hidden_${p.num}", true).apply()
                            tvBalResult.text = "Sin fondos — #${p.num} ocultado"
                            tvBalResult.setTextColor(0xFFFF6B35.toInt())
                            // Ocultar chip visualmente
                            chipView?.visibility = android.view.View.GONE
                            // Seleccionar el siguiente chip visible
                            var nextSelected = false
                            for (k in 0 until indivRow.childCount) {
                                val c = indivRow.getChildAt(k) as? TextView ?: continue
                                if (c.visibility == android.view.View.VISIBLE && c != chipView) {
                                    c.performClick()
                                    nextSelected = true
                                    break
                                }
                            }
                            if (!nextSelected) {
                                tvPuzzleStatus?.text = "No hay puzzles disponibles en este grupo"
                            }
                        }
                        else -> {
                            tvBalResult.text = "Sin conexión — reintenta"
                            tvBalResult.setTextColor(0xFF5A607A.toInt())
                        }
                    }
                }
            }
        }

        fun buildIndivChips(groupIdx: Int) {
            indivRow.removeAllViews()
            val group = groups.getOrNull(groupIdx) ?: return
            group.forEachIndexed { i, p ->
                val chip = TextView(this).apply {
                    text = "#${p.num}"
                    textSize = 12f; gravity = Gravity.CENTER
                    typeface = Typeface.create("monospace", Typeface.BOLD)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(if (i == 0) 0x1400C896.toInt() else 0xFF171C2C.toInt())
                        cornerRadius = dp(12).toFloat()
                        setStroke(1, if (i == 0) 0x3300C896.toInt() else 0xFF1E2540.toInt())
                    }
                    setTextColor(if (i == 0) ACCENT else 0xFF5A607A.toInt())
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(40)).apply { marginEnd = dp(8) }
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        for (j in 0 until indivRow.childCount) {
                            val c = indivRow.getChildAt(j) as? TextView ?: continue
                            (c.background as android.graphics.drawable.GradientDrawable).apply {
                                setColor(0xFF171C2C.toInt()); setStroke(1, 0xFF1E2540.toInt())
                            }
                            c.setTextColor(0xFF5A607A.toInt())
                        }
                        (background as android.graphics.drawable.GradientDrawable).apply {
                            setColor(0x1400C896.toInt()); setStroke(1, 0x3300C896.toInt())
                        }
                        setTextColor(ACCENT)
                        applyPuzzleAndCheckBalance(p, this)
                    }
                }
                indivRow.addView(chip)
            }
            // Auto-select first
            groups.getOrNull(groupIdx)?.firstOrNull()?.let { applyPuzzleAndCheckBalance(it) }
        }

        fun buildGroupChips() {
            groupRow.removeAllViews()
            groupChips.clear()
            groups.forEachIndexed { idx, group ->
                val first = group.first().num
                val last  = group.last().num
                val chip = TextView(this).apply {
                    text = "#$first–$last"
                    textSize = 12f; gravity = Gravity.CENTER
                    typeface = Typeface.create("monospace", Typeface.BOLD)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(if (idx == 0) 0x140087FF.toInt() else 0xFF171C2C.toInt())
                        cornerRadius = dp(12).toFloat()
                        setStroke(1, if (idx == 0) 0x330087FF.toInt() else 0xFF1E2540.toInt())
                    }
                    setTextColor(if (idx == 0) ACCENT2 else 0xFF5A607A.toInt())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                        marginEnd = dp(8); setPadding(dp(14), 0, dp(14), 0)
                    }
                    setPadding(dp(14), 0, dp(14), 0)
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        activeGroupIdx = idx
                        groupChips.forEachIndexed { i, c ->
                            val active = i == idx
                            (c.background as android.graphics.drawable.GradientDrawable).apply {
                                setColor(if (active) 0x140087FF.toInt() else 0xFF171C2C.toInt())
                                setStroke(1, if (active) 0x330087FF.toInt() else 0xFF1E2540.toInt())
                            }
                            c.setTextColor(if (active) ACCENT2 else 0xFF5A607A.toInt())
                        }
                        buildIndivChips(idx)
                    }
                }
                groupChips.add(chip)
                groupRow.addView(chip)
            }
        }

        buildGroupChips()
        buildIndivChips(0)

        // Default puzzle setup
        val dayOfYear = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val defaultIdx = dayOfYear % visiblePuzzles.size
        suppressPuzzleListener = true
        puzzleSpinner = null  // no spinner in new design
        applyPuzzle(visiblePuzzles.getOrElse(defaultIdx) { visiblePuzzles.first() })
        suppressPuzzleListener = false

        // Load checkpoint for default
        val defaultPuzzle = visiblePuzzles.getOrElse(defaultIdx) { visiblePuzzles.first() }
        val puzzlePrefsInit = getSharedPreferences("puzzle_checkpoint", MODE_PRIVATE)
        val savedKeyInit = puzzlePrefsInit.getString("last_key_${defaultPuzzle.num}", null)
        val savedTimeInit = puzzlePrefsInit.getLong("last_time_${defaultPuzzle.num}", 0)
        if (savedKeyInit != null && savedTimeInit > 0) {
            val ts = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(savedTimeInit))
            tvCheckpointLive?.text = "✓ Checkpoint #${defaultPuzzle.num}: $ts  ${savedKeyInit.take(12)}...${savedKeyInit.takeLast(6)}"
        }

        Thread {
            checkPuzzleBalance(defaultPuzzle.addr) { bal ->
                runOnUiThread {
                    if (bal > 0) {
                        tvBalResult.text = "Balance: ${bal / 100_000_000.0} BTC ✓"
                        tvBalResult.setTextColor(ACCENT)
                    } else {
                        autoSelectPuzzle()
                        tvBalResult.text = "Buscando puzzle con fondos..."
                    }
                }
            }
        }.start()

        scroll.addView(page)
        puzzleTabReady = true
        return scroll
    }

    private fun buildWalletTab(): ScrollView {
        val ACCENT  = 0xFF00C896.toInt()
        val ACCENT2 = 0xFF0087FF.toInt()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF0B0E14.toInt())
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0E14.toInt())
            setPadding(dp(12), dp(16), dp(12), dp(80))
        }

        // ── HEADER ────────────────────────────────────────────────────────
        page.addView(TextView(this).apply {
            text = "Wallet"
            textSize = 22f; setTextColor(0xFFE8EAF0.toInt())
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        })
        page.addView(TextView(this).apply {
            text = "Gestión de wallets encontradas"
            textSize = 12f; setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        })

        // ── HERO BALANCE CARD ─────────────────────────────────────────────
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                colors = intArrayOf(0xFF111520.toInt(), 0xFF171C2C.toInt())
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
                cornerRadius = dp(20).toFloat()
                setStroke(1, 0xFF1E2540.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        heroCard.addView(TextView(this).apply {
            text = "BALANCE TOTAL ENCONTRADO"
            textSize = 9f; setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.1f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        })
        val tvTotalBtc = TextView(this).apply {
            text = "0.00000000"
            textSize = 36f; setTextColor(ACCENT)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER; letterSpacing = -0.02f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val tvTotalUsd = TextView(this).apply {
            text = "BTC  ≈  $0.00 USD"
            textSize = 12f; setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        heroCard.addView(tvTotalBtc)
        heroCard.addView(tvTotalUsd)
        page.addView(heroCard)

        // Leer coincidencias y calcular total
        fun loadCoincidencias(): Pair<Double, List<Triple<String,Double,String>>> {
            val f = getExternalFilesDir(null)?.let { java.io.File(it, "coincidencias.txt") }
            if (f == null || !f.exists()) return Pair(0.0, emptyList())
            var total = 0.0
            val matches = mutableListOf<Triple<String,Double,String>>()
            f.readLines().forEach { line ->
                if (line.startsWith("MATCH|")) {
                    val parts = line.split("|").associate {
                        val kv = it.split(":", limit=2)
                        if (kv.size == 2) kv[0] to kv[1] else it to ""
                    }
                    val addr = parts["ADDR"] ?: return@forEach
                    val btc  = parts["BTC"]?.toDoubleOrNull() ?: 0.0
                    val wif  = parts["WIF"] ?: ""
                    total += btc
                    matches.add(Triple(addr, btc, wif))
                }
            }
            return Pair(total, matches)
        }

        fun refreshWallet() {
            Thread {
                val (total, matches) = loadCoincidencias()
                runOnUiThread {
                    tvTotalBtc.text = "%.8f".format(total)
                    tvTotalUsd.text = "BTC  ·  ${matches.size} wallet(s) encontrada(s)"
                }
            }.start()
        }
        refreshWallet()

        // ── ACTION CARDS ──────────────────────────────────────────────────
        fun walletBtn(icon: String, label: String, sub: String, click: () -> Unit): LinearLayout {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF111520.toInt()); cornerRadius = dp(16).toFloat()
                    setStroke(1, 0xFF1E2540.toInt())
                }
                isClickable = true; isFocusable = true
                setOnClickListener { click() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            val iconTv = TextView(this).apply {
                text = icon; textSize = 20f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginEnd = dp(14); gravity = Gravity.CENTER_VERTICAL
                }
            }
            val lc = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            lc.addView(TextView(this).apply {
                text = label; textSize = 14f; setTextColor(0xFFE8EAF0.toInt())
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            })
            lc.addView(TextView(this).apply {
                text = sub; textSize = 11f; setTextColor(0xFF5A607A.toInt())
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            })
            r.addView(iconTv); r.addView(lc)
            r.addView(TextView(this).apply {
                text = "›"; textSize = 20f; setTextColor(0xFF3A4060.toInt())
            })
            return r
        }

        page.addView(walletBtn("💰", "Ver Wallet", "Balances y direcciones") {
            startActivity(Intent(this, WalletActivity::class.java))
        })
        page.addView(walletBtn("🔑", "Importar Seed", "Restaurar desde frase semilla") {
            startActivity(Intent(this, WalletActivity::class.java))
        })
        page.addView(walletBtn("📤", "Exportar Log", "Guardar matches en archivo") {
            exportLog()
        })
        page.addView(walletBtn("↻", "Actualizar Balance", "Releer coincidencias.txt") {
            refreshWallet()
            android.widget.Toast.makeText(this, "Actualizando...", android.widget.Toast.LENGTH_SHORT).show()
        })

        scroll.addView(page)
        return scroll
    }

    // ── BUILD RECOVERY TAB ────────────────────────────────────────────────────
    private fun buildRecoveryTab(): ScrollView {
        val ACCENT  = 0xFF00C896.toInt()
        val ACCENT2 = 0xFF0087FF.toInt()

        val recoveryScroll = ScrollView(this).apply {
            setBackgroundColor(0xFF0B0E14.toInt())
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val recoveryPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0E14.toInt())
            setPadding(dp(12), dp(16), dp(12), dp(80))
        }

        // ── HELPER ────────────────────────────────────────────────────────
        fun rCard(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF111520.toInt()); cornerRadius = dp(16).toFloat()
                setStroke(1, 0xFF1E2540.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        fun fieldLabel(text: String) = TextView(this).apply {
            this.text = text
            textSize = 9f; setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }

        // ── HEADER ────────────────────────────────────────────────────────
        recoveryPage.addView(TextView(this).apply {
            text = "Recovery"
            textSize = 22f; setTextColor(0xFFE8EAF0.toInt())
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        })
        recoveryPage.addView(TextView(this).apply {
            text = "Recuperación de seed phrase"
            textSize = 12f; setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        })

        // ── SEED INPUT CARD ───────────────────────────────────────────────
        val seedCard = rCard()
        seedCard.addView(fieldLabel("SEED PHRASE"))
        seedCard.addView(TextView(this).apply {
            text = "Usa ??? para las palabras que no recuerdas"
            textSize = 10f; setTextColor(0xFF3A4060.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        })

        val etSeed = android.widget.EditText(this).apply {
            hint = "abandon ??? letter ??? advice cage absurd amount doctor acoustic avoid ???"
            setHintTextColor(0xFF3A4060.toInt()); setTextColor(0xFFE8EAF0.toInt())
            textSize = 11f; typeface = Typeface.MONOSPACE
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF171C2C.toInt()); setStroke(1, 0xFF1E2540.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; maxLines = 5; isSingleLine = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        seedCard.addView(etSeed)

        // Info combinaciones
        val tvRecoveryInfo = TextView(this).apply {
            text = "Palabras faltantes: —"
            textSize = 10f; setTextColor(ACCENT2)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x0A0087FF.toInt()); cornerRadius = dp(8).toFloat()
                setStroke(1, 0x150087FF.toInt())
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        seedCard.addView(tvRecoveryInfo)
        recoveryPage.addView(seedCard)

        // ── TARGET ADDRESS CARD ───────────────────────────────────────────
        val targetCard = rCard()
        targetCard.addView(fieldLabel("DIRECCIÓN BTC OBJETIVO (opcional)"))
        val etTarget = android.widget.EditText(this).apply {
            hint = "1A2B3C... o bc1q..."
            setHintTextColor(0xFF3A4060.toInt()); setTextColor(ACCENT)
            textSize = 11f; typeface = Typeface.MONOSPACE
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF171C2C.toInt()); setStroke(1, 0xFF1E2540.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        targetCard.addView(etTarget)
        recoveryPage.addView(targetCard)

        // ── PROGRESS CARD ─────────────────────────────────────────────────
        val progressCard = rCard()
        val pbRecovery = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 1000; progress = 0
            progressDrawable = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(ACCENT2, ACCENT)
            ).apply { cornerRadius = dp(4).toFloat() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
            ).apply { bottomMargin = dp(8) }
            visibility = android.view.View.GONE
        }
        val tvRecoveryStatus = TextView(this).apply {
            text = ""; textSize = 10f; setTextColor(0xFF5A607A.toInt())
            typeface = Typeface.MONOSPACE
            visibility = android.view.View.GONE
        }
        progressCard.addView(pbRecovery)
        progressCard.addView(tvRecoveryStatus)

        val tvRecoveryResult = TextView(this).apply {
            text = ""; textSize = 11f; setTextColor(ACCENT)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x0A00C896.toInt()); cornerRadius = dp(10).toFloat()
                setStroke(1, 0x1500C896.toInt())
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        progressCard.addView(tvRecoveryResult)
        recoveryPage.addView(progressCard)

        // ── BUTTONS ───────────────────────────────────────────────────────
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val btnStartRecovery = Button(this).apply {
            text = "▶  INICIAR RECOVERY"; textSize = 13f
            setTextColor(0xFF000000.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                colors = intArrayOf(ACCENT, ACCENT2)
                orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
                cornerRadius = dp(14).toFloat()
            }
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(8) }
        }
        val btnCancelRecovery = Button(this).apply {
            text = "■ CANCELAR"; textSize = 12f
            setTextColor(0xFFFF6B35.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF171C2C.toInt()); setStroke(1, 0xFF3A2A20.toInt())
                cornerRadius = dp(14).toFloat()
            }
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            visibility = android.view.View.GONE
        }
        btnRow.addView(btnStartRecovery); btnRow.addView(btnCancelRecovery)
        recoveryPage.addView(btnRow)

        val btnSaveWallet = Button(this).apply {
            text = "⬇  GUARDAR EN WALLET"; textSize = 13f
            setTextColor(0xFF000000.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ACCENT); cornerRadius = dp(14).toFloat()
            }
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            )
        }
        recoveryPage.addView(btnSaveWallet)

        // ── LISTENERS ─────────────────────────────────────────────────────
        etSeed.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val input = s?.toString() ?: ""
                val missing = input.split(" ").count { it.trim() == "???" }
                if (missing > 0) {
                    val combos = Math.pow(2048.0, missing.toDouble()).toLong()
                    val combosStr = when {
                        combos < 1_000_000L -> "${combos / 1000}K"
                        combos < 1_000_000_000L -> "${combos / 1_000_000}M"
                        else -> "${combos / 1_000_000_000}B"
                    }
                    val secs = combos / 50_000L
                    val timeStr = when {
                        secs < 60 -> "$secs seg"
                        secs < 3600 -> "${secs / 60} min"
                        secs < 86400 -> "${secs / 3600} h"
                        else -> "${secs / 86400} dias"
                    }
                    tvRecoveryInfo.text = "Faltan: $missing  |  Combos: ~$combosStr  |  ~$timeStr"
                } else {
                    tvRecoveryInfo.text = "Palabras faltantes: —"
                }
            }
        })

        btnSaveWallet.setOnClickListener {
            val foundMnemonic = it.tag as? String ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Guardar en Wallet")
                .setMessage("¿Guardar esta seed phrase en tu wallet principal?\n\n$foundMnemonic")
                .setPositiveButton("Guardar") { _, _ ->
                    if (PinAuthHelper.isSessionValid()) {
                        WalletManager.saveSeed(this, foundMnemonic)
                        btnSaveWallet.visibility = android.view.View.GONE
                        tvRecoveryStatus.text = "✓ Seed guardada en wallet principal"
                        tvRecoveryStatus.visibility = android.view.View.VISIBLE
                    } else {
                        PinAuthHelper.show(this) { ok ->
                            if (ok) {
                                WalletManager.saveSeed(this, foundMnemonic)
                                btnSaveWallet.visibility = android.view.View.GONE
                                tvRecoveryStatus.text = "✓ Seed guardada en wallet principal"
                                tvRecoveryStatus.visibility = android.view.View.VISIBLE
                            }
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        recoveryEngine = RecoveryEngine(this)
        val wordlistLoaded = recoveryEngine?.loadWordlist() ?: false

        recoveryEngine?.listener = object : com.hunter.btc.recovery.RecoveryEngine.ProgressListener {
            override fun onProgress(attempts: Long, total: Long, currentWord: String) {
                runOnUiThread {
                    val pct = ((attempts.toFloat() / total) * 1000).toInt()
                    pbRecovery.progress = pct
                    tvRecoveryStatus.text = "Probando: $currentWord  ($attempts / $total)"
                }
            }
            override fun onFoundWithAddress(mnemonic: String, address: String) {
                runOnUiThread {
                    pbRecovery.visibility = android.view.View.GONE
                    tvRecoveryStatus.visibility = android.view.View.GONE
                    btnCancelRecovery.visibility = android.view.View.GONE
                    btnStartRecovery.visibility = android.view.View.VISIBLE
                    tvRecoveryResult.text = "DIRECCION DERIVADA:\n$address\n\nFRASE:\n$mnemonic"
                    tvRecoveryResult.visibility = android.view.View.VISIBLE
                }
            }
            override fun onFound(mnemonic: String) {
                runOnUiThread {
                    pbRecovery.visibility = android.view.View.GONE
                    tvRecoveryStatus.visibility = android.view.View.GONE
                    btnCancelRecovery.visibility = android.view.View.GONE
                    btnStartRecovery.visibility = android.view.View.VISIBLE
                    tvRecoveryResult.text = "✓ ENCONTRADO\n\n$mnemonic"
                    tvRecoveryResult.visibility = android.view.View.VISIBLE
                    btnSaveWallet.tag = mnemonic
                    btnSaveWallet.visibility = android.view.View.VISIBLE
                    val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                    val f = java.io.File(getExternalFilesDir(null), "recovery_$ts.txt")
                    f.writeText("RECOVERY MATCH\n$mnemonic\n")
                    sendMatchNotification(mnemonic.take(30), "RECOVERY")
                }
            }
            override fun onNotFound() {
                runOnUiThread {
                    pbRecovery.visibility = android.view.View.GONE
                    tvRecoveryStatus.text = "No encontrado. Verifica las palabras conocidas."
                    btnCancelRecovery.visibility = android.view.View.GONE
                    btnStartRecovery.visibility = android.view.View.VISIBLE
                }
            }
            override fun onCancelled() {
                runOnUiThread {
                    pbRecovery.visibility = android.view.View.GONE
                    tvRecoveryStatus.text = "Cancelado."
                    btnCancelRecovery.visibility = android.view.View.GONE
                    btnStartRecovery.visibility = android.view.View.VISIBLE
                }
            }
        }

        btnStartRecovery.setOnClickListener {
            val input = etSeed.text.toString().trim()
            if (input.isEmpty()) {
                tvRecoveryStatus.text = "Ingresa la seed phrase primero."
                tvRecoveryStatus.visibility = android.view.View.VISIBLE
                return@setOnClickListener
            }
            if (!wordlistLoaded) {
                tvRecoveryStatus.text = "Error: wordlist BIP39 no cargado."
                tvRecoveryStatus.visibility = android.view.View.VISIBLE
                return@setOnClickListener
            }
            val wl = recoveryEngine?.getWordlistSet() ?: emptySet()
            val parseResult = com.hunter.btc.recovery.RecoveryParser.parse(input, wl)
            when (parseResult) {
                is com.hunter.btc.recovery.ParseResult.Error -> {
                    tvRecoveryStatus.text = parseResult.message
                    tvRecoveryStatus.visibility = android.view.View.VISIBLE
                }
                is com.hunter.btc.recovery.ParseResult.Success -> {
                    tvRecoveryResult.visibility = android.view.View.GONE
                    pbRecovery.progress = 0
                    pbRecovery.visibility = android.view.View.VISIBLE
                    tvRecoveryStatus.visibility = android.view.View.VISIBLE
                    tvRecoveryStatus.text = "Iniciando..."
                    btnStartRecovery.visibility = android.view.View.GONE
                    btnCancelRecovery.visibility = android.view.View.VISIBLE
                    recoveryEngine?.startRecovery(parseResult.parsed, etTarget?.text.toString().trim() ?: "")
                }
            }
        }

        btnCancelRecovery.setOnClickListener { recoveryEngine?.cancel() }

        recoveryScroll.addView(recoveryPage)
        return recoveryScroll
    }

    // ── FUNCIONES AUXILIARES ──────────────────────────────────────────────────
    private fun secLbl(t: String) = TextView(this).apply {
        text = t.uppercase(); textSize = 9f; setTextColor(TXT_MUTED)
        typeface = Typeface.create("monospace", Typeface.BOLD)
        letterSpacing = 0.16f; setPadding(0, 0, 0, dp(10))
    }

    private fun mkSbl(cb: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { cb() }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun themedAdapter(items: List<String>): ArrayAdapter<String> {
        val a = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return a
    }

    private fun updateLabels() {
                val t = (sbThreads?.progress ?: 3) + 1
        val c = (sbCpu?.progress ?: 70) + 10
        tvThreads?.text = "Threads: $t"
        tvCpu?.text = "CPU limit: $c%"
        tvQuickThreads?.text = "$t"
        tvQuickCpu?.text = "$c%"
        prefs.edit().putInt("threads", sbThreads?.progress ?: 3).putInt("cpu", sbCpu?.progress ?: 70).apply()
    }

    private fun updatePuzzleLabels() {
        val t = (sbThreadsPuzzle?.progress ?: 3) + 1
        val c = (sbCpuPuzzle?.progress ?: 70) + 10
        tvThreadsPuzzle?.text = "Threads: $t"
        tvCpuPuzzle?.text = "CPU limit: $c%"
        prefs.edit().putInt("puzzle_threads", sbThreadsPuzzle?.progress ?: 3)
                    .putInt("puzzle_cpu",     sbCpuPuzzle?.progress ?: 70).apply()
    }


    private fun checkThermalThrottle() {
        if (!thermalThrottleEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastThermalCheck < 10000) return  // cada 10 seg
        lastThermalCheck = now

        try {
            val batTemp = getBatteryTemp()
            val cpuTemp = if ((now / 10000) % 2 == 0L) getCpuTemp() else 0f  // CPU temp cada 20s
            val maxTemp = maxOf(batTemp, cpuTemp)

            val (targetCpu, status, color) = when {
                maxTemp >= 48f -> Triple(20,  "🔥 ${maxTemp.toInt()}°C CRITICO — CPU 20%",  0xFFFF4444.toInt())
                maxTemp >= 44f -> Triple(35,  "🌡 ${maxTemp.toInt()}°C MUY ALTO — CPU 35%", 0xFFFF8800.toInt())
                maxTemp >= 40f -> Triple(50,  "⚠ ${maxTemp.toInt()}°C ALTO — CPU 50%",      AppTheme.AMBER)
                maxTemp >= 30f -> Triple(originalCpuLimit, "✓ ${maxTemp.toInt()}°C OK",     AppTheme.GREEN)
                else           -> Triple(originalCpuLimit, "🌡 Bat:${batTemp.toInt()}° CPU:${cpuTemp.toInt()}°", AppTheme.TXT_MUTED)
            }

            if (HunterEngine.isRunning()) HunterEngine.setCpuLimit(targetCpu)
            isThrottled = maxTemp >= 40f
            runOnUiThread {
                tvThermal?.text = status
                tvThermal?.setTextColor(color)
            }
        } catch (e: Exception) {
            runOnUiThread { tvThermal?.text = "Temp: error lectura" }
        }
    }

    private fun getBatteryTemp(): Float {
        return try {
            val intent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            temp / 10f
        } catch (e: Exception) { 0f }
    }

    private fun getCpuTemp(): Float {
        // Buscar en todas las zonas térmicas disponibles
        try {
            val base = java.io.File("/sys/class/thermal")
            if (base.exists()) {
                val temps = base.listFiles()
                    ?.filter { it.name.startsWith("thermal_zone") }
                    ?.mapNotNull {
                        try {
                            val t = java.io.File(it, "temp").readText().trim().toFloatOrNull()
                            if (t != null && t > 0) if (t > 1000) t / 1000f else t else null
                        } catch (e: Exception) { null }
                    } ?: emptyList()
                if (temps.isNotEmpty()) return temps.max()
            }
        } catch (e: Exception) {}
        // Fallback paths Samsung
        for (p in listOf(
            "/sys/class/thermal/thermal_zone4/temp",
            "/sys/class/thermal/thermal_zone7/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
            "/sys/kernel/debug/spmi/spmi-0/address"
        )) {
            try {
                val raw = java.io.File(p).readText().trim().toFloatOrNull() ?: continue
                if (raw > 0) return if (raw > 1000) raw / 1000f else raw
            } catch (e: Exception) {}
        }
        return 0f
    }

    // ── Auto-detección de hardware ───────────────────────────────────────────
    data class HardwareProfile(
        val cores: Int,
        val recommendedThreads: Int,
        val recommendedCpu: Int,
        val chipName: String,
        val ramMB: Long,
        val archInfo: String
    )

    private fun detectHardware(): HardwareProfile {
        val cores = Runtime.getRuntime().availableProcessors()

        // Leer info del chip desde /proc/cpuinfo
        val chipName = try {
            val cpuinfo = java.io.File("/proc/cpuinfo").readText()
            val hardware = cpuinfo.lines()
                .firstOrNull { it.startsWith("Hardware") }
                ?.substringAfter(":")?.trim() ?: ""
            val model = cpuinfo.lines()
                .firstOrNull { it.startsWith("model name") || it.startsWith("Model name") }
                ?.substringAfter(":")?.trim() ?: ""
            when {
                hardware.contains("Snapdragon", true) -> hardware
                model.contains("Snapdragon", true)    -> model
                hardware.contains("Exynos", true)     -> hardware
                hardware.contains("Dimensity", true)  -> hardware
                hardware.isNotEmpty()                 -> hardware
                else -> {
                    // Fallback: usar Build.MODEL y SOC info
                    val soc = if (android.os.Build.VERSION.SDK_INT >= 31)
                        android.os.Build.SOC_MODEL
                    else ""
                    val model = android.os.Build.MODEL
                    when {
                        soc.isNotEmpty() && soc != "unknown" -> "$soc ($cores cores)"
                        model.contains("SM-S9", true) -> "Snapdragon 8 Gen 2 ($cores cores)"
                        model.contains("SM-S8", true) -> "Snapdragon 8 Gen 1 ($cores cores)"
                        model.contains("SM-A5", true) -> "Snapdragon 778G ($cores cores)"
                        model.contains("SM-A3", true) -> "Snapdragon 680 ($cores cores)"
                        else -> "ARM64 · ${model} ($cores cores)"
                    }
                }
            }
        } catch (e: Exception) { "ARM64 (${cores} cores)" }

        // RAM disponible
        val ramMB = try {
            val rt = Runtime.getRuntime()
            val actManager = getSystemService(android.app.ActivityManager::class.java)
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            memInfo.availMem / (1024 * 1024)
        } catch (e: Exception) { 0L }

        // Arquitectura
        val arch = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        // Calcular threads óptimos:
        // - Dejar 2 cores para sistema y UI
        // - Máximo 8 threads útiles para este tipo de workload
        val recommendedThreads = (cores - 2).coerceIn(2, 8)

        // CPU limit según RAM (menos RAM = más conservador)
        val recommendedCpu = when {
            ramMB > 3000 -> 90
            ramMB > 1500 -> 75
            ramMB > 800  -> 60
            else         -> 50
        }

        return HardwareProfile(
            cores = cores,
            recommendedThreads = recommendedThreads,
            recommendedCpu = recommendedCpu,
            chipName = chipName,
            ramMB = ramMB,
            archInfo = arch
        )
    }

    private fun applyHardwareProfile(profile: HardwareProfile) {
        val threadProgress = (profile.recommendedThreads - 1).coerceIn(0, 7)
        sbThreads?.progress = threadProgress
        sbThreadsPuzzle?.progress = threadProgress

        val cpuProgress = (profile.recommendedCpu - 10).coerceIn(0, 90)
        sbCpu?.progress = cpuProgress
        sbCpuPuzzle?.progress = cpuProgress

        updateLabels()
        updatePuzzleLabels()

        // CPU Affinity: big cores en Exynos/Snapdragon big.LITTLE
        val totalCores = profile.cores
        val bigCores = if (totalCores >= 8) {
            // Últimos 4 cores suelen ser los big (A78/Kryo)
            intArrayOf(4, 5, 6, 7)
        } else if (totalCores >= 6) {
            intArrayOf(4, 5)
        } else {
            intArrayOf(0, 1, 2, 3)
        }
        try { HunterEngine.setBigCores(bigCores, true) } catch (e: Exception) {}

        // Batch dinámico según RAM
        val batchSize = when {
            profile.ramMB > 3000 -> 32000  // RAM alta → batch grande
            profile.ramMB > 1500 -> 16000  // normal
            profile.ramMB > 800  -> 8000   // conservador
            else                 -> 4000
        }
        try { HunterEngine.setBatchSize(batchSize) } catch (e: Exception) {}

        prefs.edit()
            .putInt("threads", threadProgress)
            .putInt("cpu", cpuProgress)
            .putInt("puzzle_threads", threadProgress)
            .putInt("puzzle_cpu", cpuProgress)
            .putString("big_cores", bigCores.joinToString(","))
            .putInt("batch_size", batchSize)
            .apply()

        // Forzar actualización visual de sliders
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            sbThreads?.progress     = threadProgress
            sbCpu?.progress         = cpuProgress
            sbThreadsPuzzle?.progress = threadProgress
            sbCpuPuzzle?.progress   = cpuProgress
            updateLabels()
            updatePuzzleLabels()
        }
    }

    private fun showHardwareInfo() {
        val profile = detectHardware()
        val msg = """
            🔧 Hardware detectado:
            
            Chip: ${profile.chipName}
            Cores: ${profile.cores}
            RAM libre: ${profile.ramMB} MB
            Arch: ${profile.archInfo}
            
            Configuración recomendada:
            • Threads: ${profile.recommendedThreads}
            • CPU limit: ${profile.recommendedCpu}%
            
            Batch size: ${if (profile.ramMB > 3000) 32000 else if (profile.ramMB > 1500) 16000 else 8000} keys
            Big cores: ${if (profile.cores >= 8) "4-7" else "auto"}
            
            ¿Aplicar configuración óptima?
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Auto-configuración")
            .setMessage(msg)
            .setPositiveButton("Aplicar") { _, _ ->
                applyHardwareProfile(profile)
                // Forzar redibujado inmediato
                val t = profile.recommendedThreads
                val c = profile.recommendedCpu
                runOnUiThread {
                    sbThreads?.progress       = (t - 1).coerceIn(0, 7)
                    sbCpu?.progress           = (c - 10).coerceIn(0, 90)
                    sbThreadsPuzzle?.progress = (t - 1).coerceIn(0, 7)
                    sbCpuPuzzle?.progress     = (c - 10).coerceIn(0, 90)
                    tvThreads?.text     = "Threads: $t"
                    tvCpu?.text         = "CPU limit: $c%"
                    tvThreadsPuzzle?.text = "Threads: $t"
                    tvCpuPuzzle?.text     = "CPU limit: $c%"
                }
                Toast.makeText(this,
                    "Aplicado: $t threads / $c% CPU",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun formatCount(v: Long): String {
        val fmt = java.text.NumberFormat.getNumberInstance(java.util.Locale.US)
        return fmt.format(v)
    }

    // ── Registro local de bloques escaneados ─────────────────────────────────
    private fun getBlockPrefs() = getSharedPreferences("puzzle_blocks", MODE_PRIVATE)

    private fun getNextUnscannedBlock(puzzleNum: Int, rangeStart: String, rangeEnd: String): Pair<String, String>? {
        return try {
            val start = java.math.BigInteger(rangeStart.trimStart('0').ifEmpty{"0"}, 16)
            val end   = java.math.BigInteger(rangeEnd.trimStart('0').ifEmpty{"0"}, 16)
            val range = end.subtract(start)
            val totalBlocksBig = range.divide(BLOCK_SIZE)
            val totalBlocks = if (totalBlocksBig > java.math.BigInteger.valueOf(100_000))
                100_000L else totalBlocksBig.toLong().coerceAtLeast(1)

            val prefs = getBlockPrefs()
            val scanned = prefs.getStringSet("scanned_$puzzleNum", emptySet()) ?: emptySet()

            // Elegir bloque aleatorio no escaneado sin cargar lista entera
            var attempts = 0
            var blockIdx: Long
            do {
                blockIdx = (Math.random() * totalBlocks).toLong()
                attempts++
            } while (scanned.contains(blockIdx.toString()) && attempts < 100)

            if (attempts >= 100) return null  // todo escaneado

            val blockStart = start.add(BLOCK_SIZE.multiply(java.math.BigInteger.valueOf(blockIdx)))
            val blockEnd   = blockStart.add(BLOCK_SIZE).min(end)

            currentBlockId = blockIdx.toString()
            Pair(
                blockStart.toString(16).padStart(18, '0'),
                blockEnd.toString(16).padStart(18, '0')
            )
        } catch (e: Exception) { null }
    }

    private fun markBlockScanned(puzzleNum: Int) {
        if (currentBlockId.isEmpty()) return
        val prefs = getBlockPrefs()
        val scanned = prefs.getStringSet("scanned_$puzzleNum", emptySet())?.toMutableSet() ?: mutableSetOf()
        scanned.add(currentBlockId)
        prefs.edit().putStringSet("scanned_$puzzleNum", scanned).apply()

        // Si hay red activa como worker, reportar al master
        if (NetworkManager.isWorker && NetworkManager.isRunning.get()) {
            val masterIp = getSharedPreferences("net_prefs", MODE_PRIVATE)
                .getString("master_ip", null) ?: return
            NetworkManager.reportBlockDone(masterIp, currentBlockId)
        }
        // Agregar al registro global local
        NetworkManager.globalScannedBlocks.add(currentBlockId)
    }

    private fun getBlockProgressText(puzzleNum: Int, rangeStart: String, rangeEnd: String): String {
        // Si hay red activa, mostrar progreso global
        if (NetworkManager.isRunning.get() && NetworkManager.globalScannedBlocks.isNotEmpty()) {
            return NetworkManager.getGlobalProgress(rangeStart, rangeEnd) + " [RED]"
        }
        return try {
            val start = java.math.BigInteger(rangeStart.trimStart('0').ifEmpty{"0"}, 16)
            val end   = java.math.BigInteger(rangeEnd.trimStart('0').ifEmpty{"0"}, 16)
            val total = end.subtract(start).divide(BLOCK_SIZE).toLong()
            val scanned = getBlockPrefs().getStringSet("scanned_$puzzleNum", emptySet())?.size ?: 0
            val pct = if (total > 0) scanned * 100.0 / total else 0.0
            "Bloques: $scanned / $total (%.4f%%)".format(pct)
        } catch (e: Exception) { "" }
    }

    private fun calcPuzzleProgress(lastKeyHex: String, startHex: String, endHex: String): String {
        return try {
            val last  = java.math.BigInteger(lastKeyHex.trimStart('0').ifEmpty { "0" }, 16)
            val start = java.math.BigInteger(startHex.trimStart('0').ifEmpty { "0" }, 16)
            val end   = java.math.BigInteger(endHex.trimStart('0').ifEmpty { "0" }, 16)
            val range = end.subtract(start)
            if (range <= java.math.BigInteger.ZERO) return "0.000000%"
            val done  = last.subtract(start).max(java.math.BigInteger.ZERO)
            // BigDecimal para precisión completa sin pérdida
            val bdDone  = java.math.BigDecimal(done)
            val bdRange = java.math.BigDecimal(range)
            val pct = bdDone.multiply(java.math.BigDecimal("100"))
                            .divide(bdRange, 18, java.math.RoundingMode.HALF_UP)
            "${pct.toPlainString()}%"
        } catch (e: Exception) { "—" }
    }


    private fun formatElapsed(startTimeMs: Long): String {
        if (startTimeMs <= 0) return "00:00:00"
        val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000
        if (elapsed < 0) return "00:00:00"
        val d  = elapsed / 86400
        val h  = (elapsed % 86400) / 3600
        val m  = (elapsed % 3600) / 60
        val sc = elapsed % 60
        return if (d > 0) "%dd %02d:%02d:%02d".format(d, h, m, sc)
               else "%02d:%02d:%02d".format(h, m, sc)
    }

    private fun updateUI() {
        try {
            if (HunterEngine.isRunning()) {
                val wps = HunterEngine.getWps()
                if (puzzleMode) {
                    tvWpsPuzzle?.text = java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(wps.toLong())
                    tvCountPuzzle?.text = formatCount(HunterEngine.getCount())
                    tvTimePuzzle?.text = formatElapsed(sessionStartTime)
                } else {
                    tvWps?.text = java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(wps.toLong())
                    tvCount?.text = formatCount(HunterEngine.getCount())
                    tvTime?.text = formatElapsed(sessionStartTime)
                    chartView?.addPoint(wps.toFloat())
                    val found = HunterEngine.getCount() - sessionStartCount
                    tvMatches?.text = "$found"
                    tvQuickMatches?.text = "$found"
                }
            }
            val rt = Runtime.getRuntime()
            tvRam?.text = "RAM ${(rt.totalMemory()-rt.freeMemory())/1048576}MB"
        // Checkpoint puzzle - guardar cada ~30 seg (cada ~37 ciclos de 800ms)
        if (puzzleMode && HunterEngine.isRunning()) {
            val cycleCount = (System.currentTimeMillis() / 800).toInt()
            if (cycleCount % 37 == 0) {
                try {
                    val lastKey = HunterEngine.getLastKey()
                    if (lastKey.isNotEmpty() && lastKey != "0".repeat(64)) {
                        val puzzlePrefs = getSharedPreferences("puzzle_checkpoint", MODE_PRIVATE)
                        val puzzleNum = puzzles.firstOrNull { it.start == etRangeStart?.text.toString() }?.num ?: (puzzles.getOrNull(puzzleSpinner?.selectedItemPosition ?: 0)?.num ?: 0)
                        puzzlePrefs.edit()
                            .putString("last_key_$puzzleNum", lastKey)
                            .putLong("last_time_$puzzleNum", System.currentTimeMillis())
                            .apply()
                    }
                } catch (e: Exception) {}
            }
        }
        // Actualizar dataset status si está cargando
        if (HunterEngine.isLoading()) {
            val status = HunterEngine.getLoadStatus()
            tvCsvName?.text = status; tvCsvName?.setTextColor(AppTheme.CYAN)
        } else if (HunterEngine.isCsvLoaded() && csvPath.isNotEmpty()) {
            tvCsvName?.text = File(csvPath).name
            tvCsvName?.setTextColor(0xFF00FF88.toInt())
        }
        } catch (e: Exception) {
            // vars no inicializadas aún
        }
    }

    private fun doToggle(callerBtn: Button? = null) {
        try {
            if (HunterEngine.isRunning()) {
                HunterEngine.stopHunting()
                stopService(Intent(this, HunterService::class.java))
                // Marcar bloque como escaneado al detener
                if (puzzleMode) {
                    val pNum = puzzles.firstOrNull { it.start == currentRangeStart }?.num ?: (puzzles.getOrNull(puzzleSpinner?.selectedItemPosition ?: 0)?.num ?: 0)
                    markBlockScanned(pNum)
                }
                val btn = activeToggleBtn
                if (btn != null) {
                    val bg = btn.tag as? Array<*>
                    btn.text = if (puzzleMode) "▶  START PUZZLE" else s.start
                    btn.background = bg?.get(0) as? GradientDrawable
                }
                activeToggleBtn = null
            } else {
                if (!HunterEngine.isCsvLoaded() && !puzzleMode) {
                    Toast.makeText(this, "Load a dataset first (Config tab)", Toast.LENGTH_SHORT).show()
                    return
                }
                sessionStartTime = System.currentTimeMillis()
                sessionStartCount = HunterEngine.getCount()
                val threads: Int
                val cpu: Int
                if (puzzleMode) {
                    threads = (sbThreadsPuzzle?.progress ?: 3) + 1
                    cpu     = (sbCpuPuzzle?.progress ?: 70) + 10
                    if (etRangeStart != null && etRangeEnd != null) {
                        // Cargar checkpoint si existe
                        val puzzlePrefs = getSharedPreferences("puzzle_checkpoint", MODE_PRIVATE)
                        val puzzleNum = puzzles.firstOrNull { it.start == etRangeStart?.text.toString() }?.num ?: (puzzles.getOrNull(puzzleSpinner?.selectedItemPosition ?: 0)?.num ?: 0)
                        val savedKey = puzzlePrefs.getString("last_key_$puzzleNum", null)
                        val rangeEnd = etRangeEnd?.text.toString() ?: ""
                        // Elegir bloque no escaneado
                        val fullStart = etRangeStart?.text.toString()?.trim() ?: ""
                        if (fullStart.isEmpty()) {
                            Toast.makeText(this, "Error: rango no configurado", Toast.LENGTH_SHORT).show()
                            return
                        }
                        val block = getNextUnscannedBlock(puzzleNum, fullStart, rangeEnd)
                        if (block != null) {
                            val (bStart, bEnd) = block
                            HunterEngine.setRange(bStart, bEnd)
                            currentRangeStart = bStart
                            currentRangeEnd = bEnd
                            tvPuzzleStatus?.text = "Bloque #$currentBlockId de ${getBlockProgressText(puzzleNum, fullStart, rangeEnd)}"
                            tvPuzzleStatus?.setTextColor(AppTheme.CYAN)
                        } else if (savedKey != null && savedKey.isNotEmpty()) {
                            HunterEngine.setRange(savedKey, rangeEnd)
                            currentRangeStart = savedKey
                            currentRangeEnd = rangeEnd
                        } else {
                            HunterEngine.setRange(fullStart, rangeEnd)
                            currentRangeStart = fullStart
                            currentRangeEnd = rangeEnd
                        }
                    }
                } else {
                    threads = (sbThreads?.progress ?: 3) + 1
                    cpu     = (sbCpu?.progress ?: 70) + 10
                }
                HunterEngine.setMode(if (puzzleMode) 1 else 0)
                val debugRange = "start=${etRangeStart?.text} end=${etRangeEnd?.text} threads=$threads cpu=$cpu"
                val batchNow = HunterEngine.getBatchSize()
                Toast.makeText(this, "threads=$threads cpu=$cpu batch=$batchNow", Toast.LENGTH_LONG).show()
                HunterEngine.startHunting(threads, cpu)
                Toast.makeText(this, "2/3 startHunting OK", Toast.LENGTH_SHORT).show()
                activeToggleBtn = callerBtn
                @Suppress("UNCHECKED_CAST")
                val bg2 = callerBtn?.tag as? Array<GradientDrawable>
                callerBtn?.text = s.stop
                if (bg2 != null && bg2.size > 1) callerBtn?.background = bg2[1]
                Toast.makeText(this, "3c btn updated OK", Toast.LENGTH_SHORT).show()
                try {
                    startForegroundService(Intent(this, HunterService::class.java))
                } catch (ex: Exception) {
                    try { startService(Intent(this, HunterService::class.java)) } catch (ex2: Exception) {}
                }
                Toast.makeText(this, "3d service OK - DONE", Toast.LENGTH_SHORT).show()

            }
        } catch (e: Exception) {
            val errMsg = "${e.javaClass.simpleName}: ${e.message}"
            Toast.makeText(this, errMsg, Toast.LENGTH_LONG).show()
            java.io.File(filesDir, "crash_log.txt").appendText("\ndoToggle: $errMsg\n${e.stackTraceToString()}\n")
        }
    }

    private fun pickCsv() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, 1001)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1001 && res == RESULT_OK) {
            val uri = data?.data ?: return
            // Obtener nombre original para preservar extensión .bin o .csv
            val cursor = contentResolver.query(uri, null, null, null, null)
            val origName = cursor?.use {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                it.moveToFirst(); if (idx >= 0) it.getString(idx) else "dataset.csv"
            } ?: "dataset.csv"
            cursor?.close()
            val dest = File(getExternalFilesDir(null), origName)
            contentResolver.openInputStream(uri)?.use { it.copyTo(dest.outputStream()) }
            csvPath = dest.absolutePath
            prefs.edit().putString("csvPath", csvPath).apply()
            HunterEngine.loadCsv(csvPath)
            tvStatus?.text = dest.name
            tvCsvName?.text = dest.name
            tvCsvName?.setTextColor(0xFF00FF88.toInt())
            tvQuickCsv?.text = dest.nameWithoutExtension.take(7)
            Toast.makeText(this, "Dataset cargado: ${dest.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportLog() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val f = File(dir, "wallet_hunter_export_$ts.txt")
        val sb = StringBuilder()
        sb.appendLine("=== WALLET HUNTER EXPORT ===")
        sb.appendLine("Fecha: ${java.util.Date()}")
        sb.appendLine("Dispositivo: ${android.os.Build.MODEL}")
        sb.appendLine()

        // Incluir coincidencias
        val coincidencias = File(dir, "coincidencias.txt")
        if (coincidencias.exists()) {
            sb.appendLine("=== MATCHES ENCONTRADOS ===")
            sb.appendLine(coincidencias.readText())
        } else {
            sb.appendLine("=== SIN MATCHES AÚN ===")
        }

        // Incluir crash log si existe
        val crashLog = File(filesDir, "crash_log.txt")
        if (crashLog.exists()) {
            sb.appendLine("=== CRASH LOG ===")
            sb.appendLine(crashLog.readText().takeLast(2000))
        }

        f.writeText(sb.toString())

        // Compartir el archivo
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "${packageName}.provider", f
        )
        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Wallet Hunter Export")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(share, "Exportar log"))
        Toast.makeText(this, "Log exportado: ${f.name}", Toast.LENGTH_SHORT).show()
    }

    private fun showWalletSelector() {
        startActivity(Intent(this, WalletActivity::class.java))
    }

    private fun checkPuzzleBalance(addr: String, onResult: (Long) -> Unit) {
        Thread {
            try {
                val bal = ElectrumClient.getBalance(addr)
                val total = (bal?.confirmed ?: 0L) + (bal?.unconfirmed ?: 0L)
                runOnUiThread { onResult(total) }
            } catch (e: Exception) { runOnUiThread { onResult(-1L) } }
        }.start()
    }

    private fun applyPuzzle(p: PuzzleInfo) {
        etRangeStart?.setText(p.start)
        etRangeEnd?.setText(p.end)
        currentRangeStart = p.start
        currentRangeEnd = p.end
        etTarget?.setText(p.addr)
        tvPuzzleStatus?.text = "Puzzle #${p.num} — ${p.btc} BTC"
        // Resetear contadores al cambiar puzzle
        sessionStartTime = 0L
        sessionStartCount = 0L
        tvCountPuzzle?.text = "0"
        tvTimePuzzle?.text  = "00:00:00"
        tvPctPuzzle?.text   = "0.000000000000000000%"
        tvBlockProgress?.text = "Bloques: 0/—"
        // No llamar setRange durante construcción — solo cuando engine está corriendo
    }

    private fun autoSelectPuzzle() {
        // Auto-select disabled: user selects puzzle manually via chip selector
        runOnUiThread {
            tvPuzzleStatus?.text = "Selecciona un puzzle"
            tvPuzzleStatus?.setTextColor(0xFF5A607A.toInt())
        }
    }



    // ── Export / Import Configuración ────────────────────────────────────────
    private fun exportConfig() {
        try {
            val cfg = org.json.JSONObject().apply {
                put("threads",        prefs.getInt("threads", 3))
                put("cpu",            prefs.getInt("cpu", 70))
                put("puzzle_threads", prefs.getInt("puzzle_threads", 3))
                put("puzzle_cpu",     prefs.getInt("puzzle_cpu", 70))
                put("fastMode",       prefs.getBoolean("fastMode", false))
                put("sched_start",    prefs.getInt("sched_start", -1))
                put("sched_stop",     prefs.getInt("sched_stop", -1))
                put("batch_size",     prefs.getInt("batch_size", 16000))
                put("big_cores",      prefs.getString("big_cores", "4,5,6,7"))
                put("hw_detected",    prefs.getBoolean("hw_detected", false))
                put("exported_at",    System.currentTimeMillis())
                put("device",         android.os.Build.MODEL)
                put("app_version",    "1.0")
            }
            val json = cfg.toString(2)
            val file = java.io.File(getExternalFilesDir(null), "wallet_hunter_config.json")
            file.writeText(json)

            // Compartir archivo
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.provider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Wallet Hunter Config")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Exportar configuración"))
        } catch (e: Exception) {
            Toast.makeText(this, "Error exportando: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importConfig() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/json"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQ_IMPORT_CONFIG)
    }

    private fun applyImportedConfig(uri: android.net.Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
            val cfg = org.json.JSONObject(json)

            val edit = prefs.edit()
            if (cfg.has("threads"))        edit.putInt("threads",        cfg.getInt("threads"))
            if (cfg.has("cpu"))            edit.putInt("cpu",            cfg.getInt("cpu"))
            if (cfg.has("puzzle_threads")) edit.putInt("puzzle_threads", cfg.getInt("puzzle_threads"))
            if (cfg.has("puzzle_cpu"))     edit.putInt("puzzle_cpu",     cfg.getInt("puzzle_cpu"))
            if (cfg.has("fastMode"))       edit.putBoolean("fastMode",   cfg.getBoolean("fastMode"))
            if (cfg.has("sched_start"))    edit.putInt("sched_start",    cfg.getInt("sched_start"))
            if (cfg.has("sched_stop"))     edit.putInt("sched_stop",     cfg.getInt("sched_stop"))
            if (cfg.has("batch_size"))     edit.putInt("batch_size",     cfg.getInt("batch_size"))
            if (cfg.has("big_cores"))      edit.putString("big_cores",   cfg.getString("big_cores"))
            edit.apply()

            // Aplicar inmediatamente
            sbThreads?.progress     = prefs.getInt("threads", 3)
            sbCpu?.progress         = prefs.getInt("cpu", 70)
            sbThreadsPuzzle?.progress = prefs.getInt("puzzle_threads", 3)
            sbCpuPuzzle?.progress   = prefs.getInt("puzzle_cpu", 70)
            updateLabels(); updatePuzzleLabels()

            // Restaurar scheduler
            scheduledStart = prefs.getInt("sched_start", -1)
            scheduledStop  = prefs.getInt("sched_stop", -1)
            if (scheduledStart >= 0) startScheduler()

            val device = cfg.optString("device", "desconocido")
            val date = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.US)
                .format(java.util.Date(cfg.optLong("exported_at", 0)))
            Toast.makeText(this,
                "✓ Config importada\nDispositivo: $device\nFecha: $date",
                Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Error importando: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Modo Scheduled ────────────────────────────────────────────────────────
    private var scheduledStart: Int = -1  // hora de inicio (-1 = deshabilitado)
    private var scheduledStop:  Int = -1  // hora de parada
    private var schedulerRunning = false

    private fun startScheduler() {
        if (schedulerRunning) return
        schedulerRunning = true
        Thread {
            while (schedulerRunning) {
                try {
                    val cal = java.util.Calendar.getInstance()
                    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                    if (scheduledStart >= 0 && scheduledStop >= 0) {
                        val shouldRun = if (scheduledStart <= scheduledStop) {
                            hour in scheduledStart until scheduledStop
                        } else {
                            hour >= scheduledStart || hour < scheduledStop
                        }
                        if (shouldRun && !HunterEngine.isRunning()) {
                            runOnUiThread {
                                puzzleMode = false
                                HunterEngine.setMode(0)
                                doToggle(btnToggle)
                            }
                        } else if (!shouldRun && HunterEngine.isRunning()) {
                            runOnUiThread {
                                doToggle(activeToggleBtn)
                            }
                        }
                    }
                } catch (e: Exception) {}
                Thread.sleep(60_000) // revisar cada minuto
            }
        }.start()
    }

    private fun showSchedulerDialog() {
        val hours = (0..23).map { "%02d:00".format(it) }.toTypedArray()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        root.addView(TextView(this).apply {
            text = "Inicio del scan (hora):"
            textSize = 12f; setTextColor(AppTheme.TXT_PRI)
            setPadding(0, 0, 0, dp(4))
        })
        val startPicker = android.widget.NumberPicker(this).apply {
            minValue = 0; maxValue = 23
            displayedValues = hours
            value = if (scheduledStart >= 0) scheduledStart else 22
        }
        root.addView(startPicker)
        root.addView(TextView(this).apply {
            text = "Parada del scan (hora):"
            textSize = 12f; setTextColor(AppTheme.TXT_PRI)
            setPadding(0, dp(12), 0, dp(4))
        })
        val stopPicker = android.widget.NumberPicker(this).apply {
            minValue = 0; maxValue = 23
            displayedValues = hours
            value = if (scheduledStop >= 0) scheduledStop else 6
        }
        root.addView(stopPicker)
        root.addView(TextView(this).apply {
            text = "Ejemplo: 22:00 → 06:00 = escanea de noche"
            textSize = 10f; setTextColor(AppTheme.TXT_MUTED)
            typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("⏰ Scan Programado")
            .setView(root)
            .setPositiveButton("Activar") { _, _ ->
                scheduledStart = startPicker.value
                scheduledStop  = stopPicker.value
                prefs.edit()
                    .putInt("sched_start", scheduledStart)
                    .putInt("sched_stop",  scheduledStop)
                    .apply()
                startScheduler()
                Toast.makeText(this,
                    "Scan programado: %02d:00 → %02d:00".format(scheduledStart, scheduledStop),
                    Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Desactivar") { _, _ ->
                scheduledStart = -1; scheduledStop = -1
                schedulerRunning = false
                prefs.edit().remove("sched_start").remove("sched_stop").apply()
                Toast.makeText(this, "Scan programado desactivado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Canal normal
            val ch = android.app.NotificationChannel(
                "hunter", "Hunter", android.app.NotificationManager.IMPORTANCE_LOW
            )
            // Canal de match — alta prioridad con sonido
            val matchCh = android.app.NotificationChannel(
                "hunter_match", "Match Found!",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                enableLights(true)
                lightColor = AppTheme.AMBER
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(ch)
            nm.createNotificationChannel(matchCh)
        }
    }

    private fun sendMatchNotification(addr: String, wif: String) {
        try {
            // Vibración
            val vib = getSystemService(android.os.Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib?.vibrate(android.os.VibrationEffect.createWaveform(
                    longArrayOf(0, 500, 200, 500, 200, 500), -1
                ))
            }
            // Notificación
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val intent = android.app.PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notif = androidx.core.app.NotificationCompat.Builder(this, "hunter_match")
                .setSmallIcon(android.R.drawable.star_on)
                .setContentTitle("🎯 MATCH ENCONTRADO!")
                .setContentText("Addr: ${addr.take(20)}...")
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle()
                    .bigText("Dirección: $addr\nWIF: $wif"))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true)
                .setContentIntent(intent)
                .setColor(AppTheme.AMBER)
                .build()
            nm.notify(NOTIF_ID, notif)
        } catch (e: Exception) {}
    }

    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, i: Intent?) {
                val pct = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return
                tvBattery?.text = "BAT $pct%"
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private var appPausedTime = 0L
    private var thermalThrottleEnabled = true
    private var lastThermalCheck = 0L
    private var originalCpuLimit = 70
    private var isThrottled = false
    private var tvThermal: TextView? = null
    private val LOCK_TIMEOUT_MS = 15_000L // 15 seg en background

    override fun onResume() {
        super.onResume()
        handler.post(updater)
        val elapsed = System.currentTimeMillis() - appPausedTime
        if (appPausedTime > 0 && elapsed > LOCK_TIMEOUT_MS && WalletManager.hasPin(this)) {
            PinAuthHelper.show(this) { ok -> if (!ok) finish() }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updater)
        appPausedTime = System.currentTimeMillis()
        savePuzzleCheckpoint()
    }

    override fun onDestroy() {
        super.onDestroy()
        savePuzzleCheckpoint()
        batteryReceiver?.let { unregisterReceiver(it) }
    }

    private fun savePuzzleCheckpoint() {
        if (!puzzleMode) return
        try {
            val lastKey = HunterEngine.getLastKey()
            if (lastKey.isEmpty() || lastKey == "0".repeat(64)) return
            val puzzlePrefs = getSharedPreferences("puzzle_checkpoint", MODE_PRIVATE)
            val puzzleNum = puzzles.firstOrNull { it.start == currentRangeStart }?.num ?: puzzles.getOrNull(puzzleSpinner?.selectedItemPosition ?: 0)?.num ?: return
            puzzlePrefs.edit()
                .putString("last_key_$puzzleNum", lastKey)
                .putLong("last_time_$puzzleNum", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {}
    }


}
