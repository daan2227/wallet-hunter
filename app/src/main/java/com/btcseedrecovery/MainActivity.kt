package com.btcseedrecovery

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
import com.btcseedrecovery.recovery.RecoveryEngine
import com.btcseedrecovery.recovery.RecoveryParser
import com.btcseedrecovery.recovery.ParseResult

class SpeedChartView(context: android.content.Context) : android.view.View(context) {
    private val maxPoints = 60
    private val wpsPoints = ArrayDeque<Float>()
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCCCCCC.toInt(); strokeWidth = 1.5f; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val paintLbl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF505050.toInt(); textSize = 18f; typeface = Typeface.MONOSPACE
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
            shader=LinearGradient(0f,0f,0f,h,0x14FFFFFF,0x00FFFFFF,Shader.TileMode.CLAMP)
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
    private val REQ_IMPORT_PROGRESS = 1003
    private val REQ_INSTALL_BINARY = 1004
    private var currentBlockId: String = ""
    private var tvBlockProgress: TextView? = null
    private var etTarget: EditText? = null
    private var layoutPuzzle: LinearLayout? = null
    private var rbBip39: Button? = null
    private var rbPuzzle: Button? = null
    private var csvPath: String = ""
    private var s = Strings.EN
    private var puzzleMode = false
    private var selectedScanMode = 0 // 0=BIP39, 2=RawKey
    private var fastScanRow: android.view.View? = null
    private var tvBinInfoRef: TextView? = null
    private var tvDatasetStat: TextView? = null
    private var peakWps: Double = 0.0
    private var avgWpsSum: Double = 0.0
    private var avgWpsCount: Long = 0
    private val numberFmt = java.text.NumberFormat.getNumberInstance(java.util.Locale.US)
    private var cachedPuzzleLabel: String = ""
    private var cachedPuzzleLabelForStart: String = ""
    private var tvAvgWps: TextView? = null
    private var tvPeakWps: TextView? = null
    private var tvPeakWpsPuzzle: TextView? = null
    private var watchdogEnabled = false
    private var lastKnownRunning = false
    private var watchdogRestarts = 0
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
        // Capturar crashes globales. Las trazas pueden arrastrar estado sensible,
        // así que se quedan en almacenamiento interno (sandbox) y nunca en el
        // externo, que es legible por otras apps con permiso de lectura.
        val crashLog = java.io.File(filesDir, "crash_log.txt")
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                if (crashLog.length() > 256 * 1024) crashLog.delete()   // evitar crecimiento sin límite
                crashLog.appendText("\n=== $ts ===\n${e.javaClass.name}\n${e.message}\n${e.stackTraceToString()}\n")
                // Limpiar restos de versiones que escribían en almacenamiento externo
                getExternalFilesDir(null)?.let { java.io.File(it, "crash_log.txt").delete() }
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

        var recoveryScroll: ScrollView? = null
        var walletScroll: ScrollView? = null
        try {
            recoveryScroll = buildRecoveryTab()
            walletScroll   = buildWalletTab()
        } catch (e: Exception) {
            try {
                java.io.File(filesDir, "crash_log.txt")
                    .writeText("CRASH: ${e.javaClass.simpleName}\n${e.message}\n${e.stackTraceToString()}")
            } catch (ex: Exception) {}
            finish(); return
        }

        recoveryScroll?.let { cf.addView(it) }
        walletScroll?.let   { cf.addView(it) }
        contentFrame = cf

        // ── Header + Drawer ───────────────────────────────────────────────────
        val header = buildHeader()
        root.addView(header)

        val drawerLayout = buildDrawerLayout()
        root.addView(drawerLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)

        tabPages = listOfNotNull(recoveryScroll, walletScroll)
        tabBtns  = listOf<TextView>()
        goTab(0)

