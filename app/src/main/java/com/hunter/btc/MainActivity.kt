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

class MainActivity : Activity() {

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
    private lateinit var tvBattery: TextView
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

        tabPages = listOf(scanScroll, puzzleScroll, walletScroll, recoveryScroll)
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
        if (csvPath.isNotEmpty() && File(csvPath).exists() && !HunterEngine.isCsvLoaded())
            HunterEngine.loadCsv(csvPath)
        setupNotificationChannel()
        registerBatteryReceiver()
        updateLabels()

        val uiSp = getSharedPreferences("ui_state", MODE_PRIVATE)
        if (uiSp.contains("puzzleMode")) {
            sbThreads.progress = uiSp.getInt("threads", 3)
            sbCpu.progress = uiSp.getInt("cpu", 70)
        }
    }

    // ── BUILD SCAN TAB ────────────────────────────────────────────────────────
    private fun buildScanTab(): ScrollView {
        val scanScroll = ScrollView(this).apply {
            setBackgroundColor(BG_DEEP)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
                        val scanPage = LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}

        /* Speed hero */
        val heroBlock = LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; setBackgroundColor(BG_PANEL)
            setPadding(dp(18),dp(20),dp(18),dp(18))
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val heroLeft = LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)}
        heroLeft.addView(TextView(this).apply{text="SPEED";textSize=9f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD);letterSpacing=0.18f;setPadding(0,0,0,dp(4))})
        tvWps = TextView(this).apply{text="0";textSize=50f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD)}
        heroLeft.addView(tvWps)
        heroLeft.addView(TextView(this).apply{text="keys / second";textSize=11f;setTextColor(TXT_SEC);setPadding(0,dp(3),0,0)})
        val heroRight = LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.END}
        tvCount = TextView(this).apply{text="0";textSize=17f;setTextColor(TXT_PRI);typeface=Typeface.create("monospace",Typeface.BOLD);gravity=android.view.Gravity.END}
        tvTime  = TextView(this).apply{text="00:00:00";textSize=17f;setTextColor(TXT_PRI);typeface=Typeface.create("monospace",Typeface.BOLD);gravity=android.view.Gravity.END}
        heroRight.addView(tvCount)
        heroRight.addView(TextView(this).apply{text="SCANNED";textSize=8f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD);gravity=android.view.Gravity.END;letterSpacing=0.13f;setPadding(0,dp(2),0,dp(12))})
        heroRight.addView(tvTime)
        heroRight.addView(TextView(this).apply{text="ELAPSED";textSize=8f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD);gravity=android.view.Gravity.END;letterSpacing=0.13f;setPadding(0,dp(2),0,0)})
        heroBlock.addView(heroLeft); heroBlock.addView(heroRight)
        scanPage.addView(heroBlock)
        scanPage.addView(View(this).apply{setBackgroundColor(BORDER_C);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})

        /* Quick strip */
        val qStrip = LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setBackgroundColor(BG_PANEL);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(58))}
        fun qCell(value:String,label:String,color:Int,click:(()->Unit)?=null):Pair<LinearLayout,TextView>{
            val tv=TextView(this).apply{text=value;textSize=15f;setTextColor(color);typeface=Typeface.create("monospace",Typeface.BOLD);gravity=Gravity.CENTER}
            val lb=TextView(this).apply{text=label;textSize=8f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD);gravity=Gravity.CENTER;letterSpacing=0.12f}
            val cell=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f);if(click!=null){setOnClickListener{click()};isClickable=true}}
            cell.addView(tv);cell.addView(lb);return Pair(cell,tv)
        }
        fun vDiv()=View(this).apply{setBackgroundColor(BORDER_C);layoutParams=LinearLayout.LayoutParams(1,LinearLayout.LayoutParams.MATCH_PARENT)}
        val csvLbl=if(csvPath.isNotEmpty()&&File(csvPath).exists())File(csvPath).nameWithoutExtension.take(7) else "No file"
        val(c0,tvQT)=qCell("${prefs.getInt("threads",3)+1}","THREADS",AMBER){goTab(2)};tvQuickThreads=tvQT
        val(c1,tvQCpu)=qCell("${prefs.getInt("cpu",70)+10}%","CPU",AppTheme.CYAN){goTab(2)};tvQuickCpu=tvQCpu
        val(c2,tvQC)=qCell(csvLbl,"DATASET",TXT_MUTED){goTab(2)};tvQuickCsv=tvQC
        val(c3,tvQM)=qCell("0","MATCHES",TXT_MUTED);tvQuickMatches=tvQM
        qStrip.addView(c0);qStrip.addView(vDiv());qStrip.addView(c1);qStrip.addView(vDiv());qStrip.addView(c2);qStrip.addView(vDiv());qStrip.addView(c3)
        scanPage.addView(qStrip)
        scanPage.addView(View(this).apply{setBackgroundColor(BORDER_C);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})

        /* Big action button */
        val coinGreen=GradientDrawable().apply{setColor(AppTheme.AMBER);cornerRadius=dp(12).toFloat()}
        val coinRed  =GradientDrawable().apply{setColor(AppTheme.RED);cornerRadius=dp(12).toFloat()}
        btnToggle=Button(this).apply{
            text=s.start;textSize=15f;setTextColor(android.graphics.Color.BLACK)
            typeface=Typeface.create("sans-serif-black",Typeface.BOLD);letterSpacing=0.12f;isAllCaps=true
            background=coinGreen
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(56))
            setOnClickListener{doToggle()}
        }
        btnToggle.tag=arrayOf(coinGreen,coinRed)
        val actZone=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(14))}
        actZone.addView(btnToggle);scanPage.addView(actZone)

        /* Stat cards: tvKps apunta al mismo texto que tvWps (sin duplicar UI) */
        val statSec=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0)}
        tvKps=tvWps  // alias — no card duplicada
        val tvSc2=tvCount
        val tvTm2=tvTime

        /* tvMatches — inicializado pero mostrado solo en Quick Strip y tvMatchList */
        tvMatches=TextView(this).apply{text="0";textSize=15f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD)}

        /* Sparkline */
        val sparkCard=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=cardBg();setPadding(dp(14),dp(12),dp(14),dp(12));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(6)}}
        sparkCard.addView(TextView(this).apply{text="SPEED HISTORY";textSize=9f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.BOLD);letterSpacing=0.14f;setPadding(0,0,0,dp(10))})
        chartView=SpeedChartView(this).apply{layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(52))}
        sparkCard.addView(chartView);statSec.addView(sparkCard);scanPage.addView(statSec)

        /* Live feed */
        val feedSec=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0)}
        val feedCard=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=cardBg();clipToOutline=true}
        val feedHdr=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(9),dp(14),dp(9));setBackgroundColor(0x2D000000)}
        feedHdr.addView(View(this).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(AMBER)};layoutParams=LinearLayout.LayoutParams(dp(6),dp(6)).apply{marginEnd=dp(6)}})
        feedHdr.addView(TextView(this).apply{text="STREAMING";textSize=9f;setTextColor(AMBER);typeface=Typeface.create("monospace",Typeface.BOLD);letterSpacing=0.1f;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)})
        feedHdr.addView(TextView(this).apply{text="last 3";textSize=9f;setTextColor(TXT_MUTED)})
        feedCard.addView(feedHdr)
        tvAddrFeed=TextView(this).apply{text=s.waitingStart;setTextColor(TXT_SEC);textSize=10f;typeface=Typeface.MONOSPACE;setPadding(dp(14),dp(10),dp(14),dp(12));setLineSpacing(0f,1.5f)}
        feedCard.addView(tvAddrFeed);feedSec.addView(feedCard)
        tvMatchList=TextView(this).apply{text=s.noMatch;setTextColor(TXT_MUTED);textSize=11f;typeface=Typeface.MONOSPACE;background=cardBg();setPadding(dp(14),dp(12),dp(14),dp(12));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(6)}}
        feedSec.addView(tvMatchList);scanPage.addView(feedSec)
        tvStatus=TextView(this).apply{visibility=android.view.View.GONE;text=""}
        tvFooter=TextView(this).apply{visibility=android.view.View.GONE;text=""}
        scanPage.addView(tvStatus);scanPage.addView(tvFooter)
        scanPage.addView(View(this).apply{layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(20))})
        ;

        scanScroll.addView(scanPage)
        return scanScroll
    }

    // ── BUILD PUZZLE TAB ──────────────────────────────────────────────────────
    private fun buildPuzzleTab(): ScrollView {
        val cfgScroll = ScrollView(this).apply {
            setBackgroundColor(BG_DEEP)
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
                        val cfgPage=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(24))}
        fun cfgCard()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=cardBg();clipToOutline=true;layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(8)}}

        /* Dataset */
        val _lblDs=secLbl(s.csvSection);lblDataset=_lblDs;cfgPage.addView(_lblDs)
        val dataCard=cfgCard()
        val csvRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;clipToOutline=true}
        btnCsv=Button(this).apply{text="Load Dataset";textSize=11f;setTextColor(android.graphics.Color.BLACK);typeface=Typeface.create("sans-serif-black",Typeface.BOLD);background=GradientDrawable().apply{setColor(AMBER)};setPadding(dp(18),0,dp(18),0);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,dp(50));setOnClickListener{pickCsv()}}
        val csvInfo=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),0,dp(14),0);layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f)}
        val tvCsvName=TextView(this).apply{text=if(csvPath.isNotEmpty()&&File(csvPath).exists())File(csvPath).name else s.noFile;setTextColor(TXT_MUTED);textSize=10f;typeface=Typeface.MONOSPACE;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)}
        tvStatus=tvCsvName
        if(csvPath.isNotEmpty()&&File(csvPath).exists())csvInfo.addView(TextView(this).apply{text="●";textSize=8f;setTextColor(AppTheme.GREEN);setPadding(dp(6),0,0,0)})
        csvInfo.addView(tvCsvName);csvRow.addView(btnCsv);csvRow.addView(csvInfo);dataCard.addView(csvRow)
        csvSecView=dataCard;tvCsvSec=LinearLayout(this).also{it.visibility=android.view.View.GONE}
        cfgPage.addView(dataCard)

        /* Performance sliders */
        val _lblPf=secLbl(s.configSection);lblPerformance=_lblPf;cfgPage.addView(_lblPf)
        val perfCard=cfgCard()
        fun sliderWrap(tv:TextView,bar:SeekBar):LinearLayout{val w=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(14))};w.addView(tv);w.addView(bar);return w}
        tvThreads=TextView(this).apply{setTextColor(TXT_PRI);textSize=12f;setPadding(0,0,0,dp(10))}
        sbThreads=SeekBar(this).apply{max=7;progress=prefs.getInt("threads",3);setOnSeekBarChangeListener(mkSbl{updateLabels()})}
        tvCpu    =TextView(this).apply{setTextColor(TXT_PRI);textSize=12f;setPadding(0,0,0,dp(10))}
        sbCpu    =SeekBar(this).apply{max=90;progress=prefs.getInt("cpu",70);setOnSeekBarChangeListener(mkSbl{updateLabels();if(HunterEngine.isRunning())HunterEngine.setCpuLimit(sbCpu.progress+10)})}
        perfCard.addView(sliderWrap(tvThreads,sbThreads))
        perfCard.addView(View(this).apply{setBackgroundColor(0x08FFFFFF);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})
        perfCard.addView(sliderWrap(tvCpu,sbCpu))
        cfgPage.addView(perfCard);updateLabels()

        /* Fast Scan toggle */
        val fastCard=cfgCard()
        val fastRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(14))}
        val fastLeft=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)}
        fastLeft.addView(TextView(this).apply{text="Fast Scan Mode";textSize=13f;setTextColor(TXT_PRI)})
        fastLeft.addView(TextView(this).apply{text="1 iter — 60k/s vs 9k/s standard";textSize=9f;setTextColor(TXT_MUTED);typeface=Typeface.create("monospace",Typeface.NORMAL);setPadding(0,dp(2),0,0)})
        val fastSwitch=android.widget.Switch(this).apply{
            isChecked=fastModeEnabled
            setOnCheckedChangeListener{_,checked->
                fastModeEnabled=checked
                HunterEngine.setPbkdf2Mode(if(checked)1 else 0)
                getSharedPreferences("hunter",MODE_PRIVATE).edit().putBoolean("fastMode",checked).apply()
            }
        }
        fastRow.addView(fastLeft);fastRow.addView(fastSwitch);fastCard.addView(fastRow)
        cfgPage.addView(fastCard)
        /* Restaurar fast mode */
        fastModeEnabled=prefs.getBoolean("fastMode",false)
        HunterEngine.setPbkdf2Mode(if(fastModeEnabled)1 else 0)
        fastSwitch.isChecked=fastModeEnabled

        /* Search mode */
        val _lblMd=secLbl(s.modeSection);lblMode=_lblMd;cfgPage.addView(_lblMd)
        val modeCard=cfgCard()
        val modeRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(dp(12),dp(12),dp(12),dp(12));gravity=Gravity.CENTER_VERTICAL}
        rbBip39 =Button(this).apply{text=s.modeBip39;textSize=11f;setTextColor(AMBER);typeface=Typeface.create("monospace",Typeface.BOLD);background=GradientDrawable().apply{setColor(BG_ELEV);setStroke(1,0x33A8FF00);cornerRadius=dp(8).toFloat()};layoutParams=LinearLayout.LayoutParams(0,dp(40),1f).apply{marginEnd=dp(4)};setPadding(dp(4),0,dp(4),0)}
        rbPuzzle=Button(this).apply{text=s.modePuzzle;textSize=11f;setTextColor(TXT_SEC);typeface=Typeface.create("monospace",Typeface.NORMAL);background=GradientDrawable().apply{setColor(BG_CARD);setStroke(1,BORDER_C);cornerRadius=dp(8).toFloat()};layoutParams=LinearLayout.LayoutParams(0,dp(40),1f);setPadding(dp(4),0,dp(4),0)}
        modeRow.addView(rbBip39);modeRow.addView(rbPuzzle);modeCard.addView(modeRow)
        layoutPuzzle=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=android.view.View.GONE;setPadding(dp(14),0,dp(14),dp(14))}
        val dayOfYear=java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val defaultIdx=dayOfYear%puzzles.size
        layoutPuzzle.addView(TextView(this).apply{text=s.puzzleSelect;textSize=9f;setTextColor(TXT_SEC);setPadding(0,0,0,dp(4))})
        puzzleSpinner=Spinner(this).apply{adapter=themedAdapter(puzzles.map{"#${it.num}  -  ${it.btc}  -  ${it.addr.take(16)}..."});setSelection(defaultIdx);background=GradientDrawable().apply{setColor(BG_CARD);setStroke(1,BORDER_C);cornerRadius=dp(6).toFloat()}}
        layoutPuzzle.addView(puzzleSpinner)
        val rangeRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(8),0,0)}
        fun fld(lbl:String):Pair<LinearLayout,EditText>{val col=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f).apply{marginEnd=dp(4)}};col.addView(TextView(this).apply{text=lbl;textSize=9f;setTextColor(TXT_SEC);setPadding(0,0,0,dp(2))});val et=EditText(this).apply{setTextColor(TXT_PRI);textSize=10f;typeface=Typeface.MONOSPACE;background=GradientDrawable().apply{setColor(BG_ELEV);setStroke(1,BORDER_C);cornerRadius=dp(6).toFloat()};setPadding(dp(8),dp(6),dp(8),dp(6));inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS};
