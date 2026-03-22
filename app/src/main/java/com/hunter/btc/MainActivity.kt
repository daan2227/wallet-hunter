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

    // ── Variables ─────────────────────────────────────────────────────────────
    private var sessionStartTime = 0L
    private var sessionStartCount = 0L
    private var batteryReceiver: android.content.BroadcastReceiver? = null
    private var lastFoundCount = 0L
    private val NOTIF_CHANNEL = "hunter_match"
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

    private val updater = object : Runnable {
        override fun run() {
            updateUI()
            handler.postDelayed(this, 800)
        }
    }

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        AppTheme.init(this)
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
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
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
            puzzleScroll = buildPuzzleTab()
            android.widget.Toast.makeText(this, "Building Wallet...", android.widget.Toast.LENGTH_SHORT).show()
            walletScroll = buildWalletTab()
            android.widget.Toast.makeText(this, "Building Recovery...", android.widget.Toast.LENGTH_SHORT).show()
            recoveryScroll = buildRecoveryTab()
            android.widget.Toast.makeText(this, "All tabs built OK", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "CRASH: ${e.javaClass.simpleName}: ${e.message?.take(80)}", android.widget.Toast.LENGTH_LONG).show()
            finish(); return
        }

        scanScroll?.let { cf.addView(it) }
        puzzleScroll?.let { cf.addView(it) }
        walletScroll?.let { cf.addView(it) }
        recoveryScroll?.let { cf.addView(it) }
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
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        val tb0 = tabBtn("⊙", "Scan")
        val tb1 = tabBtn("⬡", "Puzzle")
        val tb2 = tabBtn("◈", "Wallet")
        val tb3 = tabBtn("⚷", "Recovery")
        listOf(tb0, tb1, tb2, tb3).forEach { tabBar.addView(it) }
        root.addView(tabBar)
        setContentView(root)

        tabPages = listOfNotNull(scanScroll, puzzleScroll, walletScroll, recoveryScroll)
        tabBtns  = listOf(tb0, tb1, tb2, tb3)
        listOf(tb0, tb1, tb2, tb3).forEachIndexed { i, b ->
            b.setOnClickListener {
                if (i == 2) {
                    if (WalletManager.hasPin(this) && !PinAuthHelper.isSessionValid()) {
                        PinAuthHelper.show(this) { ok -> if (ok) goTab(2) }
                    } else goTab(2)
                } else goTab(i)
            }
        }
        goTab(0)

        // Init
        try {
            if (csvPath.isNotEmpty() && File(csvPath).exists() && !HunterEngine.isCsvLoaded())
                HunterEngine.loadCsv(csvPath)
            setupNotificationChannel()
            registerBatteryReceiver()
            // Auto-detectar hardware en primera ejecución
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

    // ── BUILD SCAN TAB ────────────────────────────────────────────────────────
    private fun buildScanTab(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG_DEEP)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DEEP)
        }

        // ── CONFIG SECTION ────────────────────────────────────────────────
        val cfgSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
        }

        // Dataset
        cfgSection.addView(TextView(this).apply {
            text = "DATASET"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.16f; setPadding(0, 0, 0, dp(8))
        })
        val dataCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = cardBg(); clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        btnCsv = Button(this).apply {
            text = "Load CSV"; textSize = 11f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(AMBER) }
            setPadding(dp(18), 0, dp(18), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(50))
            setOnClickListener { pickCsv() }
        }
        val tvCsvNameLocal = TextView(this).apply {
            text = if (csvPath.isNotEmpty() && File(csvPath).exists()) File(csvPath).name else "No file"
            setTextColor(TXT_MUTED); textSize = 10f; typeface = Typeface.MONOSPACE
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvCsvName = tvCsvNameLocal
        dataCard.addView(btnCsv); dataCard.addView(tvCsvNameLocal)
        cfgSection.addView(dataCard)

        // Threads + CPU
        cfgSection.addView(TextView(this).apply {
            text = "PERFORMANCE"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.16f; setPadding(0, 0, 0, dp(8))
        })
        val perfCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        tvThreads = TextView(this).apply { setTextColor(TXT_PRI); textSize = 12f; setPadding(dp(16), dp(12), dp(16), dp(4)) }
        sbThreads = SeekBar(this).apply {
            max = 7; progress = prefs.getInt("threads", 3)
            setPadding(dp(16), 0, dp(16), dp(4))
            setOnSeekBarChangeListener(mkSbl { updateLabels() })
        }
        tvCpu = TextView(this).apply { setTextColor(TXT_PRI); textSize = 12f; setPadding(dp(16), dp(8), dp(16), dp(4)) }
        sbCpu = SeekBar(this).apply {
            max = 90; progress = prefs.getInt("cpu", 70)
            setPadding(dp(16), 0, dp(16), dp(12))
            setOnSeekBarChangeListener(mkSbl {
                updateLabels()
                if (HunterEngine.isRunning()) HunterEngine.setCpuLimit((sbCpu?.progress ?: 70) + 10)
            })
        }
        perfCard.addView(tvThreads); perfCard.addView(sbThreads)
        perfCard.addView(tvCpu); perfCard.addView(sbCpu)
        cfgSection.addView(perfCard)

        // Fast mode
        cfgSection.addView(TextView(this).apply {
            text = "SCAN MODE"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.16f; setPadding(0, 0, 0, dp(8))
        })
        val fastCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
        }
        val fastRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val fastLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fastLeft.addView(TextView(this).apply { text = "Fast Scan Mode"; textSize = 13f; setTextColor(TXT_PRI) })
        fastLeft.addView(TextView(this).apply {
            text = "1 iter — 60k/s vs 9k/s standard"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.MONOSPACE; setPadding(0, dp(2), 0, 0)
        })
        val fastSwitch = android.widget.Switch(this).apply {
            isChecked = fastModeEnabled
            setOnCheckedChangeListener { _, checked ->
                fastModeEnabled = checked
                HunterEngine.setPbkdf2Mode(if (checked) 1 else 0)
                prefs.edit().putBoolean("fastMode", checked).apply()
            }
        }
        fastModeEnabled = prefs.getBoolean("fastMode", false)
        HunterEngine.setPbkdf2Mode(if (fastModeEnabled) 1 else 0)
        fastSwitch.isChecked = fastModeEnabled
        fastRow.addView(fastLeft); fastRow.addView(fastSwitch); fastCard.addView(fastRow)
        cfgSection.addView(fastCard)

        // Botón auto-configurar hardware
        val btnHw = Button(this).apply {
            text = "⚙ Auto-configurar Hardware"
            textSize = 11f; setTextColor(AMBER)
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(1, BORDER_C); cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { topMargin = dp(8) }
            setOnClickListener { showHardwareInfo() }
        }
        cfgSection.addView(btnHw)
        page.addView(cfgSection)

        // ── DIVIDER ───────────────────────────────────────────────────────
        page.addView(View(this).apply {
            setBackgroundColor(BORDER_C)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, dp(4), 0, dp(4))
            }
        })

        // ── RUN SECTION ───────────────────────────────────────────────────
        // Speed hero
        val heroBlock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG_PANEL)
            setPadding(dp(18), dp(20), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val heroLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        heroLeft.addView(TextView(this).apply {
            text = "SPEED"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.18f; setPadding(0, 0, 0, dp(4))
        })
        tvWps = TextView(this).apply { text = "0"; textSize = 50f; setTextColor(TXT_PRI); typeface = Typeface.create("monospace", Typeface.BOLD) }
        heroLeft.addView(tvWps)
        heroLeft.addView(TextView(this).apply { text = "keys / second"; textSize = 11f; setTextColor(TXT_SEC); setPadding(0, dp(3), 0, 0) })
        val heroRight = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        tvCount = TextView(this).apply { text = "0"; textSize = 17f; setTextColor(TXT_PRI); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END }
        tvTime  = TextView(this).apply { text = "00:00:00"; textSize = 17f; setTextColor(TXT_PRI); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END }
        heroRight.addView(tvCount)
        heroRight.addView(TextView(this).apply { text = "SCANNED"; textSize = 8f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END; letterSpacing = 0.13f; setPadding(0, dp(2), 0, dp(12)) })
        heroRight.addView(tvTime)
        heroRight.addView(TextView(this).apply { text = "ELAPSED"; textSize = 8f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END; letterSpacing = 0.13f; setPadding(0, dp(2), 0, 0) })
        heroBlock.addView(heroLeft); heroBlock.addView(heroRight)
        page.addView(heroBlock)
        page.addView(View(this).apply { setBackgroundColor(BORDER_C); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1) })

        // Start/Stop button
        val coinGreen = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(12).toFloat() }
        val coinRed   = GradientDrawable().apply { setColor(RED);   cornerRadius = dp(12).toFloat() }
        btnToggle = Button(this).apply {
            text = s.start; textSize = 15f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD); letterSpacing = 0.12f; isAllCaps = true
            background = coinGreen
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
            setOnClickListener {
                puzzleMode = false
                HunterEngine.setMode(0)
                doToggle(btnToggle)
            }
        }
        btnToggle?.tag = arrayOf(coinGreen, coinRed)
        val actZone = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        actZone.addView(btnToggle); page.addView(actZone)

        // Speed chart
        val chartCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16), 0, dp(16), dp(8)) }
        }
        chartCard.addView(TextView(this).apply { text = "SPEED HISTORY"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.14f; setPadding(0, 0, 0, dp(10)) })
        chartView = SpeedChartView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)) }
        chartCard.addView(chartView); page.addView(chartCard)

        // Matches
        val matchCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16), 0, dp(16), dp(8)) }
        }
        matchCard.addView(TextView(this).apply { text = "MATCHES"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.14f; setPadding(dp(14), dp(10), dp(14), dp(4)) })
        tvMatchList = TextView(this).apply {
            text = "No matches yet"; setTextColor(TXT_MUTED); textSize = 11f; typeface = Typeface.MONOSPACE
            setPadding(dp(14), dp(4), dp(14), dp(12))
        }
        matchCard.addView(tvMatchList); page.addView(matchCard)

        // System info
        tvRam      = TextView(this).apply { text = "RAM --"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.MONOSPACE }
        tvBattery  = TextView(this).apply { text = "BAT --"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.MONOSPACE }
        tvTemp     = TextView(this).apply { text = ""; textSize = 9f; setTextColor(TXT_MUTED) }
        tvMatches  = TextView(this).apply { text = "0"; textSize = 9f; setTextColor(TXT_MUTED) }
        tvAddrFeed = TextView(this).apply { text = ""; setTextColor(TXT_SEC); textSize = 10f; typeface = Typeface.MONOSPACE }
        tvFooter   = TextView(this).apply { visibility = android.view.View.GONE; text = "" }
        tvStatus   = TextView(this).apply { visibility = android.view.View.GONE; text = "" }
        tvKps      = tvWps
        val sysRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(4), dp(16), dp(20)) }
        sysRow.addView(tvRam); sysRow.addView(tvBattery)
        page.addView(sysRow)

        // Dataset status
        val tvDatasetStatus = TextView(this).apply {
            text = if (csvPath.isNotEmpty() && File(csvPath).exists()) "Dataset: ${File(csvPath).name}" else "No dataset loaded"
            textSize = 10f; typeface = Typeface.MONOSPACE
            setTextColor(if (csvPath.isNotEmpty() && File(csvPath).exists()) 0xFF00FF88.toInt() else TXT_MUTED)
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        page.addView(tvDatasetStatus)
        page.addView(tvFooter); page.addView(tvStatus)
        page.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20)) })

        scroll.addView(page)
        return scroll
    }

    // ── BUILD PUZZLE TAB ──────────────────────────────────────────────────────
    private fun buildPuzzleTab(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG_DEEP)
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG_DEEP)
        }

        // ── CONFIG SECTION ────────────────────────────────────────────────
        val cfgSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
        }
        cfgSection.addView(TextView(this).apply {
            text = "PUZZLE"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.16f; setPadding(0, 0, 0, dp(8))
        })

        val puzzleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg()
            setPadding(dp(14), dp(12), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        val dayOfYear = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val defaultIdx = dayOfYear % puzzles.size
        puzzleSpinner = Spinner(this).apply {
            adapter = themedAdapter(puzzles.map { "#${it.num}  -  ${it.btc}  -  ${it.addr.take(16)}..." })
            setSelection(defaultIdx)
            background = GradientDrawable().apply { setColor(BG_CARD); setStroke(1, BORDER_C); cornerRadius = dp(6).toFloat() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        puzzleCard.addView(puzzleSpinner)

        fun fld(lbl: String): Pair<LinearLayout, EditText> {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) } }
            col.addView(TextView(this).apply { text = lbl; textSize = 9f; setTextColor(TXT_SEC); setPadding(0, 0, 0, dp(2)) })
            val et = EditText(this).apply {
                setTextColor(TXT_PRI); textSize = 10f; typeface = Typeface.MONOSPACE
                background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1, BORDER_C); cornerRadius = dp(6).toFloat() }
                setPadding(dp(8), dp(6), dp(8), dp(6))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            col.addView(et); return Pair(col, et)
        }
        val rangeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        val (cS, eS) = fld("Range Start"); etRangeStart = eS; rangeRow.addView(cS)
        val (cE, eE) = fld("Range End");   etRangeEnd   = eE; rangeRow.addView(cE)
        puzzleCard.addView(rangeRow)
        puzzleCard.addView(TextView(this).apply { text = "Target Address"; textSize = 9f; setTextColor(TXT_SEC); setPadding(0, dp(4), 0, dp(2)) })
        etTarget = EditText(this).apply {
            setTextColor(AMBER); textSize = 10f; typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply { setColor(BG_ELEV); setStroke(1, BORDER_C); cornerRadius = dp(6).toFloat() }
            setPadding(dp(8), dp(6), dp(8), dp(6))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        puzzleCard.addView(etTarget)
        cfgSection.addView(puzzleCard)
        applyPuzzle(puzzles[defaultIdx])

        // Threads + CPU puzzle
        cfgSection.addView(TextView(this).apply {
            text = "PERFORMANCE"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.16f; setPadding(0, dp(8), 0, dp(8))
        })
        val perfCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
        }
        tvThreadsPuzzle = TextView(this).apply { setTextColor(TXT_PRI); textSize = 12f; setPadding(dp(16), dp(12), dp(16), dp(4)) }
        sbThreadsPuzzle = SeekBar(this).apply { max = 7; progress = prefs.getInt("puzzle_threads", 3); setPadding(dp(16), 0, dp(16), dp(4)); setOnSeekBarChangeListener(mkSbl { updatePuzzleLabels() }) }
        tvCpuPuzzle = TextView(this).apply { setTextColor(TXT_PRI); textSize = 12f; setPadding(dp(16), dp(8), dp(16), dp(4)) }
        sbCpuPuzzle = SeekBar(this).apply { max = 90; progress = prefs.getInt("puzzle_cpu", 70); setPadding(dp(16), 0, dp(16), dp(12)); setOnSeekBarChangeListener(mkSbl { updatePuzzleLabels() }) }
        perfCard.addView(tvThreadsPuzzle); perfCard.addView(sbThreadsPuzzle)
        perfCard.addView(tvCpuPuzzle); perfCard.addView(sbCpuPuzzle)
        cfgSection.addView(perfCard)
        page.addView(cfgSection)
        updatePuzzleLabels()

        // ── DIVIDER ───────────────────────────────────────────────────────
        page.addView(View(this).apply {
            setBackgroundColor(BORDER_C)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, dp(4), 0, dp(4)) }
        })

        // ── RUN SECTION ───────────────────────────────────────────────────
        val runSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        // Status
        tvPuzzleStatus = TextView(this).apply {
            text = "Select a puzzle above"; textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(GREEN)
            background = GradientDrawable().apply { setColor(0x1510D97A); setStroke(1, 0x2510D97A); cornerRadius = dp(6).toFloat() }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        }
        runSection.addView(tvPuzzleStatus)

        // Speed hero puzzle
        val pHeroBlock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG_PANEL)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        }
        val pHeroLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        pHeroLeft.addView(TextView(this).apply { text = "SPEED"; textSize = 9f; setTextColor(TXT_MUTED); typeface = Typeface.create("monospace", Typeface.BOLD); letterSpacing = 0.18f })
        val tvWpsP = TextView(this).apply { text = "0"; textSize = 40f; setTextColor(TXT_PRI); typeface = Typeface.create("monospace", Typeface.BOLD) }
        tvWpsPuzzle = tvWpsP; pHeroLeft.addView(tvWpsP)
        pHeroLeft.addView(TextView(this).apply { text = "keys / second"; textSize = 10f; setTextColor(TXT_SEC) })
        val pHeroRight = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        val tvCntP = TextView(this).apply { text = "0"; textSize = 15f; setTextColor(TXT_PRI); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END }
        val tvTmP  = TextView(this).apply { text = "00:00:00"; textSize = 15f; setTextColor(TXT_PRI); typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END }
        tvCountPuzzle = tvCntP; tvTimePuzzle = tvTmP
        tvPctPuzzle = TextView(this).apply {
            text = "0.00000000%"; textSize = 11f; setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END
        }
        tvBlockProgress = TextView(this).apply {
            text = "Bloques: 0 / —"; textSize = 9f; setTextColor(TXT_MUTED)
            typeface = Typeface.MONOSPACE; gravity = Gravity.END
        }
        pHeroRight.addView(tvCntP)
        pHeroRight.addView(TextView(this).apply { text = "SCANNED"; textSize = 8f; setTextColor(TXT_MUTED); gravity = Gravity.END; letterSpacing = 0.13f; setPadding(0, dp(2), 0, dp(8)) })
        pHeroRight.addView(tvTmP)
        pHeroRight.addView(TextView(this).apply { text = "ELAPSED"; textSize = 8f; setTextColor(TXT_MUTED); gravity = Gravity.END; letterSpacing = 0.13f; setPadding(0, dp(2), 0, dp(4)) })
        pHeroRight.addView(tvPctPuzzle)
        pHeroRight.addView(tvBlockProgress)
        pHeroBlock.addView(pHeroLeft); pHeroBlock.addView(pHeroRight)
        runSection.addView(pHeroBlock)

        // Start/Stop puzzle
        val pCoinGreen = GradientDrawable().apply { setColor(AMBER); cornerRadius = dp(12).toFloat() }
        val pCoinRed   = GradientDrawable().apply { setColor(RED);   cornerRadius = dp(12).toFloat() }
        btnPuzzleToggle = Button(this).apply {
            text = "▶  START PUZZLE"; textSize = 15f; setTextColor(android.graphics.Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD); letterSpacing = 0.12f; isAllCaps = true
            background = pCoinGreen
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { bottomMargin = dp(12) }
            setOnClickListener {
                puzzleMode = true
                HunterEngine.setMode(1)
                doToggle(btnPuzzleToggle)
            }
        }
        btnPuzzleToggle?.tag = arrayOf(pCoinGreen, pCoinRed)
        runSection.addView(btnPuzzleToggle)

        // Temperatura thermal
        val tvThermalPuzzle = TextView(this).apply {
            text = "Temperatura: --"; textSize = 10f; setTextColor(AppTheme.GREEN)
            typeface = Typeface.MONOSPACE; setPadding(0, dp(4), 0, dp(4))
        }
        tvThermal = tvThermalPuzzle
        runSection.addView(tvThermalPuzzle)

        // Balance - se actualiza automáticamente con autoSelectPuzzle
        val tvBalResult = TextView(this).apply {
            text = "Checking balance..."; textSize = 11f
            setTextColor(TXT_MUTED); typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(8))
        }
        runSection.addView(tvBalResult)

        // Log de checkpoint visible (se actualiza en tiempo real)
        tvCheckpointLive = TextView(this).apply {
            text = ""; textSize = 9f; setTextColor(AppTheme.CYAN)
            typeface = Typeface.MONOSPACE; setPadding(0, dp(4), 0, dp(4))
        }
        // Cargar y mostrar checkpoint existente
        val puzzlePrefs = getSharedPreferences("puzzle_checkpoint", MODE_PRIVATE)
        val puzzleNum = puzzles[defaultIdx].num
        val savedKey = puzzlePrefs.getString("last_key_$puzzleNum", null)
        val savedTime = puzzlePrefs.getLong("last_time_$puzzleNum", 0)
        if (savedKey != null && savedTime > 0) {
            val timeStr = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(savedTime))
            tvCheckpointLive?.text = "⬡ Checkpoint #$puzzleNum: $timeStr\n${savedKey.take(16)}...${savedKey.takeLast(8)}"
        }
        runSection.addView(tvCheckpointLive)

        // Listener unificado: aplica puzzle + muestra checkpoint
        puzzleSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var init = true
            override fun onItemSelected(a: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                if (init) { init = false; return }
                val p = puzzles[pos]
                val key = puzzlePrefs.getString("last_key_${p.num}", null)
                val t   = puzzlePrefs.getLong("last_time_${p.num}", 0)
                if (key != null && t > 0) {
                    val ts = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(t))
                    tvCheckpointLive?.text = "⬡ Checkpoint #${p.num}: $ts\n${key.take(16)}...${key.takeLast(8)}"
                } else {
                    tvCheckpointLive?.text = "Sin checkpoint #${p.num} — comenzará desde inicio"
                    tvCheckpointLive?.setTextColor(TXT_MUTED)
                }
                if (!suppressPuzzleListener) applyPuzzle(p)
            }
            override fun onNothingSelected(a: AdapterView<*>) {}
        }

        // Auto-seleccionar puzzle al entrar al tab
        checkPuzzleBalance(puzzles[defaultIdx].addr) { bal ->
            if (bal > 0) {
                tvBalResult.text = "Balance: ${bal / 100_000_000.0} BTC ✓"
                tvBalResult.setTextColor(GREEN)
            } else {
                // Buscar el puzzle con menor dificultad que tenga fondos
                autoSelectPuzzle()
                tvBalResult.text = "Searching funded puzzles..."
            }
        }
        page.addView(runSection)

        scroll.addView(page)
        return scroll
    }

    private fun updatePuzzleLabels() {
                val t = (sbThreadsPuzzle?.progress ?: 3) + 1
        val c = (sbCpuPuzzle?.progress ?: 70) + 10
        tvThreadsPuzzle?.text = "Threads: $t"
        tvCpuPuzzle?.text = "CPU limit: $c%"
        prefs.edit().putInt("puzzle_threads", sbThreadsPuzzle?.progress ?: 3).putInt("puzzle_cpu", sbCpuPuzzle?.progress ?: 70).apply()
    }

    // ── BUILD WALLET TAB ──────────────────────────────────────────────────────
    private fun buildWalletTab(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG_DEEP)
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(80))
        }

        page.addView(TextView(this).apply {
            text = "◈  WALLET"; textSize = 13f; setTextColor(AMBER)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(16))
        })

        // Botones de acción
        fun walletBtn(label: String, sub: String, click: () -> Unit): LinearLayout {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = cardBg(); isClickable = true
                setOnClickListener { click() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            val lc = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            lc.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(TXT_PRI) })
            lc.addView(TextView(this).apply {
                text = sub; textSize = 10f; setTextColor(TXT_MUTED)
                typeface = Typeface.MONOSPACE; setPadding(0, dp(2), 0, 0)
            })
            r.addView(lc)
            r.addView(TextView(this).apply { text = "›"; textSize = 18f; setTextColor(TXT_MUTED) })
            return r
        }

        page.addView(walletBtn("Ver Wallet", "Balances y direcciones") {
            startActivity(Intent(this, WalletActivity::class.java))
        })
        page.addView(walletBtn("Importar Seed", "Restaurar desde frase semilla") {
            startActivity(Intent(this, WalletActivity::class.java))
        })
        page.addView(walletBtn("Exportar Log", "Guardar matches en archivo") {
            exportLog()
        })

        scroll.addView(page)
        return scroll
    }

    // ── BUILD RECOVERY TAB ────────────────────────────────────────────────────
    private fun buildRecoveryTab(): ScrollView {
        val recoveryScroll = ScrollView(this).apply {
            setBackgroundColor(BG_CARD)
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
                        val recoveryPage=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(14),dp(14),dp(80))}

        // Header
        recoveryPage.addView(TextView(this).apply{
            text="⚷  SEED RECOVERY";textSize=13f;setTextColor(AMBER)
            typeface=Typeface.create("monospace",Typeface.BOLD);setPadding(0,dp(4),0,dp(2))
        })
        recoveryPage.addView(TextView(this).apply{
            text="Ingresa tu seed phrase. Usa ??? para las palabras que no recuerdas."
            textSize=10f;setTextColor(TXT_MUTED);typeface=Typeface.MONOSPACE
            setPadding(0,0,0,dp(12))
        })

        // Input seed phrase
        val etSeed=android.widget.EditText(this).apply{
            hint="abandon ??? letter ??? advice cage absurd amount doctor acoustic avoid ???"
            setHintTextColor(0xFF555566.toInt());setTextColor(TXT_PRI)
            textSize=11f;typeface=Typeface.MONOSPACE
            setBackgroundColor(BG_PANEL);setPadding(dp(12),dp(10),dp(12),dp(10))
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines=3;maxLines=5;isSingleLine=false
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(10)}
        }
        recoveryPage.addView(etSeed)

        // Input dirección objetivo
        recoveryPage.addView(TextView(this).apply{
            text="DIRECCIÓN BTC OBJETIVO (opcional)";textSize=9f
            setTextColor(TXT_MUTED);typeface=Typeface.MONOSPACE;setPadding(0,dp(4),0,dp(4))
        })
        val etTarget=android.widget.EditText(this).apply{
            hint="1A2B3C... o bc1q...";setHintTextColor(0xFF555566.toInt())
            setTextColor(TXT_PRI);textSize=11f;typeface=Typeface.MONOSPACE
            setBackgroundColor(BG_PANEL);setPadding(dp(12),dp(10),dp(12),dp(10))
            isSingleLine=true
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(10)}
        }
        recoveryPage.addView(etTarget)

        // Info: combinaciones y tiempo estimado
        val tvRecoveryInfo=TextView(this).apply{
            text="Palabras faltantes: —";textSize=10f
            setTextColor(AppTheme.CYAN);typeface=Typeface.MONOSPACE
            setPadding(0,dp(4),0,dp(8))
        }
        recoveryPage.addView(tvRecoveryInfo)

        // Actualizar info en tiempo real al escribir
        etSeed.addTextChangedListener(object:android.text.TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){}
            override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){}
            override fun afterTextChanged(s:android.text.Editable?){
                val input=s?.toString()?:""
                val missing=input.split(" ").count{it.trim()=="???"}
                if(missing>0){
                    val combos=Math.pow(2048.0,missing.toDouble()).toLong()
                    val combosStr=when{combos<1_000_000L->"${combos/1000}K";combos<1_000_000_000L->"${combos/1_000_000}M";else->"${combos/1_000_000_000}B"}
                    val secs=combos/50_000L
                    val timeStr=when{secs<60->"$secs seg";secs<3600->"${secs/60} min";secs<86400->"${secs/3600} h";else->"${secs/86400} dias"}
                    tvRecoveryInfo.text="Faltan: $missing  Combos: ~$combosStr  (~$timeStr)"
                }else{
                    tvRecoveryInfo.text="Palabras faltantes: —"
                }
            }
        })

        // Barra de progreso
        val pbRecovery=android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{
            max=1000;progress=0
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(8)).apply{bottomMargin=dp(4)}
            visibility=android.view.View.GONE
        }
        recoveryPage.addView(pbRecovery)

        // Status text
        val tvRecoveryStatus=TextView(this).apply{
            text="";textSize=9f;setTextColor(TXT_MUTED);typeface=Typeface.MONOSPACE
            setPadding(0,0,0,dp(8));visibility=android.view.View.GONE
        }
        recoveryPage.addView(tvRecoveryStatus)

        // Resultado
        val tvRecoveryResult=TextView(this).apply{
            text="";textSize=11f;setTextColor(0xFF00FF88.toInt())
            typeface=Typeface.create("monospace",Typeface.BOLD)
            setPadding(dp(12),dp(12),dp(12),dp(12))
            setBackgroundColor(BG_PANEL)
            visibility=android.view.View.GONE
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(10)}
        }
        recoveryPage.addView(tvRecoveryResult)

        // Botones Start / Cancel
        val btnRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val btnStartRecovery=Button(this).apply{
            text="▶  INICIAR RECOVERY";textSize=11f
            setTextColor(0xFF000000.toInt());setBackgroundColor(AMBER)
            typeface=Typeface.create("monospace",Typeface.BOLD)
            layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f).apply{marginEnd=dp(8)}
        }
        val btnCancelRecovery=Button(this).apply{
            text="■  CANCELAR";textSize=11f
            setTextColor(AMBER);setBackgroundColor(BG_PANEL)
            typeface=Typeface.create("monospace",Typeface.BOLD)
            layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
            visibility=android.view.View.GONE
        }
        btnRow.addView(btnStartRecovery);btnRow.addView(btnCancelRecovery)
        recoveryPage.addView(btnRow)

        val btnSaveWallet=Button(this).apply{
            text="⬇  GUARDAR EN WALLET";textSize=11f
            setTextColor(0xFF000000.toInt());setBackgroundColor(0xFF00FF88.toInt())
            typeface=Typeface.create("monospace",Typeface.BOLD)
            visibility=android.view.View.GONE
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(8)}
        }
        recoveryPage.addView(btnSaveWallet)

        btnSaveWallet.setOnClickListener{
            val foundMnemonic=it.tag as? String ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Guardar en Wallet")
                .setMessage("¿Guardar esta seed phrase en tu wallet principal?\n\n$foundMnemonic")
                .setPositiveButton("Guardar"){_,_->
                    // Si ya está autenticado en esta sesión, guardar directo
                    if (PinAuthHelper.isSessionValid()) {
                        WalletManager.saveSeed(this, foundMnemonic)
                        btnSaveWallet.visibility=android.view.View.GONE
                        tvRecoveryStatus.text="✓ Seed guardada en wallet principal"
                        tvRecoveryStatus.visibility=android.view.View.VISIBLE
                    } else {
                        PinAuthHelper.show(this) { ok ->
                            if (ok) {
                                WalletManager.saveSeed(this, foundMnemonic)
                                btnSaveWallet.visibility=android.view.View.GONE
                                tvRecoveryStatus.text="✓ Seed guardada en wallet principal"
                                tvRecoveryStatus.visibility=android.view.View.VISIBLE
                            }
                        }
                    }
                }
                .setNegativeButton("Cancelar",null)
                .show()
        }

        // Inicializar RecoveryEngine
        recoveryEngine=RecoveryEngine(this)
        val wordlistLoaded=recoveryEngine?.loadWordlist() ?: false

        recoveryEngine?.listener=object:com.hunter.btc.recovery.RecoveryEngine.ProgressListener{
            override fun onProgress(attempts:Long,total:Long,currentWord:String){
                runOnUiThread{
                    val pct=((attempts.toFloat()/total)*1000).toInt()
                    pbRecovery.progress=pct
                    tvRecoveryStatus.text="Probando: $currentWord  ($attempts / $total)"
                }
            }
            override fun onFoundWithAddress(mnemonic:String,address:String){
                runOnUiThread{
                    pbRecovery.visibility=android.view.View.GONE
                    tvRecoveryStatus.visibility=android.view.View.GONE
                    btnCancelRecovery.visibility=android.view.View.GONE
                    btnStartRecovery.visibility=android.view.View.VISIBLE
                    tvRecoveryResult.text="DIRECCION DERIVADA:\n$address\n\nFRASE:\n$mnemonic"
                    tvRecoveryResult.visibility=android.view.View.VISIBLE
                }
            }
            override fun onFound(mnemonic:String){
                runOnUiThread{
                    pbRecovery.visibility=android.view.View.GONE
                    tvRecoveryStatus.visibility=android.view.View.GONE
                    btnCancelRecovery.visibility=android.view.View.GONE
                    btnStartRecovery.visibility=android.view.View.VISIBLE
                    tvRecoveryResult.text="✓ ENCONTRADO\n\n$mnemonic"
                    tvRecoveryResult.visibility=android.view.View.VISIBLE
                    btnSaveWallet.tag=mnemonic
                    btnSaveWallet.visibility=android.view.View.VISIBLE
                    // Guardar en logs
                    val ts=java.text.SimpleDateFormat("yyyyMMdd_HHmmss",java.util.Locale.US).format(java.util.Date())
                    val f=java.io.File(getExternalFilesDir(null),"recovery_$ts.txt")
                    f.writeText("RECOVERY MATCH\n$mnemonic\n")
                }
            }
            override fun onNotFound(){
                runOnUiThread{
                    pbRecovery.visibility=android.view.View.GONE
                    tvRecoveryStatus.text="No encontrado. Verifica las palabras conocidas."
                    btnCancelRecovery.visibility=android.view.View.GONE
                    btnStartRecovery.visibility=android.view.View.VISIBLE
                }
            }
            override fun onCancelled(){
                runOnUiThread{
                    pbRecovery.visibility=android.view.View.GONE
                    tvRecoveryStatus.text="Cancelado."
                    btnCancelRecovery.visibility=android.view.View.GONE
                    btnStartRecovery.visibility=android.view.View.VISIBLE
                }
            }
        }

        btnStartRecovery.setOnClickListener{
            val input=etSeed.text.toString().trim()
            if(input.isEmpty()){
                tvRecoveryStatus.text="Ingresa la seed phrase primero."
                tvRecoveryStatus.visibility=android.view.View.VISIBLE
                return@setOnClickListener
            }
            if(!wordlistLoaded){
                tvRecoveryStatus.text="Error: wordlist BIP39 no cargado."
                tvRecoveryStatus.visibility=android.view.View.VISIBLE
                return@setOnClickListener
            }
            val wl=recoveryEngine?.getWordlistSet() ?: emptySet()
            val parseResult=com.hunter.btc.recovery.RecoveryParser.parse(input,wl)
            when(parseResult){
                is com.hunter.btc.recovery.ParseResult.Error->{
                    tvRecoveryStatus.text=parseResult.message
                    tvRecoveryStatus.visibility=android.view.View.VISIBLE
                }
                is com.hunter.btc.recovery.ParseResult.Success->{
                    tvRecoveryResult.visibility=android.view.View.GONE
                    pbRecovery.progress=0
                    pbRecovery.visibility=android.view.View.VISIBLE
                    tvRecoveryStatus.visibility=android.view.View.VISIBLE
                    tvRecoveryStatus.text="Iniciando..."
                    btnStartRecovery.visibility=android.view.View.GONE
                    btnCancelRecovery.visibility=android.view.View.VISIBLE
                    recoveryEngine?.startRecovery(parseResult.parsed,etTarget?.text.toString().trim() ?: "")
                }
            }
        }

        btnCancelRecovery.setOnClickListener{ recoveryEngine?.cancel() }

        

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

    private fun checkThermalThrottle() {
        if (!thermalThrottleEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastThermalCheck < 3000) return
        lastThermalCheck = now

        try {
            val batTemp = getBatteryTemp()
            val cpuTemp = getCpuTemp()
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
            .putIntArray("big_cores", bigCores)
            .putInt("batch_size", batchSize)
            .apply()
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
                Toast.makeText(this, "✓ Configuración aplicada", Toast.LENGTH_SHORT).show()
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
            val totalBlocks = range.divide(BLOCK_SIZE).toLong().coerceAtMost(100_000)

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
    }

    private fun getBlockProgressText(puzzleNum: Int, rangeStart: String, rangeEnd: String): String {
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

    private fun updateUI() {
        try {
            if (HunterEngine.isRunning()) {
                val wps = HunterEngine.getWps()
                if (puzzleMode) {
                    tvWpsPuzzle?.text = java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(wps.toLong())
                    tvCountPuzzle?.text = formatCount(HunterEngine.getCount())
                    val elapsed2 = (System.currentTimeMillis() - sessionStartTime) / 1000
                    tvTimePuzzle?.text = "%02d:%02d:%02d".format(elapsed2/3600,(elapsed2%3600)/60,elapsed2%60)
                } else {
                    tvWps?.text = java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(wps.toLong())
                    tvCount?.text = formatCount(HunterEngine.getCount())
                    val elapsed = (System.currentTimeMillis() - sessionStartTime) / 1000
                    val h = elapsed / 3600; val m = (elapsed % 3600) / 60; val sc = elapsed % 60
                    tvTime?.text = "%02d:%02d:%02d".format(h, m, sc)
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
                        val puzzleNum = puzzles.getOrNull(puzzleSpinner?.selectedItemPosition ?: 0)?.num ?: 0
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
                    val pNum = puzzles.getOrNull(puzzleSpinner?.selectedItemPosition ?: 0)?.num ?: 0
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
                        val puzzleNum = puzzles.getOrNull(puzzleSpinner?.selectedItemPosition ?: 0)?.num ?: 0
                        val savedKey = puzzlePrefs.getString("last_key_$puzzleNum", null)
                        val rangeEnd = etRangeEnd?.text.toString() ?: ""
                        // Elegir bloque no escaneado
                        val fullStart = etRangeStart?.text.toString() ?: ""
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
                HunterEngine.startHunting(threads, cpu)
                activeToggleBtn = callerBtn
                val bg = callerBtn?.tag as? Array<*>
                callerBtn?.text = s.stop
                callerBtn?.background = bg?.get(1) as? GradientDrawable
                startForegroundService(Intent(this, HunterService::class.java))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: \${e.message}", Toast.LENGTH_LONG).show()
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
        val f = File(dir, "hunter_log_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.txt")
        val log = StringBuilder("WALLET HUNTER LOG\n${java.util.Date()}\n\n")
        f.writeText(log.toString())
        Toast.makeText(this, "Log guardado: ${f.name}", Toast.LENGTH_SHORT).show()
    }

    private fun showWalletSelector() {
        startActivity(Intent(this, WalletActivity::class.java))
    }

    private fun checkPuzzleBalance(addr: String, onResult: (Long) -> Unit) {
        Thread {
            try {
                val url = java.net.URL("https://blockchain.info/q/addressbalance/$addr")
                val bal = url.readText().trim().toLongOrNull() ?: 0L
                runOnUiThread { onResult(bal) }
            } catch (e: Exception) { runOnUiThread { onResult(0L) } }
        }.start()
    }

    private fun applyPuzzle(p: PuzzleInfo) {
        etRangeStart?.setText(p.start)
        etRangeEnd?.setText(p.end)
        currentRangeStart = p.start
        currentRangeEnd = p.end
        etTarget?.setText(p.addr)
        HunterEngine.setRange(p.start, p.end)
        tvPuzzleStatus?.text = "Puzzle #${p.num} — ${p.btc}"
    }

    private fun autoSelectPuzzle() {
        tvPuzzleStatus?.text = "Checking puzzles..."; tvPuzzleStatus?.setTextColor(TXT_SEC)
        Thread {
            // Verificar puzzles en orden de dificultad (ya están ordenados de menor a mayor)
            // Parar en el primero que tenga fondos
            for ((idx, p) in puzzles.withIndex()) {
                var found = false
                val latch = java.util.concurrent.CountDownLatch(1)
                checkPuzzleBalance(p.addr) { bal ->
                    if (bal > 0) {
                        found = true
                        runOnUiThread {
                            suppressPuzzleListener = true
                            puzzleSpinner?.setSelection(idx)
                            applyPuzzle(puzzles[idx])
                            tvPuzzleStatus?.text = "Puzzle #${p.num} — ${bal/100_000_000.0} BTC"
                            tvPuzzleStatus?.setTextColor(AppTheme.GREEN)
                            suppressPuzzleListener = false
                        }
                    }
                    latch.countDown()
                }
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                if (found) break
            }
        }.start()
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
            val puzzleNum = puzzles.getOrNull(puzzleSpinner?.selectedItemPosition ?: 0)?.num ?: return
            puzzlePrefs.edit()
                .putString("last_key_$puzzleNum", lastKey)
                .putLong("last_time_$puzzleNum", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {}
    }


}