        // Init
        try {
            setupNotificationChannel()
            watchdogEnabled = prefs.getBoolean("watchdog", false)

            if (!prefs.getBoolean("hw_detected", false)) {
                val profile = detectHardware()
                applyHardwareProfile(profile)
                prefs.edit().putBoolean("hw_detected", true).apply()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    showHardwareInfo()
                }, 1000)
            }
            updateLabels()
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Init error: ${e.message}", e)
            prefs.edit().putBoolean("hw_detected", true).apply()
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
            setBackgroundColor(0xFF090909.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            )
            setPadding(dp(16), 0, dp(16), 0)
            elevation = dp(4).toFloat()
        }

        // Logo icon
        val logoIcon = TextView(this).apply {
            text = "₿"
            textSize = 15f
            setTextColor(0xFFEFEFEF.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(0xFF1C1C1C.toInt())
                setStroke(1, 0xFF303030.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).also {
                it.gravity = Gravity.CENTER_VERTICAL
            }
        }
        header.addView(logoIcon)

        // Logo text
        val logoText = TextView(this).apply {
            text = "BTC Seed Recovery"
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = 0.02f
            setTextColor(0xFFEFEFEF.toInt())
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
                setColor(0xFF868686.toInt())
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
                setColor(0xFF141414.toInt())
                setStroke(1, 0xFF242424.toInt())
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
                setBackgroundColor(0xFFEFEFEF.toInt())
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
            setBackgroundColor(0xFF242424.toInt())
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
            setBackgroundColor(0xFF141414.toInt())
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
                setColor(0xFF141414.toInt())
                setStroke(0, 0)
            }
        }
        val dTitle = TextView(this).apply {
            text = "BTC Seed Recovery"
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(0xFFEFEFEF.toInt())
        }
        val dSub = TextView(this).apply {
            text = "Recupera tu propia wallet"
            textSize = 10f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextColor(0xFF868686.toInt())
            setPadding(0, dp(4), 0, 0)
        }
        drawerHeader.addView(dTitle)
        drawerHeader.addView(dSub)

        // Divider
        val divider = android.view.View(this).apply {
            setBackgroundColor(0xFF242424.toInt())
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
            NavItem("⚷", "Recovery", 0),
            NavItem("◈", "Wallet", 1),
            NavItem("📊", "Stats", -3, true)
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
                    setColor(if (item.idx == 0) 0x14FFFFFF.toInt() else 0x00000000.toInt())
                    if (item.idx == 0) setStroke(1, 0x28FFFFFF.toInt())
                }
                tag = "nav_${item.idx}"
                setOnClickListener {
                    when {
                        item.idx == -3 -> {
                            startActivity(android.content.Intent(this@MainActivity, StatsActivity::class.java))
                            closeDrawer()
                        }
                        item.idx == 1 -> {
                            if (WalletManager.hasPin(this@MainActivity) && !PinAuthHelper.isSessionValid()) {
                                PinAuthHelper.show(this@MainActivity) { ok -> if (ok) { goTab(1); closeDrawer() } }
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
                setTextColor(if (item.idx == 0) ACCENT else 0xFF868686.toInt())
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
            setBackgroundColor(0xFF242424.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        val footer = TextView(this).apply {
            text = "v2.4 · Wallet Hunter"
            textSize = 10f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextColor(0xFF555555.toInt())
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
            bg?.setColor(if (isActive) 0x14FFFFFF.toInt() else 0x00000000.toInt())
            bg?.setStroke(if (isActive) 1 else 0, if (isActive) 0x28FFFFFF.toInt() else 0x00000000.toInt())
            val label = row.getChildAt(1) as? TextView
            label?.setTextColor(if (isActive) ACCENT else 0xFF868686.toInt())
        }
    }

    // ── BUILD SCAN TAB ────────────────────────────────────────────────────────
    private fun buildScanTab(): ScrollView {
        val ACCENT  = 0xFF00C896.toInt()
        val ACCENT2 = 0xFF6EA8FE.toInt()
        val LIME    = 0xFF39FF14.toInt()  // kept for engine compat

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF090909.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF090909.toInt())
            setPadding(0, 0, 0, dp(80))
        }

        // ── HERO SPEED CARD ───────────────────────────────────────────────
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF111111.toInt())
                setStroke(1, 0xFF222222.toInt())
            }
            setPadding(dp(24), dp(24), dp(24), dp(16))
        }

        // Label
        heroCard.addView(TextView(this).apply {
            text = "VELOCIDAD"
            textSize = 9f
            setTextColor(0xFF505050.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.2f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        })

        // Big speed number
        tvWps = TextView(this).apply {
            text = "0.0"
            textSize = 56f
            setTextColor(0xFFEFEFEF.toInt())
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        heroCard.addView(tvWps)
        tvPeakWps = TextView(this).apply {
            text = ""; textSize = 9f; setTextColor(0xFF868686.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(2) }
        }
        heroCard.addView(tvPeakWps)

        // Promedio de velocidad
        tvAvgWps = TextView(this).apply {
            text = ""; textSize = 9f; setTextColor(0xFF868686.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        heroCard.addView(tvAvgWps)

        heroCard.addView(TextView(this).apply {
            text = "K KEYS / SEG"
            textSize = 10f
            setTextColor(0xFF505050.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.15f
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
                    setColor(0xFF141414.toInt())
                    cornerRadius = dp(16).toFloat()
                    setStroke(1, 0xFF242424.toInt())
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
            setTextColor(0xFF868686.toInt())
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

        tvCount = statValue("0", 0xFF6EA8FE.toInt())
        val tvBlocksStat = statValue("0", 0xFFEFEFEF.toInt())
        val tvProgressStat = statValue("0.00%", ACCENT)
        tvTime = statValue("00:00", 0xFFEFEFEF.toInt())

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
        gridRow1.addView(statCard(0xFF242424.toInt()) {
            addView(statLabel("SESIÓN"))
            val tvSessionStat = TextView(this@MainActivity).apply {
                text = "—"; textSize = 13f; setTextColor(0xFFEFEFEF.toInt())
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                letterSpacing = -0.02f
                maxLines = 2
            }
            tvBinInfoRef = tvSessionStat
            addView(tvSessionStat)
        })
        gridRow2.addView(statCard(ACCENT) {
            addView(statLabel("DATASET"))
            val tvBinStat = TextView(this@MainActivity).apply {
                val f = if (csvPath.isNotEmpty()) java.io.File(csvPath) else null
                text = if (f != null && f.exists()) {
                    val h = f.length() / 20
                    if (h >= 1_000_000) "${"%.1f".format(h/1e6)}M" else "${h/1000}K"
                } else "—"
                textSize = 28f; setTextColor(ACCENT)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                letterSpacing = -0.02f
            }
            tvDatasetStat = tvBinStat
            addView(tvBinStat)
        })
        gridRow2.addView(statCard(0xFF242424.toInt()) {
            addView(statLabel("TIEMPO"))
            addView(tvTime)
        })

        heroCard.addView(gridRow1)
        heroCard.addView(gridRow2)

        // progress bar removed
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
                    setColor(0xFF141414.toInt()); cornerRadius = dp(14).toFloat()
                    setStroke(1, 0xFF242424.toInt())
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
                setTextColor(0xFFEFEFEF.toInt())
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val arrowTv = TextView(this).apply {
                text = "›"; textSize = 18f; setTextColor(0xFF555555.toInt())
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
                text = "Dataset"; textSize = 10f; setTextColor(0xFF868686.toInt())
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                setPadding(0, dp(4), 0, dp(4))
            })
            val dataRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            btnCsv = Button(this@MainActivity).apply {
                text = "Load CSV"; textSize = 10f
                setTextColor(0xFFEFEFEF.toInt())
                background = GradientDrawable().apply {
                    setColor(0xFF1A1A1A.toInt()); cornerRadius = dp(8).toFloat()
                    setStroke(1, 0xFF383838.toInt())
                }
                setPadding(dp(12), 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
                setOnClickListener { pickCsv() }
            }
            val tvCsvLocal = TextView(this@MainActivity).apply {
                text = if (csvPath.isNotEmpty() && java.io.File(csvPath).exists())
                    java.io.File(csvPath).name else "Sin archivo"
                setTextColor(0xFF868686.toInt()); textSize = 10f; typeface = Typeface.MONOSPACE
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(10), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            tvCsvName = tvCsvLocal
            dataRow.addView(btnCsv); dataRow.addView(tvCsvLocal)
            addView(dataRow)

            // Indicador detallado del archivo .bin
            val tvBinInfo = TextView(this@MainActivity).apply {
                val f = if (csvPath.isNotEmpty()) java.io.File(csvPath) else null
                text = if (f != null && f.exists()) {
                    val mb = f.length() / 1024 / 1024
                    val hashes = f.length() / 20
                    "📦 ${f.name}  ·  ${numberFmt.format(hashes)} hashes  ·  ${mb}MB"
                } else {
                    "📦 Sin dataset cargado"
                }
                textSize = 9f
                setTextColor(if (csvPath.isNotEmpty() && java.io.File(csvPath).exists())
                    0xFF00C896.toInt() else 0xFF555555.toInt())
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF0C0C0C.toInt()); cornerRadius = dp(8).toFloat()
                    setStroke(1, 0xFF242424.toInt())
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            tvDatasetStat = tvBinInfo
            addView(tvBinInfo)

            addView(TextView(this@MainActivity).apply {
                text = "Threads"; textSize = 10f; setTextColor(0xFF868686.toInt())
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                setPadding(0, dp(10), 0, dp(2))
            })
            tvThreads = TextView(this@MainActivity).apply { setTextColor(0xFFEFEFEF.toInt()); textSize = 11f }
            addView(tvThreads)
            sbThreads = SeekBar(this@MainActivity).apply {
                max = 7; progress = prefs.getInt("threads", 3)
                setOnSeekBarChangeListener(mkSbl { updateLabels() })
            }
            addView(sbThreads)

            addView(TextView(this@MainActivity).apply {
                text = "CPU Limit"; textSize = 10f; setTextColor(0xFF868686.toInt())
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                setPadding(0, dp(8), 0, dp(2))
            })
            tvCpu = TextView(this@MainActivity).apply { setTextColor(0xFFEFEFEF.toInt()); textSize = 11f }
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
                visibility = if (selectedScanMode == 2) android.view.View.GONE else android.view.View.VISIBLE
            }
            fastScanRow = fastRow
            fastRow.addView(TextView(this@MainActivity).apply {
                text = "Fast Scan Mode"; textSize = 12f; setTextColor(0xFFEFEFEF.toInt())
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

            // Actualizar visibilidad del fastRow cuando cambia el modo


            listOf(

                Triple(if (watchdogEnabled) "🐕 Watchdog ON" else "🐕 Watchdog OFF",
                    "Auto-reinicio si el scan se detiene", {
                    watchdogEnabled = !watchdogEnabled
                    prefs.edit().putBoolean("watchdog", watchdogEnabled).apply()
                    android.widget.Toast.makeText(this@MainActivity,
                        if (watchdogEnabled) "Watchdog activado" else "Watchdog desactivado",
                        android.widget.Toast.LENGTH_SHORT).show()
                }),
                Triple("⏰", "Programar Scan", { showSchedulerDialog() }),
                Triple("⚙", "Auto-configurar Hardware", { showHardwareInfo() }),
                Triple("🔔", "Configurar Alertas", { showAlertSettings() }),

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
                    text = lbl; textSize = 12f; setTextColor(0xFFEFEFEF.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(this@MainActivity).apply { text = "›"; textSize = 16f; setTextColor(0xFF555555.toInt()) })
                addView(row)
            }
        })

        // ── SECTION: Red Multi-Dispositivo ────────────────────────────────
        page.addView(collapsibleSection("🌐", "Red Multi-Dispositivo (Cluster)") {
            addView(TextView(this@MainActivity).apply {
                text = "MASTER_IP: ${NetworkManager.getLocalIp(this@MainActivity)}"
                textSize = 11f; setTextColor(0xFF868686.toInt()); typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, dp(10))
            })
            val row1 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            val netBtn = { txt: String, action: () -> Unit ->
                Button(this@MainActivity).apply {
                    text = txt; textSize = 11f; setTextColor(0xFFEFEFEF.toInt())
                    background = GradientDrawable().apply {
                        setColor(0xFF171C2C.toInt()); setStroke(1, 0xFF242424.toInt())
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
            row2.addView(netBtn("Mode: Worker") { })
            row2.addView(netBtn("Connect") { })
            addView(row2)
            val tvNetLog = TextView(this@MainActivity).apply {
                text = "Log:"
                textSize = 10f; setTextColor(0xFF868686.toInt()); typeface = Typeface.MONOSPACE
                background = GradientDrawable().apply { setColor(0xFF0C0C0C.toInt()); cornerRadius = dp(8).toFloat() }
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



        // ── START / STOP BUTTON ───────────────────────────────────────────
        val startBg = GradientDrawable().apply {
            setColor(0xFF1A1A1A.toInt())
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), 0xFFEFEFEF.toInt())
        }
        val stopRed = GradientDrawable().apply {
            setColor(0xFF1A0808.toInt())
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), 0xFFF04040.toInt())
        }

        // ── MODO SELECTOR ─────────────────────────────────────────────
        val modeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF141414.toInt()); cornerRadius = dp(14).toFloat()
                setStroke(1, 0xFF242424.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(12), dp(8), dp(12), 0) }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        modeCard.addView(TextView(this).apply {
            text = "MODO DE ESCANEO"; textSize = 9f; setTextColor(0xFF868686.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        })

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // selectedScanMode es variable de clase
        val modeBtns = mutableListOf<LinearLayout>()

        data class ScanMode(val label: String, val sub: String, val mode: Int)
        val scanModes = listOf(
            ScanMode("BIP39", "Seed phrases", 0),
            ScanMode("RAW KEY", "Claves directas", 2)
        )

        scanModes.forEachIndexed { idx, sm ->
            val btn = TextView(this).apply {
                textSize = 12f; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx == 0) marginEnd = dp(8)
                }
                setPadding(dp(8), dp(10), dp(8), dp(10))
                isClickable = true; isFocusable = true
            }
            // Layout interno con label + sub
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                isClickable = false
            }
            inner.addView(TextView(this).apply {
                text = sm.label; textSize = 12f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(if (idx == 0) 0xFF00C896.toInt() else 0xFF868686.toInt())
            })
            inner.addView(TextView(this).apply {
                text = sm.sub; textSize = 9f
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setTextColor(0xFF555555.toInt())
            })

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (idx == 0) 0x14FFFFFF.toInt() else 0xFF171C2C.toInt())
                    cornerRadius = dp(12).toFloat()
                    setStroke(1, if (idx == 0) 0x28FFFFFF.toInt() else 0xFF242424.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                    if (idx == 0) marginEnd = dp(8)
                }
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isClickable = true; isFocusable = true
                addView(inner)
                setOnClickListener {
                    selectedScanMode = sm.mode
                    modeBtns.forEachIndexed { i, b ->
                        val active = i == idx
                        (b.background as android.graphics.drawable.GradientDrawable).apply {
                            setColor(if (active) 0x14FFFFFF.toInt() else 0xFF171C2C.toInt())
                            setStroke(1, if (active) 0x28FFFFFF.toInt() else 0xFF242424.toInt())
                        }
                        val lbl = (b as LinearLayout).getChildAt(0) as? LinearLayout
                        (lbl?.getChildAt(0) as? TextView)?.setTextColor(
                            if (active) 0xFF00C896.toInt() else 0xFF868686.toInt())
                    }
                }
            }
            modeBtns.add(card)
            modeRow.addView(card)
        }
        modeCard.addView(modeRow)

        // Info del modo seleccionado
        val tvModeInfo = TextView(this).apply {
            text = "BIP39: Genera seeds de 12/24 palabras y deriva wallets HD"
            textSize = 10f; setTextColor(0xFF868686.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        modeBtns.forEachIndexed { idx, b ->
            b.setOnClickListener {
                selectedScanMode = scanModes[idx].mode
                        try {
                            fastScanRow?.visibility = if (selectedScanMode == 2)
                                android.view.View.GONE else android.view.View.VISIBLE
                        } catch (e: Exception) {}
                        tvModeInfo.text = when (selectedScanMode) {
                    0 -> "BIP39: Genera seeds de 12/24 palabras y deriva wallets HD"
                    2 -> "RAW KEY: Genera claves privadas aleatorias puras (~10x más rápido)"
                    else -> ""
                }
                modeBtns.forEachIndexed { i, c ->
                    val active = i == idx
                    (c.background as android.graphics.drawable.GradientDrawable).apply {
                        setColor(if (active) 0x14FFFFFF.toInt() else 0xFF171C2C.toInt())
                        setStroke(1, if (active) 0x28FFFFFF.toInt() else 0xFF242424.toInt())
                    }
                    val lbl = (c as LinearLayout).getChildAt(0) as? LinearLayout
                    (lbl?.getChildAt(0) as? TextView)?.setTextColor(
                        if (active) 0xFF00C896.toInt() else 0xFF868686.toInt())
                }
            }
        }
        modeCard.addView(tvModeInfo)
        page.addView(modeCard)

        btnToggle = Button(this).apply {
            text = s.start
            textSize = 16f; setTextColor(0xFFEFEFEF.toInt())
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.1f; isAllCaps = true
            background = startBg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60)
            ).apply { setMargins(dp(12), dp(16), dp(12), dp(8)) }
            setOnClickListener {
                puzzleMode = false
                HunterEngine.setMode(selectedScanMode)

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
        val ACCENT2 = 0xFF6EA8FE.toInt()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF090909.toInt())
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF090909.toInt())
            setPadding(dp(12), dp(16), dp(12), dp(80))
        }

        // ── HELPERS ───────────────────────────────────────────────────────
        fun pCard(marginTop: Int = 10): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF141414.toInt()); cornerRadius = dp(16).toFloat()
                setStroke(1, 0xFF242424.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(marginTop), 0, 0) }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        fun sectionLabel(text: String) = TextView(this).apply {
            this.text = text; textSize = 9f; setTextColor(0xFF868686.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        fun styledInput(hint: String, color: Int = 0xFFEFEFEF.toInt()): EditText =
            EditText(this).apply {
                this.hint = hint; setTextColor(color); setHintTextColor(0xFF555555.toInt())
                textSize = 11f; typeface = Typeface.MONOSPACE
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF171C2C.toInt()); setStroke(1, 0xFF242424.toInt())
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
                    setColor(0xFF141414.toInt()); cornerRadius = dp(14).toFloat()
                    setStroke(1, 0xFF242424.toInt())
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
                text = title; textSize = 13f; setTextColor(0xFFEFEFEF.toInt())
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val arrowTv = TextView(this).apply {
                text = "›"; textSize = 18f; setTextColor(0xFF555555.toInt())
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
            textSize = 22f; setTextColor(0xFFEFEFEF.toInt())
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        })
        page.addView(TextView(this).apply {
            text = "Selecciona el puzzle objetivo"
            textSize = 12f; setTextColor(0xFF868686.toInt())
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
                setColor(0x14FFFFFF.toInt()); setStroke(1, 0x2A00C896.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        page.addView(tvPuzzleStatus)

        // ── PROGRESO VISUAL ───────────────────────────────────────────────
        val progressCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF141414.toInt()); cornerRadius = dp(14).toFloat()
                setStroke(1, 0xFF242424.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val progressHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val tvProgressPct = TextView(this).apply {
            text = "0.00%"; textSize = 11f; setTextColor(ACCENT)
            typeface = Typeface.create("monospace", Typeface.BOLD)
        }
        progressHeader.addView(TextView(this).apply {
            text = "COBERTURA DEL RANGO"; textSize = 9f; setTextColor(0xFF868686.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        progressHeader.addView(tvProgressPct)
        progressCard.addView(progressHeader)

        val progressTrack = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1A2030.toInt()); cornerRadius = dp(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
            ).apply { bottomMargin = dp(8) }
        }
        val progressBarPuzzle = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 10000; progress = 0
            progressDrawable = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(ACCENT2, ACCENT)
            ).apply { cornerRadius = dp(4).toFloat() }
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        progressTrack.addView(progressBarPuzzle)
        progressCard.addView(progressTrack)

        val tvProgressDetail = TextView(this).apply {
            text = "Bloques: —"; textSize = 10f; setTextColor(0xFF868686.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        progressCard.addView(tvProgressDetail)

        progressCard.addView(TextView(this).apply {
            text = "↺ Reiniciar progreso"; textSize = 9f; setTextColor(0xFF555555.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            gravity = Gravity.END; isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            setOnClickListener {
                val puzzleNum = puzzles.firstOrNull { it.start == currentRangeStart }?.num ?: return@setOnClickListener
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Reiniciar progreso")
                    .setMessage("¿Borrar el progreso del puzzle #$puzzleNum?")
                    .setPositiveButton("Reiniciar") { _, _ ->
                        getBlockPrefs().edit().remove("scanned_$puzzleNum").apply()
                        progressBarPuzzle.progress = 0
                        tvProgressPct.text = "0.00%"
                        tvProgressDetail.text = "Bloques: 0 / —"
                        android.widget.Toast.makeText(this@MainActivity, "Progreso reiniciado", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null).show()
            }
        })
        page.addView(progressCard)

        fun updatePuzzleProgress(puzzleNum: Int, rangeStart: String, rangeEnd: String) {
            Thread {
                try {
                    val start = java.math.BigInteger(rangeStart.trimStart('0').ifEmpty{"0"}, 16)
                    val end   = java.math.BigInteger(rangeEnd.trimStart('0').ifEmpty{"0"}, 16)
                    val total = end.subtract(start).divide(BLOCK_SIZE).toLong().coerceAtLeast(1)
                    val scanned = getBlockPrefs().getStringSet("scanned_$puzzleNum", emptySet())?.size?.toLong() ?: 0L
                    val pct = (scanned * 10000L / total).toInt().coerceIn(0, 10000)
                    val pctStr = "%.4f%%".format(scanned * 100.0 / total)
                    runOnUiThread {
                        progressBarPuzzle.progress = pct
                        tvProgressPct.text = pctStr
                        tvProgressDetail.text = "Bloques: $scanned / $total"
                    }
                } catch (e: Exception) {}
            }.start()
        }

        // Botón QR para dirección objetivo
        val btnQR = TextView(this).apply {
            text = "📷 Ver QR de dirección"
            textSize = 11f; gravity = Gravity.CENTER
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextColor(ACCENT2)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF141414.toInt()); cornerRadius = dp(10).toFloat()
                setStroke(1, 0xFF242424.toInt())
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            isClickable = true; isFocusable = true
            setOnClickListener {
                val addr = etTarget?.text?.toString()?.trim() ?: ""
                if (addr.isEmpty()) {
                    android.widget.Toast.makeText(this@MainActivity,
                        "Selecciona un puzzle primero", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                showAddressQR(addr)
            }
        }
        page.addView(btnQR)

        // Balance indicator - debajo del puzzle seleccionado
        val tvBalResult = TextView(this).apply {
            text = "Verificando balance..."
            textSize = 11f; setTextColor(0xFF868686.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF171C2C.toInt()); cornerRadius = dp(10).toFloat()
                setStroke(1, 0xFF242424.toInt())
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
            colStart.addView(TextView(this@MainActivity).apply { text = "Start"; textSize = 9f; setTextColor(0xFF868686.toInt()); typeface = Typeface.create("monospace", Typeface.NORMAL); setPadding(0,0,0,dp(4)) })
            etRangeStart = styledInput("0x...")
            colStart.addView(etRangeStart)
            val colEnd = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            colEnd.addView(TextView(this@MainActivity).apply { text = "End"; textSize = 9f; setTextColor(0xFF868686.toInt()); typeface = Typeface.create("monospace", Typeface.NORMAL); setPadding(0,0,0,dp(4)) })
            etRangeEnd = styledInput("0x...")
            colEnd.addView(etRangeEnd)
            rangeRow.addView(colStart); rangeRow.addView(colEnd)
            addView(rangeRow)
            addView(TextView(this@MainActivity).apply { text = "Target Address"; textSize = 9f; setTextColor(0xFF868686.toInt()); typeface = Typeface.create("monospace", Typeface.NORMAL); setPadding(0,0,0,dp(4)) })
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
        // Peak speed en esquina
        tvPeakWpsPuzzle = TextView(this).apply {
            text = ""; textSize = 9f; setTextColor(0xFF868686.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            gravity = Gravity.BOTTOM or Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) }
        }
        speedRow.addView(tvPeakWpsPuzzle)
        val speedUnit = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        speedUnit.addView(TextView(this).apply { text = "kKeys"; textSize = 11f; setTextColor(0xFF868686.toInt()); typeface = Typeface.create("monospace", Typeface.NORMAL) })
        speedUnit.addView(TextView(this).apply { text = "por seg"; textSize = 10f; setTextColor(0xFF555555.toInt()); typeface = Typeface.create("monospace", Typeface.NORMAL) })
        speedRow.addView(speedUnit)
        statsCard.addView(speedRow)

        fun miniStat(label: String, tv: TextView): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF171C2C.toInt()); cornerRadius = dp(10).toFloat(); setStroke(1, 0xFF242424.toInt())
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(TextView(this@MainActivity).apply { text = label; textSize = 8f; setTextColor(0xFF868686.toInt()); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.1f })
            addView(tv)
        }

        val tvCntP = TextView(this).apply { text = "0"; textSize = 16f; setTextColor(0xFFEFEFEF.toInt()); typeface = Typeface.create("monospace", Typeface.BOLD) }
        val tvTmP  = TextView(this).apply { text = "00:00:00"; textSize = 16f; setTextColor(0xFFEFEFEF.toInt()); typeface = Typeface.create("monospace", Typeface.BOLD) }
        tvCountPuzzle = tvCntP; tvTimePuzzle = tvTmP
        tvPctPuzzle = TextView(this).apply { text = "0.000%"; textSize = 13f; setTextColor(ACCENT2); typeface = Typeface.create("monospace", Typeface.BOLD) }
        tvBlockProgress = TextView(this).apply { text = "0/—"; textSize = 13f; setTextColor(0xFFEFEFEF.toInt()); typeface = Typeface.MONOSPACE }

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
                    setColor(if (idx == 1) 0x14FFFFFF.toInt() else 0xFF171C2C.toInt())
                    cornerRadius = dp(12).toFloat()
                    setStroke(1, if (idx == 1) 0x28FFFFFF.toInt() else 0xFF242424.toInt())
                }
                setTextColor(if (idx == 1) ACCENT else 0xFF868686.toInt())
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    if (idx < 2) marginEnd = dp(8)
                }
                isClickable = true; isFocusable = true
                setOnClickListener {
                    selectedPower = idx
                    powerBtns.forEachIndexed { i, b ->
                        val active = i == idx
                        (b.background as android.graphics.drawable.GradientDrawable).apply {
                            setColor(if (active) 0x14FFFFFF.toInt() else 0xFF171C2C.toInt())
                            setStroke(1, if (active) 0x28FFFFFF.toInt() else 0xFF242424.toInt())
                        }
                        b.setTextColor(if (active) ACCENT else 0xFF868686.toInt())
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

        // ── MODO DE ESCANEO ───────────────────────────────────────────────
        val scanModeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        scanModeRow.addView(TextView(this).apply {
            text = "MODO"; textSize = 9f; setTextColor(0xFF868686.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        val btnRandom = TextView(this).apply {
            text = "ALEATORIO"; textSize = 11f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x14FFFFFF.toInt()); cornerRadius = dp(10).toFloat()
                setStroke(1, 0x28FFFFFF.toInt())
            }
            setTextColor(ACCENT)
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) }
            isClickable = true; isFocusable = true
        }
        val btnSeq = TextView(this).apply {
            text = "SECUENCIAL"; textSize = 11f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF171C2C.toInt()); cornerRadius = dp(10).toFloat()
                setStroke(1, 0xFF242424.toInt())
            }
            setTextColor(0xFF868686.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
            isClickable = true; isFocusable = true
        }

        fun updateScanMode(sequential: Boolean) {
            HunterEngine.setSequential(sequential)
            listOf(btnRandom to !sequential, btnSeq to sequential).forEach { (btn, active) ->
                (btn.background as android.graphics.drawable.GradientDrawable).apply {
                    setColor(if (active) 0x14FFFFFF.toInt() else 0xFF171C2C.toInt())
                    setStroke(1, if (active) 0x28FFFFFF.toInt() else 0xFF242424.toInt())
                }
                btn.setTextColor(if (active) ACCENT else 0xFF868686.toInt())
            }
        }

        btnRandom.setOnClickListener { updateScanMode(false) }
        btnSeq.setOnClickListener { updateScanMode(true) }
        scanModeRow.addView(btnRandom); scanModeRow.addView(btnSeq)
        powerCard.addView(scanModeRow)

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
            text = "BATCH SIZE"; textSize = 9f; setTextColor(0xFF868686.toInt())
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
                text = lbl; textSize = 8f; setTextColor(0xFF555555.toInt())
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
                Triple("📤", "Exportar Progreso", { exportPuzzleProgress() }),
                Triple("📥", "Importar Progreso", { importPuzzleProgress() }),
                Triple("📤", "Exportar Config", { exportConfig() }),
                Triple("📥", "Importar Config", { importConfig() })
            ).forEach { (icon, label, action) ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, dp(10)); isClickable = true; isFocusable = true
                    setOnClickListener { action() }
                }
                row.addView(TextView(this@MainActivity).apply { text = icon; textSize = 16f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) } })
                row.addView(TextView(this@MainActivity).apply { text = label; textSize = 12f; setTextColor(0xFFEFEFEF.toInt()); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                row.addView(TextView(this@MainActivity).apply { text = "›"; textSize = 16f; setTextColor(0xFF555555.toInt()) })
                addView(row)
                addView(android.view.View(this@MainActivity).apply { setBackgroundColor(0xFF242424.toInt()); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1) })
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
            setColor(0xFF1A1A1A.toInt()); cornerRadius = dp(16).toFloat()
            setStroke(dp(1), 0xFFEFEFEF.toInt())
        }
        val stopRed = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF1A0808.toInt()); cornerRadius = dp(16).toFloat()
            setStroke(dp(1), 0xFFF04040.toInt())
        }
        btnPuzzleToggle = Button(this).apply {
            text = "▶  START PUZZLE"; textSize = 16f; setTextColor(0xFFEFEFEF.toInt())
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
                            tvBalResult.setTextColor(0xFF868686.toInt())
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
                        setColor(if (i == 0) 0x14FFFFFF.toInt() else 0xFF171C2C.toInt())
                        cornerRadius = dp(12).toFloat()
                        setStroke(1, if (i == 0) 0x28FFFFFF.toInt() else 0xFF242424.toInt())
                    }
                    setTextColor(if (i == 0) ACCENT else 0xFF868686.toInt())
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(40)).apply { marginEnd = dp(8) }
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        for (j in 0 until indivRow.childCount) {
                            val c = indivRow.getChildAt(j) as? TextView ?: continue
                            (c.background as android.graphics.drawable.GradientDrawable).apply {
                                setColor(0xFF171C2C.toInt()); setStroke(1, 0xFF242424.toInt())
                            }
                            c.setTextColor(0xFF868686.toInt())
                        }
                        (background as android.graphics.drawable.GradientDrawable).apply {
                            setColor(0x14FFFFFF.toInt()); setStroke(1, 0x28FFFFFF.toInt())
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
                        setStroke(1, if (idx == 0) 0x330087FF.toInt() else 0xFF242424.toInt())
                    }
                    setTextColor(if (idx == 0) ACCENT2 else 0xFF868686.toInt())
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
                                setStroke(1, if (active) 0x330087FF.toInt() else 0xFF242424.toInt())
                            }
                            c.setTextColor(if (active) ACCENT2 else 0xFF868686.toInt())
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
        val ACCENT2 = 0xFF6EA8FE.toInt()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF090909.toInt())
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF090909.toInt())
            setPadding(dp(12), dp(16), dp(12), dp(80))
        }

        // ── HEADER ────────────────────────────────────────────────────────
        page.addView(TextView(this).apply {
            text = "Wallet"
            textSize = 22f; setTextColor(0xFFEFEFEF.toInt())
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        })
        page.addView(TextView(this).apply {
            text = "Gestión de wallets encontradas"
            textSize = 12f; setTextColor(0xFF868686.toInt())
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
                colors = intArrayOf(0xFF141414.toInt(), 0xFF171C2C.toInt())
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
                cornerRadius = dp(20).toFloat()
                setStroke(1, 0xFF242424.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        heroCard.addView(TextView(this).apply {
            text = "BALANCE TOTAL ENCONTRADO"
            textSize = 9f; setTextColor(0xFF868686.toInt())
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
            textSize = 12f; setTextColor(0xFF868686.toInt())
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
                    setColor(0xFF141414.toInt()); cornerRadius = dp(16).toFloat()
                    setStroke(1, 0xFF242424.toInt())
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
                text = label; textSize = 14f; setTextColor(0xFFEFEFEF.toInt())
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            })
            lc.addView(TextView(this).apply {
                text = sub; textSize = 11f; setTextColor(0xFF868686.toInt())
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            })
            r.addView(iconTv); r.addView(lc)
            r.addView(TextView(this).apply {
                text = "›"; textSize = 20f; setTextColor(0xFF555555.toInt())
            })
            return r
        }

        page.addView(walletBtn("💰", "Ver Wallet", "Balances y direcciones guardadas") {
            val hasSeed = WalletManager.hasPin(this) && WalletManager.loadSeed(this) != null
            val hasWif  = WalletManager.listWifs(this).isNotEmpty()
            if (hasSeed || hasWif) {
                if (!PinAuthHelper.isSessionValid()) {
                    PinAuthHelper.show(this) { ok ->
                        if (ok) startActivity(Intent(this, WalletActivity::class.java).apply {
                            putExtra("MODE", "seed")
                        })
                    }
                } else {
                    startActivity(Intent(this, WalletActivity::class.java).apply {
                        putExtra("MODE", "seed")
                    })
                }
            } else {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Sin wallets guardadas")
                    .setMessage("No tienes wallets guardadas aún. ¿Quieres agregar una?")
                    .setPositiveButton("Agregar") { _, _ ->
                        startActivity(Intent(this, WalletActivity::class.java).apply {
                            putExtra("MODE", "setup")
                        })
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        })
        page.addView(walletBtn("🔑", "Agregar Wallet", "Importar seed, WIF o dirección") {
            // Mostrar opciones de importación
            val opciones = arrayOf("📝 Seed Phrase (BIP39)", "🔑 Clave WIF", "👁 Watch-only (dirección)")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Tipo de wallet")
                .setItems(opciones) { _, which ->
                    val mode = when (which) {
                        0 -> "setup"
                        1 -> "wif_import"
                        2 -> "watch_import"
                        else -> "setup"
                    }
                    startActivity(Intent(this, WalletActivity::class.java).apply {
                        putExtra("MODE", mode)
                    })
                }
                .show()
        })
        page.addView(walletBtn("📤", "Exportar Log", "Guardar matches en archivo") {
            exportLog()
        })
        page.addView(walletBtn("↻", "Actualizar Balance", "Releer coincidencias.txt") {
            refreshWallet()
            android.widget.Toast.makeText(this, "Actualizando...", android.widget.Toast.LENGTH_SHORT).show()
        })
        page.addView(walletBtn("🔒", "Backup Cifrado", "Exportar matches con PIN") {
            if (!PinAuthHelper.isSessionValid()) {
                PinAuthHelper.show(this) { ok -> if (ok) exportEncryptedBackup() }
            } else {
                exportEncryptedBackup()
            }
        })

        scroll.addView(page)
        return scroll
    }

    // ── BUILD RECOVERY TAB ────────────────────────────────────────────────────
    private fun buildRecoveryTab(): ScrollView {
        val ACCENT  = 0xFF00C896.toInt()
        val ACCENT2 = 0xFF6EA8FE.toInt()

        val recoveryScroll = ScrollView(this).apply {
            setBackgroundColor(0xFF090909.toInt())
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val recoveryPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF090909.toInt())
            setPadding(dp(12), dp(16), dp(12), dp(80))
        }

        // ── HELPER ────────────────────────────────────────────────────────
        fun rCard(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF141414.toInt()); cornerRadius = dp(16).toFloat()
                setStroke(1, 0xFF242424.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        fun fieldLabel(text: String) = TextView(this).apply {
            this.text = text
            textSize = 9f; setTextColor(0xFF868686.toInt())
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
            textSize = 22f; setTextColor(0xFFEFEFEF.toInt())
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        })
        recoveryPage.addView(TextView(this).apply {
            text = "Recuperación de seed phrase"
            textSize = 12f; setTextColor(0xFF868686.toInt())
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
            textSize = 10f; setTextColor(0xFF555555.toInt())
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        })

        val etSeed = android.widget.EditText(this).apply {
            hint = "abandon ??? letter ??? advice cage absurd amount doctor acoustic avoid ???"
            setHintTextColor(0xFF555555.toInt()); setTextColor(0xFFEFEFEF.toInt())
            textSize = 11f; typeface = Typeface.MONOSPACE
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF171C2C.toInt()); setStroke(1, 0xFF242424.toInt())
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
            setHintTextColor(0xFF555555.toInt()); setTextColor(ACCENT)
            textSize = 11f; typeface = Typeface.MONOSPACE
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF171C2C.toInt()); setStroke(1, 0xFF242424.toInt())
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
            text = ""; textSize = 10f; setTextColor(0xFF868686.toInt())
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

        recoveryEngine?.listener = object : com.btcseedrecovery.recovery.RecoveryEngine.ProgressListener {
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
            val parseResult = com.btcseedrecovery.recovery.RecoveryParser.parse(input, wl)
            when (parseResult) {
                is com.btcseedrecovery.recovery.ParseResult.Error -> {
                    tvRecoveryStatus.text = parseResult.message
                    tvRecoveryStatus.visibility = android.view.View.VISIBLE
                }
                is com.btcseedrecovery.recovery.ParseResult.Success -> {
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

    private fun formatCount(v: Long): String = numberFmt.format(v)

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
                // Actualizar peak y promedio
                if (wps > peakWps) {
                    peakWps = wps
                    val peakFmt = numberFmt.format(wps.toLong())
                    tvPeakWps?.text = "peak $peakFmt"
                    tvPeakWpsPuzzle?.text = "peak $peakFmt"
                }
                if (wps > 0) {
                    avgWpsSum += wps
                    avgWpsCount++
                    val avg = avgWpsSum / avgWpsCount
                    tvAvgWps?.text = "promedio ${numberFmt.format(avg.toLong())}"
                }

                if (puzzleMode) {
                    tvWpsPuzzle?.text = numberFmt.format(wps.toLong())
                    tvCountPuzzle?.text = formatCount(HunterEngine.getCount())
                    tvTimePuzzle?.text = formatElapsed(sessionStartTime)
                    // Tiempo estimado para completar el rango
                    if (wps > 0 && currentRangeStart.isNotEmpty() && currentRangeEnd.isNotEmpty()) {
                        try {
                            val start = java.math.BigInteger(currentRangeStart.trimStart('0').ifEmpty{"0"}, 16)
                            val end   = java.math.BigInteger(currentRangeEnd.trimStart('0').ifEmpty{"0"}, 16)
                            val rangeSize = end.subtract(start)
                            val keysPerSec = wps * 1000.0 // wps está en k/s
                            val secsLeft = rangeSize.divide(java.math.BigInteger.valueOf(keysPerSec.toLong().coerceAtLeast(1))).toLong()
                            val eta = when {
                                secsLeft < 60 -> "${secsLeft}s"
                                secsLeft < 3600 -> "${secsLeft/60}m ${secsLeft%60}s"
                                secsLeft < 86400 -> "${secsLeft/3600}h ${(secsLeft%3600)/60}m"
                                secsLeft < 86400*365 -> "${secsLeft/86400}d ${secsLeft%86400/3600}h"
                                else -> "${secsLeft/86400/365}años"
                            }
                            if (currentRangeStart != cachedPuzzleLabelForStart) {
                                cachedPuzzleLabelForStart = currentRangeStart
                                cachedPuzzleLabel = puzzles.firstOrNull { it.start == currentRangeStart }?.num?.let { "#$it" } ?: ""
                            }
                            tvPuzzleStatus?.text = "ETA: $eta · Puzzle $cachedPuzzleLabel"
                        } catch (e: Exception) {}
                    }
                } else {
                    tvWps?.text = numberFmt.format(wps.toLong())
                    tvCount?.text = formatCount(HunterEngine.getCount())
                    tvTime?.text = formatElapsed(sessionStartTime)
                    chartView?.addPoint(wps.toFloat())
                    val found = HunterEngine.getCount() - sessionStartCount
                    tvMatches?.text = "$found"
                    tvQuickMatches?.text = "$found"
                    // Stats adicionales para Raw Key
                    if (wps > 0) {
                        val keysPerSec = wps * 1000.0
                        val totalKeys = HunterEngine.getCount() - sessionStartCount
                        val perDay = (keysPerSec * 86400).toLong()
                        val perDayStr = when {
                            perDay >= 1_000_000_000 -> "${numberFmt.format(perDay/1_000_000_000)}B/día"
                            perDay >= 1_000_000 -> "${numberFmt.format(perDay/1_000_000)}M/día"
                            else -> "${numberFmt.format(perDay/1000)}K/día"
                        }
                        tvBinInfoRef?.text = "$perDayStr"
                        tvBinInfoRef?.setTextColor(0xFF00C896.toInt())
                    }
                }
            }
            val rt = Runtime.getRuntime()
            tvRam?.text = "RAM ${(rt.totalMemory()-rt.freeMemory())/1048576}MB"

        // ── WATCHDOG ─────────────────────────────────────────────────────
        val wasRunning = prefs.getBoolean("scan_was_running", false)
        val isNowRunning = HunterEngine.isRunning()
        if (watchdogEnabled && wasRunning && !isNowRunning && lastKnownRunning) {
            watchdogRestarts++
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!HunterEngine.isRunning() && prefs.getBoolean("scan_was_running", false)) {
                    doToggle(if (puzzleMode) btnPuzzleToggle else btnToggle)
                }
            }, 2000)
        }
        lastKnownRunning = isNowRunning

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
            // Actualizar card DATASET con conteo real del engine
            val total = HunterEngine.getCsvCount()
            if (total > 0) {
                val fmt = if (total >= 1_000_000) "${"%.1f".format(total/1e6)}M"
                          else "${total/1000}K"
                tvDatasetStat?.text = fmt
                tvDatasetStat?.setTextColor(0xFF00C896.toInt())
            }
        }
        } catch (_: Throwable) {
            // vars not yet initialised, or native library not loaded
        }
    }

    private fun doToggle(callerBtn: Button? = null) {
        try {
            if (HunterEngine.isRunning()) {
                HunterEngine.stopHunting()
                // HunterService removed for Play Store build
                prefs.edit().putBoolean("scan_was_running", false).apply()
                peakWps = 0.0
                avgWpsSum = 0.0
                avgWpsCount = 0
                tvPeakWps?.text = ""
                tvPeakWpsPuzzle?.text = ""
                tvAvgWps?.text = ""
                // Guardar sesión en historial
                val sessionKeys = HunterEngine.getCount() - sessionStartCount
                val sessionDur = if (sessionStartTime > 0)
                    (System.currentTimeMillis() - sessionStartTime) / 1000 else 0
                val sessionKps = if (sessionDur > 0) sessionKeys / sessionDur.toDouble() else 0.0
                val sessionMode = if (puzzleMode) "PUZZLE" else "BIP39"
                StatsActivity.saveSession(this, sessionMode, sessionKeys,
                    HunterEngine.getFound(), sessionDur, sessionKps / 1000.0)
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
                HunterEngine.setMode(if (puzzleMode) 1 else selectedScanMode)
                val debugRange = "start=${etRangeStart?.text} end=${etRangeEnd?.text} threads=$threads cpu=$cpu"
                val batchNow = HunterEngine.getBatchSize()
                Toast.makeText(this, "threads=$threads cpu=$cpu batch=$batchNow", Toast.LENGTH_LONG).show()
                HunterEngine.startHunting(threads, cpu)

                // Guardar estado para auto-reinicio
                prefs.edit()
                    .putBoolean("scan_was_running", true)
                    .putBoolean("scan_was_puzzle", puzzleMode)
                    .apply()
                activeToggleBtn = callerBtn
                @Suppress("UNCHECKED_CAST")
                val bg2 = callerBtn?.tag as? Array<GradientDrawable>
                callerBtn?.text = s.stop
                if (bg2 != null && bg2.size > 1) callerBtn?.background = bg2[1]

                // HunterService removed for Play Store build


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
        if (req == REQ_INSTALL_BINARY && res == RESULT_OK) {
            data?.data?.let { processInstallBinary(it) }
            return
        }
        if (req == REQ_IMPORT_PROGRESS && res == RESULT_OK) {
            data?.data?.let { processImportedProgress(it) }
            return
        }
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
            val mb = dest.length() / 1024 / 1024
            val hashes = dest.length() / 20
            tvBinInfoRef?.text = "📦 ${dest.name}  ·  ${numberFmt.format(hashes)} hashes  ·  ${mb}MB"
            tvBinInfoRef?.setTextColor(0xFF00C896.toInt())
            // Actualizar stat card con conteo de hashes
            tvDatasetStat?.text = if (hashes >= 1_000_000) "${"%.1f".format(hashes/1e6)}M" else "${hashes/1000}K"
            Toast.makeText(this, "Dataset cargado: ${dest.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAlertSettings() {
        val ACCENT = 0xFF00C896.toInt()
        val BG     = 0xFF141414.toInt()
        val TXT    = 0xFFEFEFEF.toInt()
        val MUTED  = 0xFF868686.toInt()
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        val alertPrefs = getSharedPreferences("alert_settings", MODE_PRIVATE)
        val vibEnabled  = alertPrefs.getBoolean("vibration", true)
        val soundEnabled = alertPrefs.getBoolean("sound", true)
        val ledEnabled  = alertPrefs.getBoolean("led", true)
        val notifEnabled = alertPrefs.getBoolean("notification", true)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(BG)
        }

        fun toggleRow(label: String, subtitle: String, checked: Boolean, key: String): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                val left = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                left.addView(android.widget.TextView(this@MainActivity).apply {
                    text = label; textSize = 13f; setTextColor(TXT)
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                })
                left.addView(android.widget.TextView(this@MainActivity).apply {
                    text = subtitle; textSize = 10f; setTextColor(MUTED)
                    typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
                })
                addView(left)
                val sw = android.widget.Switch(this@MainActivity).apply {
                    isChecked = checked
                    setOnCheckedChangeListener { _, v ->
                        alertPrefs.edit().putBoolean(key, v).apply()
                        updateAlertChannel()
                    }
                }
                addView(sw)
            }
        }

        layout.addView(android.widget.TextView(this).apply {
            text = "ALERTAS AL ENCONTRAR MATCH"
            textSize = 9f; setTextColor(MUTED)
            typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
            letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        })
        layout.addView(toggleRow("Notificación", "Mostrar alerta en pantalla", notifEnabled, "notification"))
        layout.addView(toggleRow("Vibración", "Vibrar al encontrar wallet", vibEnabled, "vibration"))
        layout.addView(toggleRow("Sonido", "Alarma al encontrar wallet", soundEnabled, "sound"))
        layout.addView(toggleRow("LED", "Parpadeo de LED", ledEnabled, "led"))

        // Test button
        layout.addView(android.widget.Button(this).apply {
            text = "🔔  PROBAR ALERTA"
            textSize = 12f; setTextColor(android.graphics.Color.BLACK)
            background = android.graphics.drawable.GradientDrawable().apply {
                colors = intArrayOf(ACCENT, 0xFF6EA8FE.toInt())
                orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(16) }
            setOnClickListener {
                testVibration()
            }
        })

        AlertDialog.Builder(this)
            .setView(layout)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updateAlertChannel() {
        val alertPrefs = getSharedPreferences("alert_settings", MODE_PRIVATE)
        val vibEnabled  = alertPrefs.getBoolean("vibration", true)
        val soundEnabled = alertPrefs.getBoolean("sound", true)
        val ledEnabled  = alertPrefs.getBoolean("led", true)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            // Recrear canal con nuevas configuraciones
            nm.deleteNotificationChannel(NOTIF_CHANNEL)
            val alarmAttr = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            val ch = android.app.NotificationChannel(
                NOTIF_CHANNEL,
                "Match encontrado",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableLights(ledEnabled)
                lightColor = android.graphics.Color.YELLOW
                enableVibration(vibEnabled)
                if (vibEnabled) vibrationPattern = longArrayOf(0,300,150,300,150,300)
                if (soundEnabled)
                    setSound(android.media.RingtoneManager.getDefaultUri(
                        android.media.RingtoneManager.TYPE_ALARM), alarmAttr)
                else setSound(null, null)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun testVibration() {
        val alertPrefs = getSharedPreferences("alert_settings", MODE_PRIVATE)
        if (!alertPrefs.getBoolean("vibration", true)) return
        val vib = getSystemService(android.os.Vibrator::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vib.vibrate(android.os.VibrationEffect.createWaveform(
                longArrayOf(0,300,150,300,150,300), -1))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(longArrayOf(0,300,150,300,150,300), -1)
        }
    }

    private fun exportPuzzleProgress() {
        try {
            val prefs = getBlockPrefs()
            val json = org.json.JSONObject()
            json.put("version", 1)
            json.put("exported", System.currentTimeMillis())
            json.put("device", android.os.Build.MODEL)
            val puzzlesJson = org.json.JSONObject()
            puzzles.forEach { p ->
                val scanned = prefs.getStringSet("scanned_${p.num}", emptySet()) ?: emptySet()
                if (scanned.isNotEmpty()) {
                    val arr = org.json.JSONArray()
                    scanned.forEach { arr.put(it) }
                    puzzlesJson.put("puzzle_${p.num}", arr)
                }
            }
            json.put("puzzles", puzzlesJson)
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val file = java.io.File(getExternalFilesDir(null), "wh_progress_$ts.json")
            file.writeText(json.toString(2))
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Wallet Hunter Progress")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(share, "Exportar progreso"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun importPuzzleProgress() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, REQ_IMPORT_PROGRESS)
    }

    private fun processImportedProgress(uri: android.net.Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
            val json = org.json.JSONObject(text)
            val puzzlesJson = json.optJSONObject("puzzles") ?: run {
                android.widget.Toast.makeText(this, "Formato inválido", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val prefs = getBlockPrefs()
            val editor = prefs.edit()
            var totalImported = 0
            puzzles.forEach { p ->
                val arr = puzzlesJson.optJSONArray("puzzle_${p.num}") ?: return@forEach
                val existing = prefs.getStringSet("scanned_${p.num}", emptySet())?.toMutableSet() ?: mutableSetOf()
                val before = existing.size
                for (i in 0 until arr.length()) existing.add(arr.getString(i))
                editor.putStringSet("scanned_${p.num}", existing)
                totalImported += existing.size - before
            }
            editor.apply()
            android.widget.Toast.makeText(this,
                "✓ Progreso importado: +$totalImported bloques nuevos",
                android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Error importando: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun exportEncryptedBackup() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val coincidencias = java.io.File(dir, "coincidencias.txt")
        if (!coincidencias.exists()) {
            android.widget.Toast.makeText(this, "Sin matches para exportar", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // Pedir PIN para cifrar
        android.app.AlertDialog.Builder(this)
            .setTitle("PIN de cifrado")
            .setMessage("Ingresa tu PIN para cifrar el backup")
            .setPositiveButton("Cifrar con PIN") { _, _ ->
                // Pedir PIN al usuario
                val pinInput = android.widget.EditText(this).apply {
                    hint = "Ingresa tu PIN"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle("PIN de cifrado")
                    .setView(pinInput)
                    .setPositiveButton("OK") { _, _ ->
                        val pin = pinInput.text.toString()
                        if (!WalletManager.checkPin(this, pin)) {
                            android.widget.Toast.makeText(this, "PIN incorrecto", android.widget.Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        try {
                    val data = coincidencias.readBytes()
                    val (encrypted, iv) = WalletManager.encryptData(data, pin)
                    val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                    val backupFile = java.io.File(dir, "wh_backup_$ts.enc")
                    // Guardar IV + datos cifrados
                    val out = java.io.ByteArrayOutputStream()
                    out.write(iv.size)
                    out.write(iv)
                    out.write(encrypted)
                    backupFile.writeBytes(out.toByteArray())
                    // Compartir
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this, "${packageName}.provider", backupFile)
                    val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Wallet Hunter Encrypted Backup")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(android.content.Intent.createChooser(share, "Guardar backup cifrado"))
                    android.widget.Toast.makeText(this, "Backup cifrado: ${backupFile.name}", android.widget.Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Cancelar", null).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
        // Guardar rango para modo distribuido
        prefs.edit()
            .putString("current_range_start", p.start)
            .putString("current_range_end", p.end)
            .putInt("current_puzzle_num", p.num)
            .apply()
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
            tvPuzzleStatus?.setTextColor(0xFF868686.toInt())
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
                NOTIF_CHANNEL, "Match Found!",
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
            val notif = androidx.core.app.NotificationCompat.Builder(this, NOTIF_CHANNEL)
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
        // Auto-reinicio: si el engine estaba corriendo pero el servicio fue matado
        checkAndRestartScan()
    }

    private fun showAddressQR(address: String) {
        try {
            val size = (resources.displayMetrics.widthPixels * 0.7).toInt()
            val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 2)
            val bitMatrix = com.google.zxing.MultiFormatWriter().encode(
                address, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints
            )
            val ACCENT = 0xFF00C896.toInt()
            val BG = 0xFF090909.toInt()
            // Fill an IntArray and hand it over in one call: setPixel() per pixel
            // is ~size^2 individual JNI crossings (≈500k on a typical screen).
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val row = y * size
                for (x in 0 until size) {
                    pixels[row + x] = if (bitMatrix[x, y]) ACCENT else BG
                }
            }
            val bitmap = android.graphics.Bitmap.createBitmap(
                pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888
            )

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xFF090909.toInt())
                setPadding(dp(24), dp(24), dp(24), dp(24))
            }

            layout.addView(android.widget.TextView(this).apply {
                text = "Dirección objetivo"; textSize = 14f
                setTextColor(0xFFEFEFEF.toInt())
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(16) }
            })

            val imgView = android.widget.ImageView(this).apply {
                setImageBitmap(bitmap)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.CENTER
                    bottomMargin = dp(16)
                }
            }
            layout.addView(imgView)

            layout.addView(android.widget.TextView(this).apply {
                text = address
                textSize = 10f; setTextColor(0xFF868686.toInt())
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setTextIsSelectable(true)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            })

            // Botón abrir en explorer
            layout.addView(android.widget.Button(this).apply {
                text = "🌐 Ver en Blockchain Explorer"
                textSize = 12f; setTextColor(android.graphics.Color.BLACK)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF00C896.toInt()); cornerRadius = dp(10).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
                )
                setOnClickListener {
                    val url = "https://mempool.space/address/$address"
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)))
                }
            })

            AlertDialog.Builder(this)
                .setView(layout)
                .setPositiveButton("Cerrar", null)
                .show()

        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Error generando QR: ${e.message}",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun installNativeBinary() {
        // Usar file picker para acceder al binario
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_INSTALL_BINARY)
    }

    private fun processInstallBinary(uri: android.net.Uri) {
        try {
            val dest = java.io.File(filesDir, "hunter_master")
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            // Setear permisos de ejecución via chmod
            dest.setExecutable(true, false)
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", dest.absolutePath)).waitFor()
            } catch (e: Exception) {}

            val exists = dest.exists()
            val canExec = dest.canExecute()
            val size = dest.length()

            android.app.AlertDialog.Builder(this)
                .setTitle(if (exists) "Motor nativo instalado" else "Error")
                .setMessage("Path: ${dest.absolutePath} | Existe: $exists | Exec: $canExec | Size: $size")
                .setPositiveButton("OK", null).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Error: ${e.message}",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun debugNativeSetup() {
        val sb = StringBuilder()
        val bin1 = java.io.File(filesDir, "hunter_master")
        val bin2 = java.io.File(getExternalFilesDir(null), "hunter_master")
        sb.appendLine("filesDir: ${filesDir.absolutePath}")
        sb.appendLine("bin1 existe: ${bin1.exists()} ejecutable: ${bin1.canExecute()} size: ${bin1.length()}")
        sb.appendLine("bin2 existe: ${bin2.exists()} ejecutable: ${bin2.canExecute()} size: ${bin2.length()}")
        sb.appendLine("filesDir contents:")
        filesDir.listFiles()?.forEach { sb.appendLine("  ${it.name} ${it.length()}b exec:${it.canExecute()}") }
        android.app.AlertDialog.Builder(this)
            .setTitle("Debug Setup")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .setNeutralButton("Fix permisos") { _, _ ->
                bin1.setExecutable(true, false)
                bin2.setExecutable(true, false)
                android.widget.Toast.makeText(this, "Permisos aplicados", android.widget.Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun doToggleNative() {
        if (NativeEngine.isRunning()) {
            NativeEngine.stop()
            btnToggle?.text = "▶  START SCAN"
            btnToggle?.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1A1A1A.toInt()); cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0xFFEFEFEF.toInt())
            }
            return
        }

        // Buscar el archivo .bin de base de datos
        // Buscar archivo .bin en múltiples ubicaciones
        val dbFile = listOf(
            File(getExternalFilesDir(null), "utxos.bin"),
            File(getExternalFilesDir(null), "utxos_legacy_segwit.bin"),
            File(getExternalFilesDir(null), "utxos_legacy.bin"),
            File(getExternalFilesDir(null), "utxos_segwit.bin"),
            File(filesDir, "utxos.bin"),
            File(filesDir, "utxos_legacy_segwit.bin")
        ).firstOrNull { it.exists() }?.absolutePath ?: ""

        if (dbFile.isEmpty()) {
            val extPath = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
            android.app.AlertDialog.Builder(this)
                .setTitle("Archivo .bin requerido")
                .setMessage("Copia utxos_legacy_segwit.bin a: $extPath")
                .setPositiveButton("OK", null).show()
            return
        }

        val threads = (sbThreads?.progress ?: 3) + 1

        // Copiar .bin a filesDir para que el proceso nativo pueda accederlo
        val internalDb = java.io.File(filesDir, "utxos.bin")
        if (!internalDb.exists() || internalDb.length() != java.io.File(dbFile).length()) {
            android.widget.Toast.makeText(this,
                "Copiando base de datos...", android.widget.Toast.LENGTH_SHORT).show()
            Thread {
                try {
                    java.io.File(dbFile).copyTo(internalDb, overwrite = true)
                    runOnUiThread {
                        NativeEngine.start(this, internalDb.absolutePath, threads)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        android.widget.Toast.makeText(this,
                            "Error copiando DB: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
            return
        }
        val finalDbPath = internalDb.absolutePath



        NativeEngine.onLog = { line ->
            runOnUiThread {
                android.util.Log.d("NativeEngine", line)
                if (line.isNotEmpty()) {
                    android.widget.Toast.makeText(
                        this@MainActivity, line, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        NativeEngine.onMatch = { line ->
            runOnUiThread {
                android.widget.Toast.makeText(this@MainActivity,
                    "MATCH: $line", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        NativeEngine.start(this, finalDbPath, threads, "LEGACY")

        // Actualizar UI con velocidad del proceso nativo
        handler.post(object : Runnable {
            override fun run() {
                if (NativeEngine.isRunning()) {
                    val spd = NativeEngine.speed.get()
                    val tot = NativeEngine.total.get()
                    // spd ya viene en K/s del script, multiplicar por 1000 para k/s display
                    tvWps?.text = if (spd >= 1000)
                        "${"%.1f".format(spd/1000.0)}M"
                    else
                        "${spd}K"
                    tvCount?.text = formatCount(tot)
                    tvTime?.text = formatElapsed(sessionStartTime)
                    handler.postDelayed(this, 800)
                } else if (!HunterEngine.isRunning()) {
                    // Proceso terminó — actualizar botón
                    runOnUiThread {
                        btnToggle?.text = "▶  START SCAN"
                        btnToggle?.background = android.graphics.drawable.GradientDrawable().apply {
                            colors = intArrayOf(0xFF00C896.toInt(), 0xFF6EA8FE.toInt())
                            orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
                            cornerRadius = dp(16).toFloat()
                        }
                    }
                }
            }
        })
        sessionStartTime = System.currentTimeMillis()

        btnToggle?.text = "⏹  STOP"
        btnToggle?.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF1A0808.toInt()); cornerRadius = dp(16).toFloat()
            setStroke(dp(1), 0xFFF04040.toInt())
        }

        // HunterService removed for Play Store build
    }

    private fun checkAndRestartScan() {
        val wasRunning = prefs.getBoolean("scan_was_running", false)
        if (!wasRunning) return
        if (HunterEngine.isRunning()) return // ya está corriendo

        // El scan estaba activo pero fue matado — preguntar si reiniciar
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (HunterEngine.isRunning()) return@postDelayed // doble check
            val mode = if (prefs.getBoolean("scan_was_puzzle", false)) "Puzzle" else "BIP39"
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Scan interrumpido")
                .setMessage("El scan en modo $mode fue interrumpido. ¿Reiniciar?")
                .setPositiveButton("Reiniciar") { _, _ ->
                    if (prefs.getBoolean("scan_was_puzzle", false)) {
                        puzzleMode = true
                        HunterEngine.setMode(1)
                    } else {
                        puzzleMode = false
                        HunterEngine.setMode(0)
                    }
                    doToggle(if (puzzleMode) btnPuzzleToggle else btnToggle)
                }
                .setNegativeButton("No") { _, _ ->
                    prefs.edit().putBoolean("scan_was_running", false).apply()
                }
                .show()
        }, 1000)
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