;return Pair(col,et)}
        val(cS,eS)=fld(s.rangeStart);etRangeStart=eS;rangeRow.addView(cS)
        val(cE,eE)=fld(s.rangeEnd);etRangeEnd=eE;rangeRow.addView(cE)
        layoutPuzzle.addView(rangeRow)
        layoutPuzzle.addView(TextView(this).apply{text=s.targetAddr;textSize=9f;setTextColor(TXT_SEC);setPadding(0,dp(8),0,dp(2))})
        etTarget=EditText(this).apply{setTextColor(AMBER);textSize=10f;typeface=Typeface.MONOSPACE;background=GradientDrawable().apply{setColor(BG_ELEV);setStroke(1,BORDER_C);cornerRadius=dp(6).toFloat()};setPadding(dp(8),dp(6),dp(8),dp(6));inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS}
        layoutPuzzle.addView(etTarget)
        tvPuzzleStatus=TextView(this).apply{text="";textSize=10f;typeface=Typeface.MONOSPACE;setTextColor(AppTheme.GREEN);background=GradientDrawable().apply{setColor(0x1510D97A);setStroke(1,0x2510D97A);cornerRadius=dp(6).toFloat()};setPadding(dp(10),dp(6),dp(10),dp(6));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(6)}}
        layoutPuzzle.addView(tvPuzzleStatus);applyPuzzle(puzzles[defaultIdx])
        puzzleSpinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{var init=true;override fun onItemSelected(a:AdapterView<*>,v:android.view.View?,pos:Int,id:Long){if(init){init=false;return};applyPuzzle(puzzles[pos])};override fun onNothingSelected(a:AdapterView<*>){}}
        modeCard.addView(layoutPuzzle);cfgPage.addView(modeCard)
        tvConfigSec=LinearLayout(this).also{it.visibility=android.view.View.GONE}
        val modeToggle={isPuzzle:Boolean->
            puzzleMode=isPuzzle
            layoutPuzzle.visibility=if(isPuzzle)android.view.View.VISIBLE else android.view.View.GONE
            HunterEngine.setMode(if(isPuzzle)1 else 0)
            if(isPuzzle){rbPuzzle.setTextColor(AMBER);rbPuzzle.background=GradientDrawable().apply{setColor(BG_ELEV);setStroke(1,0x33A8FF00);cornerRadius=dp(8).toFloat()};rbBip39.setTextColor(TXT_SEC);rbBip39.background=GradientDrawable().apply{setColor(BG_CARD);setStroke(1,BORDER_C);cornerRadius=dp(8).toFloat()}}
            else{rbBip39.setTextColor(AMBER);rbBip39.background=GradientDrawable().apply{setColor(BG_ELEV);setStroke(1,0x33A8FF00);cornerRadius=dp(8).toFloat()};rbPuzzle.setTextColor(TXT_SEC);rbPuzzle.background=GradientDrawable().apply{setColor(BG_CARD);setStroke(1,BORDER_C);cornerRadius=dp(8).toFloat()}}
        }
        rbBip39.setOnClickListener{modeToggle(false)};rbPuzzle.setOnClickListener{modeToggle(true); if(puzzleMode) autoSelectPuzzle()}

        /* Wallet + export */
        val _lblWl=secLbl("Wallet");lblWallet=_lblWl;cfgPage.addView(_lblWl)
        val walletCard=cfgCard()
        fun navRow(title:String,sub:String,click:()->Unit):LinearLayout{val r=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(13),dp(16),dp(13));isClickable=true;setOnClickListener{click()}};val lc=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)};lc.addView(TextView(this).apply{text=title;textSize=13f;setTextColor(TXT_PRI)});lc.addView(TextView(this).apply{text=sub;textSize=10f;setTextColor(TXT_SEC);typeface=Typeface.MONOSPACE;setPadding(0,dp(2),0,0)});r.addView(lc);r.addView(TextView(this).apply{text="›";textSize=18f;setTextColor(TXT_MUTED)});return r}
        walletCard.addView(navRow("Open Wallet","View balances & addresses"){showWalletSelector()})
        walletCard.addView(View(this).apply{setBackgroundColor(0x08FFFFFF);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})
        walletCard.addView(navRow("Export Log","Save matches to file"){exportLog()})
        cfgPage.addView(walletCard)


        /* System log */
        val _lblLg=secLbl(s.logSection);lblLog=_lblLg;cfgPage.addView(_lblLg)
        val logCard=cfgCard()
        val logHdr=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(9),dp(14),dp(9));setBackgroundColor(0x2D000000)}
        logHdr.addView(TextView(this).apply{text="System Events";textSize=9f;setTextColor(TXT_PRI);typeface=Typeface.create("monospace",Typeface.BOLD);layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)})
        logHdr.addView(TextView(this).apply{text="last session";textSize=9f;setTextColor(TXT_MUTED)})
        logCard.addView(logHdr)
        tvLog=TextView(this).apply{text="";setTextColor(TXT_SEC);textSize=9f;typeface=Typeface.MONOSPACE;setPadding(dp(14),dp(8),dp(14),dp(12));setLineSpacing(0f,1.3f);maxLines=15}
        logCard.addView(tvLog);cfgPage.addView(logCard)
        ;

        cfgScroll.addView(cfgPage)
        return cfgScroll
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
        val wordlistLoaded=recoveryEngine.loadWordlist()

        recoveryEngine.listener=object:com.hunter.btc.recovery.RecoveryEngine.ProgressListener{
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
            val wl=recoveryEngine.getWordlistSet()
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
                    recoveryEngine.startRecovery(parseResult.parsed,etTarget.text.toString().trim())
                }
            }
        }

        btnCancelRecovery.setOnClickListener{ recoveryEngine.cancel() }

        

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
        val t = sbThreads.progress + 1
        val c = sbCpu.progress + 10
        tvThreads.text = "Threads: $t"
        tvCpu.text = "CPU limit: $c%"
        tvQuickThreads?.text = "$t"
        tvQuickCpu?.text = "$c%"
        prefs.edit().putInt("threads", sbThreads.progress).putInt("cpu", sbCpu.progress).apply()
    }

    private fun formatCount(v: Long) = when {
        v >= 1_000_000_000L -> "%.2fB".format(v / 1e9)
        v >= 1_000_000L     -> "%.2fM".format(v / 1e6)
        v >= 1_000L         -> "%.2fK".format(v / 1e3)
        else -> "$v"
    }

    private fun updateUI() {
        if (HunterEngine.isRunning()) {
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
        }
        val rt = Runtime.getRuntime()
        tvRam.text = "RAM ${(rt.totalMemory()-rt.freeMemory())/1048576}MB"
    }

    private fun doToggle() {
        if (HunterEngine.isRunning()) {
            HunterEngine.stopHunting()
            getSharedPreferences("ui_state", MODE_PRIVATE).edit()
                .putBoolean("puzzleMode", puzzleMode)
                .putInt("threads", sbThreads.progress)
                .putInt("cpu", sbCpu.progress)
                .apply()
            val bg = btnToggle.tag as? Array<*>
            btnToggle.text = s.start
            btnToggle.background = bg?.get(0) as? GradientDrawable
        } else {
            if (!HunterEngine.isCsvLoaded() && !puzzleMode) {
                Toast.makeText(this, "No dataset loaded", Toast.LENGTH_SHORT).show(); return
            }
            sessionStartTime = System.currentTimeMillis()
            sessionStartCount = HunterEngine.getCount()
            val threads = sbThreads.progress + 1
            val cpu = sbCpu.progress + 10
            if (puzzleMode) {
                HunterEngine.setRange(etRangeStart.text.toString(), etRangeEnd.text.toString())
            }
            HunterEngine.startHunting(threads, cpu)
            val bg = btnToggle.tag as? Array<*>
            btnToggle.text = s.stop
            btnToggle.background = bg?.get(1) as? GradientDrawable
            startForegroundService(Intent(this, HunterService::class.java))
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
            val dest = File(getExternalFilesDir(null), "dataset.csv")
            contentResolver.openInputStream(uri)?.use { it.copyTo(dest.outputStream()) }
            csvPath = dest.absolutePath
            prefs.edit().putString("csvPath", csvPath).apply()
            HunterEngine.loadCsv(csvPath)
            tvStatus.text = dest.name
            tvQuickCsv?.text = dest.nameWithoutExtension.take(7)
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
        etRangeStart.setText(p.start)
        etRangeEnd.setText(p.end)
        etTarget.setText(p.addr)
        HunterEngine.setRange(p.start, p.end)
        tvPuzzleStatus.text = "Puzzle #${p.num} — ${p.btc}"
    }

    private fun autoSelectPuzzle() {
        tvPuzzleStatus.text = "Checking puzzles..."; tvPuzzleStatus.setTextColor(TXT_SEC)
        Thread {
            var bestIdx = 0
            for ((idx, p) in puzzles.withIndex()) {
                checkPuzzleBalance(p.addr) { bal ->
                    if (bal > 0) {
                        bestIdx = idx
                        runOnUiThread {
                            suppressPuzzleListener = true
                            puzzleSpinner.setSelection(bestIdx)
                            applyPuzzle(puzzles[bestIdx])
                            tvPuzzleStatus.text = "Auto-selected #${p.num} — ${bal/100_000_000.0} BTC"
                            tvPuzzleStatus.setTextColor(AppTheme.GREEN)
                            suppressPuzzleListener = false
                        }
                    }
                }
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
}
